package com.gymflow.synccore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/** Regresión de los hallazgos N-02..N-06 de la segunda auditoría de Codex (datos sintéticos). */
class CodexRound3Test {
    private val state = FakeServerState()
    private fun server() = FakeServer(state, UID)

    // N-02: importar sin servidor configurado (la identidad local no incluye servidor)
    @Test fun `N-02 importar JSON sin servidor funciona con identidad local`() = runBlocking {
        val st = MemStore(); st.boundIdentity = OperationIdentity.of(UID, "local"); st.live = st.boundIdentity
        val plan = ImportPlanner.plan(ExportParser.parse(exportJson(workoutRecs(1)).toByteArray(), UID), emptyList(), UID, st.boundIdentity)
        ImportExecutor.apply(st, plan)
        assertEquals(workoutRecs(1).size, st.rows.size)
        // idempotente: repetirlo no cambia ni duplica
        val again = ImportPlanner.plan(ExportParser.parse(exportJson(workoutRecs(1)).toByteArray(), UID), st.rows.values.toList(), UID, st.boundIdentity)
        assertEquals(0, again.newCount)
    }

    @Test fun `N-02 aun sin servidor un cambio de cuenta sigue bloqueando`() = runBlocking {
        val st = MemStore(); st.boundIdentity = OperationIdentity.of(UID, "local"); st.live = st.boundIdentity
        val plan = ImportPlanner.plan(ExportParser.parse(exportJson(workoutRecs(1)).toByteArray(), UID), emptyList(), UID, st.boundIdentity)
        st.live = OperationIdentity.of("otra", "local")
        assertFailsWith<IdentityChangedException> { ImportExecutor.apply(st, plan) }
        assertEquals(0, st.rows.size)
    }

    // N-03: el uid con el que se interpreta el archivo y el de la operación deben coincidir
    @Test fun `N-03 plan con uid A y contexto de operacion B se rechaza al preparar`() {
        val parsed = ExportParser.parse(exportJson(workoutRecs(1)).toByteArray(), UID)
        assertFailsWith<IdentityChangedException> { ImportPlanner.plan(parsed, emptyList(), UID, OperationIdentity.of("uid-B", "local")) }
    }

    // N-04: el resolver revalida justo antes de confirmar
    @Test fun `N-04 useRemote con cambio de cuenta durante la resolucion revierte todo`() = runBlocking {
        val a = Device("A", routineRecs(1), server()); a.onboard(); a.run()
        val b = Device("B", routineRecs(1), server()); b.onboard(); b.run(); a.run()
        val rid = uuid("r1")
        a.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("A")); b.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("B")); a.run(); b.run()
        val before = b.rows.toMap()
        b.store.onArchive = { b.store.live = "otra-cuenta" }
        assertFailsWith<IdentityChangedException> { ConflictResolver.useRemote(b.sync, Key(Tables.ROUTINES, rid), 1) }
        assertEquals(before, b.rows); assertEquals(1, b.store.conflictGroups.size); assertTrue(b.store.archived.isEmpty())
    }

    @Test fun `N-04 keepLocal con cambio de cuenta durante la resolucion revierte todo`() = runBlocking {
        val a = Device("A", routineRecs(1), server()); a.onboard(); a.run()
        val b = Device("B", routineRecs(1), server()); b.onboard(); b.run(); a.run()
        val rid = uuid("r1")
        a.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("A")); b.edit(Tables.ROUTINES, rid, "name", JsonPrimitive("B")); a.run(); b.run()
        val before = b.rows.toMap()
        b.store.onArchive = { b.store.live = "otra-cuenta" }
        assertFailsWith<IdentityChangedException> { ConflictResolver.keepLocal(b.sync, Key(Tables.ROUTINES, rid), 1) }
        assertEquals(before, b.rows); assertEquals(1, b.store.conflictGroups.size)
    }

    // N-06 / C5: integridad referencial
    private fun serverPut(r: Rec) {
        val u = state.user(UID); u.version++
        u.rows[r.key] = FakeServerState.Row(1, 1000, null, r.data, u.version)
    }

    @Test fun `N-06 hijo sin padre no se confirma ni avanza el cursor y se recupera cuando llega el padre`() = runBlocking {
        val d = Device("B", emptyList(), server()); d.onboard()
        val recs = workoutRecs(1, exCount = 1, setCount = 1)
        serverPut(recs[1])                                                    // el hijo llega solo
        val out = d.run()
        assertEquals(SyncOutcome.Failed(RemoteException.Kind.SERVER, retryable = true), out)
        assertEquals(0, d.rows.size); assertEquals(0L, d.store.metaState.cursor)
        recs.filter { it.key != recs[1].key }.forEach { serverPut(it) }       // llega el resto del agregado
        assertTrue(d.run() is SyncOutcome.Success)
        assertEquals(recs.size, d.rows.size); assertNoOrphans(d.rows.values)
    }

    @Test fun `C5 hijo recibido antes que el padre en paginas distintas se aplica completo`() = runBlocking {
        val d = Device("B", emptyList(), server(), pageSize = 1); d.onboard()
        val recs = workoutRecs(1, exCount = 2, setCount = 2)
        (recs.drop(1) + recs.first()).forEach { serverPut(it) }               // el padre es lo ÚLTIMO en versionarse
        assertTrue(d.run() is SyncOutcome.Success)
        assertEquals(recs.size, d.rows.size); assertNoOrphans(d.rows.values)
    }

    @Test fun `C5 corte de red entre paginas con padre pendiente no deja huerfanos y el reintento converge`() = runBlocking {
        val d = Device("B", emptyList(), server(), pageSize = 2); d.onboard()
        val recs = workoutRecs(1, exCount = 2, setCount = 2)
        (recs.drop(1) + recs.first()).forEach { serverPut(it) }
        d.server.failPullAfterPages = 2
        assertTrue(d.run() is SyncOutcome.Failed)
        assertEquals(0, d.rows.size); assertEquals(0L, d.store.metaState.cursor)
        assertTrue(d.run() is SyncOutcome.Success); assertEquals(recs.size, d.rows.size); assertNoOrphans(d.rows.values)
    }
}
