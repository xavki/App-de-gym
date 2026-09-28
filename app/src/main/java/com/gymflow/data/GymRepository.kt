package com.gymflow.data

import android.content.Context
import androidx.room.withTransaction
import com.gymflow.Achievement
import com.gymflow.AchievementCatalog
import com.gymflow.BodyMeasurement
import com.gymflow.CustomExercise
import com.gymflow.ExerciseDefinition
import com.gymflow.ExerciseHistoryEntry
import com.gymflow.ExerciseSet
import com.gymflow.ScheduledRoutine
import com.gymflow.WorkoutExercise
import com.gymflow.WorkoutSession
import java.util.Date
import java.util.UUID

/**
 * Única puerta de entrada a los datos del usuario. Room es la fuente de verdad:
 * la app funciona igual sin conexión. Traduce entre las tablas normalizadas y los
 * modelos anidados que ya usa la UI (WorkoutSession → WorkoutExercise → ExerciseSet).
 */
class GymRepository(private val db: GymDatabase) {

    private val dao = db.dao()
    private fun now() = System.currentTimeMillis()

    // ══════════════════════════════════════════════════════════════════════════
    // CATÁLOGO
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun globalExercises(): List<ExerciseDefinition> =
        dao.globalExercises().map { it.toDefinition() }

    suspend fun saveGlobalCatalog(items: List<Pair<String, ExerciseDefinition>>) {
        val t = now()
        dao.upsertExercises(items.map { (sourceId, d) ->
            ExerciseEntity(
                id = stableUuid("exercise:$sourceId"), userId = null,
                name = d.name, nameEs = d.nameEs, mainGroup = d.mainGroup, musclesUsed = d.musclesUsed,
                equipment = d.equipment, instructions = d.instructions, instructionsEs = d.instructionsEs,
                subCategory = d.subCategory, difficulty = d.difficulty,
                imageUrl = d.imageUrl, gifUrl = d.gifUrl, notes = "", isCustom = false,
                createdAt = t, updatedAt = t
            )
        })
    }

    suspend fun customExercises(uid: String): List<CustomExercise> =
        dao.customExercises(uid).map {
            CustomExercise(it.id, uid, it.name, it.mainGroup, it.musclesUsed, it.equipment ?: "", it.notes)
        }

    suspend fun saveCustomExercise(uid: String, c: CustomExercise) {
        val t = now()
        val existing = dao.exercise(c.id)
        dao.upsertExercises(listOf(ExerciseEntity(
            id = c.id, userId = uid, name = c.name, nameEs = c.name, mainGroup = c.mainGroup,
            musclesUsed = c.musclesUsed, equipment = c.equipment, instructions = "", instructionsEs = null,
            subCategory = null, difficulty = null, imageUrl = null, gifUrl = null, notes = c.notes,
            isCustom = true, createdAt = existing?.createdAt ?: t, updatedAt = t
        )))
    }

    suspend fun deleteCustomExercise(id: String) = dao.softDeleteExercise(id, now())

    /** nombre → id del catálogo (global + personalizados) para rellenar exerciseId. */
    private suspend fun exerciseIdsByName(uid: String): Map<String, String> =
        dao.allExercisesFor(uid).associate { it.name to it.id }

    // ══════════════════════════════════════════════════════════════════════════
    // RUTINAS
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun routines(uid: String): List<WorkoutSession> {
        val setsByEx = dao.routineSets(uid).groupBy { it.routineExerciseId }
        val exByRoutine = dao.routineExercises(uid).groupBy { it.routineId }
        return dao.routines(uid).map { r ->
            WorkoutSession(
                id = r.id, userId = uid, name = r.name, date = Date(r.createdAt),
                exercises = exByRoutine[r.id].orEmpty().map { e ->
                    WorkoutExercise(
                        id = e.id, exerciseId = e.exerciseId ?: "", exerciseName = e.exerciseName, notes = e.notes,
                        sets = setsByEx[e.id].orEmpty().map { s ->
                            ExerciseSet(
                                id = s.id, weight = s.targetWeightKg, repetitions = s.targetReps,
                                timeSeconds = s.targetTimeSeconds, setType = s.setType
                            )
                        }.toMutableList()
                    )
                }.toMutableList()
            )
        }
    }

