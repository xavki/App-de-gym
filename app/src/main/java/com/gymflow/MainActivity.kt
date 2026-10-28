package com.gymflow

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.gymflow.data.GymRepository
import kotlinx.coroutines.launch

enum class Screen {
    LOGIN, HOME, SELECT_EXERCISES, CREATE_ROUTINE, EDIT_ROUTINE, ROUTINE_DETAILS,
    EXERCISE_DETAIL, WORKOUT, EXERCISE_INFO, WORKOUT_SUMMARY, PR_BOARD, EXERCISE_PROGRESS
}

class MainActivity : ComponentActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    // Launcher para pedir permiso POST_NOTIFICATIONS en Android 13+
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* resultado ignorado; la notificación se envía si se concede */ }

    // ViewModel de verdad (sobrevive a girar la pantalla o recrear la actividad): antes se
    // creaba con remember{} dentro de setContent y se perdía el entreno en curso al recrearse.
    private val viewModel: GymFlowViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GymFlowViewModel(Firebase.auth, Firebase.firestore, GymRepository.get(applicationContext), applicationContext) as T
        }
    }

    // La app es en español (uso personal): se fija el idioma aunque quede guardado otro
    override fun attachBaseContext(newBase: Context) {
        val locale = java.util.Locale("es", "ES")
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = Firebase.auth
        db   = Firebase.firestore

        // Crear canales de notificaciones
        NotificationHelper.createChannel(this)
        RestAlert.createChannel(this)
        ExerciseNamesEs.load(applicationContext)

        // Pedir permiso de notificaciones (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val prefs = getSharedPreferences("GymFlowPrefs", Context.MODE_PRIVATE)
        val shouldRemember = prefs.getBoolean("remember_me", false)
        
        if (auth.currentUser != null && !shouldRemember) {
            auth.signOut()
        }

        // Tema guardado desde el primer fotograma (claro por defecto): sin destellos al arrancar
        applySystemBars(viewModel.darkTheme)

        setContent {
            val dark = viewModel.darkTheme
            LaunchedEffect(dark) { applySystemBars(dark) }
            GymFlowTheme(dark = dark) {
                GymFlowApp(
                    viewModel     = viewModel,
                    onGoogleSignIn = { onResult -> signInWithGoogle(onResult) }
                )
            }
        }
    }

    /** Barras del sistema transparentes (la app dibuja debajo y respeta los insets) con iconos legibles. */
    private fun applySystemBars(dark: Boolean) {
        val palette = if (dark) DarkPalette else LightPalette
        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.background.toArgb()))
    }

    // El descanso avisa con una alarma del sistema mientras la app no está a la vista
    override fun onStart() { super.onStart(); viewModel.onAppForeground() }
    override fun onStop() { viewModel.onAppBackground(); super.onStop() }

    private fun signInWithGoogle(onResult: (Boolean) -> Unit) {
        val credentialManager = CredentialManager.create(this)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId("961115677843-no9vf5lk0g2fdbs2igir8u5u4fkee5l2.apps.googleusercontent.com")
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        lifecycleScope.launch {
            try {
                Log.d("GymFlowAuth", "Iniciando proceso de Google Sign-In...")
                val result = credentialManager.getCredential(this@MainActivity, request)
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
                val credential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                auth.signInWithCredential(credential).addOnCompleteListener(this@MainActivity) { task ->
                    if (task.isSuccessful) {
                        Log.d("GymFlowAuth", "Login con Firebase exitoso")
                        getSharedPreferences("GymFlowPrefs", Context.MODE_PRIVATE).edit().putBoolean("remember_me", true).apply()
                        auth.currentUser?.let { user ->
                            FirebaseService.saveUser(user.uid, user.displayName ?: "Usuario Google", user.email ?: "")
                        }
                    } else {
                        Log.e("GymFlowAuth", "Error en Firebase: ${task.exception?.message}")
                        Toast.makeText(this@MainActivity, "Error de Firebase: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    }
                    onResult(task.isSuccessful)
                }
            } catch (e: Exception) {
                Log.e("GymFlowAuth", "Excepción durante el proceso: ${e.message}")
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                onResult(false)
            }
        }
    }
}

@Composable
fun GymFlowApp(
    viewModel: GymFlowViewModel,
    onGoogleSignIn: ((Boolean) -> Unit) -> Unit
) {
    var currentScreen by remember { mutableStateOf<Screen>(if (FirebaseService.auth.currentUser != null) Screen.HOME else Screen.LOGIN) }
    var previousScreen by remember { mutableStateOf<Screen?>(null) }
    
    // Estado para los ejercicios de Firestore
    var firestoreExercises by remember { mutableStateOf<List<ExerciseDefinition>>(emptyList()) }
    var isLoadingExercises by remember { mutableStateOf(true) }

    // Cargar ejercicios (Room; Firestore solo la primera vez, y exige estar logueado:
    // por eso se reintenta al salir del login si aún no hay catálogo)
    val context = LocalContext.current
    val loggedIn = currentScreen != Screen.LOGIN
    LaunchedEffect(loggedIn) {
        if (firestoreExercises.isEmpty()) {
            firestoreExercises = ExerciseRepository.loadExercises(GymRepository.get(context), FirebaseService.db)
        }
        isLoadingExercises = false
    }

    // Limpiar y recargar datos cada vez que cambie la pantalla o el usuario
    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.HOME) {
            viewModel.loadRoutines()
            viewModel.loadHistory()
        }
    }

    // Botón/gesto "atrás" del sistema (navegación de 3 botones incluida): hace lo mismo que la flecha
    // de cada pantalla. Sin esto cerraba la app desde cualquier pantalla interna. En LOGIN y HOME no
    // se intercepta (HOME gestiona sus pestañas y, desde Inicio, atrás sale de la app como siempre).
    BackHandler(enabled = currentScreen != Screen.LOGIN && currentScreen != Screen.HOME) {
        currentScreen = when (currentScreen) {
            Screen.SELECT_EXERCISES -> if (viewModel.selectedRoutine != null) Screen.EDIT_ROUTINE else Screen.HOME
            Screen.EDIT_ROUTINE -> Screen.ROUTINE_DETAILS
            Screen.EXERCISE_DETAIL -> {
                if (viewModel.selectedRoutine != null) viewModel.syncRoutine(viewModel.selectedRoutine!!, false)
                if (viewModel.selectedRoutine != null) Screen.ROUTINE_DETAILS else Screen.CREATE_ROUTINE
            }
            Screen.EXERCISE_INFO, Screen.EXERCISE_PROGRESS -> previousScreen ?: Screen.HOME
            // WORKOUT: igual que minimizar; el entreno sigue en curso y su borrador guardado
            else -> Screen.HOME
        }
    }

    // El teclado empuja el contenido (los campos de peso/reps no quedan tapados). Se descuenta la barra
    // de navegación porque cada pantalla ya la tiene en cuenta: así no hay paddings duplicados.
    Surface(
        color = BackgroundDark, contentColor = TextPrimary,
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))
    ) {
        when (currentScreen) {
            Screen.LOGIN -> LoginScreen(
                auth = FirebaseService.auth,
                db = FirebaseService.db,
                onLoginSuccess = { currentScreen = Screen.HOME },
                onGoogleSignIn = { onGoogleSignIn { if(it) currentScreen = Screen.HOME } },
                onError = { viewModel.errorMessage = it }
            )
            Screen.HOME -> HomeScreen(
                viewModel = viewModel,
                onCreateRoutine = { viewModel.tempExercises.clear(); viewModel.selectedRoutine = null; viewModel.exerciseSearch.beginSelection(emptyList()); currentScreen = Screen.SELECT_EXERCISES },
                onRoutineClick = { routine -> viewModel.selectedRoutine = routine; currentScreen = Screen.ROUTINE_DETAILS },
                onContinueWorkout = { viewModel.selectedRoutine = viewModel.activeWorkout; currentScreen = Screen.WORKOUT },
                onDeleteRoutine = { viewModel.routines.remove(it); viewModel.syncRoutine(it, true) },
                onCopyRoutine = { routine ->
                    viewModel.copyRoutine(routine)
                },
                onLogout = { 
                    FirebaseService.auth.signOut()
                    viewModel.clearData()
                    currentScreen = Screen.LOGIN 
                },
                onExerciseProgress = { name ->
                    viewModel.selectedExercise = WorkoutExercise(exerciseName = name)
                    previousScreen = Screen.HOME
                    currentScreen = Screen.EXERCISE_PROGRESS
                }
            )
            Screen.SELECT_EXERCISES -> ExerciseSelectionScreen(
                exercises = firestoreExercises + viewModel.customExercises.map { c ->
                    ExerciseDefinition(name = c.name, mainGroup = c.mainGroup, musclesUsed = c.musclesUsed, equipment = c.equipment, nameEs = c.name)
                },
                isLoading = isLoadingExercises,
                alreadySelected = if (viewModel.selectedRoutine != null) viewModel.selectedRoutine!!.exercises.map { it.exerciseName } else viewModel.tempExercises.map { it.exerciseName },
                onExercisesSelected = { names ->
                    val currentList = if (viewModel.selectedRoutine != null) viewModel.selectedRoutine!!.exercises else viewModel.tempExercises
                    
                    val newList = names.map { name ->
                        currentList.find { it.exerciseName == name } ?: WorkoutExercise(
                            exerciseName = name, 
                            sets = mutableListOf(ExerciseSet(weight = 0.0, repetitions = 0))
                        )
                    }

                    if (viewModel.selectedRoutine != null) {
                        viewModel.selectedRoutine!!.exercises.clear()
                        viewModel.selectedRoutine!!.exercises.addAll(newList)
                    } else {
                        viewModel.tempExercises.clear()
                        viewModel.tempExercises.addAll(newList)
                    }

                    currentScreen = if (viewModel.selectedRoutine != null) Screen.EDIT_ROUTINE else Screen.CREATE_ROUTINE
                },
                onBack = { currentScreen = if (viewModel.selectedRoutine != null) Screen.EDIT_ROUTINE else Screen.HOME },
                onInfoClick = { name -> 
                    viewModel.selectedExercise = WorkoutExercise(exerciseName = name)
                    previousScreen = currentScreen
                    currentScreen = Screen.EXERCISE_INFO
                },
                onCreateCustom = { custom -> viewModel.saveCustomExercise(custom) },
                state = viewModel.exerciseSearch
            )
            Screen.CREATE_ROUTINE, Screen.EDIT_ROUTINE -> {
                EditRoutineScreen(
                    initialRoutine = if (currentScreen == Screen.EDIT_ROUTINE) viewModel.selectedRoutine else null,
                    preselectedExercises = if (currentScreen == Screen.CREATE_ROUTINE) viewModel.tempExercises else null,
                    onSave = { new -> 
                        viewModel.syncRoutine(new)
                        currentScreen = Screen.HOME 
                    },
                    onBack = { currentScreen = if (currentScreen == Screen.EDIT_ROUTINE) Screen.ROUTINE_DETAILS else Screen.HOME },
                    onAddMore = {
                        viewModel.exerciseSearch.beginSelection(
                            (viewModel.selectedRoutine?.exercises ?: viewModel.tempExercises).map { it.exerciseName }
                        )
                        currentScreen = Screen.SELECT_EXERCISES
                    },
                    onExerciseClick = { viewModel.selectedExercise = it; currentScreen = Screen.EXERCISE_DETAIL },
                    onInfoClick = { 
                        viewModel.selectedExercise = it
                        previousScreen = currentScreen
                        currentScreen = Screen.EXERCISE_INFO
                    }
                )
            }
            Screen.ROUTINE_DETAILS -> viewModel.selectedRoutine?.let { 
                RoutineDetailScreen(
                    it, 
                    viewModel.activeWorkout != null, 
                    viewModel.activeWorkout?.id == it.id, 
                    onBack = { currentScreen = Screen.HOME }, 
                    onEditRoutine = { currentScreen = Screen.EDIT_ROUTINE }, 
                    onExerciseClick = { ex -> viewModel.selectedExercise = ex; currentScreen = Screen.EXERCISE_DETAIL }, 
                    onStartWorkout = { 
                        viewModel.startWorkout(it)
                        currentScreen = Screen.WORKOUT 
                    },
                    onInfoClick = { ex ->
                        viewModel.selectedExercise = ex
                        previousScreen = currentScreen
                        currentScreen = Screen.EXERCISE_INFO
                    },
                    viewModel = viewModel
                )
            }
            Screen.EXERCISE_DETAIL -> viewModel.selectedExercise?.let { 
                ExerciseDetailScreen(it, onBack = {
                    if (viewModel.selectedRoutine != null) viewModel.syncRoutine(viewModel.selectedRoutine!!, false)
                    currentScreen = if (viewModel.selectedRoutine != null) Screen.ROUTINE_DETAILS else Screen.CREATE_ROUTINE
                }, onInfoClick = {
                    previousScreen = currentScreen
                    currentScreen = Screen.EXERCISE_INFO
                })
            }
            Screen.WORKOUT -> viewModel.activeWorkout?.let { 
                WorkoutScreen(
                    routine = it, 
                    totalSeconds = viewModel.totalSeconds, 
                    onFinish = {
                        // Guardar datos de resumen ANTES de finalizar
                        viewModel.lastWorkoutSummary = WorkoutSummaryData(
                            routineName     = it.name,
                            durationSeconds = viewModel.totalSeconds,
                            exercises       = it.exercises.toList(),
                            totalVolume     = it.exercises.sumOf { ex ->
                                ex.sets.filter { s -> s.isCompleted || s.weight > 0 }
                                    .sumOf { s -> (s.weight * s.repetitions).toInt() }
                            },
                            totalSets       = it.exercises.sumOf { ex ->
                                ex.sets.count { s -> s.isCompleted }
                            }
                        )
                        viewModel.finishWorkout(it)
                        currentScreen = Screen.WORKOUT_SUMMARY 
                    },
                    onMinimize = { currentScreen = Screen.HOME },
                    onInfoClick = { ex ->
                        viewModel.selectedExercise = ex
                        previousScreen = currentScreen
                        currentScreen = Screen.EXERCISE_INFO
                    },
                    viewModel = viewModel
                )
            }
            Screen.EXERCISE_INFO -> viewModel.selectedExercise?.let { 
                ExerciseInfoScreen(
                    exerciseName = it.exerciseName,
                    onBack = { 
                        currentScreen = previousScreen ?: Screen.HOME
                    }
                )
            }
            Screen.WORKOUT_SUMMARY -> viewModel.lastWorkoutSummary?.let { summary ->
                WorkoutSummaryScreen(
                    summary = summary,
                    onDone  = { currentScreen = Screen.HOME },
                    onShare = {
                        // El botón compartir existe en WorkoutSummaryScreen y abre ShareWorkoutDialog
                    }
                )
            } ?: run { currentScreen = Screen.HOME }
            Screen.PR_BOARD -> PRBoardScreen(
                viewModel = viewModel,
                onBack = { currentScreen = Screen.HOME }
            )
            Screen.EXERCISE_PROGRESS -> {
                val exName = viewModel.selectedExercise?.exerciseName ?: ""
                ExerciseProgressScreen(
                    exerciseName = exName,
                    viewModel = viewModel,
                    onBack = { currentScreen = previousScreen ?: Screen.HOME }
                )
            }
        }
    }
}
