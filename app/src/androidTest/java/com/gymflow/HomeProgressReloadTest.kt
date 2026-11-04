package com.gymflow

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.gymflow.data.GymDatabase
import com.gymflow.data.GymRepository
import java.util.Date
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R4-03 sobre los composables REALES de Inicio y Progreso. FirebaseApp ficticia (demo-gymflow), Room en memoria, sin sesión y sin red.
 * Reproduce el patrón de recarga clear()+addAll() con un entreno sustituto del MISMO id y el MISMO número de entrenos.
 * PENDIENTE DE EJECUCIÓN en emulador desechable (no se puede ejecutar en Linux sin SDK de Android).
 * El cambio de cuenta y las recargas solapadas se cubren en JVM (ListPublisherTest, ReloadGate): aquí no hay sesión Firebase local.
 */
@RunWith(AndroidJUnit4::class)
class HomeProgressReloadTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: GymDatabase
    private lateinit var vm: GymFlowViewModel

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val app = FirebaseApp.getApps(ctx).firstOrNull { it.name == "round5" } ?: FirebaseApp.initializeApp(
            ctx, FirebaseOptions.Builder().setProjectId("demo-gymflow").setApiKey("fake-api-key").setApplicationId("1:000000000000:android:0000000000000000").build(), "round5")
        db = Room.inMemoryDatabaseBuilder(ctx, GymDatabase::class.java).allowMainThreadQueries().build()
        vm = GymFlowViewModel(FirebaseAuth.getInstance(app), FirebaseFirestore.getInstance(app), GymRepository(db), ctx)
    }

    @After fun tearDown() = db.close()

    private fun w(name: String, weight: Double = 60.0, date: Long = System.currentTimeMillis() - 1_000, id: String = "w1") = WorkoutSession(
        id = id, userId = "u", name = name, date = Date(date), durationSeconds = 1800,
        exercises = mutableListOf(WorkoutExercise(id = "e1", exerciseName = "Press banca", sets = mutableListOf(ExerciseSet(id = "s1", weight = weight, repetitions = 8, isCompleted = true)))))

    /** Mismo patrón de recarga que el ViewModel (clear + addAll en el hilo de UI), sin tocar la lista con un evento extra. */
    private fun reload(vararg list: WorkoutSession) = compose.runOnUiThread { vm.workoutHistory.clear(); vm.workoutHistory.addAll(list) }

    @Test fun homeShowsReplacedWorkoutWithSameCountAndSameId() {
        vm.workoutHistory.add(w("AUDIT OLD WORKOUT"))
        compose.setContent { GymFlowTheme(dark = true) { HomeTab(vm, onContinueWorkout = {}, onGoTrain = {}, onGoProgress = {}) } }
        compose.onNodeWithText("AUDIT OLD WORKOUT", substring = true).assertIsDisplayed()
        reload(w("AUDIT NEW WORKOUT"))
        compose.waitForIdle()
        compose.onNodeWithText("AUDIT NEW WORKOUT", substring = true).assertIsDisplayed()
        compose.onNodeWithText("AUDIT OLD WORKOUT", substring = true).assertDoesNotExist()
    }

    @Test fun homeReflectsNameChangeAndDateChangeWithSameCount() {
        val now = System.currentTimeMillis()
        vm.workoutHistory.add(w("Antes", date = now - 1_000))
        vm.workoutHistory.add(w("Otro", date = now - 5_000, id = "w2"))
        compose.setContent { GymFlowTheme(dark = true) { HomeTab(vm, onContinueWorkout = {}, onGoTrain = {}, onGoProgress = {}) } }
        reload(w("Despues", date = now - 1_000), w("Otro", date = now - 5_000, id = "w2"))      // nombre
        compose.waitForIdle(); compose.onNodeWithText("Despues", substring = true).assertIsDisplayed()
        reload(w("Despues", date = now - 9_000_000_000L), w("Otro", date = now - 1_000, id = "w2"))   // fechas: «Otro» pasa a ser el más reciente
        compose.waitForIdle(); compose.onNodeWithText("Otro", substring = true).assertIsDisplayed()
    }

    @Test fun progressRecordsReflectWeightChangeWithSameCount() {
        vm.workoutHistory.add(w("Push", weight = 60.0))
        compose.setContent { GymFlowTheme(dark = true) { ProgressTab(vm, onOpenPRBoard = {}, onExerciseProgress = {}) } }
        reload(w("Push", weight = 100.0))
        compose.waitForIdle()
        compose.onNodeWithText("100", substring = true).assertIsDisplayed()     // el récord/volumen refleja el nuevo peso
    }
}
