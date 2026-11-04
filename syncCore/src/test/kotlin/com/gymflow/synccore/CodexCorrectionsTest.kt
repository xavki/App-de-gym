package com.gymflow.synccore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Regresión de los hallazgos H1–H5 de la auditoría independiente de Codex (datos 100 % sintéticos). */
class CodexCorrectionsTest {
    private val state = FakeServerState()
    private fun server(uid: String = UID) = FakeServer(state, uid)

    private suspend fun pair(a: List<Rec> = emptyList(), b: List<Rec> = emptyList()): Pair<Device, Device> {
        val da = Device("A", a, server()); val db = Device("B", b, server())
        da.onboard(); da.run(); db.onboard(); db.run(); da.run()
        return da to db
    }

    // ───────────── H1: cambio de cuenta entre vista previa y confirmación ─────────────

    @Test fun `H1 importar - cambio de cuenta tras la vista previa no escribe nada`() = runBlocking {
        val d = Device("A", emptyList(), server()); d.onboard()
        val plan = ImportPlanner.plan(ExportParser.parse(exportJson(workoutRecs(1)).toByteArray(), UID), emptyList(), UID)
        d.store.live = "otra-cuenta"                       // la sesión cambió tras la vista previa
        assertFailsWith<IdentityChangedException> { ImportExecutor.apply(d.store, plan) }
        assertEquals(0, d.rows.size)
    }

    @Test fun `H1 importar - plan de una cuenta no se aplica en un almacen ligado a otra`() = runBlocking {
        val d = Device("A", emptyList(), server()); d.onboard()
        d.store.boundIdentity = "otra-cuenta"; d.store.live = "otra-cuenta"
        val plan = ImportPlanner.plan(ExportParser.parse(exportJson(workoutRecs(1)).toByteArray(), UID), emptyList(), UID)
        assertFailsWith<IdentityChangedException> { ImportExecutor.apply(d.store, plan) }
        assertEquals(0, d.rows.size)
    }

    @Test fun `H1 incorporacion - cambio de cuenta antes de confirmar no escribe ni activa nada`() = runBlocking {
        val d = Device("A", workoutRecs(1), server())
        val plan = d.plan(); val proof = d.backup()
        val before = d.rows.toMap()
        d.store.live = "otra-cuenta"
        assertFailsWith<IdentityChangedException> { Onboarding.apply(d.sync, plan, proof) }
        assertEquals(before, d.rows); assertEquals(false, d.store.metaState.onboarded)
    }

    @Test fun `H1 sincronizacion - cuenta distinta no sube ni baja y no cambia el estado`() = runBlocking {
        val s = server(); val d = Device("A", workoutRecs(1), s); d.onboard(); d.run()
        d.edit(Tables.WORKOUTS, uuid("w1"), "notes", JsonPrimitive("cambio"))
        val calls = s.pushCalls + s.pullCalls; val before = d.rows.toMap(); val meta = d.store.metaState
        d.store.live = "otra-cuenta"
        assertEquals(SyncOutcome.Failed(RemoteException.Kind.IDENTITY, false), d.run())
        assertEquals(calls, s.pushCalls + s.pullCalls); assertEquals(before, d.rows); assertEquals(meta, d.store.metaState)
    }

    @Test fun `H1 sincronizacion - cambio de cuenta DURANTE la descarga descarta la respuesta`() = runBlocking {
        val s = server(); val d = Device("B", emptyList(), s); d.onboard()          // B se incorpora con el servidor vacío…
        val src = Device("A", workoutRecs(1), server()); src.onboard(); src.run()   // …y luego A sube datos
        // la descarga se resuelve pero la sesión cambió mientras esperaba la red
        s.beforePull = { d.store.live = "otra-cuenta" }
        assertEquals(SyncOutcome.Failed(RemoteException.Kind.IDENTITY, false), d.run())
        assertEquals(0, d.rows.size)
    }

