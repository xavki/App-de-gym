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
import com.gymflow.sync.SyncManager
import com.gymflow.sync.SyncState
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
    /** Buscador de ejercicios: texto, filtros, selección y posición se conservan al ir al detalle y volver. */
    val exerciseSearch = ExerciseSearchState()

    // ─── Timer activo ───────────────────────────────────────────────────────
    var totalSeconds by mutableIntStateOf(0)
    private var timerJob: Job? = null

    var activeWorkout by mutableStateOf<WorkoutSession?>(null)

    // ─── Entreno en curso guardado en el móvil (sobrevive a que Android cierre la app) ──
    private val draftStore = ActiveWorkoutStore(appContext.filesDir)
    /** Id del entreno en curso: al finalizar se guarda con él (repetir no crea duplicados). */
    private var activeWorkoutId: String? = null
    /** Usuario dueño del entreno en curso (fijado al empezar o recuperar, no al guardar). */
    private var activeWorkoutUid: String? = null
    private var workoutStartedAtMs: Long = 0
    private var draftJob: Job? = null
    /** Hay un entreno recuperado tras cerrar la app (para avisar en Inicio). */
    var workoutRecovered by mutableStateOf(false)
    var activeWorkoutExerciseIndex by mutableIntStateOf(0)

    // ─── Descanso entre series ──────────────────────────────────────────────
    // Vive aquí (no en la pantalla) para seguir contando al cerrar el diálogo, cambiar de
    // ejercicio o de pantalla. Reloj monotónico: sigue bien con la pantalla bloqueada.
    private val restTimer = RestTimer { android.os.SystemClock.elapsedRealtime() }
    private var restJob: Job? = null
    var restStatus by mutableStateOf(RestTimer.Status.IDLE)
        private set
    var restSecondsLeft by mutableIntStateOf(0)
        private set

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

    // ─── Preferencias de entrenamiento (se guardan en el móvil) ─────────────
    private val prefs = appContext.getSharedPreferences("GymFlowPrefs", Context.MODE_PRIVATE)
    private var autoRestState by mutableStateOf(prefs.getBoolean(PREF_AUTO_REST, false))
    private var restSecondsState by mutableIntStateOf(prefs.getInt(PREF_REST_SECONDS, 90))
    /** Empezar el descanso automáticamente al marcar una serie como hecha. */
    var autoRestTimer: Boolean
        get() = autoRestState
        set(value) { autoRestState = value; prefs.edit().putBoolean(PREF_AUTO_REST, value).apply() }
    /** Tema oscuro elegido por el usuario (por defecto, claro). */
    private var darkThemeState by mutableStateOf(ThemePrefs.isDark(appContext))
    var darkTheme: Boolean
        get() = darkThemeState
        set(value) { darkThemeState = value; ThemePrefs.setDark(appContext, value) }
    /** Duración del descanso (segundos). */
    var defaultRestSeconds: Int
        get() = restSecondsState
        set(value) { restSecondsState = value.coerceIn(15, 600); prefs.edit().putInt(PREF_REST_SECONDS, restSecondsState).apply() }

    // ─── Sincronización (declarado antes de init: init ya lo usa) ───────────
    var syncState by mutableStateOf<SyncState>(SyncState.Idle)
        private set
    var pendingChanges by mutableIntStateOf(0)
        private set
    var serverUrl by mutableStateOf(SyncManager.serverUrl(appContext))
        private set

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user == null) {
                clearData()
            } else {
                restoreDraft(user.uid)
                viewModelScope.launch {
                    savePendingFinishes(user.uid)   // entrenos finalizados que no llegaron a Room
                    loadAll()   // lo local aparece al instante
                    FirestoreImporter.importUserDataIfNeeded(appContext, repo, db, user.uid)
                    loadAll()
                    SyncManager.schedule(appContext)
                    refreshPending()
                }
            }
        }
        // Cuando llegan cambios del servidor (otro móvil), se recargan las listas
        viewModelScope.launch {
            SyncManager.state.collect { state ->
                syncState = state
                if (state is SyncState.Done && state.result.pulled > 0) {
                    loadAll()
                    if ("schedules" in state.result.pulledTables) rescheduleAlarms()
                }
                if (state !is SyncState.Running) refreshPending()
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
    // SINCRONIZACIÓN con el servidor propio
    // ══════════════════════════════════════════════════════════════════════════

    /** Tras cualquier cambio local: sincronizar en segundo plano en cuanto haya red. */
    private fun changed() {
        SyncManager.requestSync(appContext)
        refreshPending()
    }

    private fun refreshPending() {
        val uid = auth.currentUser?.uid ?: return
        viewModelScope.launch { pendingChanges = repo.pendingChanges(uid) }
    }

    fun updateServerUrl(url: String) {
        SyncManager.setServerUrl(appContext, url)
        serverUrl = SyncManager.serverUrl(appContext)
    }

    fun syncNow() {
        viewModelScope.launch {
            try {
                if (SyncManager.syncNow(appContext) == null) errorMessage = "Configura antes la dirección del servidor"
            } catch (e: Exception) {
                errorMessage = "No se pudo sincronizar: ${e.message}"
            }
        }
    }

    /** Código para vincular el panel web (caduca en 10 minutos). */
    fun createPairingCode(onResult: (String?) -> Unit) {
        val api = SyncManager.api(appContext) ?: return onResult(null)
        viewModelScope.launch {
            onResult(try { api.pairingCode().code } catch (e: Exception) { errorMessage = "No se pudo pedir el código: ${e.message}"; null })
        }
    }

    fun revokePanels(onResult: (Int?) -> Unit) {
        val api = SyncManager.api(appContext) ?: return onResult(null)
        viewModelScope.launch {
            onResult(try { api.revokePanels().revoked } catch (e: Exception) { errorMessage = "No se pudo desvincular: ${e.message}"; null })
        }
    }

    /** Las programaciones que llegan de otro móvil necesitan su alarma en este. */
    private fun rescheduleAlarms() {
        val uid = auth.currentUser?.uid ?: return
        viewModelScope.launch {
            NotificationHelper.let { helper ->
                repo.deletedSchedules(uid).forEach { helper.cancelAlarm(appContext, it) }
                repo.schedules(uid).filter { helper.nextTriggerMs(it) != null }.forEach { helper.scheduleAlarm(appContext, it) }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // WORKOUT
    // ══════════════════════════════════════════════════════════════════════════

    fun startWorkout(routine: WorkoutSession) {
        activeWorkout = routine
        activeWorkoutExerciseIndex = 0
        activeWorkoutId = java.util.UUID.randomUUID().toString()
        activeWorkoutUid = auth.currentUser?.uid
        workoutStartedAtMs = System.currentTimeMillis()
        workoutRecovered = false
        cancelRest()
        totalSeconds = 0
        startTimer()
        saveDraftNow()
    }

    // ── Borrador del entreno en curso ─────────────────────────────────────────

    private fun currentDraft(): ActiveWorkoutDraft? {
        val w = activeWorkout ?: return null
        return ActiveWorkoutDraft(
            draftId = activeWorkoutId ?: java.util.UUID.randomUUID().toString().also { activeWorkoutId = it },
            uid = activeWorkoutUid, session = w, startedAtMs = workoutStartedAtMs,
            exerciseIndex = activeWorkoutExerciseIndex, savedAtMs = System.currentTimeMillis(),
        )
    }

    /** Guarda ya (en el hilo actual): al pasar a segundo plano, antes de que Android pueda cerrar la app. */
    fun saveDraftNow() {
        val d = currentDraft() ?: return
        try { draftStore.write(d.uid, draftStore.encode(d)) } catch (_: Exception) { }
    }

    /** Guarda en breve (agrupa las pulsaciones seguidas). Se serializa en el hilo principal: copia coherente. */
    fun saveDraftSoon() {
        if (activeWorkout == null) return
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(800)
            val d = currentDraft() ?: return@launch
            val json = draftStore.encode(d)
            withContext(Dispatchers.IO) { try { draftStore.write(d.uid, json) } catch (_: Exception) { } }
        }
    }

    /**
     * Recupera el entreno que quedó a medias (proceso cerrado por Android, app cerrada…).
     * Solo si no hay ya uno en marcha y pertenece al mismo usuario. La duración se recalcula
     * desde el inicio real.
     */
    fun restoreDraft(uid: String?) {
        if (activeWorkout != null) return
        val d = draftStore.read(uid) ?: return
        if (d.uid != uid) return
        activeWorkoutId = d.draftId
        activeWorkoutUid = d.uid
        workoutStartedAtMs = ActiveWorkoutStore.resumeStartMs(d, System.currentTimeMillis())
        activeWorkout = d.session
        activeWorkoutExerciseIndex = d.exerciseIndex.coerceIn(0, (d.session.exercises.size - 1).coerceAtLeast(0))
        workoutRecovered = true
        startTimer()
    }

    /** Descarta el entreno en curso: no se guarda nada y se borra el borrador. */
    fun discardWorkout() {
        stopTimer()
        cancelRest()
        draftJob?.cancel()
        draftStore.clear(activeWorkoutUid)
        activeWorkout = null
        activeWorkoutId = null
        activeWorkoutUid = null
        activeWorkoutExerciseIndex = 0
        totalSeconds = 0
        workoutRecovered = false
    }

    // ── Descanso ──────────────────────────────────────────────────────────────

    /** Empieza un descanso nuevo (sustituye al que hubiera) con la duración configurada. */
    fun startRest(seconds: Int = defaultRestSeconds) {
        restTimer.start(seconds)
        RestAlert.cancelBackground(appContext)
        runRestTicker()
    }

    fun pauseRest() { restTimer.pause(); publishRest() }
    fun resumeRest() { restTimer.resume(); runRestTicker() }
    /** Vuelve a empezar el descanso actual desde su duración. */
    fun restartRest() { restTimer.restart(defaultRestSeconds); RestAlert.cancelBackground(appContext); runRestTicker() }
    fun addRestSeconds(seconds: Int) { restTimer.add(seconds); publishRest() }

    fun cancelRest() {
        restJob?.cancel()
        restTimer.cancel()
        RestAlert.cancelBackground(appContext)
        publishRest()
    }

    private fun publishRest() {
        restStatus = restTimer.status
        restSecondsLeft = restTimer.remainingSeconds()
    }

    /** Un solo bucle de refresco; el tiempo real lo da el reloj, no el número de vueltas. */
    private fun runRestTicker() {
        publishRest()
        if (restJob?.isActive == true) return
        restJob = viewModelScope.launch {
            while (isActive) {
                if (restTimer.tick()) RestAlert.playInApp(appContext)
                publishRest()
                if (restTimer.status != RestTimer.Status.RUNNING) break
                delay(200L)
            }
        }
    }

    /** La app pasa a segundo plano: si hay descanso en marcha, avisa la alarma del sistema. */
    fun onAppBackground() {
        saveDraftNow()
        if (restTimer.status != RestTimer.Status.RUNNING) return
        restTimer.delegateAlert()
        RestAlert.scheduleBackground(appContext, restTimer.remainingMs())
    }

    /** La app vuelve: si el descanso no ha acabado, el aviso lo vuelve a dar la app. */
    fun onAppForeground() {
        if (restTimer.status == RestTimer.Status.RUNNING) {
            RestAlert.cancelBackground(appContext)
            restTimer.reclaimAlert()
            runRestTicker()
        } else {
            publishRest()
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            // Desde la hora de inicio real: tras recuperar un entreno la duración sigue siendo la buena
            while (isActive) {
                totalSeconds = (((System.currentTimeMillis() - workoutStartedAtMs) / 1000).toInt()).coerceAtLeast(0)
                delay(1000L)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    fun clearData() {
        // Al cerrar sesión el entreno en curso queda en el borrador de su usuario (no se pierde)
        saveDraftNow()
        draftJob?.cancel()
        activeWorkoutId = null
        activeWorkoutUid = null
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
        cancelRest()
        totalSeconds = 0
    }

    fun finishWorkout(routine: WorkoutSession) {
        val user = auth.currentUser ?: return
        // Segunda pulsación de Finalizar (o con el entreno ya cerrado): no hay nada más que guardar.
        // Sin esto se guardaba otra vez con un id nuevo y quedaba duplicado.
        if (activeWorkout == null) return
        stopTimer()

        routine.durationSeconds = totalSeconds
        routine.userId = user.uid
        val startedAt = System.currentTimeMillis() - totalSeconds * 1000L
        // Mismo id que el borrador: si se repite el guardado (recuperación) no se duplica.
        val workoutId = activeWorkoutId ?: java.util.UUID.randomUUID().toString()
        draftJob?.cancel()
        // Primero queda en disco como finalizado pendiente y después se quita el borrador: si Room
        // falla o Android cierra la app antes de guardar, se guarda al volver a abrirla.
        val pending = PendingFinish(workoutId, user.uid, routine.id, routine, startedAt)
        try {
            draftStore.addPendingFinish(pending)
            draftStore.clear(activeWorkoutUid)
        } catch (_: Exception) { } // sin disco: el borrador sigue ahí y el guardado en Room continúa
        activeWorkoutId = null
        activeWorkoutUid = null
        workoutRecovered = false

        val cleanedExercises = routine.exercises.map { ex ->
            ex.copy(sets = ex.sets.map { it.copy(isCompleted = false, rpe = null) }.toMutableList())
        }.toMutableList()

        activeWorkout = null
        activeWorkoutExerciseIndex = 0
        cancelRest()
        totalSeconds = 0

        viewModelScope.launch {
            try {
                repo.saveWorkout(user.uid, routine, routineId = routine.id, workoutId = workoutId, startedAt = startedAt)
            } catch (e: Exception) {
                errorMessage = "No se pudo guardar el entreno. Sigue en el móvil y se guardará al volver a abrir la app."
                return@launch
            }
            draftStore.removePendingFinish(workoutId)
            try {
                // La rutina recuerda los últimos pesos usados
                repo.saveRoutine(user.uid, routine.copy(exercises = cleanedExercises))
            } catch (_: Exception) { }
            changed()
            reloadRoutines(user.uid)
            reloadHistory(user.uid)
            checkAchievements(routine)
        }
    }

    /**
     * Guarda en Room los entrenos finalizados que no llegaron a guardarse (fallo de Room o app
     * cerrada justo al finalizar). Si ya estaban, solo se retira el pendiente: no se duplican ni
     * se recrean entrenos que el usuario borró después.
     */
    suspend fun savePendingFinishes(uid: String) {
        for (p in draftStore.pendingFinishes(uid)) {
            try {
                if (!repo.workoutExists(p.workoutId)) {
                    repo.saveWorkout(uid, p.session, routineId = p.routineId, workoutId = p.workoutId, startedAt = p.startedAtMs)
                    changed()
                }
                draftStore.removePendingFinish(p.workoutId)
            } catch (_: Exception) { } // se reintenta en la próxima apertura
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
                changed()
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
                changed()
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
                changed()
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
            changed()
            bodyMeasurements.removeAll { it.id == m.id }
            bodyMeasurements.add(m)
            bodyMeasurements.sortBy { it.date }
        }
    }

    fun deleteMeasurement(measurement: BodyMeasurement) {
        auth.currentUser ?: return
        viewModelScope.launch {
            repo.deleteMeasurement(measurement.id)
            changed()
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
            changed()
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
            changed()
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

    private fun computeStreak(): Int =
        TrainingStats.streak(workoutHistory.map { it.date.time }, System.currentTimeMillis())

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
        viewModelScope.launch { repo.unlockAchievement(uid, id, now.time); changed() }
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
}

private const val PREF_AUTO_REST = "auto_rest_timer"
private const val PREF_REST_SECONDS = "rest_seconds"

// ─── 1RM Fórmulas (top-level para reusar en UI) ───────────────────────────────
fun oneRMBrzycki(weight: Double, reps: Int): Double =
    if (reps <= 0) 0.0 else weight * (36.0 / (37.0 - reps))

fun oneRMEpley(weight: Double, reps: Int): Double =
    if (reps <= 0) 0.0 else weight * (1 + reps / 30.0)

fun oneRMLander(weight: Double, reps: Int): Double =
    if (reps <= 0) 0.0 else (100 * weight) / (101.3 - 2.67123 * reps)
