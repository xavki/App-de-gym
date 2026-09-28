package com.gymflow.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Consultas de la sincronización, tabla por tabla:
 *  • pending*  → filas del usuario con cambios sin subir (incluidos borrados lógicos)
 *  • find*     → fila por id (viva o borrada) para comparar con la del servidor
 *  • save*     → escribir lo que llega del servidor
 *  • synced*   → marcar como subida, solo si no ha cambiado mientras tanto
 */
@Dao
interface SyncDao {

    // ─── Pendientes ─────────────────────────────────────────────────────────
    @Query("SELECT * FROM exercises WHERE userId = :uid AND dirty = 1")
    suspend fun pendingExercises(uid: String): List<ExerciseEntity>

    @Query("SELECT * FROM routines WHERE userId = :uid AND dirty = 1")
    suspend fun pendingRoutines(uid: String): List<RoutineEntity>

    @Query("SELECT re.* FROM routine_exercises re JOIN routines r ON r.id = re.routineId WHERE r.userId = :uid AND re.dirty = 1")
    suspend fun pendingRoutineExercises(uid: String): List<RoutineExerciseEntity>

    @Query("""
        SELECT rs.* FROM routine_sets rs
        JOIN routine_exercises re ON re.id = rs.routineExerciseId
        JOIN routines r ON r.id = re.routineId
        WHERE r.userId = :uid AND rs.dirty = 1
    """)
    suspend fun pendingRoutineSets(uid: String): List<RoutineSetEntity>

    @Query("SELECT * FROM workouts WHERE userId = :uid AND dirty = 1")
    suspend fun pendingWorkouts(uid: String): List<WorkoutEntity>

    @Query("SELECT we.* FROM workout_exercises we JOIN workouts w ON w.id = we.workoutId WHERE w.userId = :uid AND we.dirty = 1")
    suspend fun pendingWorkoutExercises(uid: String): List<WorkoutExerciseEntity>

    @Query("""
        SELECT ws.* FROM workout_sets ws
        JOIN workout_exercises we ON we.id = ws.workoutExerciseId
        JOIN workouts w ON w.id = we.workoutId
        WHERE w.userId = :uid AND ws.dirty = 1
    """)
    suspend fun pendingWorkoutSets(uid: String): List<WorkoutSetEntity>

    @Query("SELECT * FROM body_measurements WHERE userId = :uid AND dirty = 1")
    suspend fun pendingMeasurements(uid: String): List<BodyMeasurementEntity>

    @Query("SELECT * FROM schedules WHERE userId = :uid AND dirty = 1")
    suspend fun pendingSchedules(uid: String): List<ScheduleEntity>

    @Query("SELECT * FROM achievements WHERE userId = :uid AND dirty = 1")
    suspend fun pendingAchievements(uid: String): List<AchievementEntity>

    // ─── Por id ─────────────────────────────────────────────────────────────
    @Query("SELECT * FROM exercises WHERE id = :id") suspend fun findExercise(id: String): ExerciseEntity?
    @Query("SELECT * FROM routines WHERE id = :id") suspend fun findRoutine(id: String): RoutineEntity?
    @Query("SELECT * FROM routine_exercises WHERE id = :id") suspend fun findRoutineExercise(id: String): RoutineExerciseEntity?
    @Query("SELECT * FROM routine_sets WHERE id = :id") suspend fun findRoutineSet(id: String): RoutineSetEntity?
    @Query("SELECT * FROM workouts WHERE id = :id") suspend fun findWorkout(id: String): WorkoutEntity?
    @Query("SELECT * FROM workout_exercises WHERE id = :id") suspend fun findWorkoutExercise(id: String): WorkoutExerciseEntity?
    @Query("SELECT * FROM workout_sets WHERE id = :id") suspend fun findWorkoutSet(id: String): WorkoutSetEntity?
    @Query("SELECT * FROM body_measurements WHERE id = :id") suspend fun findMeasurement(id: String): BodyMeasurementEntity?
    @Query("SELECT * FROM schedules WHERE id = :id") suspend fun findSchedule(id: String): ScheduleEntity?
    @Query("SELECT * FROM achievements WHERE id = :id") suspend fun findAchievement(id: String): AchievementEntity?

    // ─── Guardar lo que llega del servidor ──────────────────────────────────
    @Upsert suspend fun saveExercise(e: ExerciseEntity)
    @Upsert suspend fun saveRoutine(e: RoutineEntity)
    @Upsert suspend fun saveRoutineExercise(e: RoutineExerciseEntity)
    @Upsert suspend fun saveRoutineSet(e: RoutineSetEntity)
    @Upsert suspend fun saveWorkout(e: WorkoutEntity)
    @Upsert suspend fun saveWorkoutExercise(e: WorkoutExerciseEntity)
    @Upsert suspend fun saveWorkoutSet(e: WorkoutSetEntity)
    @Upsert suspend fun saveMeasurement(e: BodyMeasurementEntity)
    @Upsert suspend fun saveSchedule(e: ScheduleEntity)
    @Upsert suspend fun saveAchievement(e: AchievementEntity)

    // ─── Marcar como subido (si updatedAt no cambió durante la subida) ──────
    @Query("UPDATE exercises SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") suspend fun syncedExercise(id: String, updatedAt: Long)
    @Query("UPDATE routines SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") suspend fun syncedRoutine(id: String, updatedAt: Long)
    @Query("UPDATE routine_exercises SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") suspend fun syncedRoutineExercise(id: String, updatedAt: Long)
    @Query("UPDATE routine_sets SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") suspend fun syncedRoutineSet(id: String, updatedAt: Long)
    @Query("UPDATE workouts SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") suspend fun syncedWorkout(id: String, updatedAt: Long)
    @Query("UPDATE workout_exercises SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") suspend fun syncedWorkoutExercise(id: String, updatedAt: Long)
    @Query("UPDATE workout_sets SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") suspend fun syncedWorkoutSet(id: String, updatedAt: Long)
    @Query("UPDATE body_measurements SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") suspend fun syncedMeasurement(id: String, updatedAt: Long)
    @Query("UPDATE schedules SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") suspend fun syncedSchedule(id: String, updatedAt: Long)
    @Query("UPDATE achievements SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt") suspend fun syncedAchievement(id: String, updatedAt: Long)
}
