package com.gymflow.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────────────────────
//  Modelo local (Room). Reglas comunes a TODAS las tablas, pensadas para la
//  futura sincronización con el servidor propio:
//    • id        → UUID en texto, generado en el móvil (nunca autoincremental)
//    • updatedAt → epoch ms de la última modificación (el sync empuja lo > lastSync)
//    • deletedAt → borrado lógico; null = vivo. Nunca se borra una fila de verdad.
// ─────────────────────────────────────────────────────────────────────────────

/** Catálogo de ejercicios: los globales (userId = null) y los personalizados. */
@Entity(
    tableName = "exercises",
    indices = [Index("name"), Index("userId")]
)
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val userId: String?,              // null = catálogo global
    val name: String,
    val nameEs: String?,
    val mainGroup: String,
    val musclesUsed: String,
    val equipment: String?,
    val instructions: String,
    val instructionsEs: String?,
    val subCategory: String?,
    val difficulty: String?,
    val imageUrl: String?,
    val gifUrl: String?,
    val notes: String,
    val isCustom: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

// ─── Rutinas (plantillas) ─────────────────────────────────────────────────────

@Entity(tableName = "routines", indices = [Index("userId")])
data class RoutineEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

@Entity(
    tableName = "routine_exercises",
    foreignKeys = [ForeignKey(
        entity = RoutineEntity::class, parentColumns = ["id"], childColumns = ["routineId"]
    )],
    indices = [Index("routineId"), Index("exerciseId")]
)
data class RoutineExerciseEntity(
    @PrimaryKey val id: String,
    val routineId: String,
    val exerciseId: String?,          // null si el nombre no está en el catálogo
    val exerciseName: String,         // desnormalizado: la UI y el historial van por nombre
    val position: Int,
    val notes: String,
    val supersetGroup: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

@Entity(
    tableName = "routine_sets",
    foreignKeys = [ForeignKey(
        entity = RoutineExerciseEntity::class, parentColumns = ["id"], childColumns = ["routineExerciseId"]
    )],
    indices = [Index("routineExerciseId")]
)
data class RoutineSetEntity(
    @PrimaryKey val id: String,
    val routineExerciseId: String,
    val position: Int,
    val setType: String,
    val targetWeightKg: Double,
    val targetReps: Int,
    val targetTimeSeconds: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

// ─── Entrenamientos realizados (historial) ────────────────────────────────────

@Entity(tableName = "workouts", indices = [Index("userId"), Index("startedAt")])
data class WorkoutEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val routineId: String?,
    val name: String,
    val startedAt: Long,
    val durationSeconds: Int,
    val bodyweightKg: Double?,        // peso corporal ese día → niveles de fuerza (ratio peso/cuerpo)
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

@Entity(
    tableName = "workout_exercises",
    foreignKeys = [ForeignKey(
        entity = WorkoutEntity::class, parentColumns = ["id"], childColumns = ["workoutId"]
    )],
    indices = [Index("workoutId"), Index("exerciseId"), Index("exerciseName")]
)
data class WorkoutExerciseEntity(
    @PrimaryKey val id: String,
    val workoutId: String,
    val exerciseId: String?,
    val exerciseName: String,
    val position: Int,
    val notes: String,
    val supersetGroup: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

/** Una fila por serie: es la base de la progresión, los récords y el 1RM. */
@Entity(
    tableName = "workout_sets",
    foreignKeys = [ForeignKey(
        entity = WorkoutExerciseEntity::class, parentColumns = ["id"], childColumns = ["workoutExerciseId"]
    )],
    indices = [Index("workoutExerciseId")]
)
data class WorkoutSetEntity(
    @PrimaryKey val id: String,
    val workoutExerciseId: String,
    val position: Int,
    val setType: String,
    val weightKg: Double,
    val reps: Int,
    val timeSeconds: Int,
    val rpe: Double?,
    val completed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

// ─── Resto de datos del usuario ───────────────────────────────────────────────

@Entity(tableName = "body_measurements", indices = [Index("userId"), Index("measuredAt")])
data class BodyMeasurementEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val measuredAt: Long,
    val weightKg: Double,
    val bodyFatPct: Double,
    val chestCm: Double,
    val waistCm: Double,
    val hipsCm: Double,
    val bicepCm: Double,
    val thighCm: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

@Entity(tableName = "schedules", indices = [Index("userId")])
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val routineId: String,
    val routineName: String,
    val startDate: Long,
    val hourOfDay: Int,
    val minute: Int,
    val recurrenceType: String,
    val intervalDays: Int,
    val weekDay: Int,
    val endDate: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

@Entity(
    tableName = "achievements",
    indices = [Index(value = ["userId", "achievementKey"], unique = true)]
)
data class AchievementEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val achievementKey: String,       // id del catálogo (AchievementCatalog)
    val unlockedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)
