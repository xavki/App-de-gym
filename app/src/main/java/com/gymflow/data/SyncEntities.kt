package com.gymflow.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

// ─────────────────────────────────────────────────────────────────────────────
//  Estado de la sincronización v2. Va APARTE de las tablas de datos a propósito:
//  la app edita filas reconstruyendo las entidades, y así nunca puede borrar por
//  accidente la revisión del servidor ni el estado de incorporación.
//  Todo va acotado por (usuario, servidor): cambiar de servidor o de cuenta exige
//  una nueva incorporación inicial.
// ─────────────────────────────────────────────────────────────────────────────

/** Última revisión del servidor vista de una fila (la "base" de su próximo cambio). */
@Entity(tableName = "sync_revs", primaryKeys = ["serverKey", "tableName", "id"])
data class SyncRevEntity(val serverKey: String, val tableName: String, val id: String, val rev: Long)

@Entity(tableName = "sync_state", primaryKeys = ["userId", "serverKey"])
data class SyncStateEntity(
    val userId: String,
    val serverKey: String,
    val cursor: Long,
    val onboarded: Boolean,
    val lastSuccessAt: Long?,
    val lastErrorKind: String?,
)

/** Conflicto de un agregado (rutina o entreno completo), serializado con ConflictCodec. Sobrevive a reinicios. */
@Entity(tableName = "sync_conflicts", primaryKeys = ["userId", "serverKey", "rootTable", "rootId"])
data class SyncConflictEntity(
    val userId: String,
    val serverKey: String,
    val rootTable: String,
    val rootId: String,
    val groupJson: String,
)

/** Histórico recuperable de lo que se descartó al resolver un conflicto. */
@Entity(tableName = "sync_archive")
data class SyncArchiveEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val serverKey: String,
    val rootTable: String,
    val rootId: String,
    val resolution: String,
    val at: Long,
    val groupJson: String,
)

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE userId = :uid AND serverKey = :serverKey")
    suspend fun state(uid: String, serverKey: String): SyncStateEntity?

    @Upsert suspend fun saveState(e: SyncStateEntity)

    @Query("SELECT * FROM sync_conflicts WHERE userId = :uid AND serverKey = :serverKey")
    suspend fun conflicts(uid: String, serverKey: String): List<SyncConflictEntity>

    @Upsert suspend fun saveConflict(e: SyncConflictEntity)

    @Query("DELETE FROM sync_conflicts WHERE userId = :uid AND serverKey = :serverKey AND rootTable = :rootTable AND rootId = :rootId")
    suspend fun removeConflict(uid: String, serverKey: String, rootTable: String, rootId: String)

    @Insert suspend fun archive(e: SyncArchiveEntity)

    @Query("SELECT COUNT(*) FROM sync_archive WHERE userId = :uid AND serverKey = :serverKey")
    suspend fun archivedCount(uid: String, serverKey: String): Int
}
