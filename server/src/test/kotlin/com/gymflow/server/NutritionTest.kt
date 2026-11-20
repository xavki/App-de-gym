package com.gymflow.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NutritionTest {

    private val keys get() = TestEnv.keys
    private val madrid = ZoneId.of("Europe/Madrid")

    // D1 del plan: 710 kcal | P 48 | F 19 | C 90 — C1: 645 kcal | P 50 | F 13 | C 77
    private val d1 = Macros(710.0, 48.0, 19.0, 90.0)
    private val c1 = Macros(645.0, 50.0, 13.0, 77.0)

    private suspend fun HttpClient.log(token: String, mealCode: String, date: String? = "2026-10-20", servings: Double = 1.0, id: String = uuid()) =
        post("/api/nutrition/logs") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(CreateLogRequest(id, mealCode, date, servings))
        }

    private suspend fun HttpClient.logsOf(token: String, date: String): LogsResponse =
        get("/api/nutrition/logs?date=$date") { bearerAuth(token) }.body()

    private suspend fun HttpClient.summaryOf(token: String, date: String): DaySummary =
        get("/api/nutrition/summary?date=$date") { bearerAuth(token) }.body()

    /** Vincula un panel como lo hace el usuario y devuelve su token. */
    private suspend fun HttpClient.panelToken(appToken: String): String {
        val code = post("/api/pairing/code") { bearerAuth(appToken) }.body<PairingCodeResponse>().code
        return post("/api/pairing/claim") { contentType(ContentType.Application.Json); setBody(ClaimRequest(code)) }.body<ClaimResponse>().token
    }

    // ─── Catálogo y objetivos ────────────────────────────────────────────────

    @Test fun mealsCatalogComesFromThePlan() = testApplication {
        val client = setUp()
        val meals = client.get("/api/nutrition/meals") { bearerAuth(keys.token(newUid())) }.body<MealsResponse>().meals
        assertEquals(
            listOf("D1", "D2", "D3", "C1", "C2", "C3", "C4", "M1", "M2", "M3", "Cn1", "Cn2", "Cn3", "Cn4"),
            meals.map { it.code },
        )
        assertEquals(mapOf("BREAKFAST" to 3, "LUNCH" to 4, "SNACK" to 3, "DINNER" to 4), meals.groupingBy { it.category }.eachCount())
        assertEquals(d1, meals.first { it.code == "D1" }.perServing)
        assertEquals(listOf(4), meals.first { it.code == "Cn4" }.planDays)
    }

    @Test fun goalsDefaultToThePlanAndCanBeUpdated() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        val initial = client.get("/api/nutrition/goals") { bearerAuth(token) }.body<GoalsResponse>()
        assertEquals(NutritionGoals(2450, 185.0, 65.0, 280.0), initial.goals) // tal cual, sin recalcular a 2445
        assertTrue(initial.isDefault)

        val new = NutritionGoals(2300, 190.0, 60.0, 250.5)
        val saved = client.put("/api/nutrition/goals") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(new) }
        assertEquals(HttpStatusCode.OK, saved.status)
        val after = client.get("/api/nutrition/goals") { bearerAuth(token) }.body<GoalsResponse>()
        assertEquals(new, after.goals)
        assertEquals(false, after.isDefault)
        val today = LocalDate.now(madrid).toString()
        assertEquals(new, client.summaryOf(token, today).goals)
        // Un día anterior al cambio conserva los objetivos que tenía (ver NutritionHistoryTest)
        assertEquals(NutritionRepository.DEFAULT_GOALS, client.summaryOf(token, "2026-10-20").goals)
    }

    @Test fun rejectsInvalidGoals() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        for (bad in listOf(
            NutritionGoals(100, 185.0, 65.0, 280.0),
            NutritionGoals(2450, -1.0, 65.0, 280.0),
            NutritionGoals(2450, 185.0, 5000.0, 280.0),
        )) {
            val r = client.put("/api/nutrition/goals") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(bad) }
            assertEquals(HttpStatusCode.BadRequest, r.status, bad.toString())
        }
        assertTrue(client.get("/api/nutrition/goals") { bearerAuth(token) }.body<GoalsResponse>().isDefault)
    }

    // ─── Registro ────────────────────────────────────────────────────────────

    @Test fun createReadAndTotals() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        val created = client.log(token, "D1")
        assertEquals(HttpStatusCode.Created, created.status)
        val log = created.body<NutritionLog>()
        assertEquals("Avena con proteína y plátano", log.mealName)
        assertEquals(d1, log.total)

        client.log(token, "C1", servings = 1.5)
        client.log(token, "C1") // la misma comida dos veces el mismo día es válido
        client.log(token, "M3", date = "2026-10-21") // otro día: no cuenta

        val logs = client.logsOf(token, "2026-10-20").logs
        assertEquals(listOf("D1", "C1", "C1"), logs.map { it.mealCode })

        val s = client.summaryOf(token, "2026-10-20")
        assertEquals(3, s.logs)
        // 710 + 645·1,5 + 645 = 2322,5 kcal; P 48 + 75 + 50; F 19 + 19,5 + 13; C 90 + 115,5 + 77
        assertEquals(Macros(2322.5, 173.0, 51.5, 282.5), s.consumed)
        assertEquals(Macros(190.0, 25.0, 2.0, 18.0), client.summaryOf(token, "2026-10-21").consumed)
    }

    @Test fun retriesWithTheSameIdDoNotDuplicate() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        val id = uuid()
        assertEquals(HttpStatusCode.Created, client.log(token, "D2", id = id).status)
        val retry = client.log(token, "D2", id = id)
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(id, retry.body<NutritionLog>().id)
        assertEquals(1, client.logsOf(token, "2026-10-20").logs.size)

        // Mismo id con otro contenido: es un error del cliente, no un reintento
        assertEquals(HttpStatusCode.Conflict, client.log(token, "C2", id = id).status)
        assertEquals(1, client.logsOf(token, "2026-10-20").logs.size)
    }

    @Test fun updateServingsAndDelete() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        val log = client.log(token, "C1").body<NutritionLog>()

        val patched = client.patch("/api/nutrition/logs/${log.id}") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(UpdateLogRequest(2.0))
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        assertEquals(c1 * 2.0, patched.body<NutritionLog>().total)
        assertEquals(1290.0, client.summaryOf(token, "2026-10-20").consumed.kcal)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/nutrition/logs/${log.id}") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/nutrition/logs/${log.id}") { bearerAuth(token) }.status) // repetir no falla
        assertTrue(client.logsOf(token, "2026-10-20").logs.isEmpty())
        assertEquals(Macros.ZERO, client.summaryOf(token, "2026-10-20").consumed)

        // Un registro borrado ya no se puede editar
        val again = client.patch("/api/nutrition/logs/${log.id}") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(UpdateLogRequest(1.0))
        }
        assertEquals(HttpStatusCode.NotFound, again.status)
    }

    @Test fun rejectsInvalidInput() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        assertEquals(HttpStatusCode.BadRequest, client.log(token, "D1", servings = 0.0).status)
        assertEquals(HttpStatusCode.BadRequest, client.log(token, "D1", servings = -1.0).status)
        assertEquals(HttpStatusCode.BadRequest, client.log(token, "D1", servings = 11.0).status)
        assertEquals(HttpStatusCode.BadRequest, client.log(token, "X9").status)
        assertEquals(HttpStatusCode.BadRequest, client.log(token, "D1", date = "20-10-2026").status)
        assertEquals(HttpStatusCode.BadRequest, client.log(token, "D1", date = "1999-12-31").status)
        assertEquals(HttpStatusCode.BadRequest, client.log(token, "D1", date = "2999-01-01").status)
        assertEquals(HttpStatusCode.BadRequest, client.log(token, "D1", id = "no-es-un-uuid").status)
        val raw = client.post("/api/nutrition/logs") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("{basura") }
        assertEquals(HttpStatusCode.BadRequest, raw.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/nutrition/logs?date=ayer") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.patch("/api/nutrition/logs/123") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(UpdateLogRequest(1.0))
        }.status)
        assertTrue(client.logsOf(token, "2026-10-20").logs.isEmpty())
    }

    // ─── Seguridad ───────────────────────────────────────────────────────────

    @Test fun requiresAuthentication() = testApplication {
        val client = setUp()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/nutrition/goals").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/nutrition/meals").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/nutrition/logs") {
            contentType(ContentType.Application.Json); setBody(CreateLogRequest(uuid(), "D1"))
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/nutrition/summary") { bearerAuth("gfp_inventado") }.status)
    }

    @Test fun usersAreIsolated() = testApplication {
        val client = setUp()
        val alice = keys.token(newUid())
        val bob = keys.token(newUid())
        val log = client.log(alice, "D1").body<NutritionLog>()

        assertTrue(client.logsOf(bob, "2026-10-20").logs.isEmpty())
        assertEquals(0, client.summaryOf(bob, "2026-10-20").logs)
        val patch = client.patch("/api/nutrition/logs/${log.id}") {
            bearerAuth(bob); contentType(ContentType.Application.Json); setBody(UpdateLogRequest(5.0))
        }
        assertEquals(HttpStatusCode.NotFound, patch.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/nutrition/logs/${log.id}") { bearerAuth(bob) }.status)
        // Reutilizar el id de otro usuario no le da acceso a su registro
        assertEquals(HttpStatusCode.Conflict, client.log(bob, "D1", id = log.id).status)

        client.put("/api/nutrition/goals") { bearerAuth(bob); contentType(ContentType.Application.Json); setBody(NutritionGoals(1800, 150.0, 50.0, 200.0)) }
        assertEquals(2450, client.get("/api/nutrition/goals") { bearerAuth(alice) }.body<GoalsResponse>().goals.kcal)
        assertEquals(listOf(d1), client.logsOf(alice, "2026-10-20").logs.map { it.total })
    }

    @Test fun pairedPanelCanWriteNutritionButNothingElse() = testApplication {
        val client = setUp()
        val uid = newUid()
        val app = keys.token(uid)
        val panel = client.panelToken(app)

        // Nutrición: el panel escribe y la app ve lo mismo (mismo usuario)
        assertEquals(HttpStatusCode.Created, client.log(panel, "M1").status)
        assertEquals(1, client.logsOf(app, "2026-10-20").logs.size)
        val goals = client.put("/api/nutrition/goals") { bearerAuth(panel); contentType(ContentType.Application.Json); setBody(NutritionGoals(2400, 180.0, 65.0, 270.0)) }
        assertEquals(HttpStatusCode.OK, goals.status)

        // El resto sigue siendo de solo lectura para el panel
        val push = client.post("/api/sync/push") {
            bearerAuth(panel); contentType(ContentType.Application.Json); setBody(PushRequest(listOf(change("workouts"))))
        }
        assertEquals(HttpStatusCode.Unauthorized, push.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/sync/pull") { bearerAuth(panel) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/pairing/code") { bearerAuth(panel) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/panel-tokens") { bearerAuth(panel) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/export") { bearerAuth(panel) }.status)
        assertEquals(0, client.get("/api/me") { bearerAuth(app) }.body<MeResponse>().records) // nada entró en sync

        // Revocar el panel corta también su acceso a nutrición
        client.delete("/api/panel-tokens") { bearerAuth(app) }
        assertEquals(HttpStatusCode.Unauthorized, client.log(panel, "M1").status)
    }

    @Test fun securityHeadersAndNoCrossOriginAccess() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        for (r in listOf(client.get("/health"), client.get("/api/nutrition/meals") { bearerAuth(token) })) {
            assertEquals(CONTENT_SECURITY_POLICY, r.headers["Content-Security-Policy"])
            assertEquals("nosniff", r.headers["X-Content-Type-Options"])
            assertEquals("DENY", r.headers["X-Frame-Options"])
            assertEquals("no-referrer", r.headers["Referrer-Policy"])
        }
        // Sin CORS_ORIGINS (como en producción) otra web no puede leer ni escribir desde el navegador
        val cross = client.post("/api/nutrition/logs") {
            bearerAuth(token); header(HttpHeaders.Origin, "https://evil.example")
            contentType(ContentType.Application.Json); setBody(CreateLogRequest(uuid(), "D1", "2026-10-20"))
        }
        assertEquals(null, cross.headers[HttpHeaders.AccessControlAllowOrigin])
        val preflight = client.options("/api/nutrition/logs") {
            header(HttpHeaders.Origin, "https://evil.example"); header(HttpHeaders.AccessControlRequestMethod, "POST")
        }
        assertEquals(null, preflight.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    // ─── Fechas ──────────────────────────────────────────────────────────────

    @Test fun todayFollowsTheConfiguredTimeZone() = testApplication {
        // 23:30 UTC del 20 → ya es día 21 en Madrid (horario de verano, UTC+2)
        val clock = MutableClock(Instant.parse("2026-07-20T23:30:00Z"))
        val client = setUp(clock, TestEnv.config.copy(nutritionZone = madrid))
        val token = keys.token(newUid())
        val log = client.log(token, "D3", date = null).body<NutritionLog>()
        assertEquals("2026-07-21", log.date)
        assertEquals("2026-07-21", client.get("/api/nutrition/summary") { bearerAuth(token) }.body<DaySummary>().date)
        // "Mañana" se acepta (margen de zona horaria), pasado mañana no
        assertEquals(HttpStatusCode.Created, client.log(token, "D3", date = "2026-07-22").status)
        assertEquals(HttpStatusCode.BadRequest, client.log(token, "D3", date = "2026-07-23").status)
    }

    @Test fun historyGroupsByDay() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        client.log(token, "D1", date = "2026-10-18")
        client.log(token, "C1", date = "2026-10-18")
        client.log(token, "M3", date = "2026-10-19")
        val gone = client.log(token, "Cn1", date = "2026-10-19").body<NutritionLog>()
        client.delete("/api/nutrition/logs/${gone.id}") { bearerAuth(token) }
        client.log(token, "D1", date = "2026-09-01") // fuera del rango

        val res: HttpResponse = client.get("/api/nutrition/days?from=2026-10-01&to=2026-10-20") { bearerAuth(token) }
        val days = res.body<DaysResponse>().days
        assertEquals(listOf("2026-10-19", "2026-10-18"), days.map { it.date })
        assertEquals(listOf(1, 2), days.map { it.logs })
        assertEquals(1355.0, days[1].consumed.kcal)
        assertEquals(190.0, days[0].consumed.kcal)

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/nutrition/days?from=2026-10-20&to=2026-10-01") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/nutrition/days?from=2024-01-01&to=2026-10-20") { bearerAuth(token) }.status)
    }

    // ─── Persistencia ────────────────────────────────────────────────────────

    @Test fun dataSurvivesARestart() = testApplication {
        val uid = newUid()
        val token = keys.token(uid)
        val client = setUp()
        val log = client.log(token, "Cn2", servings = 0.5).body<NutritionLog>()
        client.put("/api/nutrition/goals") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(NutritionGoals(2500, 185.0, 65.0, 290.0)) }

        // Otra conexión a PostgreSQL (como tras reiniciar el servidor): los datos siguen ahí
        Database(TestEnv.config.db).use { fresh ->
            val repo = NutritionRepository(fresh)
            assertEquals(listOf(log.id), repo.logs(uid, LocalDate.parse("2026-10-20")).map { it.id })
            assertEquals(0.5, repo.logs(uid, LocalDate.parse("2026-10-20")).single().servings)
            assertEquals(2500, repo.goals(uid, LocalDate.now(madrid)).goals.kcal)
        }
    }
}
