package com.gymflow.synccore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

class ImportPlannerTest {
    private fun parsed(recs: List<Rec>, uid: String = UID) = ExportParser.parse(exportJson(recs, uid = uid).toByteArray(), UID)
    private fun plan(file: List<Rec>, local: List<Rec>, fileUid: String = UID) = ImportPlanner.plan(parsed(file, fileUid), local, UID)
    private fun apply(store: MemStore, p: ImportPlan, c: ImportChoices = ImportChoices()) = runBlocking { ImportExecutor.apply(store, p, c) }

    @Test fun `registros nuevos se insertan con todas sus relaciones`() {
        val local = MemStore()
        val p = plan(workoutRecs(1) + routineRecs(1) + achievementRec("a"), emptyList())
        assertEquals(3, p.newCount); assertEquals(0, p.conflictCount)
        val r = apply(local, p)
        assertEquals(3, r.insertedAggregates); assertEquals(local.rows.size, r.insertedRows)
        val idx = RecIndex(local.rows.values)
        assertTrue(local.rows.values.all { it.dirty && it.rev == 0L })
        assertEquals(6, idx.aggregate(Key(Tables.WORKOUTS, uuid("w1"))).count { it.table == Tables.WORKOUT_SETS })
    }

    @Test fun `mismo id y mismo contenido se omite y la importacion repetida es idempotente`() {
        val file = workoutRecs(1) + routineRecs(1) + listOf(measurementRec(1), customExerciseRec(1), achievementRec("a"))
        val store = MemStore()
        apply(store, plan(file, emptyList()))
        val snapshot = store.rows.toMap()
        val again = plan(file, store.rows.values.toList())
        assertEquals(0, again.newCount); assertEquals(5, again.identicalCount); assertEquals(0, again.conflictCount)
        assertEquals(false, again.canApply())
        assertEquals(snapshot, store.rows)                                     // nada cambia
    }

    @Test fun `un local con milisegundos y ids de serie de rutina distintos sigue siendo identico`() {
        val localWorkout = workoutRecs(1, startedAt = BASE_MS + 86_400_000L + 123)          // el formato exporta al segundo
        val localRoutine = routineRecs(1, setTag = "otro-dispositivo")                       // ids de serie distintos: no se exportan
        val p = plan(workoutRecs(1) + routineRecs(1), localWorkout + localRoutine)
        assertEquals(2, p.identicalCount); assertEquals(0, p.conflictCount)
    }

    @Test fun `mismo id y contenido diferente es un conflicto visible y nunca se sobrescribe`() {
        val local = workoutRecs(1)
        val file = workoutRecs(1).map { if (it.table == Tables.WORKOUT_SETS && it.str("position") == "1") it.withNum("reps", 99) else it }
        val p = plan(file, local)
        val item = p.items.single()
        assertEquals(ItemStatus.CONFLICT, item.status)
        assertTrue(item.diffs.any { it.contains("reps") }, "debe indicar qué campo difiere: ${item.diffs}")
        assertEquals(false, p.canApply())
        val store = MemStore(local); val before = store.rows.toMap()
        assertFailsWith<IllegalArgumentException> { apply(store, p) }              // sin elección explícita no hay nada que aplicar
        assertEquals(before, store.rows)
    }

    @Test fun `un conflicto se puede conservar como copia y nunca pisa el original`() {
        val local = workoutRecs(1)
        val file = workoutRecs(1, name = "Entreno modificado")
        val store = MemStore(local); val before = store.rows.toMap()
        val p = plan(file, local)
        val root = p.items.single().rootKey
        val res = apply(store, p, ImportChoices(importAsCopy = setOf(root)))
        assertEquals(1, res.copies)
        before.forEach { (k, v) -> assertEquals(v, store.rows[k]) }              // lo original, intacto
        assertEquals(before.size * 2, store.rows.size)
        val copy = store.rows.values.single { it.table == Tables.WORKOUTS && it.id != uuid("w1") }
        assertEquals("Entreno modificado (importado)", copy.str("name"))
        assertTrue(RecIndex(store.rows.values).aggregate(copy.key).size == before.size)  // copia completa con sus relaciones
        // repetir la importación como copia es idempotente: el plan ya lo reconoce y no escribe nada
        val p2 = plan(file, store.rows.values.toList())
        assertEquals(ItemStatus.IDENTICAL, p2.items.single().status); assertEquals(false, p2.canApply(ImportChoices(importAsCopy = setOf(root))))
        assertEquals(before.size * 2, store.rows.size)
    }

