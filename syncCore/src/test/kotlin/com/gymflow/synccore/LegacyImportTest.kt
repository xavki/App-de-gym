package com.gymflow.synccore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/** Importación heredada: incremental, reintentable, sin duplicados y sin confundir colisiones entre cuentas. Datos sintéticos, sin Firebase. */
class LegacyImportTest {
    private val items = (1..5).map { LegacyItem("routines", uuid("lr$it")) }

    /** «Base» sintética: id → propietario. */
    private class Db { val rows = linkedMapOf<String, String>(); var writes = 0 }

    private suspend fun run(uid: String, db: Db, failOn: String? = null, active: () -> Boolean = { true }) =
        runLegacyImport(uid, items,
            ownerOf = { i -> db.rows[i.id]?.let { Owner.User(it) } ?: Owner.Absent },
            write = { i -> if (i.id == failOn) throw IllegalStateException("fallo inyectado"); db.rows[i.id] = uid; db.writes++ },
            stillActive = active,
            atomically = { block -> val snap = LinkedHashMap(db.rows); try { block() } catch (e: Throwable) { db.rows.clear(); db.rows.putAll(snap); throw e } })

    @Test fun `un fallo entre agregados conserva lo escrito y el reintento termina sin duplicar`() = runBlocking {
        val db = Db()
        val r1 = run("A", db, failOn = items[2].id)
        assertEquals(false, r1.complete); assertEquals(items[2], r1.failedAt); assertEquals(2, r1.written)
        assertEquals(2, db.rows.size, "lo ya importado se conserva (no es atómico y así se documenta)")
        val r2 = run("A", db)                      // reintento sin fallo
        assertTrue(r2.complete); assertEquals(2, r2.alreadyImported); assertEquals(3, r2.written)
        assertEquals(5, db.rows.size); assertEquals(5, db.writes)       // cada agregado escrito exactamente una vez
        val r3 = run("A", db)                      // y repetirlo no cambia nada
        assertEquals(5, r3.alreadyImported); assertEquals(0, r3.written); assertEquals(5, db.writes)
    }

    @Test fun `un id de otra cuenta es colision y nunca se sobrescribe ni cuenta como ya importado`() = runBlocking {
        val db = Db(); db.rows[items[1].id] = "B"
        val r = run("A", db)
        assertTrue(r.complete)
        assertEquals(listOf(items[1]), r.collisions)
        assertEquals("B", db.rows[items[1].id], "la fila de B no se toca")
        assertEquals(4, r.written); assertEquals(0, r.alreadyImported)
    }

    @Test fun `la decision distingue ausente propio ajeno y catalogo global`() {
        assertEquals(LegacyDecision.WRITE, LegacyImportPolicy.decide("A", Owner.Absent))
        assertEquals(LegacyDecision.ALREADY_IMPORTED, LegacyImportPolicy.decide("A", Owner.User("A")))
        assertEquals(LegacyDecision.FOREIGN_COLLISION, LegacyImportPolicy.decide("A", Owner.User("B")))
        assertEquals(LegacyDecision.FOREIGN_COLLISION, LegacyImportPolicy.decide("A", Owner.Global))
    }

    @Test fun `si la cuenta cambia a mitad no se escribe nada mas`() = runBlocking {
        val db = Db()
        val r = run("A", db, active = { db.rows.size < 3 })      // la cuenta «cambia» al escribir el 3.º: ese se revierte, los 2 anteriores quedan confirmados
        assertEquals(2, db.rows.size); assertTrue(r.failure is IdentityChangedException); assertEquals(items[2], r.failedAt)
    }
}

/** R4-02: cambio de cuenta en cada punto de la importación heredada. */
class LegacyImportIdentityTest {
    private val item = LegacyItem(Tables.ROUTINES, uuid("lx1"))
    private val two = listOf(item, LegacyItem(Tables.ROUTINES, uuid("lx2")))

