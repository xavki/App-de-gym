package com.gymflow

import com.google.firebase.firestore.PropertyName
import java.util.Date
import java.util.UUID

// ─── Tipos de recurrencia ─────────────────────────────────────────────────────
object RecurrenceType {
    const val ONCE        = "ONCE"          // Un único día
    const val EVERY_N_DAYS = "EVERY_N_DAYS" // Cada N días
    const val WEEKLY_DAY  = "WEEKLY_DAY"    // Cada X día de la semana durante N meses
}

// ─── Tipos de serie ────────────────────────────────────────────────────────────
object SetType {
    const val NORMAL   = "NORMAL"    // Serie normal
    const val WARMUP   = "WARMUP"    // Calentamiento
    const val DROP_SET = "DROP_SET"  // Drop set (bajada de peso)
    const val FAILURE  = "FAILURE"   // Hasta el fallo
}

data class User(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val email: String = "",
    val weightUnit: String = "KG"
)

data class WorkoutSession(
    val id: String = UUID.randomUUID().toString(),
    var userId: String = "",
    var name: String = "",
    val date: Date = Date(),
    var exercises: MutableList<WorkoutExercise> = mutableListOf(),
    var durationSeconds: Int = 0
)

data class WorkoutExercise(
    val id: String = UUID.randomUUID().toString(),
    val exerciseId: String = "",
    val exerciseName: String = "",
    var sets: MutableList<ExerciseSet> = mutableListOf(),
    var notes: String? = ""
)

data class ExerciseSet(
    val id: String = UUID.randomUUID().toString(),
    var weight: Double = 0.0,
    var repetitions: Int = 0,
    var timeSeconds: Int = 0,
    @get:PropertyName("isCompleted")
    @set:PropertyName("isCompleted")
    var isCompleted: Boolean = false,
    var setType: String = SetType.NORMAL  // NORMAL | WARMUP | DROP_SET | FAILURE
)

data class ExerciseHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val userId: String = "",
    val exerciseName: String = "",
    val date: Date = Date(),
    val sets: List<ExerciseSet> = emptyList()
)

data class ExerciseDefinition(
    val name: String = "",
    val mainGroup: String = "",
    val musclesUsed: String = "",
    val instructions: String = "",
    val gifUrl: String? = null,
    val imageUrl: String? = null,
    val subCategory: String? = null,
    val difficulty: String? = null,
    val equipment: String? = null,
    val nameEs: String? = null,
    val instructionsEs: String? = null,
)

// ─── Rutina programada ────────────────────────────────────────────────────────
data class ScheduledRoutine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val routineId: String = "",
    val routineName: String = "",
    val startDate: Long = 0L,
    val hourOfDay: Int = 8,
    val minute: Int = 0,
    val recurrenceType: String = RecurrenceType.ONCE,
    val intervalDays: Int = 1,
    val weekDay: Int = 2,
    val endDate: Long = 0L,
    val userId: String = ""
)

// ─── Resumen post-entrenamiento ───────────────────────────────────────────────
data class WorkoutSummaryData(
    val routineName: String = "",
    val durationSeconds: Int = 0,
    val exercises: List<WorkoutExercise> = emptyList(),
    val totalVolume: Int = 0,
    val totalSets: Int = 0
)

// ─── Medidas corporales ───────────────────────────────────────────────────────
data class BodyMeasurement(
    val id: String = UUID.randomUUID().toString(),
    val userId: String = "",
    val date: Date = Date(),
    val weight: Double = 0.0,      // kg
    val bodyFat: Double = 0.0,     // %
    val chest: Double = 0.0,       // cm
    val waist: Double = 0.0,       // cm
    val hips: Double = 0.0,        // cm
    val bicep: Double = 0.0,       // cm
    val thigh: Double = 0.0        // cm
)

// ─── Ejercicio personalizado ──────────────────────────────────────────────────
data class CustomExercise(
    val id: String = UUID.randomUUID().toString(),
    val userId: String = "",
    val name: String = "",
    val mainGroup: String = "",
    val musclesUsed: String = "",
    val equipment: String = "",
    val notes: String = ""
)

// ─── Logros ───────────────────────────────────────────────────────────────────
data class Achievement(
    val id: String = "",
    val emoji: String = "",
    val titleEs: String = "",
    val titleEn: String = "",
    val descEs: String = "",
    val descEn: String = "",
    val unlockedAt: Date? = null   // null = bloqueado
)

// ─── Catálogo de logros disponibles ──────────────────────────────────────────
object AchievementCatalog {
    val all = listOf(
        Achievement("first_workout",  "🥇", "Primer entreno",      "First Workout",    "Completaste tu primer entrenamiento",          "Completed your first workout"),
        Achievement("workouts_10",    "💪", "10 Entrenamientos",   "10 Workouts",      "Has completado 10 entrenamientos",             "Completed 10 workouts"),
        Achievement("workouts_50",    "🏆", "50 Entrenamientos",   "50 Workouts",      "Has completado 50 entrenamientos",             "Completed 50 workouts"),
        Achievement("workouts_100",   "💎", "100 Entrenamientos",  "100 Workouts",     "Has completado 100 entrenamientos",            "Completed 100 workouts"),
        Achievement("streak_7",       "🔥", "Racha de 7 días",     "7-Day Streak",     "7 días consecutivos entrenando",               "7 consecutive days working out"),
        Achievement("streak_30",      "⚡", "Racha de 30 días",    "30-Day Streak",    "30 días consecutivos entrenando",              "30 consecutive days working out"),
        Achievement("first_pr",       "🦁", "Primer PR",           "First PR",         "Superaste tu récord personal por primera vez", "Beat your personal record for the first time"),
        Achievement("volume_1000",    "🚀", "1 Tonelada",          "1 Ton",            "Levantaste 1000 kg en un solo entreno",        "Lifted 1000 kg in a single workout"),
        Achievement("custom_exercise","🎨", "Creador",             "Creator",          "Creaste tu primer ejercicio personalizado",    "Created your first custom exercise")
    )
}
