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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
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

    // Aplicar idioma guardado al crear el contexto base
    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("GymFlowPrefs", Context.MODE_PRIVATE)
        val lang = prefs.getString("app_language", "es") ?: "es"
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = Firebase.auth
        db   = Firebase.firestore

        // Crear canal de notificaciones
        NotificationHelper.createChannel(this)

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

        // Forzar barras del sistema oscuras
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        
        setContent {
            val viewModel: GymFlowViewModel = remember { GymFlowViewModel(auth, db) }
            
            GymFlowApp(
                viewModel     = viewModel,
                onGoogleSignIn = { onResult -> signInWithGoogle(onResult) }
            ) 
        }
    }

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

    // Cargar ejercicios UNA sola vez al arrancar
    LaunchedEffect(Unit) {
        ExerciseRepository.loadExercises(FirebaseService.db) { exercises ->
            firestoreExercises = exercises
            isLoadingExercises = false
        }
    }

    // Limpiar y recargar datos cada vez que cambie la pantalla o el usuario
    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.HOME) {
            viewModel.loadRoutines()
            viewModel.loadHistory()
        }
    }

    Surface(color = BackgroundDark) {
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
                onCreateRoutine = { viewModel.tempExercises.clear(); viewModel.selectedRoutine = null; currentScreen = Screen.SELECT_EXERCISES },
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
                onCreateCustom = { custom -> viewModel.saveCustomExercise(custom) }
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
                    onAddMore = { currentScreen = Screen.SELECT_EXERCISES },
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