    /** Reproducción literal de Codex: la cuenta cambia dentro de ownerOf. */
    @Test fun `cuenta cambia durante ownerOf no escribe nada`() = runBlocking {
        var active = "A"; var writes = 0
        val r = runLegacyImport("A", listOf(item), ownerOf = { active = "B"; Owner.Absent }, write = { writes++ }, stillActive = { active == "A" })
        assertEquals(0, writes); assertEquals(false, r.complete); assertTrue(r.failure is IdentityChangedException); assertEquals(item, r.failedAt)
    }

    @Test fun `cuenta cambia entre la decision y la transaccion no escribe y la transaccion se revierte`() = runBlocking {
        var active = "A"; val db = linkedMapOf<String, String>(); var rolledBack = 0
        val r = runLegacyImport("A", listOf(item), ownerOf = { Owner.Absent },
            write = { db[it.id] = "A" }, stillActive = { active == "A" },
            atomically = { block ->
                val snapshot = LinkedHashMap(db); active = "B"       // la sesión cambia justo al abrir la transacción
                try { block() } catch (e: Throwable) { db.clear(); db.putAll(snapshot); rolledBack++; throw e }
            })
        assertTrue(db.isEmpty()); assertEquals(1, rolledBack); assertTrue(r.failure is IdentityChangedException)
    }

    @Test fun `otra cuenta reclama el id entre la consulta y la escritura se detecta dentro de la transaccion`() = runBlocking {
        var calls = 0; var writes = 0
        val r = runLegacyImport("A", listOf(item),
            ownerOf = { if (calls++ == 0) Owner.Absent else Owner.User("B") },      // 1.ª lectura: libre; relectura en la transacción: ya es de B
            write = { writes++ }, stillActive = { true })
        assertEquals(0, writes); assertTrue(r.failure is LegacyOwnershipChangedException)
    }

    @Test fun `cuenta cambia durante los logros no se escriben mas ni se marca completado`() = runBlocking {
        var active = "A"; var done = false; val written = mutableListOf<String>()
        val achievements = (1..3).map { LegacyItem(Tables.ACHIEVEMENTS, uuid("ac$it")) }
        val r = runLegacyImport("A", achievements,
            ownerOf = { if (it == achievements[1]) active = "B"; Owner.Absent },   // cambia al consultar el 2.º logro
            write = { written += it.id }, stillActive = { active == "A" }, onComplete = { done = true })
        assertEquals(listOf(achievements[0].id), written); assertEquals(false, done); assertEquals(false, r.complete)
        assertEquals(achievements[1], r.failedAt)
    }

    @Test fun `cuenta cambia justo antes de marcar completado no se marca`() = runBlocking {
        var checks = 0; var done = false
        val r = runLegacyImport("A", listOf(item), ownerOf = { Owner.Absent }, write = {},
            stillActive = { ++checks <= 4 },                   // por agregado: antes, tras ownerOf y 2 en la transacción = 4; la 5.ª (antes de onComplete) falla
            onComplete = { done = true })
        assertEquals(false, done); assertEquals(false, r.complete); assertTrue(r.failure is IdentityChangedException)
    }

    @Test fun `si todo sigue activo onComplete se ejecuta una vez y el resultado es completo`() = runBlocking {
        var done = 0
        val r = runLegacyImport("A", two, ownerOf = { Owner.Absent }, write = {}, stillActive = { true }, onComplete = { done++ })
        assertTrue(r.complete); assertEquals(2, r.written); assertEquals(1, done)
    }

