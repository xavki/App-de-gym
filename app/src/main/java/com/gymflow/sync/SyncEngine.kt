package com.gymflow.sync

import android.util.Log
import androidx.room.withTransaction
import com.gymflow.data.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Resultado de una sincronización. [pulledTables]: tablas en las que llegaron cambios. */
data class SyncResult(val pushed: Int, val pulled: Int, val pulledTables: Set<String>)

/** Dónde guarda el motor su cursor (en la app: SharedPreferences; en tests: memoria). */
interface CursorStore {
    fun get(uid: String): Long
    fun set(uid: String, cursor: Long)
}

/**
 * Motor de sincronización:
 *  1. Sube las filas con dirty = 1 (en lotes) y las marca como subidas.
 *  2. Baja lo que cambió en el servidor desde el último cursor y lo aplica con
 *     last-write-wins: si la fila local es igual de nueva o más, se queda la local.
 * Es seguro repetirlo o cortarlo a medias: todo es idempotente.
 */
class SyncEngine(
    private val db: GymDatabase,
    private val api: SyncApi,
    private val cursors: CursorStore,
) {
    private val dao = db.syncDao()
    private val tables: List<Table<*>> = buildTables()
    private val byName = tables.associateBy { it.name }

    suspend fun sync(uid: String): SyncResult = lock.withLock {
        val pushed = push(uid)
        val (pulled, touched) = pull(uid)
        SyncResult(pushed, pulled, touched)
    }

    private suspend fun push(uid: String): Int {
        val pending = tables.flatMap { it.pendingChanges(uid) }
        pending.chunked(PUSH_BATCH).forEach { batch ->
            api.push(batch.map { it.change })
            // Se marcan como subidas solo si no cambiaron mientras tanto (mismo updatedAt)
            db.withTransaction { batch.forEach { it.markSynced() } }
        }
        return pending.size
    }

    private suspend fun pull(uid: String): Pair<Int, Set<String>> {
        var cursor = cursors.get(uid)
        var applied = 0
        val touched = mutableSetOf<String>()
        do {
            val page = api.pull(cursor)
            db.withTransaction {
                for (ch in page.changes) {
                    val table = byName[ch.table] ?: continue // tabla de una versión más nueva de la app
                    try {
                        if (table.applyRemote(ch, uid)) { applied++; touched += ch.table }
                    } catch (e: Exception) {
                        // Un registro ilegible no debe bloquear la sincronización entera
                        Log.w(TAG, "No se pudo aplicar ${ch.table}/${ch.id}: ${e.message}")
                    }
                }
            }
            cursor = page.cursor
            cursors.set(uid, cursor)
        } while (page.hasMore)
        return applied to touched
    }

    // ─── Tablas ─────────────────────────────────────────────────────────────

    private class PendingChange(val change: Change, val markSynced: suspend () -> Unit)

    /** Cómo sincronizar una tabla concreta de Room. */
    private class Table<T : Any>(
        val name: String,
        val serializer: KSerializer<T>,
        val pending: suspend (uid: String) -> List<T>,
        val find: suspend (id: String) -> T?,
        val save: suspend (T) -> Unit,
        val synced: suspend (id: String, updatedAt: Long) -> Unit,
        val meta: (T) -> Triple<String, Long, Long?>,         // id, updatedAt, deletedAt
        val fromRemote: (T, uid: String, updatedAt: Long, deletedAt: Long?) -> T,
    ) {
        suspend fun pendingChanges(uid: String): List<PendingChange> = pending(uid).map { row ->
            val (id, updatedAt, deletedAt) = meta(row)
            val data = syncJson.encodeToJsonElement(serializer, row).jsonObject
            PendingChange(Change(name, id, updatedAt, deletedAt, data)) { synced(id, updatedAt) }
        }

        /** true si se ha escrito la versión del servidor. */
        suspend fun applyRemote(ch: PulledChange, uid: String): Boolean {
            val local = find(ch.id)
            if (local != null && meta(local).second >= ch.updatedAt) return false
            val remote = syncJson.decodeFromJsonElement(serializer, ch.data)
            save(fromRemote(remote, uid, ch.updatedAt, ch.deletedAt))
            return true
        }
    }

    private fun buildTables(): List<Table<*>> = listOf(
        Table("exercises", ExerciseEntity.serializer(), dao::pendingExercises, dao::findExercise, dao::saveExercise, dao::syncedExercise,
            { Triple(it.id, it.updatedAt, it.deletedAt) },
            { e, uid, u, d -> e.copy(userId = uid, isCustom = true, updatedAt = u, deletedAt = d, dirty = false) }),
        Table("routines", RoutineEntity.serializer(), dao::pendingRoutines, dao::findRoutine, dao::saveRoutine, dao::syncedRoutine,
            { Triple(it.id, it.updatedAt, it.deletedAt) },
            { e, uid, u, d -> e.copy(userId = uid, updatedAt = u, deletedAt = d, dirty = false) }),
        Table("routine_exercises", RoutineExerciseEntity.serializer(), dao::pendingRoutineExercises, dao::findRoutineExercise, dao::saveRoutineExercise, dao::syncedRoutineExercise,
            { Triple(it.id, it.updatedAt, it.deletedAt) },
            { e, _, u, d -> e.copy(updatedAt = u, deletedAt = d, dirty = false) }),
        Table("routine_sets", RoutineSetEntity.serializer(), dao::pendingRoutineSets, dao::findRoutineSet, dao::saveRoutineSet, dao::syncedRoutineSet,
            { Triple(it.id, it.updatedAt, it.deletedAt) },
            { e, _, u, d -> e.copy(updatedAt = u, deletedAt = d, dirty = false) }),
        Table("workouts", WorkoutEntity.serializer(), dao::pendingWorkouts, dao::findWorkout, dao::saveWorkout, dao::syncedWorkout,
            { Triple(it.id, it.updatedAt, it.deletedAt) },
            { e, uid, u, d -> e.copy(userId = uid, updatedAt = u, deletedAt = d, dirty = false) }),
        Table("workout_exercises", WorkoutExerciseEntity.serializer(), dao::pendingWorkoutExercises, dao::findWorkoutExercise, dao::saveWorkoutExercise, dao::syncedWorkoutExercise,
            { Triple(it.id, it.updatedAt, it.deletedAt) },
            { e, _, u, d -> e.copy(updatedAt = u, deletedAt = d, dirty = false) }),
        Table("workout_sets", WorkoutSetEntity.serializer(), dao::pendingWorkoutSets, dao::findWorkoutSet, dao::saveWorkoutSet, dao::syncedWorkoutSet,
            { Triple(it.id, it.updatedAt, it.deletedAt) },
            { e, _, u, d -> e.copy(updatedAt = u, deletedAt = d, dirty = false) }),
        Table("body_measurements", BodyMeasurementEntity.serializer(), dao::pendingMeasurements, dao::findMeasurement, dao::saveMeasurement, dao::syncedMeasurement,
            { Triple(it.id, it.updatedAt, it.deletedAt) },
            { e, uid, u, d -> e.copy(userId = uid, updatedAt = u, deletedAt = d, dirty = false) }),
        Table("schedules", ScheduleEntity.serializer(), dao::pendingSchedules, dao::findSchedule, dao::saveSchedule, dao::syncedSchedule,
            { Triple(it.id, it.updatedAt, it.deletedAt) },
            { e, uid, u, d -> e.copy(userId = uid, updatedAt = u, deletedAt = d, dirty = false) }),
        Table("achievements", AchievementEntity.serializer(), dao::pendingAchievements, dao::findAchievement, dao::saveAchievement, dao::syncedAchievement,
            { Triple(it.id, it.updatedAt, it.deletedAt) },
            { e, uid, u, d -> e.copy(userId = uid, updatedAt = u, deletedAt = d, dirty = false) }),
    )

    companion object {
        private const val TAG = "SyncEngine"
        private const val PUSH_BATCH = 500
        /** Una sola sincronización a la vez en todo el proceso (botón + segundo plano). */
        private val lock = Mutex()

        /** Contenido que se envía de una fila (útil para tests y para el contrato). */
        fun <T> encode(serializer: KSerializer<T>, row: T): JsonObject = syncJson.encodeToJsonElement(serializer, row).jsonObject
    }
}
