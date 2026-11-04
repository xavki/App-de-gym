package com.gymflow.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ─── Protocolo de sincronización ─────────────────────────────────────────────
// Cada registro de la app viaja como un "Change": la tabla, su id (UUID), cuándo
// se modificó (epoch ms, informativo), el contenido completo en `data` y la REVISIÓN del servidor
// sobre la que se hizo el cambio (`baseRev`). Control de versiones optimista (protocolo v2):
//  · fila nueva                                   → se acepta (rev 1)
//  · mismo contenido que el guardado              → "unchanged" (idempotente: reenviar no cambia nada)
//  · baseRev == rev actual                        → se acepta (rev + 1)
//  · cualquier otro caso                          → "conflict": NO se sobrescribe, se devuelve la versión del servidor
// La fecha del móvil NO decide quién gana. Los cambios con el mismo `group` se aplican todos o ninguno.

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
    /** Revisión del servidor sobre la que se basa el cambio (0 = el cliente cree que es una fila nueva). */
    val baseRev: Long = 0,
    /** Cambios con el mismo grupo (raíz del agregado) se aplican atómicamente. */
    val group: String? = null,
)

@Serializable
data class PushRequest(val changes: List<Change>)

@Serializable
data class PushResult(
    val table: String,
    val id: String,
    /** accepted | unchanged | conflict | blocked */
    val status: String,
    val rev: Long = 0,
    val server: PulledChange? = null,
)

/** accepted = aceptados + sin cambios; ignored = conflictos + bloqueados; results = uno por cambio, en el mismo orden. */
@Serializable
data class PushResponse(val accepted: Int, val ignored: Int, val results: List<PushResult> = emptyList())

@Serializable
data class PulledChange(
    val table: String,
    val id: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val data: JsonObject,
    val version: Long,
    val rev: Long = 1,
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