    @Test fun `un registro borrado localmente no se resucita sin decision`() {
        val deleted = workoutRecs(1).map { if (it.table == Tables.WORKOUTS) it.softDeleted() else it }
        val p = plan(workoutRecs(1), deleted)
        assertEquals(ItemStatus.DELETED_LOCALLY, p.items.single().status)
        assertEquals(false, p.canApply())
    }

    @Test fun `mismo nombre y distinto id no se deduplica automaticamente y se avisa`() {
        val local = routineRecs(1, name = "Push")
        val file = routineRecs(2, name = "Push")
        val p = plan(file, local)
        assertEquals(1, p.newCount); assertTrue(p.warnings.any { it.contains("mismo nombre") })
        val store = MemStore(local)
        apply(store, p)
        assertEquals(2, store.rows.values.count { it.table == Tables.ROUTINES })
    }

    @Test fun `una colision de ids hijos con otro registro es conflicto`() {
        val other = workoutRecs(1)
        val clash = workoutRecs(2).toMutableList().apply {      // el entreno 2 reutiliza un id de serie del 1
            val i = indexOfFirst { it.table == Tables.WORKOUT_SETS }
            val sharedId = other.first { it.table == Tables.WORKOUT_SETS }.id
            this[i] = this[i].copy(id = sharedId).withStr("id", sharedId)
        }
        val p = plan(clash, other)
        assertEquals(ItemStatus.CONFLICT, p.items.single().status)
    }

    @Test fun `identidad distinta bloquea salvo confirmacion explicita`() {
        val p = plan(workoutRecs(1), emptyList(), fileUid = "otra-cuenta")
        assertEquals(Identity.MISMATCH, p.identity)
        assertEquals(false, p.canApply())
        assertEquals(true, p.canApply(ImportChoices(allowForeignAccount = true)))
        val store = MemStore()
        apply(store, p, ImportChoices(allowForeignAccount = true))
        assertTrue(store.rows.values.filter { it.table == Tables.WORKOUTS }.all { it.str("userId") == UID })   // siempre a nombre de la sesión activa
    }

    @Test fun `la importacion es atomica un fallo a mitad no deja nada`() {
        val local = workoutRecs(0)
        val store = MemStore(local); val before = store.rows.toMap()
        val p = plan(workoutRecs(1) + workoutRecs(2) + routineRecs(1), local)
        for (failAt in listOf(1, 5, 12, 20)) {
            store.failOnWrite = failAt
            assertFailsWith<IllegalStateException> { apply(store, p) }
            assertEquals(before, store.rows, "fallo en la escritura $failAt dejó filas parciales")
        }
        store.failOnWrite = 0
        apply(store, p)                                                         // y luego funciona
        assertEquals(3, p.newCount)
    }

    @Test fun `si la base cambia entre la vista previa y la confirmacion el plan se invalida`() {
        val store = MemStore(workoutRecs(0))
        val p = plan(workoutRecs(1), store.rows.values.toList())
        store.rows.putAll(workoutRecs(7).associateBy { it.key })                 // otro proceso escribe mientras se mostraba la vista previa
        val before = store.rows.toMap()
        assertFailsWith<StaleImportPlanException> { apply(store, p) }
        assertEquals(before, store.rows)
        apply(store, plan(workoutRecs(1), store.rows.values.toList()))           // recalcular funciona
    }

    @Test fun `cancelar no escribe nada y analizar no modifica la base`() {
        val store = MemStore(workoutRecs(0)); val before = store.rows.toMap()
        plan(workoutRecs(1) + routineRecs(1), store.rows.values.toList())
        assertEquals(before, store.rows)
    }

    @Test fun `los datos locales se conservan idénticos tras importar`() {
        val local = workoutRecs(0) + routineRecs(0) + listOf(achievementRec("x")) + workoutRecs(9).map { if (it.table == Tables.WORKOUTS) it.softDeleted() else it }
        val store = MemStore(local); val before = store.rows.toMap()
        apply(store, plan(workoutRecs(1) + workoutRecs(0) + routineRecs(1), local))
        before.forEach { (k, v) -> assertEquals(v, store.rows[k], "cambió ${k.table}/${k.id}") }
    }

    @Test fun `el recuento de conflictos por logro distinto se informa y no se pisa`() {
        val local = listOf(achievementRec("first", at = BASE_MS))
        val p = plan(listOf(achievementRec("first", at = BASE_MS + 5000)), local)
        assertEquals(ItemStatus.CONFLICT, p.items.single().status)
        assertEquals(JsonPrimitive(BASE_MS), MemStore(local).rows.values.single().data["unlockedAt"])
    }
}
