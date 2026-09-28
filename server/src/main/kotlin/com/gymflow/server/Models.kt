package com.gymflow.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ─── Protocolo de sincronización ─────────────────────────────────────────────
// Cada registro de la app viaja como un "Change": la tabla, su id (UUID), cuándo
// se modificó (epoch ms, lo decide el móvil) y el contenido completo en `data`.
// Gana la versión con updatedAt más reciente (last-write-wins por registro).

/** Tablas de la app que se sincronizan (mismos nombres que en Room). */
val SYNC_TABLES = setOf(
    "exercises", "routines", "routine_exercises", "routine_sets",
    "workouts", "workout_exercises", "workout_sets",
    "body_measurements", "schedules", "achievements",
)

@Serializable
data class Change(
    val table: String,
    val id: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val data: JsonObject,
)

@Serializable
data class PushRequest(val changes: List<Change>)

@Serializable
data class PushResponse(val accepted: Int, val ignored: Int)

@Serializable
data class PulledChange(
    val table: String,
    val id: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val data: JsonObject,
    val version: Long,
)

/**
 * [cursor]: pásalo como `since` en la siguiente llamada.
 * [reset]: el servidor tiene menos historial que el cursor del cliente (p. ej. se
 * restauró una copia de seguridad); se ha devuelto todo desde el principio.
 */
@Serializable
data class PullResponse(
    val changes: List<PulledChange>,
    val cursor: Long,
    val hasMore: Boolean,
    val reset: Boolean = false,
)

@Serializable
data class MeResponse(val uid: String, val email: String?, val records: Long, val version: Long)

// ─── Vinculación del panel web ───────────────────────────────────────────────

@Serializable
data class PairingCodeResponse(val code: String, val expiresAt: Long)

@Serializable
data class ClaimRequest(val code: String, val label: String? = null)

@Serializable
data class ClaimResponse(val token: String)

@Serializable
data class RevokeResponse(val revoked: Int)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class HealthResponse(val status: String, val app: String = "gymflow")
