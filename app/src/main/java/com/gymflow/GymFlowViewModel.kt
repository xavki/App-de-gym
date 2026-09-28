package com.gymflow

import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestore
import com.gymflow.data.DataExporter
import com.gymflow.data.FirestoreImporter
import com.gymflow.data.GymRepository
import kotlinx.coroutines.*
import java.util.Calendar
import java.util.Date

/**
 * Los datos viven en Room (GymRepository): todo funciona sin conexión.
 * Firebase solo se usa para el login y para la importación inicial desde Firestore.
 */
class GymFlowViewModel(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val repo: GymRepository,
    private val appContext: Context
) : ViewModel() {

    // ─── Rutinas e historial ────────────────────────────────────────────────
    var routines = mutableStateListOf<WorkoutSession>()
    var workoutHistory = mutableStateListOf<WorkoutSession>()
    var scheduledRoutines = mutableStateListOf<ScheduledRoutine>()

    var selectedRoutine by mutableStateOf<WorkoutSession?>(null)
    var selectedExercise by mutableStateOf<WorkoutExercise?>(null)
    val tempExercises = mutableStateListOf<WorkoutExercise>()

    // ─── Timer activo ───────────────────────────────────────────────────────
    var totalSeconds by mutableIntStateOf(0)
    private var timerJob: Job? = null

    var activeWorkout by mutableStateOf<WorkoutSession?>(null)
    var activeWorkoutExerciseIndex by mutableIntStateOf(0)

    // ─── Rest Timer ─────────────────────────────────────────────────────────
    var restTimerSeconds by mutableIntStateOf(60)
    var restTimerRunning by mutableStateOf(false)

    var isLoadingRoutines by mutableStateOf(false)
    var isLoadingHistory by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var lastWorkoutSummary by mutableStateOf<WorkoutSummaryData?>(null)
    var newPersonalRecord by mutableStateOf<String?>(null)

    // ─── Medidas corporales ─────────────────────────────────────────────────
    var bodyMeasurements = mutableStateListOf<BodyMeasurement>()
    var isLoadingMeasurements by mutableStateOf(false)

    // ─── Ejercicios personalizados ──────────────────────────────────────────
    var customExercises = mutableStateListOf<CustomExercise>()

    // ─── Logros ─────────────────────────────────────────────────────────────
    var achievements = mutableStateListOf<Achievement>()

    // ─── Racha (streak) ─────────────────────────────────────────────────────
    val workoutStreak: Int
        get() = computeStreak()

    // ─── Nuevo logro desbloqueado (para mostrar banner) ─────────────────────
    var newAchievement by mutableStateOf<Achievement?>(null)

    // ─── Preferencias de entrenamiento ──────────────────────────────────────
    var autoRestTimer      by mutableStateOf(false)  // Auto-iniciar timer al completar serie
    var defaultRestSeconds by mutableIntStateOf(90)  // Duración del descanso automático

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user == null) {
                clearData()
            } else {
                viewModelScope.launch {
                    loadAll()   // lo local aparece al instante
                    FirestoreImporter.importUserDataIfNeeded(appContext, repo, db, user.uid)
                    loadAll()
                }
            }
        }
    }

    private fun loadAll() {
        loadRoutines()
        loadHistory()
        loadSchedules()
        loadMeasurements()
        loadCustomExercises()
        loadAchievements()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // WORKOUT
    // ══════════════════════════════════════════════════════════════════════════

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
        bodyMeasurements.clear()
        customExercises.clear()
        achievements.clear()
        selectedRoutine = null
        selectedExercise = null
        tempExercises.clear()
        activeWorkout = null
        activeWorkoutExerciseIndex = 0
        restTimerSeconds = 60
        restTimerRunning = false
        totalSeconds = 0
    }

    fun finishWorkout(routine: WorkoutSession) {
        val user = auth.currentUser ?: return
        stopTimer()

        routine.durationSeconds = totalSeconds
        routine.userId = user.uid
        val startedAt = System.currentTimeMillis() - totalSeconds * 1000L

        val cleanedExercises = routine.exercises.map { ex ->
            ex.copy(sets = ex.sets.map { it.copy(isCompleted = false, rpe = null) }.toMutableList())
        }.toMutableList()

        activeWorkout = null
        activeWorkoutExerciseIndex = 0
        restTimerSeconds = 60
        restTimerRunning = false
        totalSeconds = 0

        viewModelScope.launch {
            repo.saveWorkout(user.uid, routine, routineId = routine.id, startedAt = startedAt)
            // La rutina recuerda los últimos pesos usados
            repo.saveRoutine(user.uid, routine.copy(exercises = cleanedExercises))
            reloadRoutines(user.uid)
            reloadHistory(user.uid)
            checkAchievements(routine)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCHEDULES
    // ══════════════════════════════════════════════════════════════════════════

    fun loadSchedules() {
        val uid = auth.currentUser?.uid ?: return
        viewModelScope.launch {
            val loaded = repo.schedules(uid)
            scheduledRoutines.clear()
            scheduledRoutines.addAll(loaded)
        }
    }

    fun saveSchedule(context: Context, schedule: ScheduledRoutine) {
        val uid = auth.currentUser?.uid ?: return
        val s = schedule.copy(userId = uid)
        viewModelScope.launch {
            try {
                repo.saveSchedule(uid, s)
                scheduledRoutines.removeAll { it.id == s.id }
                scheduledRoutines.add(s)
                NotificationHelper.scheduleAlarm(context, s)
            } catch (e: Exception) {
                errorMessage = "Error al guardar la programación"
            }
        }
    }

    fun deleteSchedule(context: Context, schedule: ScheduledRoutine) {
        auth.currentUser ?: return
        viewModelScope.launch {
            try {
                repo.deleteSchedule(schedule.id)
                scheduledRoutines.removeAll { it.id == schedule.id }
                NotificationHelper.cancelAlarm(context, schedule)
            } catch (e: Exception) {
                errorMessage = "Error al eliminar la programación"
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ROUTINES
    // ══════════════════════════════════════════════════════════════════════════

    fun loadRoutines() {
        val user = auth.currentUser ?: return
        viewModelScope.launch { reloadRoutines(user.uid) }
    }

    fun loadHistory() {
        val user = auth.currentUser ?: return
        viewModelScope.launch { reloadHistory(user.uid) }
    }

    private suspend fun reloadRoutines(uid: String) {
        isLoadingRoutines = true
        val loaded = repo.routines(uid)
        routines.clear()
        routines.addAll(loaded)
        isLoadingRoutines = false
    }

    private suspend fun reloadHistory(uid: String) {
        isLoadingHistory = true
        val loaded = repo.workouts(uid)
        workoutHistory.clear()
        workoutHistory.addAll(loaded)
        isLoadingHistory = false
    }

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
        viewModelScope.launch {
            try {
                if (delete) repo.deleteRoutine(routine.id) else repo.saveRoutine(user.uid, routine)
                reloadRoutines(user.uid)
            } catch (e: Exception) {
                errorMessage = if (delete) "Error al borrar" else "Error al guardar"
            }
        }
    }

    fun createFromTemplate(templateName: String) {
        val user = auth.currentUser ?: return
        val exercises: List<WorkoutExercise> = when (templateName) {
            "Hipertrofia" -> listOf(
                WorkoutExercise(exerciseName = "Bench Press",            sets = MutableList(4) { ExerciseSet(repetitions = 10, weight = 60.0) }),
                WorkoutExercise(exerciseName = "Bent Over Barbell Row",  sets = MutableList(4) { ExerciseSet(repetitions = 10, weight = 60.0) }),
                WorkoutExercise(exerciseName = "Barbell Squat",          sets = MutableList(4) { ExerciseSet(repetitions = 10, weight = 80.0) }),
                WorkoutExercise(exerciseName = "Overhead Press",         sets = MutableList(3) { ExerciseSet(repetitions = 10, weight = 40.0) }),
                WorkoutExercise(exerciseName = "Barbell Curl",           sets = MutableList(3) { ExerciseSet(repetitions = 12, weight = 25.0) }),
                WorkoutExercise(exerciseName = "Triceps Pushdown",       sets = MutableList(3) { ExerciseSet(repetitions = 12, weight = 20.0) })
            )
            "Fuerza" -> listOf(
                WorkoutExercise(exerciseName = "Barbell Squat",          sets = MutableList(5) { ExerciseSet(repetitions = 5, weight = 100.0) }),
                WorkoutExercise(exerciseName = "Bench Press",            sets = MutableList(5) { ExerciseSet(repetitions = 5, weight = 80.0) }),
                WorkoutExercise(exerciseName = "Deadlift",               sets = MutableList(3) { ExerciseSet(repetitions = 5, weight = 120.0) }),
                WorkoutExercise(exerciseName = "Overhead Press",         sets = MutableList(3) { ExerciseSet(repetitions = 5, weight = 50.0) }),
                WorkoutExercise(exerciseName = "Bent Over Barbell Row",  sets = MutableList(3) { ExerciseSet(repetitions = 5, weight = 70.0) })
            )
            "Definición" -> listOf(
                WorkoutExercise(exerciseName = "Running, Treadmill",     sets = MutableList(1) { ExerciseSet(timeSeconds = 1200) }),
                WorkoutExercise(exerciseName = "Pushup",                 sets = MutableList(3) { ExerciseSet(repetitions = 20) }),
                WorkoutExercise(exerciseName = "Bodyweight Squat",       sets = MutableList(3) { ExerciseSet(repetitions = 20) }),
                WorkoutExercise(exerciseName = "Pull-Up",                sets = MutableList(3) { ExerciseSet(repetitions = 10) }),
                WorkoutExercise(exerciseName = "Plank",                  sets = MutableList(3) { ExerciseSet(timeSeconds = 60) }),
                WorkoutExercise(exerciseName = "Burpees",                sets = MutableList(3) { ExerciseSet(repetitions = 15) })
            )
            else -> emptyList()
        }
        syncRoutine(WorkoutSession(
            id        = java.util.UUID.randomUUID().toString(),
            name      = templateName,
            userId    = user.uid,
            exercises = exercises.toMutableList()
        ))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PERSONAL RECORDS & 1RM
    // ══════════════════════════════════════════════════════════════════════════

    fun checkPersonalRecord(exerciseName: String, weight: Double, reps: Int): Boolean {
        if (weight <= 0 || reps <= 0) return false
        val volume = weight * reps
        val previousBest = workoutHistory
            .flatMap { it.exercises.filter { ex -> ex.exerciseName == exerciseName } }
            .flatMap { it.sets }
            .filter { it.isCompleted || it.weight > 0 }
            .maxOfOrNull { it.weight * it.repetitions } ?: 0.0
        return volume > previousBest
    }

    /** Devuelve el 1RM estimado (Brzycki) para un ejercicio dado, basándose en el mejor set del historial. */
    fun estimatedOneRM(exerciseName: String): Double {
        val bestSet = workoutHistory
            .flatMap { it.exercises.filter { ex -> ex.exerciseName == exerciseName } }
            .flatMap { it.sets }
            .filter { it.weight > 0 && it.repetitions > 0 }
            .maxByOrNull { oneRMBrzycki(it.weight, it.repetitions) }
            ?: return 0.0
        return oneRMBrzycki(bestSet.weight, bestSet.repetitions)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BODY MEASUREMENTS
    // ══════════════════════════════════════════════════════════════════════════

    fun loadMeasurements() {
        val uid = auth.currentUser?.uid ?: return
        isLoadingMeasurements = true
        viewModelScope.launch {
            val loaded = repo.measurements(uid)
            bodyMeasurements.clear()
            bodyMeasurements.addAll(loaded)
            isLoadingMeasurements = false
        }
    }

    fun saveMeasurement(measurement: BodyMeasurement) {
        val uid = auth.currentUser?.uid ?: return
        val m = measurement.copy(userId = uid)
        viewModelScope.launch {
            repo.saveMeasurement(uid, m)
            bodyMeasurements.removeAll { it.id == m.id }
            bodyMeasurements.add(m)
            bodyMeasurements.sortBy { it.date }
        }
    }

    fun deleteMeasurement(measurement: BodyMeasurement) {
        auth.currentUser ?: return
        viewModelScope.launch {
            repo.deleteMeasurement(measurement.id)
            bodyMeasurements.removeAll { it.id == measurement.id }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CUSTOM EXERCISES
    // ══════════════════════════════════════════════════════════════════════════

    fun loadCustomExercises() {
        val uid = auth.currentUser?.uid ?: return
        viewModelScope.launch {
            val loaded = repo.customExercises(uid)
            customExercises.clear()
            customExercises.addAll(loaded)
        }
    }

    fun saveCustomExercise(exercise: CustomExercise) {
        val uid = auth.currentUser?.uid ?: return
        val ex = exercise.copy(userId = uid)
        viewModelScope.launch {
            repo.saveCustomExercise(uid, ex)
            customExercises.removeAll { it.id == ex.id }
            customExercises.add(ex)
            // Comprobar logro "Creador"
            if (customExercises.size == 1) unlockAchievement("custom_exercise")
        }
    }

    fun deleteCustomExercise(exercise: CustomExercise) {
        auth.currentUser ?: return
        viewModelScope.launch {
            repo.deleteCustomExercise(exercise.id)
            customExercises.removeAll { it.id == exercise.id }
        }
    }

    /** Devuelve la lista combinada de ejercicios del repositorio + personalizados como ExerciseDefinition */
    fun allExerciseDefinitions(): List<ExerciseDefinition> {
        val custom = customExercises.map { c ->
            ExerciseDefinition(
                name        = c.name,
                mainGroup   = c.mainGroup,
                musclesUsed = c.musclesUsed,
                equipment   = c.equipment,
                nameEs      = c.name
            )
        }
        return ExerciseRepository.getCached() + custom
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STREAK & ACHIEVEMENTS
    // ══════════════════════════════════════════════════════════════════════════

    private fun computeStreak(): Int {
        if (workoutHistory.isEmpty()) return 0
        val sorted = workoutHistory
            .map { midnight(it.date.time) }
            .distinct()
            .sortedDescending()
        val today = midnight(System.currentTimeMillis())
        val yesterday = today - 86_400_000L
        // Si no hay entrenamiento hoy ni ayer → racha en 0
        if (sorted.first() != today && sorted.first() != yesterday) return 0
        var streak = 0
        var expected = if (sorted.first() == today) today else yesterday
        for (day in sorted) {
            if (day == expected) { streak++; expected -= 86_400_000L } else break
        }
        return streak
    }

    fun loadAchievements() {
        val uid = auth.currentUser?.uid ?: return
        viewModelScope.launch {
            val loaded = repo.achievements(uid)
            achievements.clear()
            achievements.addAll(loaded)
        }
    }

    private fun unlockAchievement(id: String) {
        val uid = auth.currentUser?.uid ?: return
        val idx = achievements.indexOfFirst { it.id == id }
        if (idx == -1 || achievements[idx].unlockedAt != null) return
        val now = Date()
        val unlocked = achievements[idx].copy(unlockedAt = now)
        achievements[idx] = unlocked
        newAchievement = unlocked
        viewModelScope.launch { repo.unlockAchievement(uid, id, now.time) }
    }

    fun checkAchievements(lastWorkout: WorkoutSession? = null) {
        val count = workoutHistory.size
        val streak = workoutStreak

        if (count >= 1)   unlockAchievement("first_workout")
        if (count >= 10)  unlockAchievement("workouts_10")
        if (count >= 50)  unlockAchievement("workouts_50")
        if (count >= 100) unlockAchievement("workouts_100")
        if (streak >= 7)  unlockAchievement("streak_7")
        if (streak >= 30) unlockAchievement("streak_30")

        // Volumen > 1000 kg en un solo entreno
        lastWorkout?.let { w ->
            val vol = w.exercises.sumOf { ex ->
                ex.sets.filter { it.isCompleted }.sumOf { it.weight * it.repetitions }
            }
            if (vol >= 1000) unlockAchievement("volume_1000")
        }

        // PR desbloqueado (ya se detecta en WorkoutScreen → viewModel.newPersonalRecord)
        if (achievements.none { it.id == "first_pr" && it.unlockedAt != null } && newPersonalRecord != null) {
            unlockAchievement("first_pr")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EXPORTAR (JSON / CSV)
    // ══════════════════════════════════════════════════════════════════════════

    /** Escribe la exportación en el archivo elegido por el usuario. Devuelve éxito o error por callback. */
    fun exportData(target: Uri, asCsv: Boolean, onDone: (Boolean) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onDone(false)
        viewModelScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    if (asCsv) DataExporter.exportCsv(appContext, repo, uid, target)
                    else DataExporter.exportJson(appContext, repo, uid, target)
                }
                true
            } catch (e: Exception) {
                errorMessage = "Error al exportar: ${e.message}"
                false
            }
            onDone(ok)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private fun midnight(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

// ─── 1RM Fórmulas (top-level para reusar en UI) ───────────────────────────────
fun oneRMBrzycki(weight: Double, reps: Int): Double =
    if (reps <= 0) 0.0 else weight * (36.0 / (37.0 - reps))

fun oneRMEpley(weight: Double, reps: Int): Double =
    if (reps <= 0) 0.0 else weight * (1 + reps / 30.0)

fun oneRMLander(weight: Double, reps: Int): Double =
    if (reps <= 0) 0.0 else (100 * weight) / (101.3 - 2.67123 * reps)