    @Test fun `la cancelacion en ownerOf write o onComplete se propaga y no se convierte en fallo`() = runBlocking {
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            runLegacyImport("A", listOf(item), ownerOf = { throw kotlinx.coroutines.CancellationException("c") }, write = {}, stillActive = { true })
        }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            runLegacyImport("A", listOf(item), ownerOf = { Owner.Absent }, write = { throw kotlinx.coroutines.CancellationException("c") }, stillActive = { true })
        }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            runLegacyImport("A", listOf(item), ownerOf = { Owner.Absent }, write = {}, stillActive = { true }, onComplete = { throw kotlinx.coroutines.CancellationException("c") })
        }
        Unit
    }

    @Test fun `cancelacion durante write revierte la transaccion`() = runBlocking {
        val db = linkedMapOf<String, String>()
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            runLegacyImport("A", listOf(item), ownerOf = { Owner.Absent }, stillActive = { true },
                write = { db[it.id] = "A"; throw kotlinx.coroutines.CancellationException("cancelado") },
                atomically = { block -> val snap = LinkedHashMap(db); try { block() } catch (e: Throwable) { db.clear(); db.putAll(snap); throw e } })
        }
        assertTrue(db.isEmpty())
    }

    @Test fun `una colision con otra cuenta sigue sin escribirse y el onComplete se ejecuta tras informar`() = runBlocking {
        var done = false
        val r = runLegacyImport("A", two, ownerOf = { if (it == item) Owner.User("B") else Owner.Absent }, write = {}, stillActive = { true }, onComplete = { done = true })
        assertEquals(listOf(item), r.collisions); assertEquals(1, r.written); assertTrue(done)
    }
}

/** R5-02: intercalaciones controladas (CompletableDeferred) con rollback verificable y el caso A→B→A. */
class LegacyImportInterleavingTest {
    private class Store { val rows = linkedMapOf<String, String>(); var rollbacks = 0 }

    private fun atomicallyOn(st: Store): suspend (suspend () -> Unit) -> Unit = { block ->
        val snap = LinkedHashMap(st.rows)
        try { block() } catch (e: Throwable) { st.rows.clear(); st.rows.putAll(snap); st.rollbacks++; throw e }
    }

    private val a1 = LegacyItem(Tables.ROUTINES, uuid("i1"))
    private val a2 = LegacyItem(Tables.ROUTINES, uuid("i2"))
    private val ach = LegacyItem(Tables.ACHIEVEMENTS, uuid("iach"))

    /** Reproducción de Codex: la cuenta cambia MIENTRAS write está suspendido; el agregado en curso debe revertirse. */
    @Test fun `cambio mientras write esta suspendido revierte el agregado en curso y conserva los anteriores`() = runBlocking {
        val st = Store(); val guard = SessionGuard(); val token = guard.capture("A")!!
        val inside = kotlinx.coroutines.CompletableDeferred<Unit>(); val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = async {
            runLegacyImport("A", listOf(a1, a2), ownerOf = { Owner.Absent },
                write = { i -> st.rows[i.id] = "A"; if (i == a2) { inside.complete(Unit); release.await() } },    // a2 se suspende dentro de write
                stillActive = { guard.isCurrent(token, active.get()) },
                atomically = atomicallyOn(st))
        }
        inside.await(); active.set("B"); guard.bump(); release.complete(Unit)       // la sesión cambia con a2 ya escrito pero sin confirmar
        val r = job.await()
        assertEquals(setOf(a1.id), st.rows.keys, "a1 (confirmado antes) se conserva; a2 se revierte")
        assertEquals(1, st.rollbacks); assertEquals(false, r.complete); assertEquals(a2, r.failedAt); assertTrue(r.failure is IdentityChangedException)
        assertEquals(1, r.written)
    }
    private val active = java.util.concurrent.atomic.AtomicReference("A")
    @kotlin.test.BeforeTest fun reset() { active.set("A") }

    @Test fun `cambio mientras se escribe un logro revierte el logro y no marca completado`() = runBlocking {
        val st = Store(); val guard = SessionGuard(); val token = guard.capture("A")!!; var done = false
        val inside = kotlinx.coroutines.CompletableDeferred<Unit>(); val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = async {
            runLegacyImport("A", listOf(a1, ach), ownerOf = { Owner.Absent },
                write = { i -> st.rows[i.id] = "A"; if (i == ach) { inside.complete(Unit); release.await() } },
                stillActive = { guard.isCurrent(token, active.get()) }, atomically = atomicallyOn(st), onComplete = { done = true })
        }
        inside.await(); active.set("B"); guard.bump(); release.complete(Unit)
        val r = job.await()
        assertEquals(setOf(a1.id), st.rows.keys); assertEquals(false, done); assertEquals(ach, r.failedAt); assertEquals(1, st.rollbacks)
    }