    @Test fun `H1 resolver conflicto - cambio de cuenta bloquea las dos resoluciones`() = runBlocking {
        val (a, b) = pair(routineRecs(1), routineRecs(1)); val rid = uuid("r1")
        a.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("A")); b.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("B")); a.run(); b.run()
        b.store.live = "otra-cuenta"
        val rows = b.rows.toMap()
        assertFailsWith<IdentityChangedException> { ConflictResolver.useRemote(b.sync, Key(Tables.ROUTINES, rid), 1) }
        assertFailsWith<IdentityChangedException> { ConflictResolver.keepLocal(b.sync, Key(Tables.ROUTINES, rid), 1) }
        assertEquals(rows, b.rows); assertEquals(1, b.store.conflictGroups.size); assertTrue(b.store.archived.isEmpty())
    }

    // ───────────── H2: aislamiento entre usuarios ─────────────

    @Test fun `H2 una fila ajena con el mismo id nunca se sustituye al importar`() = runBlocking {
        val d = Device("A", emptyList(), server()); d.onboard()
        val mine = workoutRecs(1)
        d.store.foreign[mine.first().key] = "uid-otro"
        val plan = ImportPlanner.plan(ExportParser.parse(exportJson(mine).toByteArray(), UID), emptyList(), UID)
        assertFailsWith<RecordRejectedException> { ImportExecutor.apply(d.store, plan) }
        assertEquals(0, d.rows.size)
    }

    @Test fun `H2 un hijo cuyo padre es de otra cuenta se rechaza`() {
        val kid = workoutRecs(1)[1]
        val lookup = OwnerLookup { t, i -> if (t == Tables.WORKOUTS && i == uuid("w1")) Owner.User("uid-otro") else Owner.Absent }
        assertFailsWith<ForeignRowException> { OwnerGuard.checkWrite(UID, kid, lookup) }
    }

    @Test fun `H2 ids no UUID tablas desconocidas o data id distinto se rechazan`() {
        val ok = workoutRecs(1).first(); val none = OwnerLookup { _, _ -> Owner.Absent }
        OwnerGuard.checkWrite(UID, ok, none)
        assertFailsWith<RecordRejectedException> { OwnerGuard.checkWrite(UID, ok.copy(id = "no-uuid"), none) }
        assertFailsWith<RecordRejectedException> { OwnerGuard.checkWrite(UID, ok.copy(table = "tabla_x"), none) }
        assertFailsWith<RecordRejectedException> { OwnerGuard.checkWrite(UID, ok.withStr("id", uuid("otro")), none) }
        assertFailsWith<ForeignRowException> { OwnerGuard.checkDelete(UID, ok.table, ok.id) { _, _ -> Owner.User("x") } }
    }

    @Test fun `H2 la descarga de una fila ajena revierte toda la transaccion`() = runBlocking {
        val d = Device("B", emptyList(), server()); d.onboard()
        val src = Device("A", workoutRecs(1) + routineRecs(2), server()); src.onboard(); src.run()
        d.store.foreign[Key(Tables.ROUTINES, uuid("r2"))] = "uid-otro"
        assertTrue(d.run() is SyncOutcome.Failed)
        assertEquals(0, d.rows.size); assertEquals(0L, d.store.metaState.cursor)
    }

    // ───────────── H3: resolver conservando la última edición local ─────────────

    @Test fun `H3 usar servidor archiva la ULTIMA edicion local y exige reconfirmar`() = runBlocking {
        val (a, b) = pair(routineRecs(1), routineRecs(1)); val rid = uuid("r1"); val root = Key(Tables.ROUTINES, rid)
        a.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("Servidor")); b.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("Local v1"))
        a.run(); b.run()
        b.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("Local v2 posterior al conflicto"))      // edición posterior
        val ex = assertFailsWith<ConflictResolver.LocalChangedSinceConflictException> { ConflictResolver.useRemote(b.sync, root, 5) }
        assertTrue(root in ex.changedKeys)
        assertEquals("Local v2 posterior al conflicto", b.rows.getValue(root).str("name")); assertTrue(b.store.archived.isEmpty())
        ConflictResolver.useRemote(b.sync, root, 5, acknowledgeLocalEdits = true)
        assertEquals("Servidor", b.rows.getValue(root).str("name"))
        val names = b.store.archived.flatMap { ArchiveRecovery.localRows(it.first) }.filter { it.table == Tables.ROUTINES }.map { it.str("name") }
        assertTrue("Local v2 posterior al conflicto" in names, "la última edición local debe quedar archivada: $names")
        assertTrue("Local v1" in names)
    }

    @Test fun `H3 una fila hija nueva creada despues del conflicto tambien se archiva y se avisa`() = runBlocking {
        val (a, b) = pair(routineRecs(1), routineRecs(1)); val rid = uuid("r1"); val root = Key(Tables.ROUTINES, rid)
        a.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("S")); b.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("L")); a.run(); b.run()
        val extra = routineRecs(1, setTag = "nuevo").first { it.table == Tables.ROUTINE_SETS }
        b.insert(listOf(extra))
        assertFailsWith<ConflictResolver.LocalChangedSinceConflictException> { ConflictResolver.useRemote(b.sync, root, 5) }
        ConflictResolver.useRemote(b.sync, root, 5, acknowledgeLocalEdits = true)
        assertTrue(b.store.archived.any { g -> ArchiveRecovery.localRows(g.first).any { it.id == extra.id } })
        assertTrue(b.rows.values.none { it.key == extra.key }, "la fila local que el servidor no tiene se retira solo tras archivarla")
    }

    @Test fun `H3 sin ediciones posteriores no hace falta reconfirmar y todo queda archivado`() = runBlocking {
        val (a, b) = pair(routineRecs(1), routineRecs(1)); val rid = uuid("r1")
        a.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("S")); b.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("L")); a.run(); b.run()
        ConflictResolver.useRemote(b.sync, Key(Tables.ROUTINES, rid), 5)
        assertEquals(1, b.store.archived.size); assertEquals("S", b.rows.getValue(Key(Tables.ROUTINES, rid)).str("name"))
    }

    @Test fun `H3 fallo a mitad de la resolucion no pierde nada`() = runBlocking {
        val (a, b) = pair(routineRecs(1), routineRecs(1)); val rid = uuid("r1")
        a.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("S")); b.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("L")); a.run(); b.run()
        val before = b.rows.toMap()
        b.store.failOnWrite = 2
        assertFailsWith<IllegalStateException> { ConflictResolver.useRemote(b.sync, Key(Tables.ROUTINES, rid), 5) }
        b.store.failOnWrite = 0
        assertEquals(before, b.rows); assertEquals(1, b.store.conflictGroups.size); assertTrue(b.store.archived.isEmpty())
    }

    // ───────────── H4: agregados y paginación ─────────────

    @Test fun `H4 un agregado local sucio frente a cambios remotos repartidos en paginas es un conflicto de agregado`() = runBlocking {
        val (a, b) = pair(routineRecs(1, exCount = 3, setCount = 3), routineRecs(1, exCount = 3, setCount = 3)); val rid = uuid("r1")
        // A cambia el nombre y una serie profunda; B cambia solo una serie: con páginas de 2 filas los cambios de A llegan en varias páginas
        val deepSet = a.rows.values.last { it.table == Tables.ROUTINE_SETS }
        a.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("A")); a.edit(deepSet.table, deepSet.id, "targetReps", JsonPrimitive(99)); a.run()
        val bSet = b.rows.values.first { it.table == Tables.ROUTINE_SETS }
        b.edit(bSet.table, bSet.id, "targetReps", JsonPrimitive(1))
        val small = SyncRunner(b.sync, b.server, { BASE_MS }, pageSize = 2).run() as SyncOutcome.Success
        assertEquals(1, small.openConflicts)
        assertEquals(1, b.rows.getValue(bSet.key).long("targetReps"), "lo local sigue intacto y nada remoto se aplicó a medias")
        assertNotEquals("A", b.rows.getValue(Key(Tables.ROUTINES, rid)).str("name"))
        assertTrue(b.store.conflictGroups.values.single().complete)
    }

    @Test fun `H4 un cambio de un hijo hermano concurrente se detecta aunque cada lado edite filas distintas`() = runBlocking {
        val (a, b) = pair(routineRecs(1), routineRecs(1))
        val sets = a.rows.values.filter { it.table == Tables.ROUTINE_SETS }
        a.edit(sets[0].table, sets[0].id, "targetReps", JsonPrimitive(50)); b.edit(sets[1].table, sets[1].id, "targetReps", JsonPrimitive(60))
        a.run(); val out = b.run() as SyncOutcome.Success
        assertEquals(1, out.openConflicts)
        assertEquals(10L, state.user(UID).rows.getValue(sets[1].key).data["targetReps"].let { (it as JsonPrimitive).content.toLong() }, "el servidor no recibió la mitad del agregado de B")
    }

    @Test fun `H4 un corte a mitad de la aplicacion local no deja agregados partidos ni avanza el cursor`() = runBlocking {
        val d = Device("B", emptyList(), server(), pageSize = 5); d.onboard()
        val src = Device("A", (0..3).flatMap { workoutRecs(it) }, server()); src.onboard(); src.run()
        d.store.failOnWrite = 7
        assertFailsWith<IllegalStateException> { d.run() }
        d.store.failOnWrite = 0
        assertNoOrphans(d.rows.values); assertEquals(0, d.rows.size, "ni una fila a medias"); assertEquals(0L, d.store.metaState.cursor)
        assertTrue(d.run() is SyncOutcome.Success); assertEquals(src.contentMap(), d.contentMap())
    }

    @Test fun `H4 un agregado demasiado grande se omite sin bloquear al resto`() = runBlocking {
        val d = Device("A", workoutRecs(1, exCount = 3, setCount = 3) + workoutRecs(2, exCount = 1, setCount = 1), server()); d.onboard()
        val out = SyncRunner(d.sync, d.server, { BASE_MS }, maxAggregateRows = 5).run() as SyncOutcome.Success
        assertEquals(1, out.oversized)
        assertEquals(3, state.user(UID).rows.size, "solo el entreno pequeño se subió")
    }

    // ───────────── H5: tipos ─────────────

    @Test fun `H5 las cadenas true false 1 y null se conservan como texto y no se convierten`() {
        for (s in listOf("true", "false", "1", "0", "null", "1.0", " ")) {
            val sql = SqlValues.toSql("workouts", "notes", "TEXT", JsonPrimitive(s)); assertEquals(SqlValue.Text(s), sql)
            assertEquals(JsonPrimitive(s), SqlValues.fromSql("workouts", "notes", sql))
        }
    }

    @Test fun `H5 booleanos solo en columnas booleanas y ida y vuelta exacta`() {
        assertEquals(SqlValue.Int(1), SqlValues.toSql("workout_sets", "completed", "INTEGER", JsonPrimitive(true)))
        assertEquals(JsonPrimitive(false), SqlValues.fromSql("workout_sets", "completed", SqlValue.Int(0)))
        assertFailsWith<ValueTypeException> { SqlValues.toSql("workout_sets", "completed", "INTEGER", JsonPrimitive("true")) }
        assertFailsWith<ValueTypeException> { SqlValues.toSql("workout_sets", "completed", "INTEGER", JsonPrimitive(1)) }
        assertFailsWith<ValueTypeException> { SqlValues.toSql("workouts", "notes", "TEXT", JsonPrimitive(true)) }
        assertFailsWith<ValueTypeException> { SqlValues.toSql("workouts", "durationSeconds", "INTEGER", JsonPrimitive(false)) }
        assertFailsWith<ValueTypeException> { SqlValues.fromSql("workout_sets", "completed", SqlValue.Int(2)) }
    }

    @Test fun `H5 numeros extremos y tipos incompatibles`() {
        assertEquals(SqlValue.Int(Long.MAX_VALUE), SqlValues.toSql("workouts", "startedAt", "INTEGER", JsonPrimitive(Long.MAX_VALUE)))
        assertEquals(SqlValue.Int(Long.MIN_VALUE), SqlValues.toSql("workouts", "startedAt", "INTEGER", JsonPrimitive(Long.MIN_VALUE)))
        assertFailsWith<ValueTypeException> { SqlValues.toSql("workouts", "startedAt", "INTEGER", JsonPrimitive("12")) }
        assertFailsWith<ValueTypeException> { SqlValues.toSql("workouts", "startedAt", "INTEGER", JsonPrimitive(1.5)) }
        assertFailsWith<ValueTypeException> { SqlValues.toSql("workouts", "notes", "TEXT", JsonPrimitive(3)) }
        assertEquals(SqlValue.Real(80.5), SqlValues.toSql("workouts", "bodyweightKg", "REAL", JsonPrimitive(80.5)))
        assertEquals(SqlValue.Null, SqlValues.toSql("workouts", "notes", "TEXT", JsonNull))
    }

    // ───────────── parser: unidades, tamaño de agregado, tipo de fecha ─────────────

    @Test fun `parser - unidades distintas de kg cm s invalidan el archivo`() {
        val json = exportJson(workoutRecs(1)).replace("\"weight\":\"kg\"", "\"weight\":\"lb\"")
        val ex = assertFailsWith<ImportFileException> { ExportParser.parse(json.toByteArray(), UID) }
        assertEquals(ImportError.BAD_FIELD, ex.code)
    }

    @Test fun `parser - un registro con demasiadas filas se marca invalido sin tumbar el resto`() {
        val big = workoutRecs(1, exCount = 2, setCount = 3) + workoutRecs(2, exCount = 1, setCount = 1)
        val parsed = ExportParser.parse(exportJson(big).toByteArray(), UID, ImportLimits(maxRowsPerAggregate = 5))
        assertEquals(1, parsed.invalid.size); assertEquals(1, parsed.aggregates.size)
    }

    @Test fun `parser - updatedAt numerico es un registro invalido y no se acepta como fecha`() {
        val json = exportJson(workoutRecs(1)).replaceFirst(Regex("\"updatedAt\":\"[^\"]+\""), "\"updatedAt\":12345")
        val parsed = ExportParser.parse(json.toByteArray(), UID)
        assertEquals(1, parsed.invalid.size); assertEquals(0, parsed.aggregates.size)
    }
}