    /**
     * Guarda la rutina completa. Solo cambia updatedAt de lo que realmente cambió y
     * marca como borrado (lógico) lo que ya no está, para que el sync sea incremental.
     */
    suspend fun saveRoutine(uid: String, routine: WorkoutSession) = db.withTransaction {
        val t = now()
        val ids = exerciseIdsByName(uid)

        val oldRoutine = dao.routine(routine.id)
        val newRoutine = RoutineEntity(routine.id, uid, routine.name, oldRoutine?.createdAt ?: t, t)
        if (oldRoutine == null || oldRoutine.deletedAt != null ||
            oldRoutine.copy(updatedAt = t) != newRoutine) {
            dao.upsertRoutines(listOf(newRoutine))
        }

        val oldEx = dao.routineExercisesOf(routine.id).associateBy { it.id }
        val oldSets = dao.routineSetsOf(routine.id).associateBy { it.id }
        val keptEx = mutableSetOf<String>()
        val keptSets = mutableSetOf<String>()
        val exOut = mutableListOf<RoutineExerciseEntity>()
        val setOut = mutableListOf<RoutineSetEntity>()

        // Un mismo id solo puede pertenecer a esta rutina: si viene de otra, se regenera
        routine.exercises.forEachIndexed { i, ex ->
            val exId = ex.id.takeIf { it !in keptEx && (it in oldEx || dao.routineExerciseOwner(it) == null) }
                ?: UUID.randomUUID().toString()
            keptEx += exId
            val old = oldEx[exId]
            val e = RoutineExerciseEntity(
                id = exId, routineId = routine.id,
                exerciseId = ex.exerciseId.ifBlank { null } ?: ids[ex.exerciseName],
                exerciseName = ex.exerciseName, position = i, notes = ex.notes ?: "",
                supersetGroup = old?.supersetGroup,
                createdAt = old?.createdAt ?: t, updatedAt = old?.updatedAt ?: t
            )
            if (old == null || old != e) exOut += e.copy(updatedAt = t)

            ex.sets.forEachIndexed { j, s ->
                val setId = s.id.takeIf { it !in keptSets && (it in oldSets || dao.routineSetOwner(it) == null) }
                    ?: UUID.randomUUID().toString()
                keptSets += setId
                val oldS = oldSets[setId]
                val se = RoutineSetEntity(
                    id = setId, routineExerciseId = exId, position = j, setType = s.setType,
                    targetWeightKg = s.weight, targetReps = s.repetitions, targetTimeSeconds = s.timeSeconds,
                    createdAt = oldS?.createdAt ?: t, updatedAt = oldS?.updatedAt ?: t
                )
                if (oldS == null || oldS != se) setOut += se.copy(updatedAt = t)
            }
        }
        oldEx.values.filter { it.id !in keptEx && it.deletedAt == null }
            .forEach { exOut += it.copy(deletedAt = t, updatedAt = t) }
        oldSets.values.filter { it.id !in keptSets && it.deletedAt == null }
            .forEach { setOut += it.copy(deletedAt = t, updatedAt = t) }

        dao.upsertRoutineExercises(exOut)
        dao.upsertRoutineSets(setOut)
    }

    suspend fun deleteRoutine(id: String) = dao.softDeleteRoutine(id, now())

    // ══════════════════════════════════════════════════════════════════════════
    // ENTRENAMIENTOS (historial)
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun workouts(uid: String): List<WorkoutSession> {
        val setsByEx = dao.workoutSets(uid).groupBy { it.workoutExerciseId }
        val exByWorkout = dao.workoutExercises(uid).groupBy { it.workoutId }
        return dao.workouts(uid).map { w ->
            WorkoutSession(
                id = w.id, userId = uid, name = w.name, date = Date(w.startedAt),
                durationSeconds = w.durationSeconds,
                exercises = exByWorkout[w.id].orEmpty().map { e ->
                    WorkoutExercise(
                        id = e.id, exerciseId = e.exerciseId ?: "", exerciseName = e.exerciseName, notes = e.notes,
                        sets = setsByEx[e.id].orEmpty().map { s ->
                            ExerciseSet(
                                id = s.id, weight = s.weightKg, repetitions = s.reps, timeSeconds = s.timeSeconds,
                                isCompleted = s.completed, setType = s.setType, rpe = s.rpe
                            )
                        }.toMutableList()
                    )
                }.toMutableList()
            )
        }
    }

