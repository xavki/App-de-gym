package com.gymflow.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface GymDao {

    // ─── Catálogo de ejercicios ──────────────────────────────────────────────
    @Upsert suspend fun upsertExercises(items: List<ExerciseEntity>)

    @Query("SELECT * FROM exercises WHERE deletedAt IS NULL AND userId IS NULL ORDER BY name")
    suspend fun globalExercises(): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE deletedAt IS NULL AND userId = :uid ORDER BY name")
    suspend fun customExercises(uid: String): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun exercise(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE deletedAt IS NULL AND (userId IS NULL OR userId = :uid)")
    suspend fun allExercisesFor(uid: String): List<ExerciseEntity>

    // ─── Rutinas ─────────────────────────────────────────────────────────────
    @Upsert suspend fun upsertRoutines(items: List<RoutineEntity>)
    @Upsert suspend fun upsertRoutineExercises(items: List<RoutineExerciseEntity>)
    @Upsert suspend fun upsertRoutineSets(items: List<RoutineSetEntity>)

    @Query("SELECT * FROM routines WHERE userId = :uid AND deletedAt IS NULL ORDER BY createdAt")
    suspend fun routines(uid: String): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun routine(id: String): RoutineEntity?

    @Query("""
        SELECT re.* FROM routine_exercises re
        JOIN routines r ON r.id = re.routineId
        WHERE r.userId = :uid AND r.deletedAt IS NULL AND re.deletedAt IS NULL
        ORDER BY re.position
    """)
    suspend fun routineExercises(uid: String): List<RoutineExerciseEntity>

    @Query("""
        SELECT rs.* FROM routine_sets rs
        JOIN routine_exercises re ON re.id = rs.routineExerciseId
        JOIN routines r ON r.id = re.routineId
        WHERE r.userId = :uid AND r.deletedAt IS NULL AND re.deletedAt IS NULL AND rs.deletedAt IS NULL
        ORDER BY rs.position
    """)
    suspend fun routineSets(uid: String): List<RoutineSetEntity>

    /** Incluye borrados: se usa para calcular qué ha cambiado al guardar. */
    @Query("SELECT * FROM routine_exercises WHERE routineId = :routineId")
    suspend fun routineExercisesOf(routineId: String): List<RoutineExerciseEntity>

    @Query("""
        SELECT rs.* FROM routine_sets rs
        JOIN routine_exercises re ON re.id = rs.routineExerciseId
        WHERE re.routineId = :routineId
    """)
    suspend fun routineSetsOf(routineId: String): List<RoutineSetEntity>

    @Query("SELECT routineId FROM routine_exercises WHERE id = :id")
    suspend fun routineExerciseOwner(id: String): String?

    @Query("SELECT routineExerciseId FROM routine_sets WHERE id = :id")
    suspend fun routineSetOwner(id: String): String?

    // ─── Entrenamientos ──────────────────────────────────────────────────────
    @Upsert suspend fun upsertWorkouts(items: List<WorkoutEntity>)
    @Upsert suspend fun upsertWorkoutExercises(items: List<WorkoutExerciseEntity>)
    @Upsert suspend fun upsertWorkoutSets(items: List<WorkoutSetEntity>)

    @Query("SELECT * FROM workouts WHERE userId = :uid AND deletedAt IS NULL ORDER BY startedAt DESC")
    suspend fun workouts(uid: String): List<WorkoutEntity>

    @Query("""
        SELECT we.* FROM workout_exercises we
        JOIN workouts w ON w.id = we.workoutId
        WHERE w.userId = :uid AND w.deletedAt IS NULL AND we.deletedAt IS NULL
        ORDER BY we.position
    """)
    suspend fun workoutExercises(uid: String): List<WorkoutExerciseEntity>

    @Query("""
        SELECT ws.* FROM workout_sets ws
        JOIN workout_exercises we ON we.id = ws.workoutExerciseId
        JOIN workouts w ON w.id = we.workoutId
        WHERE w.userId = :uid AND w.deletedAt IS NULL AND we.deletedAt IS NULL AND ws.deletedAt IS NULL
        ORDER BY ws.position
    """)
    suspend fun workoutSets(uid: String): List<WorkoutSetEntity>

    @Query("SELECT COUNT(*) FROM workouts WHERE userId = :uid")
    suspend fun workoutCount(uid: String): Int

    // ─── Medidas, programaciones y logros ────────────────────────────────────
    @Upsert suspend fun upsertMeasurements(items: List<BodyMeasurementEntity>)

    @Query("SELECT * FROM body_measurements WHERE userId = :uid AND deletedAt IS NULL ORDER BY measuredAt")
    suspend fun measurements(uid: String): List<BodyMeasurementEntity>

    @Query("SELECT * FROM body_measurements WHERE id = :id")
    suspend fun measurement(id: String): BodyMeasurementEntity?

    @Query("""
        SELECT weightKg FROM body_measurements
        WHERE userId = :uid AND deletedAt IS NULL AND weightKg > 0
        ORDER BY measuredAt DESC LIMIT 1
    """)
    suspend fun latestBodyweight(uid: String): Double?

    @Upsert suspend fun upsertSchedules(items: List<ScheduleEntity>)

    @Query("SELECT * FROM schedules WHERE userId = :uid AND deletedAt IS NULL")
    suspend fun schedules(uid: String): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun schedule(id: String): ScheduleEntity?

    @Upsert suspend fun upsertAchievements(items: List<AchievementEntity>)

    @Query("SELECT * FROM achievements WHERE userId = :uid AND deletedAt IS NULL")
    suspend fun achievements(uid: String): List<AchievementEntity>

    // ─── Borrado lógico ──────────────────────────────────────────────────────
    @Query("UPDATE routines SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDeleteRoutine(id: String, now: Long)

    @Query("UPDATE exercises SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDeleteExercise(id: String, now: Long)

    @Query("UPDATE body_measurements SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDeleteMeasurement(id: String, now: Long)

    @Query("UPDATE schedules SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDeleteSchedule(id: String, now: Long)
}
