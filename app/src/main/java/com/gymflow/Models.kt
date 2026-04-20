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
    var timeSeconds: Int = 0, // Nuevo campo para cardio
    @get:PropertyName("isCompleted")
    @set:PropertyName("isCompleted")
    var isCompleted: Boolean = false
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
    // Campos para la traducción al español
    val nameEs: String? = null,
    val instructionsEs: String? = null,
)

// ─── Rutina programada ────────────────────────────────────────────────────────
data class ScheduledRoutine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val routineId: String = "",
    val routineName: String = "",
    val startDate: Long = 0L,             // Timestamp ms del primer/único día
    val hourOfDay: Int = 8,               // Hora de la notificación
    val minute: Int = 0,                  // Minuto de la notificación
    val recurrenceType: String = RecurrenceType.ONCE,
    val intervalDays: Int = 1,            // Para EVERY_N_DAYS: cada cuántos días
    val weekDay: Int = 2,                 // Para WEEKLY_DAY: Calendar.MONDAY..SUNDAY
    val endDate: Long = 0L,               // 0 = sin límite; para recurrentes
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