    /**
     * Guarda un entreno terminado. Los ids de ejercicios y series se generan de nuevo
     * porque la sesión parte de una rutina y reutilizaría sus ids.
     */
    suspend fun saveWorkout(
        uid: String,
        session: WorkoutSession,
        routineId: String?,
        workoutId: String = UUID.randomUUID().toString(),
        startedAt: Long = session.date.time,
        bodyweightKg: Double? = null,
        onlyLoggedSets: Boolean = true,
        createdAt: Long = now()
    ) = db.withTransaction {
        val ids = exerciseIdsByName(uid)
        val bw = bodyweightKg ?: dao.latestBodyweight(uid)
        dao.upsertWorkouts(listOf(WorkoutEntity(
            id = workoutId, userId = uid, routineId = routineId, name = session.name,
            startedAt = startedAt, durationSeconds = session.durationSeconds, bodyweightKg = bw,
            notes = "", createdAt = createdAt, updatedAt = createdAt
        )))
        val exOut = mutableListOf<WorkoutExerciseEntity>()
        val setOut = mutableListOf<WorkoutSetEntity>()
        session.exercises.forEachIndexed { i, ex ->
            val sets = if (onlyLoggedSets) ex.sets.filter { it.isLogged() } else ex.sets
            if (sets.isEmpty()) return@forEachIndexed
            val exId = stableUuid("$workoutId:ex:$i")
            exOut += WorkoutExerciseEntity(
                id = exId, workoutId = workoutId,
                exerciseId = ex.exerciseId.ifBlank { null } ?: ids[ex.exerciseName],
                exerciseName = ex.exerciseName, position = exOut.size, notes = ex.notes ?: "",
                createdAt = createdAt, updatedAt = createdAt
            )
            sets.forEachIndexed { j, s ->
                setOut += WorkoutSetEntity(
                    id = stableUuid("$workoutId:set:$i:$j"), workoutExerciseId = exId, position = j,
                    setType = s.setType, weightKg = s.weight, reps = s.repetitions, timeSeconds = s.timeSeconds,
                    rpe = s.rpe, completed = s.isCompleted, createdAt = createdAt, updatedAt = createdAt
                )
            }
        }
        dao.upsertWorkoutExercises(exOut)
        dao.upsertWorkoutSets(setOut)
    }

    /** Historial de un ejercicio concreto (pantalla de información). */
    suspend fun exerciseHistory(uid: String, exerciseName: String): List<ExerciseHistoryEntry> =
        workouts(uid).mapNotNull { w ->
            val sets = w.exercises.filter { it.exerciseName == exerciseName }.flatMap { it.sets }
            if (sets.isEmpty()) null
            else ExerciseHistoryEntry(id = w.id, userId = uid, exerciseName = exerciseName, date = w.date, sets = sets)
        }

    // ══════════════════════════════════════════════════════════════════════════
    // MEDIDAS CORPORALES
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun measurements(uid: String): List<BodyMeasurement> = dao.measurements(uid).map {
        BodyMeasurement(it.id, uid, Date(it.measuredAt), it.weightKg, it.bodyFatPct,
            it.chestCm, it.waistCm, it.hipsCm, it.bicepCm, it.thighCm)
    }

    suspend fun saveMeasurement(uid: String, m: BodyMeasurement) {
        val t = now()
        val existing = dao.measurement(m.id)
        dao.upsertMeasurements(listOf(BodyMeasurementEntity(
            id = m.id, userId = uid, measuredAt = m.date.time, weightKg = m.weight, bodyFatPct = m.bodyFat,
            chestCm = m.chest, waistCm = m.waist, hipsCm = m.hips, bicepCm = m.bicep, thighCm = m.thigh,
            createdAt = existing?.createdAt ?: t, updatedAt = t
        )))
    }

    suspend fun deleteMeasurement(id: String) = dao.softDeleteMeasurement(id, now())