    @Test fun `cambio justo antes del commit (tras terminar write) revierte`() = runBlocking {
        val st = Store(); var switchNow = false
        val r = runLegacyImport("A", listOf(a1), ownerOf = { Owner.Absent },
            write = { i -> st.rows[i.id] = "A"; switchNow = true },                // al terminar write la sesión ya no es la misma
            stillActive = { !switchNow }, atomically = atomicallyOn(st))
        assertTrue(st.rows.isEmpty()); assertEquals(1, st.rollbacks); assertTrue(r.failure is IdentityChangedException)
    }

    /** A→B→A: el UID vuelve a ser A, pero la generación cambió; con SessionGuard la operación se invalida. */
    @Test fun `A a B a A se detecta por generacion aunque el uid coincida`() = runBlocking {
        val st = Store(); val guard = SessionGuard(); val token = guard.capture("A")!!
        val r = runLegacyImport("A", listOf(a1, a2), ownerOf = { Owner.Absent },
            write = { i -> st.rows[i.id] = "A"; if (i == a1) { active.set("B"); guard.bump(); active.set("A"); guard.bump() } },
            stillActive = { guard.isCurrent(token, active.get()) }, atomically = atomicallyOn(st))
        assertEquals(true, active.get() == "A")                                    // el UID activo es A al final…
        assertTrue(st.rows.isEmpty(), "…pero a1 se revirtió y a2 no se intentó")
        assertEquals(a1, r.failedAt); assertTrue(r.failure is IdentityChangedException)
    }

    @Test fun `con comparar solo el uid A a B a A NO se detecta (por eso existe la generacion)`() = runBlocking {
        val st = Store()
        val r = runLegacyImport("A", listOf(a1, a2), ownerOf = { Owner.Absent },
            write = { i -> st.rows[i.id] = "A"; if (i == a1) { active.set("B"); active.set("A") } },
            stillActive = { active.get() == "A" }, atomically = atomicallyOn(st))
        assertTrue(r.complete && st.rows.size == 2)      // documenta la limitación del chequeo solo por UID
    }

    @Test fun `SessionGuard token invalido tras cualquier cambio y valido sin cambios`() {
        val g = SessionGuard(); val t = g.capture("A")!!
        assertTrue(g.isCurrent(t, "A")); assertEquals(false, g.isCurrent(t, "B")); assertEquals(false, g.isCurrent(t, null))
        g.bump(); assertEquals(false, g.isCurrent(t, "A")); assertTrue(g.isCurrent(g.capture("A")!!, "A"))
        assertEquals(null, g.capture(null))
    }

    @Test fun `reintento tras fallo parcial por cambio de cuenta continua sin duplicar`() = runBlocking {
        val st = Store(); var switched = true; var onA2 = false
        val r1 = runLegacyImport("A", listOf(a1, a2), ownerOf = { onA2 = it == a2; st.rows[it.id]?.let { u -> Owner.User(u) } ?: Owner.Absent },
            write = { i -> st.rows[i.id] = "A" }, stillActive = { !(switched && onA2) }, atomically = atomicallyOn(st))
        assertEquals(setOf(a1.id), st.rows.keys); assertEquals(false, r1.complete)
        switched = false
        val r2 = runLegacyImport("A", listOf(a1, a2), ownerOf = { st.rows[it.id]?.let { u -> Owner.User(u) } ?: Owner.Absent },
            write = { i -> st.rows[i.id] = "A" }, stillActive = { true }, atomically = atomicallyOn(st))
        assertTrue(r2.complete); assertEquals(1, r2.alreadyImported); assertEquals(1, r2.written); assertEquals(2, st.rows.size)
    }
}
