package com.gymflow.synccore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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
            stillActive = active)

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
        val r = run("A", db, active = { db.rows.size < 2 })      // la cuenta «cambia» tras escribir 2 agregados (no depende de cuántas comprobaciones haga el helper)
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
