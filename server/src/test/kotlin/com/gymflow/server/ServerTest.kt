package com.gymflow.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerTest {

    private val keys get() = TestEnv.keys

    private suspend fun HttpClient.push(token: String, vararg changes: Change): HttpResponse =
        post("/api/sync/push") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(PushRequest(changes.toList())) }

    private suspend fun HttpClient.pull(token: String, since: Long = 0, limit: Int = 500): PullResponse =
        get("/api/sync/pull?since=$since&limit=$limit") { bearerAuth(token) }.body()

    // ─── Autenticación ──────────────────────────────────────────────────────

    @Test fun healthIsPublic() = testApplication {
        val client = setUp()
        val r = client.get("/health")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"ok\""))
    }

    @Test fun rejectsMissingOrInvalidTokens() = testApplication {
        val client = setUp()
        val uid = newUid()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me").status)
        val otherKey = TestKeys().pair
        for (bad in listOf(
            keys.token(uid, signWith = otherKey),           // firma de otra clave
            keys.token(uid, project = "otro-proyecto"),     // audiencia/emisor de otro proyecto
            keys.token(uid, expiresInSec = -3600),          // caducado
            "basura",
        )) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { bearerAuth(bad) }.status, bad.take(20))
        }
        val ok = client.get("/api/me") { bearerAuth(keys.token(uid, email = "a@b.c")) }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(uid, ok.body<MeResponse>().uid)
    }

    // ─── Sincronización ─────────────────────────────────────────────────────

    @Test fun pushThenPullRoundTrip() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        val a = change("routines", data = buildJsonObject { put("name", "Push") })
        val b = change("workouts")
        val c = change("workout_sets", deletedAt = 500)
        val res = client.push(token, a, b, c).body<PushResponse>()
        assertEquals(PushResponse(3, 0), res)

        val pulled = client.pull(token)
        assertEquals(setOf(a.id, b.id, c.id), pulled.changes.map { it.id }.toSet())
        assertEquals(pulled.changes.map { it.version }.sorted(), pulled.changes.map { it.version })
        assertEquals("Push", pulled.changes.first { it.id == a.id }.data["name"]!!.jsonPrimitive.content)
        assertEquals(500L, pulled.changes.first { it.id == c.id }.deletedAt)
        assertEquals(false, pulled.hasMore)

        assertTrue(client.pull(token, since = pulled.cursor).changes.isEmpty())
    }

    @Test fun lastWriteWins() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        val id = uuid()
        fun named(name: String, at: Long) = change("routines", id, at, data = buildJsonObject { put("name", name) })

        client.push(token, named("v2", 2000))
        assertEquals(PushResponse(0, 1), client.push(token, named("viejo", 1000)).body())   // más antiguo: ignorado
        assertEquals(PushResponse(0, 1), client.push(token, named("igual", 2000)).body())   // misma fecha: ignorado
        val first = client.pull(token)
        assertEquals("v2", first.changes.single().data["name"]!!.jsonPrimitive.content)

        assertEquals(PushResponse(1, 0), client.push(token, named("v3", 3000)).body())
        val next = client.pull(token, since = first.cursor)
        assertEquals("v3", next.changes.single().data["name"]!!.jsonPrimitive.content)
        assertTrue(next.changes.single().version > first.cursor)
    }

    @Test fun usersAreIsolated() = testApplication {
        val client = setUp()
        val alice = keys.token(newUid())
        val bob = keys.token(newUid())
        val shared = uuid()
        client.push(alice, change("routines", shared, 1000, data = buildJsonObject { put("name", "de Alice") }))
        // Bob usa el mismo id: no pisa a Alice, tiene su propio registro
        client.push(bob, change("routines", shared, 9000, data = buildJsonObject { put("name", "de Bob") }))
        assertEquals("de Alice", client.pull(alice).changes.single().data["name"]!!.jsonPrimitive.content)
        assertEquals("de Bob", client.pull(bob).changes.single().data["name"]!!.jsonPrimitive.content)
    }

    @Test fun pullPaginates() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        client.push(token, *Array(25) { change("workout_sets") })
        val seen = mutableListOf<String>()
        var cursor = 0L
        var pages = 0
        do {
            val page = client.pull(token, since = cursor, limit = 10)
            seen += page.changes.map { it.id }
            cursor = page.cursor
            pages++
        } while (page.hasMore)
        assertEquals(3, pages)
        assertEquals(25, seen.toSet().size)
    }

    @Test fun cursorAheadOfServerResets() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        client.push(token, change("routines"), change("routines"))
        val r = client.pull(token, since = 999_999)
        assertTrue(r.reset)
        assertEquals(2, r.changes.size)
    }

    @Test fun validatesChanges() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        val future = System.currentTimeMillis() + Duration.ofDays(3).toMillis()
        for (bad in listOf(
            change("passwords"),
            change("routines", id = "no-es-un-uuid"),
            change("routines", updatedAt = future),
            change("routines", updatedAt = 0),
        )) {
            assertEquals(HttpStatusCode.BadRequest, client.push(token, bad).status, bad.toString())
        }
        assertEquals(HttpStatusCode.BadRequest, client.push(token, *Array(2001) { change("workout_sets") }).status)
        // Nada de lo anterior se ha guardado
        assertTrue(client.pull(token).changes.isEmpty())
    }

    // ─── Exportación (mismo formato que la app) ─────────────────────────────

    @Test fun exportMatchesAppFormat() = testApplication {
        val client = setUp()
        val uid = newUid()
        val token = keys.token(uid)
        val changes = Json.decodeFromString<PushRequest>(contractFile("sync-push-example.json").readText())
        assertEquals(HttpStatusCode.OK, client.push(token, *changes.changes.toTypedArray()).status)

        val export = Json.parseToJsonElement(client.get("/api/export") { bearerAuth(token) }.bodyAsText()).jsonObject
        val expected = Json.parseToJsonElement(contractFile("export-example.json").readText()).jsonObject
        assertEquals(shape(expected), shape(export), "La exportación del servidor debe tener la misma forma que la de la app")

        val workout = export["workouts"]!!.jsonArray.single().jsonObject
        assertEquals("Push, heavy", workout["name"]!!.jsonPrimitive.content)
        val exercises = workout["exercises"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Bench Press", "Overhead Press"), exercises.map { it["name"]!!.jsonPrimitive.content })
        assertEquals("Pecho", exercises[0]["mainGroup"]!!.jsonPrimitive.content)
        assertEquals(2, exercises[0]["sets"]!!.jsonArray.size)
        assertEquals(1, export["schemaVersion"]!!.jsonPrimitive.int)
    }

    @Test fun exportHidesDeletedRowsAndOrphans() = testApplication {
        val client = setUp()
        val token = keys.token(newUid())
        val w = uuid()
        val ex = uuid()
        client.push(token,
            change("workouts", w, data = buildJsonObject { put("id", w); put("name", "Borrado"); put("startedAt", 1000) }),
            change("workout_exercises", ex, data = buildJsonObject { put("id", ex); put("workoutId", w); put("exerciseName", "X"); put("position", 0) }),
        )
        client.push(token, change("workouts", w, updatedAt = 2000, deletedAt = 2000, data = buildJsonObject { put("id", w) }))
        val export = Json.parseToJsonElement(client.get("/api/export") { bearerAuth(token) }.bodyAsText()).jsonObject
        assertEquals(0, export["workouts"]!!.jsonArray.size)
    }

    // ─── Vinculación del panel ──────────────────────────────────────────────

    @Test fun pairingGivesReadOnlyPanelToken() = testApplication {
        val clock = MutableClock()
        val client = setUp(clock)
        val token = keys.token(newUid())
        client.push(token, change("routines"))

        val code = client.post("/api/pairing/code") { bearerAuth(token) }.body<PairingCodeResponse>()
        assertTrue(Regex("^[A-Z2-9]{4}-[A-Z2-9]{4}$").matches(code.code), code.code)

        val claim = client.post("/api/pairing/claim") {
            contentType(ContentType.Application.Json); setBody(ClaimRequest(code.code.lowercase().replace("-", " "), "PC"))
        }
        assertEquals(HttpStatusCode.OK, claim.status)
        val panel = claim.body<ClaimResponse>().token

        assertEquals(HttpStatusCode.OK, client.get("/api/export") { bearerAuth(panel) }.status)
        // Solo lectura: no puede sincronizar ni pedir códigos
        assertEquals(HttpStatusCode.Unauthorized, client.push(panel, change("routines")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/pairing/code") { bearerAuth(panel) }.status)

        // Un código solo sirve una vez
        val again = client.post("/api/pairing/claim") { contentType(ContentType.Application.Json); setBody(ClaimRequest(code.code)) }
        assertEquals(HttpStatusCode.NotFound, again.status)

        // Revocar desde la app corta el acceso
        assertEquals(1, client.delete("/api/panel-tokens") { bearerAuth(token) }.body<RevokeResponse>().revoked)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/export") { bearerAuth(panel) }.status)
    }

    @Test fun pairingCodesExpire() = testApplication {
        val clock = MutableClock()
        val client = setUp(clock)
        val token = keys.token(newUid())
        val code = client.post("/api/pairing/code") { bearerAuth(token) }.body<PairingCodeResponse>()
        clock.now = clock.now.plus(Duration.ofMinutes(11))
        val r = client.post("/api/pairing/claim") { contentType(ContentType.Application.Json); setBody(ClaimRequest(code.code)) }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test fun servesPanelWhenConfigured() = testApplication {
        val dir = kotlin.io.path.createTempDirectory("static").toFile().apply { resolve("index.html").writeText("<h1>panel</h1>") }
        val client = setUp(config = TestEnv.config.copy(staticDir = dir.path))
        val r = client.get("/")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("panel"))
    }

    private fun contractFile(name: String): File =
        listOf(File("../contract/$name"), File("contract/$name")).firstOrNull { it.exists() }
            ?: error("No encuentro contract/$name")

    /** Estructura de claves (recursiva) sin valores; los arrays se representan por su primer elemento. */
    private fun shape(e: JsonElement): Any? = when (e) {
        is JsonObject -> e.mapValues { shape(it.value) }.toSortedMap()
        is JsonArray -> listOfNotNull(e.firstOrNull()?.let(::shape))
        else -> "valor"
    }.also { assertNotNull(e) }
}
