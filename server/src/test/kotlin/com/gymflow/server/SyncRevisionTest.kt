package com.gymflow.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Protocolo v2: revisión por fila, conflictos explícitos, idempotencia, tombstones, grupos atómicos. */
class SyncRevisionTest {
    private val keys get() = TestEnv.keys

    private suspend fun HttpClient.push(token: String, vararg changes: Change): HttpResponse =
        post("/api/sync/push") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(PushRequest(changes.toList())) }

    private suspend fun HttpClient.pushOk(token: String, vararg changes: Change): PushResponse = push(token, *changes).also { assertEquals(HttpStatusCode.OK, it.status) }.body()

    private suspend fun HttpClient.pull(token: String, since: Long = 0, limit: Int = 500): PullResponse =
        get("/api/sync/pull?since=$since&limit=$limit") { bearerAuth(token) }.body()

    private fun named(id: String, name: String, baseRev: Long, at: Long = 1000, deletedAt: Long? = null, group: String? = null) =
        change("routines", id, at, deletedAt, buildJsonObject { put("name", name) }, baseRev, group)

    @Test fun nuevaFilaSeAceptaYCadaCambioSubeLaRevision() = testApplication {
        val client = setUp(); val token = keys.token(newUid()); val id = uuid()
        assertEquals(1L, client.pushOk(token, named(id, "v1", 0)).results.single().rev)
        assertEquals(2L, client.pushOk(token, named(id, "v2", 1)).results.single().rev)
        assertEquals(3L, client.pushOk(token, named(id, "v3", 2)).results.single().rev)
        val row = client.pull(token).changes.single()
        assertEquals(3L, row.rev); assertEquals("v3", row.data["name"]!!.jsonPrimitive.content)
    }

    @Test fun reenviarElMismoCambioEsIdempotenteYNoMueveElCursor() = testApplication {
        val client = setUp(); val token = keys.token(newUid()); val id = uuid()
        client.pushOk(token, named(id, "v1", 0))
        val cursor = client.pull(token).cursor
        val again = client.pushOk(token, named(id, "v1", 0))                         // reintento tras un corte de red
        assertEquals("unchanged", again.results.single().status); assertEquals(1L, again.results.single().rev)
        val sameWithOtherClock = client.pushOk(token, named(id, "v1", 0, at = 99_000))   // mismo contenido, otra fecha del móvil
        assertEquals("unchanged", sameWithOtherClock.results.single().status)
        assertTrue(client.pull(token, since = cursor).changes.isEmpty(), "reenviar no debe generar cambios que bajar")
        assertEquals(2, again.accepted + sameWithOtherClock.accepted)
    }

    @Test fun unaBaseObsoletaEsConflictoYNoSobrescribeNiPorTenerFechaMasReciente() = testApplication {
        val client = setUp(); val token = keys.token(newUid()); val id = uuid()
        client.pushOk(token, named(id, "base", 0, at = 1000))
        client.pushOk(token, named(id, "de A", 1, at = 2000))                         // A parte de la rev 1 → rev 2
        val res = client.pushOk(token, named(id, "de B", 1, at = 9_999_999))          // B también partía de la rev 1 y tiene una fecha MUY posterior
        val r = res.results.single()
        assertEquals("conflict", r.status); assertEquals(1, res.ignored)
        assertNotNull(r.server); assertEquals("de A", r.server!!.data["name"]!!.jsonPrimitive.content); assertEquals(2L, r.server!!.rev)
        assertEquals("de A", client.pull(token).changes.single().data["name"]!!.jsonPrimitive.content)   // el servidor no se tocó
        // tras ver la rev 2, B puede subir su versión deliberadamente
        assertEquals("accepted", client.pushOk(token, named(id, "de B", 2, at = 3000)).results.single().status)
    }

    @Test fun unaFilaNuevaSobreUnaExistenteDistintaEsConflicto() = testApplication {
        val client = setUp(); val token = keys.token(newUid()); val id = uuid()
        client.pushOk(token, named(id, "original", 0))
        assertEquals("conflict", client.pushOk(token, named(id, "otro dispositivo cree que es nueva", 0)).results.single().status)
        assertEquals("original", client.pull(token).changes.single().data["name"]!!.jsonPrimitive.content)
    }

    @Test fun losTombstonesNoSeResucitanDesdeUnaBaseObsoleta() = testApplication {
        val client = setUp(); val token = keys.token(newUid()); val id = uuid()
        client.pushOk(token, named(id, "viva", 0))
        assertEquals("accepted", client.pushOk(token, named(id, "viva", 1, at = 2000, deletedAt = 2000)).results.single().status)   // A borra
        val stale = client.pushOk(token, named(id, "editada sin saber que se borró", 1, at = 3000)).results.single()                  // B edita con la rev 1
        assertEquals("conflict", stale.status)
        assertNotNull(stale.server!!.deletedAt)
        val row = client.pull(token).changes.single()
        assertNotNull(row.deletedAt); assertEquals(2L, row.rev)                          // sigue borrada
        // quien SÍ ha visto el borrado (base = rev actual) puede restaurarla de forma explícita
        assertEquals("accepted", client.pushOk(token, named(id, "restaurada", 2, at = 4000)).results.single().status)
    }

    @Test fun unGrupoSeAplicaEnteroONingunaParte() = testApplication {
        val client = setUp(); val token = keys.token(newUid()); val a = uuid(); val b = uuid(); val c = uuid()
        client.pushOk(token, named(b, "b1", 0)); client.pushOk(token, named(b, "b2", 1))     // b va por la rev 2
        val cursor = client.pull(token).cursor
        val res = client.pushOk(token, named(a, "nueva a", 0, group = "g"), named(b, "b stale", 1, group = "g"), named(c, "nueva c", 0, group = "g"))
        assertEquals(listOf("blocked", "conflict", "blocked"), res.results.map { it.status })
        assertTrue(client.pull(token, since = cursor).changes.isEmpty(), "no debe haberse aplicado ninguna parte del grupo")
        // sin conflicto, el grupo entra completo
        val ok = client.pushOk(token, named(a, "nueva a", 0, group = "h"), named(b, "b3", 2, group = "h"))
        assertEquals(listOf("accepted", "accepted"), ok.results.map { it.status })
    }

    @Test fun reenviarUnLoteCompletoYaAplicadoNoCambiaNada() = testApplication {
        val client = setUp(); val token = keys.token(newUid())
        val batch = Array(30) { change("workout_sets", data = buildJsonObject { put("reps", it) }) }
        client.pushOk(token, *batch)
        val before = client.pull(token)
        val again = client.pushOk(token, *batch)
        assertEquals(30, again.results.count { it.status == "unchanged" })
        val after = client.pull(token)
        assertEquals(before.changes.map { it.version to it.rev }, after.changes.map { it.version to it.rev })
        assertEquals(before.cursor, after.cursor)
    }

    @Test fun unServidorRestauradoAceptaFilasConBaseMayorQueLaSuya() = testApplication {
        val client = setUp(); val token = keys.token(newUid()); val id = uuid()
        val r = client.pushOk(token, named(id, "recreada", 7)).results.single()          // el cliente cree que va por la rev 7; el servidor no tiene la fila
        assertEquals("accepted", r.status); assertEquals(1L, r.rev)
    }

    @Test fun peticionesInvalidasSeRechazanSinGuardarNada() = testApplication {
        val client = setUp(); val token = keys.token(newUid()); val id = uuid()
        assertEquals(HttpStatusCode.BadRequest, client.push(token, named(id, "x", 0), named(id, "y", 0)).status)       // mismo registro dos veces
        assertEquals(HttpStatusCode.BadRequest, client.push(token, named(uuid(), "x", -1)).status)                        // baseRev negativa
        assertEquals(HttpStatusCode.BadRequest, client.push(token, named(uuid(), "x", 0, group = " ")).status)           // grupo vacío
        assertEquals(HttpStatusCode.BadRequest, client.push(token, named(uuid(), "x", 0, group = "g".repeat(101))).status)
        assertTrue(client.pull(token).changes.isEmpty())
    }

    @Test fun dosDispositivosSubiendoALaVezSoloUnoGanaYElOtroRecibeConflicto() = testApplication {
        val client = setUp(); val token = keys.token(newUid()); val id = uuid()
        val results = coroutineScope { (1..8).map { n -> async { client.pushOk(token, named(id, "dispositivo $n", 0)).results.single().status } }.awaitAll() }
        assertEquals(1, results.count { it == "accepted" }, results.toString())
        assertEquals(7, results.count { it == "conflict" })
        assertEquals(1L, client.pull(token).changes.single().rev)
    }

    @Test fun losConflictosNoFiltranDatosEntreUsuarios() = testApplication {
        val client = setUp(); val alice = keys.token(newUid()); val bob = keys.token(newUid()); val id = uuid()
        client.pushOk(alice, named(id, "secreto de Alice", 0))
        val r = client.pushOk(bob, named(id, "de Bob", 0)).results.single()              // Bob usa el mismo id: es SU fila, no hay conflicto con Alice
        assertEquals("accepted", r.status); assertEquals(null, r.server)
        assertEquals("de Bob", client.pull(bob).changes.single().data["name"]!!.jsonPrimitive.content)
    }

    @Test fun lasFilasAntiguasSinRevisionMigranConRevisionUno() = testApplication {
        val client = setUp(); val token = keys.token(newUid()); val id = uuid()
        client.pushOk(token, named(id, "x", 0))
        assertEquals(1L, client.pull(token).changes.single().rev)                        // el valor por defecto de la migración V4
    }

    @Test fun igualdadDeContenidoCoincideConElNucleoDelCliente() {
        val f = listOf(File("../contract/content-equality-cases.json"), File("contract/content-equality-cases.json")).first { it.exists() }
        val cases = Json.parseToJsonElement(f.readText()).jsonObject["cases"]!!.jsonArray
        assertTrue(cases.size >= 10)
        for (c in cases.map { it.jsonObject }) {
            val got = ContentEquality.same(c["a"]!!.jsonObject, c["aDeleted"]!!.jsonPrimitive.boolean, c["b"]!!.jsonObject, c["bDeleted"]!!.jsonPrimitive.boolean)
            assertEquals(c["same"]!!.jsonPrimitive.boolean, got, c["name"]!!.jsonPrimitive.content)
        }
    }
}