    // ══════════════════════════════════════════════════════════════════════════
    // PROGRAMACIONES
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun schedules(uid: String): List<ScheduledRoutine> = dao.schedules(uid).map {
        ScheduledRoutine(it.id, it.routineId, it.routineName, it.startDate, it.hourOfDay, it.minute,
            it.recurrenceType, it.intervalDays, it.weekDay, it.endDate, uid)
    }

    suspend fun saveSchedule(uid: String, s: ScheduledRoutine) {
        val t = now()
        val existing = dao.schedule(s.id)
        dao.upsertSchedules(listOf(ScheduleEntity(
            id = s.id, userId = uid, routineId = s.routineId, routineName = s.routineName,
            startDate = s.startDate, hourOfDay = s.hourOfDay, minute = s.minute,
            recurrenceType = s.recurrenceType, intervalDays = s.intervalDays, weekDay = s.weekDay,
            endDate = s.endDate, createdAt = existing?.createdAt ?: t, updatedAt = t
        )))
    }

    suspend fun deleteSchedule(id: String) = dao.softDeleteSchedule(id, now())

    // ══════════════════════════════════════════════════════════════════════════
    // LOGROS
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun achievements(uid: String): List<Achievement> {
        val unlocked = dao.achievements(uid).associateBy { it.achievementKey }
        return AchievementCatalog.all.map { a -> a.copy(unlockedAt = unlocked[a.id]?.let { Date(it.unlockedAt) }) }
    }

    suspend fun unlockAchievement(uid: String, key: String, at: Long = now()) {
        dao.upsertAchievements(listOf(AchievementEntity(
            id = stableUuid("$uid:achievement:$key"), userId = uid, achievementKey = key,
            unlockedAt = at, createdAt = at, updatedAt = at
        )))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EXPORTACIÓN (tablas tal cual, sin las filas borradas)
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun hasAnyWorkout(uid: String) = dao.workoutCount(uid) > 0

    suspend fun rawSnapshot(uid: String) = RawSnapshot(
        exercises = dao.customExercises(uid),
        routines = dao.routines(uid),
        routineExercises = dao.routineExercises(uid),
        routineSets = dao.routineSets(uid),
        workouts = dao.workouts(uid),
        workoutExercises = dao.workoutExercises(uid),
        workoutSets = dao.workoutSets(uid),
        bodyMeasurements = dao.measurements(uid),
        schedules = dao.schedules(uid),
        achievements = dao.achievements(uid),
        catalog = dao.allExercisesFor(uid)
    )

    data class RawSnapshot(
        val exercises: List<ExerciseEntity>,
        val routines: List<RoutineEntity>,
        val routineExercises: List<RoutineExerciseEntity>,
        val routineSets: List<RoutineSetEntity>,
        val workouts: List<WorkoutEntity>,
        val workoutExercises: List<WorkoutExerciseEntity>,
        val workoutSets: List<WorkoutSetEntity>,
        val bodyMeasurements: List<BodyMeasurementEntity>,
        val schedules: List<ScheduleEntity>,
        val achievements: List<AchievementEntity>,
        val catalog: List<ExerciseEntity>   // global + personalizados, para resolver grupo muscular
    )

    // ══════════════════════════════════════════════════════════════════════════

    companion object {
        @Volatile private var instance: GymRepository? = null

        fun get(context: Context): GymRepository = instance ?: synchronized(this) {
            instance ?: GymRepository(GymDatabase.get(context)).also { instance = it }
        }

        /** Convierte cualquier id (p. ej. los autogenerados de Firestore) en un UUID estable. */
        fun stableUuid(raw: String): String =
            runCatching { UUID.fromString(raw).toString() }.getOrNull()
                ?: UUID.nameUUIDFromBytes(raw.toByteArray()).toString()
    }
}

/** Una serie cuenta para el historial si se marcó o se llegó a rellenar. */
fun ExerciseSet.isLogged() = isCompleted || weight > 0 || repetitions > 0 || timeSeconds > 0

private fun ExerciseEntity.toDefinition() = ExerciseDefinition(
    name = name, mainGroup = mainGroup, musclesUsed = musclesUsed, instructions = instructions,
    gifUrl = gifUrl, imageUrl = imageUrl, subCategory = subCategory, difficulty = difficulty,
    equipment = equipment, nameEs = nameEs, instructionsEs = instructionsEs
)
