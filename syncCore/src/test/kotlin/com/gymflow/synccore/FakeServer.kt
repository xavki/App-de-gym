package com.gymflow.synccore

import kotlinx.serialization.json.JsonObject

/**
 * Servidor en memoria con las MISMAS reglas que el servidor Ktor (ver server/SyncRepository): revisión por fila, baseRev,
 * idempotencia por contenido, grupos atómicos, tombstones sin resurrección y cursor por versión de usuario.
 */
class FakeServerState {
    class Row(var rev: Long, var updatedAt: Long, var deletedAt: Long?, var data: JsonObject, var version: Long)
    class User { var version = 0L; val rows = linkedMapOf<Key, Row>() }
    val users = mutableMapOf<String, User>()
    fun user(uid: String) = users.getOrPut(uid) { User() }
    fun rowCount(uid: String) = user(uid).rows.size
}

class FakeServer(private val state: FakeServerState, private val uid: String, private val now: () -> Long = { BASE_MS + 10_000_000 }) : RemoteApi {
    var online = true
    var tokenExpired = false
    /** Si no es null, tras aplicar esta cantidad de cambios de un push se corta la red SIN devolver respuesta (subida parcial). */
    var cutAfterApplying: Int? = null
    var failPullAfterPages: Int? = null
    var pushCalls = 0
    var pullCalls = 0
    private var pagesServed = 0

    override suspend fun push(changes: List<PushChange>): PushResponse {
        pushCalls++
        guard()
        require(changes.size <= 500) { "demasiados cambios" }
        require(changes.map { it.table to it.id }.toSet().size == changes.size) { "cambios repetidos en la misma petición" }
        val u = state.user(uid)
        val results = mutableMapOf<Key, PushResult>()
        var applied = 0
        val groups = changes.groupBy { it.group }
        for ((g, list) in groups) {
            val decisions = list.map { it to decide(u.rows[Key(it.table, it.id)], it) }
            val anyConflict = g != null && decisions.any { it.second is Decision.Conflict }
            for ((c, d) in decisions) {
                val key = Key(c.table, c.id)
                when {
                    d is Decision.Conflict -> results[key] = PushResult(c.table, c.id, PushStatus.CONFLICT, 0, u.rows.getValue(key).toRemote(c.table, c.id))
                    anyConflict -> results[key] = PushResult(c.table, c.id, PushStatus.BLOCKED)
                    d is Decision.Unchanged -> results[key] = PushResult(c.table, c.id, PushStatus.UNCHANGED, d.rev)
                    d is Decision.Accept -> {
                        u.version++
                        u.rows[key] = FakeServerState.Row(d.rev, c.updatedAt, c.deletedAt, c.data, u.version)
                        results[key] = PushResult(c.table, c.id, PushStatus.ACCEPTED, d.rev)
                        applied++
                        if (cutAfterApplying != null && applied >= cutAfterApplying!!) { cutAfterApplying = null; throw RemoteException(RemoteException.Kind.NETWORK, "corte de red") }
                    }
                }
            }
        }
        val ordered = changes.map { results.getValue(Key(it.table, it.id)) }
        return PushResponse(ordered.count { it.status == PushStatus.ACCEPTED || it.status == PushStatus.UNCHANGED }, ordered.count { it.status == PushStatus.CONFLICT || it.status == PushStatus.BLOCKED }, ordered)
    }

    override suspend fun pull(since: Long, limit: Int): PullResponse {
        pullCalls++
        guard()
        if (failPullAfterPages != null && pagesServed >= failPullAfterPages!!) { failPullAfterPages = null; throw RemoteException(RemoteException.Kind.NETWORK, "corte de red") }
        val u = state.user(uid)
        val reset = since > u.version
        val from = if (reset) 0 else since
        val rows = u.rows.entries.filter { it.value.version > from }.sortedBy { it.value.version }
        val page = rows.take(limit)
        pagesServed++
        return PullResponse(page.map { it.value.toRemote(it.key.table, it.key.id) }, page.lastOrNull()?.value?.version ?: from, rows.size > limit, reset)
    }

    private fun guard() {
        if (!online) throw RemoteException(RemoteException.Kind.NETWORK, "sin conexión")
        if (tokenExpired) throw RemoteException(RemoteException.Kind.AUTH, "token caducado")
    }

    private sealed class Decision {
        data class Accept(val rev: Long) : Decision()
        data class Unchanged(val rev: Long) : Decision()
        data object Conflict : Decision()
    }

    private fun decide(existing: FakeServerState.Row?, c: PushChange): Decision {
        if (existing == null) return Decision.Accept(1)
        val incoming = Rec(c.table, c.id, c.deletedAt, c.data)
        val stored = Rec(c.table, c.id, existing.deletedAt, existing.data)
        if (Content.same(incoming, stored)) return Decision.Unchanged(existing.rev)
        if (c.baseRev == existing.rev) return Decision.Accept(existing.rev + 1)
        return Decision.Conflict
    }

    private fun FakeServerState.Row.toRemote(table: String, id: String) = RemoteRecord(table, id, rev, updatedAt, deletedAt, data, version)

    /** Estado servidor→contenido para comparar convergencia en las pruebas. */
    fun snapshot(): Map<Key, Rec> = state.user(uid).rows.mapValues { (k, r) -> Rec(k.table, k.id, r.deletedAt, r.data, r.rev) }
}
