package com.gymflow

import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import java.util.Date

class GymFlowViewModel(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore
) : ViewModel() {
    var routines = mutableStateListOf<WorkoutSession>()
    var workoutHistory = mutableStateListOf<WorkoutSession>()
    var scheduledRoutines = mutableStateListOf<ScheduledRoutine>()
    
    var selectedRoutine by mutableStateOf<WorkoutSession?>(null)
    var selectedExercise by mutableStateOf<WorkoutExercise?>(null)
    val tempExercises = mutableStateListOf<WorkoutExercise>()
    
    var totalSeconds by mutableIntStateOf(0)
    private var timerJob: Job? = null

    var activeWorkout by mutableStateOf<WorkoutSession?>(null)
    var activeWorkoutExerciseIndex by mutableIntStateOf(0)

    // ─── Rest Timer (persiste al navegar a info, minimizar, etc.) ──────────
    var restTimerSeconds by mutableIntStateOf(60)
    var restTimerRunning by mutableStateOf(false)
    
    var isLoadingRoutines by mutableStateOf(false)
    var isLoadingHistory by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var lastWorkoutSummary by mutableStateOf<WorkoutSummaryData?>(null)
    // PR detectado: nombre del ejercicio si hay nuevo récord
    var newPersonalRecord by mutableStateOf<String?>(null)

    init {
        auth.addAuthStateListener { firebaseAuth ->
            if (firebaseAuth.currentUser == null) {
                clearData()
            } else {
                loadRoutines()
                loadHistory()
                loadSchedules()
            }
        }
    }

    fun startWorkout(routine: WorkoutSession) {
        activeWorkout = routine
        activeWorkoutExerciseIndex = 0
        restTimerSeconds = 60
        restTimerRunning = false
        totalSeconds = 0
        startTimer()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                totalSeconds++
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    fun clearData() {
        stopTimer()
        routines.clear()
        workoutHistory.clear()
        scheduledRoutines.clear()
        selectedRoutine = null
        selectedExercise = null
        tempExercises.clear()
        activeWorkout = null
        activeWorkoutExerciseIndex = 0
        restTimerSeconds = 60
        restTimerRunning = false
        totalSeconds = 0
    }

    // ─── Schedules ─────────────────────────────────────────────────────────────
    fun loadSchedules() {
        val uid = auth.currentUser?.uid ?: return
        ScheduleRepository.loadSchedules(uid) { loaded ->
            scheduledRoutines.clear()
            scheduledRoutines.addAll(loaded)
        }
    }

    fun saveSchedule(context: Context, schedule: ScheduledRoutine) {
        val uid = auth.currentUser?.uid ?: return
        val s = schedule.copy(userId = uid)
        ScheduleRepository.saveSchedule(uid, s) { success ->
            if (success) {
                scheduledRoutines.removeAll { it.id == s.id }
                scheduledRoutines.add(s)
                NotificationHelper.scheduleAlarm(context, s)
            } else {
                errorMessage = "Error al guardar la programación"
            }
        }
    }

    fun deleteSchedule(context: Context, schedule: ScheduledRoutine) {
        val uid = auth.currentUser?.uid ?: return
        ScheduleRepository.deleteSchedule(uid, schedule.id) { success ->
            if (success) {
                scheduledRoutines.removeAll { it.id == schedule.id }
                NotificationHelper.cancelAlarm(context, schedule)
            } else {
                errorMessage = "Error al eliminar la programación"
            }
        }
    }

    fun loadRoutines() {
        val user = auth.currentUser ?: return
        isLoadingRoutines = true
        RoutineRepository.loadRoutines(user.uid) { loaded ->
            routines.clear()
            routines.addAll(loaded)
            isLoadingRoutines = false
        }
    }

    fun loadHistory() {
        val user = auth.currentUser ?: return
        isLoadingHistory = true
        RoutineRepository.loadWorkoutHistory(user.uid) { loaded ->
            workoutHistory.clear()
            workoutHistory.addAll(loaded)
            isLoadingHistory = false
        }
    }

    fun finishWorkout(routine: WorkoutSession) {
        val user = auth.currentUser ?: return
        stopTimer()
        
        // Asignar duración final y usuario
        routine.durationSeconds = totalSeconds
        routine.userId = user.uid
        
        // 1. Guardar en historial real de Firebase
        RoutineRepository.saveHistory(user.uid, routine)
        
        // 2. Guardar historial por ejercicio individual (para la pantalla info)
        saveExerciseHistory(user.uid, routine)
        
        // 3. Crear una copia limpia para la plantilla (quitar checks de completado)
        val cleanedExercises = routine.exercises.map { ex ->
            ex.copy(sets = ex.sets.map { it.copy(isCompleted = false) }.toMutableList())
        }.toMutableList()
        
        val templateRoutine = routine.copy(exercises = cleanedExercises)
        
        // 4. Sincronizar la plantilla
        syncRoutine(templateRoutine)
        
        // 5. Resetear estado
        activeWorkout = null
        activeWorkoutExerciseIndex = 0
        restTimerSeconds = 60
        restTimerRunning = false
        totalSeconds = 0
        loadHistory()
    }

    /**
     * Guarda un ExerciseHistoryEntry por cada ejercicio que tenga al menos una serie
     * completada (o con datos), para que aparezca en ExerciseInfoScreen.
     */
    private fun saveExerciseHistory(userId: String, routine: WorkoutSession) {
        routine.exercises.forEach { exercise ->
            val completedSets = exercise.sets.filter { it.isCompleted || it.weight > 0 || it.repetitions > 0 || it.timeSeconds > 0 }
            if (completedSets.isNotEmpty()) {
                val entry = ExerciseHistoryEntry(
                    userId       = userId,
                    exerciseName = exercise.exerciseName,
                    date         = Date(),
                    sets         = completedSets.map { it.copy() }
                )
                db.collection("users").document(userId)
                    .collection("exercise_history")
                    .document(entry.id)
                    .set(entry)
            }
        }
    }

    /**
     * Duplica una rutina con un nuevo ID y nombre "(copia)".
     */
    fun copyRoutine(routine: WorkoutSession) {
        val copiedExercises = routine.exercises.map { ex ->
            ex.copy(
                id   = java.util.UUID.randomUUID().toString(),
                sets = ex.sets.map { it.copy(id = java.util.UUID.randomUUID().toString()) }.toMutableList()
            )
        }.toMutableList()
        
        val copy = routine.copy(
            id        = java.util.UUID.randomUUID().toString(),
            name      = "${routine.name} (copia)",
            exercises = copiedExercises
        )
        syncRoutine(copy)
    }

    fun syncRoutine(routine: WorkoutSession, delete: Boolean = false) {
        val user = auth.currentUser ?: return
        routine.userId = user.uid
        if (delete) {
            RoutineRepository.deleteRoutine(user.uid, routine.id) { success ->
                if (!success) errorMessage = "Error al borrar" else loadRoutines()
            }
        } else {
            RoutineRepository.saveRoutine(user.uid, routine) { success ->
                if (!success) errorMessage = "Error al guardar" else loadRoutines()
            }
        }
    }

    /**
     * Comprueba si el peso x reps es un nuevo PR para ese ejercicio.
     * Devuelve true si supera el máximo conocido en el historial.
     */
    fun checkPersonalRecord(exerciseName: String, weight: Double, reps: Int): Boolean {
        if (weight <= 0 || reps <= 0) return false
        val volume = weight * reps
        val previousBest = workoutHistory
            .flatMap { session -> session.exercises.filter { it.exerciseName == exerciseName } }
            .flatMap { it.sets }
            .filter { it.isCompleted || it.weight > 0 }
            .maxOfOrNull { it.weight * it.repetitions } ?: 0.0
        return volume > previousBest
    }

    /**
     * Crea una rutina desde una plantilla predefinida.
     */
    fun createFromTemplate(templateName: String) {
        val user = auth.currentUser ?: return

        val exercises: List<WorkoutExercise> = when (templateName) {
            "Hipertrofia" -> listOf(
                WorkoutExercise(exerciseName = "Bench Press", sets = MutableList(4) { ExerciseSet(repetitions = 10, weight = 60.0) }),
                WorkoutExercise(exerciseName = "Bent Over Barbell Row", sets = MutableList(4) { ExerciseSet(repetitions = 10, weight = 60.0) }),
                WorkoutExercise(exerciseName = "Barbell Squat", sets = MutableList(4) { ExerciseSet(repetitions = 10, weight = 80.0) }),
                WorkoutExercise(exerciseName = "Overhead Press", sets = MutableList(3) { ExerciseSet(repetitions = 10, weight = 40.0) }),
                WorkoutExercise(exerciseName = "Barbell Curl", sets = MutableList(3) { ExerciseSet(repetitions = 12, weight = 25.0) }),
                WorkoutExercise(exerciseName = "Triceps Pushdown", sets = MutableList(3) { ExerciseSet(repetitions = 12, weight = 20.0) })
            )
            "Fuerza" -> listOf(
                WorkoutExercise(exerciseName = "Barbell Squat", sets = MutableList(5) { ExerciseSet(repetitions = 5, weight = 100.0) }),
                WorkoutExercise(exerciseName = "Bench Press", sets = MutableList(5) { ExerciseSet(repetitions = 5, weight = 80.0) }),
                WorkoutExercise(exerciseName = "Deadlift", sets = MutableList(3) { ExerciseSet(repetitions = 5, weight = 120.0) }),
                WorkoutExercise(exerciseName = "Overhead Press", sets = MutableList(3) { ExerciseSet(repetitions = 5, weight = 50.0) }),
                WorkoutExercise(exerciseName = "Bent Over Barbell Row", sets = MutableList(3) { ExerciseSet(repetitions = 5, weight = 70.0) })
            )
            "Definición" -> listOf(
                WorkoutExercise(exerciseName = "Running, Treadmill", sets = MutableList(1) { ExerciseSet(timeSeconds = 1200) }),
                WorkoutExercise(exerciseName = "Pushup", sets = MutableList(3) { ExerciseSet(repetitions = 20) }),
                WorkoutExercise(exerciseName = "Bodyweight Squat", sets = MutableList(3) { ExerciseSet(repetitions = 20) }),
                WorkoutExercise(exerciseName = "Pull-Up", sets = MutableList(3) { ExerciseSet(repetitions = 10) }),
                WorkoutExercise(exerciseName = "Plank", sets = MutableList(3) { ExerciseSet(timeSeconds = 60) }),
                WorkoutExercise(exerciseName = "Burpees", sets = MutableList(3) { ExerciseSet(repetitions = 15) })
            )
            else -> emptyList()
        }

        val newRoutine = WorkoutSession(
            id       = java.util.UUID.randomUUID().toString(),
            name     = templateName,
            userId   = user.uid,
            exercises = exercises.toMutableList()
        )
        syncRoutine(newRoutine)
    }
}
