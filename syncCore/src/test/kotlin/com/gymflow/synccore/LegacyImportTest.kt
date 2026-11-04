package com.gymflow.synccore

import kotlin.test.Test
import kotlin.test.assertEquals
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
        val db = Db(); var n = 0
        val r = run("A", db, active = { n++ < 2 })
        assertEquals(2, db.rows.size); assertTrue(r.failure is IdentityChangedException); assertEquals(items[2], r.failedAt)
    }
}
