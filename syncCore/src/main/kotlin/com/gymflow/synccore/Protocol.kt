package com.gymflow.synccore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ─── Protocolo de sincronización v2 (control de versiones optimista) ─────────────────────────────────────────────
// Cada fila del servidor tiene una REVISIÓN (rev) que sube en 1 con cada cambio aceptado. El cliente declara sobre qué revisión
// basó su cambio (baseRev). Si no coincide con la del servidor y el contenido difiere ⇒ CONFLICTO (nunca se sobrescribe por tener
// una fecha "más reciente"). Reenviar el mismo cambio es idempotente (mismo contenido ⇒ "unchanged").

@Serializable
data class PushChange(
    val table: String,
    val id: String,
    /** Revisión del servidor sobre la que se hizo el cambio (0 = fila nueva). */
    val baseRev: Long = 0,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val data: JsonObject,
    /** Cambios con el mismo grupo (raíz del agregado) se aplican todos o ninguno. */
    val group: String? = null,
)

@Serializable data class PushRequest(val changes: List<PushChange>)

@Serializable
data class RemoteRecord(
    val table: String,
    val id: String,
    val rev: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val data: JsonObject,
    val version: Long,
)

@Serializable
data class PushResult(
    val table: String,
    val id: String,
    /** accepted | unchanged | conflict | blocked (otro cambio de su grupo entró en conflicto) */
    val status: String,
    val rev: Long = 0,
    val server: RemoteRecord? = null,
)

@Serializable
data class PushResponse(val accepted: Int, val ignored: Int, val results: List<PushResult> = emptyList())

@Serializable
data class PullResponse(val changes: List<RemoteRecord>, val cursor: Long, val hasMore: Boolean, val reset: Boolean = false)

object PushStatus {
    const val ACCEPTED = "accepted"
    const val UNCHANGED = "unchanged"
    const val CONFLICT = "conflict"
    const val BLOCKED = "blocked"
}

fun RemoteRecord.toRec(dirty: Boolean = false) = Rec(table, id, deletedAt, data, rev, dirty)

fun Rec.toPushChange(group: String?): PushChange = PushChange(
    table = table, id = id, baseRev = rev, updatedAt = (long("updatedAt") ?: 1L).coerceAtLeast(1L),
    deletedAt = deletedAt, data = data, group = group,
)

class RemoteException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { AUTH, NETWORK, SERVER, REJECTED, IDENTITY }
    /** Se puede reintentar sin intervención del usuario (red o servidor); AUTH/REJECTED/IDENTITY requieren acción. */
    val retryable get() = kind == Kind.NETWORK || kind == Kind.SERVER
}

interface RemoteApi {
    suspend fun push(changes: List<PushChange>): PushResponse
    suspend fun pull(since: Long, limit: Int): PullResponse
}

/** Espera entre reintentos: exponencial con tope, determinista (el jitter lo añade el planificador). */
object Backoff {
    fun delayMs(attempt: Int, baseMs: Long = 30_000, maxMs: Long = 6 * 3_600_000L): Long {
        require(attempt >= 0)
        val shift = attempt.coerceAtMost(20)
        return minOf(maxMs, baseMs shl shift).coerceAtLeast(baseMs)
    }
}
