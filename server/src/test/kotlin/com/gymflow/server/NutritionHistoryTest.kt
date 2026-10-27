package com.gymflow.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Comidas personalizadas, historial de objetivos (V3) y migraciones sobre bases de datos existentes. */
class NutritionHistoryTest {

    private val keys get() = TestEnv.keys

    private suspend fun HttpClient.logCustom(token: String, food: CustomFood, date: String = "2026-10-20", servings: Double = 1.0, id: String = uuid()) =
        post("/api/nutrition/logs") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(CreateLogRequest(id = id, date = date, servings = servings, custom = food))
        }

    private suspend fun HttpClient.logMeal(token: String, code: String, date: String) =
        post("/api/nutrition/logs") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(CreateLogRequest(uuid(), code, date)) }

    private suspend fun HttpClient.saveGoals(token: String, g: NutritionGoals) =
        put("/api/nutrition/goals") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(g) }

    private val kebab = CustomFood("Kebab de pollo", kcal = 650.0, proteinG = 35.5, fatG = 28.0, carbsG = 60.0)

    // ─── Comidas personalizadas ──────────────────────────────────────────────

    @Test fun customMealsAreLoggedWithTheirOwnValues() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        val r = client.logCustom(token, kebab, servings = 1.5)
        assertEquals(HttpStatusCode.Created, r.status)
        val log = r.body<NutritionLog>()
        assertNull(log.mealCode)
        assertEquals("Kebab de pollo", log.mealName)
        assertEquals(Macros(650.0, 35.5, 28.0, 60.0), log.perServing)
        assertEquals(Macros(975.0, 53.3, 42.0, 90.0), log.total) // 35,5 × 1,5 = 53,25 → 53,3

        client.logMeal(token, "D1", "2026-10-20") // el registro rápido del plan sigue igual
        val s = client.get("/api/nutrition/summary?date=2026-10-20") { bearerAuth(token) }.body<DaySummary>()
        assertEquals(2, s.logs)
        assertEquals(Macros(1685.0, 101.3, 61.0, 180.0), s.consumed)
    }

    @Test fun customMealRetriesDoNotDuplicate() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        val id = uuid()
        assertEquals(HttpStatusCode.Created, client.logCustom(token, kebab, id = id).status)
        assertEquals(HttpStatusCode.OK, client.logCustom(token, kebab, id = id).status)
        assertEquals(HttpStatusCode.Conflict, client.logCustom(token, kebab.copy(kcal = 700.0), id = id).status)
        assertEquals(HttpStatusCode.Conflict, client.logMeal(token, "D1", "2026-10-20").let { client.logCustom(token, kebab, id = it.body<NutritionLog>().id) }.status)
        val logs = client.get("/api/nutrition/logs?date=2026-10-20") { bearerAuth(token) }.body<LogsResponse>().logs
        assertEquals(listOf("Kebab de pollo", "Avena con proteína y plátano"), logs.map { it.mealName })
    }

    @Test fun customMealValidation() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        for (bad in listOf(
            kebab.copy(name = "   "),
            kebab.copy(name = "x".repeat(101)),
            kebab.copy(name = "Pan\u0000"),
            kebab.copy(kcal = -1.0),
            kebab.copy(kcal = 5001.0),
            kebab.copy(proteinG = 501.0),
            kebab.copy(fatG = -0.1),
            kebab.copy(carbsG = 1000.0),
        )) assertEquals(HttpStatusCode.BadRequest, client.logCustom(token, bad).status, bad.toString())
        assertEquals(HttpStatusCode.BadRequest, client.logCustom(token, kebab, servings = 0.0).status)

        // mealCode y custom a la vez, o ninguno
        val both = client.post("/api/nutrition/logs") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(CreateLogRequest(uuid(), "D1", "2026-10-20", custom = kebab))
        }
        assertEquals(HttpStatusCode.BadRequest, both.status)
        val none = client.post("/api/nutrition/logs") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(CreateLogRequest(uuid(), date = "2026-10-20"))
        }
        assertEquals(HttpStatusCode.BadRequest, none.status)
        assertTrue(client.get("/api/nutrition/logs?date=2026-10-20") { bearerAuth(token) }.body<LogsResponse>().logs.isEmpty())

        // El nombre se guarda recortado y tal cual (el panel lo muestra como texto, nunca como HTML)
        val odd = client.logCustom(token, kebab.copy(name = "  <b>Tortilla</b> & \"pan\"  ")).body<NutritionLog>()
        assertEquals("<b>Tortilla</b> & \"pan\"", odd.mealName)
    }

    @Test fun customMealsAreIsolatedAndPersisted() = testApplication {
        val client = setUp()
        val aliceUid = newUid()
        val alice = keys.token(aliceUid)
        val bob = keys.token(newUid())
        val log = client.logCustom(alice, kebab, servings = 2.0).body<NutritionLog>()
        assertTrue(client.get("/api/nutrition/logs?date=2026-10-20") { bearerAuth(bob) }.body<LogsResponse>().logs.isEmpty())
        assertEquals(HttpStatusCode.Conflict, client.logCustom(bob, kebab, id = log.id).status)

        Database(TestEnv.config.db).use { fresh ->
            val stored = NutritionRepository(fresh).logs(aliceUid, LocalDate.parse("2026-10-20")).single()
            assertEquals(log.copy(updatedAt = stored.updatedAt), stored)
            assertEquals(1300.0, stored.total.kcal)
        }
    }

    // ─── Historial de objetivos ──────────────────────────────────────────────

    @Test fun changingGoalsKeepsPastDaysComparisons() = testApplication {
        val clock = MutableClock(Instant.parse("2026-10-01T10:00:00Z"))
        val client = setUp(clock)
        val token = keys.token(newUid())
        val a = NutritionGoals(2300, 180.0, 60.0, 260.0)
        val b = NutritionGoals(2200, 180.0, 60.0, 240.0)
        val c = NutritionGoals(2100, 175.0, 55.0, 230.0)

        client.logMeal(token, "D1", "2026-09-30")          // antes de guardar nada: iniciales
        assertEquals(HttpStatusCode.OK, client.saveGoals(token, a).status) // vale desde el 1
        clock.now = clock.now.plus(Duration.ofDays(9))      // 10 de octubre
        client.logMeal(token, "D1", "2026-10-05")           // día pasado, registrado tarde
        client.saveGoals(token, b)
        client.saveGoals(token, c)                          // mismo día: sustituye a b
        client.logMeal(token, "D1", "2026-10-10")

        val days = client.get("/api/nutrition/days?from=2026-09-01&to=2026-10-10") { bearerAuth(token) }.body<DaysResponse>().days
        assertEquals(
            listOf("2026-10-10" to c, "2026-10-05" to a, "2026-09-30" to NutritionRepository.DEFAULT_GOALS),
            days.map { it.date to it.goals },
        )
        assertEquals(a, client.get("/api/nutrition/summary?date=2026-10-05") { bearerAuth(token) }.body<DaySummary>().goals)

        val onFifth = client.get("/api/nutrition/goals?date=2026-10-05") { bearerAuth(token) }.body<GoalsResponse>()
        assertEquals(a, onFifth.goals)
        assertEquals("2026-10-01", onFifth.effectiveFrom)
        val current = client.get("/api/nutrition/goals") { bearerAuth(token) }.body<GoalsResponse>()
        assertEquals(c, current.goals)
        assertEquals("2026-10-10", current.effectiveFrom)
        assertTrue(client.get("/api/nutrition/goals?date=2026-09-30") { bearerAuth(token) }.body<GoalsResponse>().isDefault)
    }

    @Test fun goalHistoryIsIsolatedBetweenUsers() = testApplication {
        val client = setUp()
        val alice = keys.token(newUid())
        val bob = keys.token(newUid())
        client.saveGoals(bob, NutritionGoals(1800, 150.0, 50.0, 200.0))
        assertTrue(client.get("/api/nutrition/goals") { bearerAuth(alice) }.body<GoalsResponse>().isDefault)
    }

    // ─── Compatibilidad con la sincronización de la app Android ──────────────

    @Test fun nutritionDoesNotTouchAndroidSync() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        val workout = change("workouts", updatedAt = 1000)
        val pushed = client.post("/api/sync/push") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(PushRequest(listOf(workout)))
        }.body<PushResponse>()
        assertEquals(PushResponse(1, 0), pushed)
        val before = client.get("/api/sync/pull?since=0") { bearerAuth(token) }.body<PullResponse>()

        client.logMeal(token, "C1", "2026-10-20")
        client.logCustom(token, kebab)
        client.saveGoals(token, NutritionGoals(2300, 180.0, 60.0, 260.0))

        val after = client.get("/api/sync/pull?since=0") { bearerAuth(token) }.body<PullResponse>()
        assertEquals(before, after) // ni registros nuevos ni versiones nuevas
        assertTrue(client.get("/api/sync/pull?since=${before.cursor}") { bearerAuth(token) }.body<PullResponse>().changes.isEmpty())
        assertEquals(1, client.get("/api/me") { bearerAuth(token) }.body<MeResponse>().records)
        // La exportación (lo que lee el panel) sigue con el mismo formato
        assertEquals(HttpStatusCode.OK, client.get("/api/export") { bearerAuth(token) }.status)
        // Las tablas de nutrición no se aceptan por sincronización
        val viaSync = client.post("/api/sync/push") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(PushRequest(listOf(change("nutrition_logs"))))
        }
        assertEquals(HttpStatusCode.BadRequest, viaSync.status)
    }

    // ─── Migraciones sobre bases de datos existentes ─────────────────────────

    /** Base de datos nueva y vacía en el PostgreSQL embebido. */
    private fun freshDb(): DbConfig {
        val name = "mig_" + uuid().replace("-", "").take(16)
        DriverManager.getConnection(TestEnv.postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres").use { c ->
            c.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return DbConfig(TestEnv.postgres.getJdbcUrl("postgres", name), "postgres", "postgres")
    }

    private fun migrate(db: DbConfig, target: String) {
        Flyway.configure().dataSource(db.url, db.user, db.password).target(target).load().migrate()
    }

    private fun sql(db: DbConfig, vararg statements: String) =
        DriverManager.getConnection(db.url, db.user, db.password).use { c -> c.createStatement().use { st -> statements.forEach(st::execute) } }

    private fun <T> query(db: DbConfig, q: String, read: (java.sql.ResultSet) -> T): T =
        DriverManager.getConnection(db.url, db.user, db.password).use { c -> c.createStatement().use { st -> st.executeQuery(q).use { rs -> rs.next(); read(rs) } } }

    @Test fun migratesAProductionLikeV1DatabaseWithoutTouchingItsData() {
        val db = freshDb()
        migrate(db, "1") // como está hoy producción
        sql(
            db,
            "INSERT INTO users (id, email, sync_version) VALUES ('u1', 'a@b.c', 2)",
            """INSERT INTO records (user_id, table_name, id, updated_at, version, data) VALUES
               ('u1', 'workouts', 'w1', 1000, 1, '{"name":"Push"}'), ('u1', 'workout_sets', 's1', 1000, 2, '{"reps":8}')""",
            "INSERT INTO panel_tokens (token_hash, user_id) VALUES ('h1', 'u1')",
        )
        val before = query(db, "SELECT md5(string_agg(t::text, ',' ORDER BY t::text)) FROM (SELECT * FROM records) t") { it.getString(1) }

        Database(db).use { database -> // así arranca el servidor nuevo: migra hasta V3
            val repo = NutritionRepository(database)
            assertEquals(14, repo.meals().size)
            assertTrue(repo.goals("u1", LocalDate.parse("2026-10-20")).isDefault)
            val r = repo.create("u1", null, java.util.UUID.randomUUID(), LogSource.Catalog("D1"), LocalDate.parse("2026-10-20"), 1.0)
            assertTrue(r is CreateLogResult.Created)
        }
        assertEquals(before, query(db, "SELECT md5(string_agg(t::text, ',' ORDER BY t::text)) FROM (SELECT * FROM records) t") { it.getString(1) })
        assertEquals(2L, query(db, "SELECT sync_version FROM users WHERE id = 'u1'") { it.getLong(1) })
        assertEquals(1, query(db, "SELECT count(*) FROM panel_tokens") { it.getInt(1) })
        assertEquals("3", query(db, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success") { it.getString(1) })
    }

    @Test fun migratesV2NutritionDataToV3() {
        val db = freshDb()
        migrate(db, "2")
        sql(
            db,
            "INSERT INTO users (id) VALUES ('u1'), ('u2')",
            // u1 guardó objetivos el 15/10 a las 23:30 UTC = 16/10 en Madrid
            "INSERT INTO nutrition_goals (user_id, kcal, protein_g, fat_g, carbs_g, created_at, updated_at) VALUES ('u1', 2300, 180, 60, 260, '2026-10-15T23:30:00Z', '2026-10-15T23:30:00Z')",
            """INSERT INTO nutrition_logs (id, user_id, log_date, meal_code, meal_name, servings, kcal_per_serving, protein_per_serving, fat_per_serving, carbs_per_serving)
               VALUES ('11111111-1111-4111-8111-111111111111', 'u1', '2026-10-14', 'C1', 'Arroz con pollo y verduras', 1.5, 645, 50, 13, 77)""",
        )
        Database(db).use { database ->
            val repo = NutritionRepository(database)
            assertTrue(repo.goals("u1", LocalDate.parse("2026-10-15")).isDefault)
            assertEquals("2026-10-16", repo.goals("u1", LocalDate.parse("2026-10-16")).effectiveFrom)
            assertEquals(2300, repo.goals("u1", LocalDate.parse("2026-10-20")).goals.kcal)
            assertTrue(repo.goals("u2", LocalDate.parse("2026-10-20")).isDefault)
            val old = repo.logs("u1", LocalDate.parse("2026-10-14")).single()
            assertEquals("C1", old.mealCode)
            assertEquals(967.5, old.total.kcal)
            assertEquals(NutritionRepository.DEFAULT_GOALS, repo.days("u1", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-20")).single().goals)
        }
    }

    /**
     * Rollback de la aplicación: si se vuelve a desplegar la versión actual de producción
     * (solo conoce V1) sobre una base ya migrada a V3, Flyway debe arrancar igualmente
     * (las migraciones "futuras" se ignoran) y no tocar nada.
     */
    @Test fun previousServerVersionStillStartsOnAMigratedDatabase() {
        val db = freshDb()
        migrate(db, "latest")
        val onlyV1 = Files.createTempDirectory("v1-only").toFile().also { it.deleteOnExit() }
        javaClass.getResourceAsStream("/db/migration/V1__init.sql")!!.use { input ->
            File(onlyV1, "V1__init.sql").outputStream().use { input.copyTo(it) }
        }
        val result = Flyway.configure().dataSource(db.url, db.user, db.password)
            .locations("filesystem:${onlyV1.absolutePath}").load().migrate()
        assertEquals(0, result.migrationsExecuted)
        assertEquals("3", query(db, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success") { it.getString(1) })
    }
}
