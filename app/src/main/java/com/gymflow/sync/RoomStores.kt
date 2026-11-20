package com.gymflow.sync

import androidx.room.withTransaction
import com.gymflow.data.GymDatabase
import com.gymflow.data.SyncArchiveEntity
import com.gymflow.data.SyncConflictEntity
import com.gymflow.data.SyncStateEntity
import com.gymflow.synccore.ConflictCodec
import com.gymflow.synccore.ConflictGroup
import com.gymflow.synccore.Identified
import com.gymflow.synccore.OperationIdentity
import com.gymflow.synccore.Key
import com.gymflow.synccore.Rec
import com.gymflow.synccore.RecStore
import com.gymflow.synccore.RecTx
import com.gymflow.synccore.SyncMeta
import com.gymflow.synccore.SyncStore
import com.gymflow.synccore.SyncTx
import java.util.UUID

/** Base local (Room) para el motor de sincronización del núcleo. Cada transacción es de Room: todo o nada. */
class RoomSyncStore(
    private val db: GymDatabase, private val uid: String, private val serverKey: String,
    /** Identidad VIVA (sesión de Firebase + servidor configurado AHORA); null = sin sesión o sin servidor. */
    private val live: () -> String?,
) : SyncStore {
    override val boundIdentity: String = OperationIdentity.of(uid, serverKey)
    override fun liveIdentity(): String? = live()

    override suspend fun <T> transaction(block: suspend SyncTx.() -> T): T = db.withTransaction {
        Tx(RoomRecordAccess(db.openHelper.writableDatabase, uid, serverKey), db, uid, serverKey).block()
    }

    private class Tx(
        private val access: RoomRecordAccess,
        private val db: GymDatabase,
        private val uid: String,
        private val serverKey: String,
    ) : SyncTx {
        private val dao get() = db.syncStateDao()

        override suspend fun records(): List<Rec> = access.records()
        override suspend fun put(rec: Rec) = access.put(rec)
        override suspend fun remove(table: String, id: String) = access.remove(table, id)

        override suspend fun conflicts(): List<ConflictGroup> = dao.conflicts(uid, serverKey).map { ConflictCodec.decode(it.groupJson) }
        override suspend fun saveConflict(group: ConflictGroup) =
            dao.saveConflict(SyncConflictEntity(uid, serverKey, group.root.table, group.root.id, ConflictCodec.encode(group)))
        override suspend fun removeConflict(root: Key) = dao.removeConflict(uid, serverKey, root.table, root.id)
        override suspend fun archive(group: ConflictGroup, resolution: String, at: Long) =
            dao.archive(SyncArchiveEntity(UUID.randomUUID().toString(), uid, serverKey, group.root.table, group.root.id, resolution, at, ConflictCodec.encode(group)))

        override suspend fun meta(): SyncMeta = dao.state(uid, serverKey)?.let {
            SyncMeta(it.cursor, it.onboarded, it.lastSuccessAt, it.lastErrorKind)
        } ?: SyncMeta()

        override suspend fun setMeta(meta: SyncMeta) =
            dao.saveState(SyncStateEntity(uid, serverKey, meta.cursor, meta.onboarded, meta.lastSuccessAt, meta.lastErrorKind))
    }
}

/** Base local (Room) para la importación JSON. Las inserciones nunca sobrescriben: si la fila existe, falla y se revierte todo. */
class RoomImportStore(
    private val db: GymDatabase, private val uid: String, private val serverKey: String,
    private val live: () -> String?,
) : RecStore {
    override val boundIdentity: String = OperationIdentity.of(uid, serverKey)
    override fun liveIdentity(): String? = live()

    override suspend fun <T> transaction(block: suspend RecTx.() -> T): T = db.withTransaction {
        Tx(RoomRecordAccess(db.openHelper.writableDatabase, uid, serverKey)).block()
    }

    private class Tx(private val access: RoomRecordAccess) : RecTx {
        override suspend fun all(): List<Rec> = access.records()
        override suspend fun insert(rec: Rec) {
            check(!access.exists(rec.table, rec.id)) { "Ya existe ${rec.table}/${rec.id}: la importación no sobrescribe" }
            access.put(rec)
        }
    }
}
