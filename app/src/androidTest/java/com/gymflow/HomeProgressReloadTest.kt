package com.gymflow

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * NOTA DE API: `assertDoesNotExist()` es un MIEMBRO de SemanticsNodeInteraction (no una extensión importable): no lleva import. Para
 * «no existe» entre varios nodos se usa onAllNodesWithText(..).assertCountEquals(0).
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

    @After fun tearDown() { ExerciseRepository.replaceCached(emptyList()); db.close() }

    /** El contenedor es más alto que la pantalla para que el LazyColumn componga TODAS las filas (si no, las de abajo no existen). */
    private fun tall(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        GymFlowTheme(dark = true) { Box(Modifier.requiredSize(412.dp, 3000.dp)) { content() } }
    }

    private fun w(name: String, weight: Double = 100.0, date: Long = System.currentTimeMillis() - 60_000, id: String = "w1", reps: Int = 8,
                  exercise: String = "Press banca", sets: Int = 1) = WorkoutSession(
        id = id, userId = "u", name = name, date = Date(date), durationSeconds = 0,
        exercises = mutableListOf(WorkoutExercise(id = "e-$id", exerciseName = exercise,
            sets = MutableList(sets) { n -> ExerciseSet(id = "s-$id-$n", weight = weight, repetitions = reps, isCompleted = true) })))

    /** Mismo patrón de recarga que el ViewModel (clear + addAll en el hilo de UI), sin emitir ningún evento extra. */
    private fun reload(vararg list: WorkoutSession) = compose.runOnUiThread { vm.workoutHistory.clear(); vm.workoutHistory.addAll(list) }

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
    private fun gone(text: String) = compose.onAllNodesWithText(text).assertCountEquals(0)
    private fun shown(text: String) = compose.onNodeWithText(text).assertIsDisplayed()       // coincidencia EXACTA del texto completo

    @Test fun homeShowsReplacedWorkoutWithSameCountAndSameId() {
        val old = w("AUDIT OLD WORKOUT", weight = 100.0); val new = w("AUDIT NEW WORKOUT", weight = 125.0)
        assertEquals(800.0, TrainingStats.volume(old), 0.0); assertEquals(1000.0, TrainingStats.volume(new), 0.0)     // valores exactos
        vm.workoutHistory.add(old)
        tall { HomeTab(vm, onContinueWorkout = {}, onGoTrain = {}, onGoProgress = {}) }
        // estado anterior (precondición)
        shown("AUDIT OLD WORKOUT"); shown("${fmtVolume(800.0)} · 1 series"); shown(fmtVolume(800.0))      // fila y tile «Volumen»
        gone("AUDIT NEW WORKOUT")
        reload(new); compose.waitForIdle()
        // estado posterior: mismo nº de entrenos, mismo id, contenido distinto
        assertEquals(1, vm.workoutHistory.size)
        shown("AUDIT NEW WORKOUT"); shown("${fmtVolume(1000.0)} · 1 series"); shown(fmtVolume(1000.0))
        gone("AUDIT OLD WORKOUT"); gone("${fmtVolume(800.0)} · 1 series"); gone(fmtVolume(800.0))
    }

    @Test fun homeReflectsNameChangeAndDateChangeWithSameCount() {
        val now = System.currentTimeMillis(); val h = 3_600_000L
        vm.workoutHistory.addAll(listOf(w("Alfa", date = now - 1 * h, id = "a"), w("Beta", date = now - 2 * h, id = "b")))
        tall { HomeTab(vm, onContinueWorkout = {}, onGoTrain = {}, onGoProgress = {}) }
        assertTrue("precondición: Alfa (más reciente) antes que Beta", top("Alfa") < top("Beta"))
        // fechas intercambiadas, mismos nombres y mismo número de entrenos: ahora Beta es el más reciente
        reload(w("Alfa", date = now - 2 * h, id = "a"), w("Beta", date = now - 1 * h, id = "b")); compose.waitForIdle()
        assertTrue("orden cronológico real tras el cambio de fecha", top("Beta") < top("Alfa"))
        // cambio de nombre
        reload(w("Alfa RENOMBRADA", date = now - 2 * h, id = "a"), w("Beta", date = now - 1 * h, id = "b")); compose.waitForIdle()
        shown("Alfa RENOMBRADA"); gone("Alfa"); assertTrue(top("Beta") < top("Alfa RENOMBRADA"))
    }

    @Test fun progressRecordsReflectWeightChangeWithSameCount() {
        val day1 = System.currentTimeMillis() - 86_400_000L; val day3 = System.currentTimeMillis() - 3 * 86_400_000L
        val display = ExerciseNamesEs.display("Press banca")
        fun record(kg: Double, date: Long) = "1 sesiones · máx. ${fmtKg(kg)} × 8 · ${fmtDay(date)}"
        fun orm(kg: Double) = fmtKg(Math.round(TrainingStats.e1rm(kg, 8) * 10) / 10.0)
        assertEquals(480.0, TrainingStats.volume(w("x", weight = 60.0)) * 1.0, 0.0)          // 60 × 8
        vm.workoutHistory.add(w("Push", weight = 60.0, date = day1))
        tall { ProgressTab(vm, onOpenPRBoard = {}, onExerciseProgress = {}) }
        // estado anterior
        shown(display); shown(record(60.0, day1)); shown(orm(60.0)); shown(fmtVolume(480.0))
        // peso distinto, mismo nº de entrenos: récord, 1RM estimado y volumen exactos
        reload(w("Push", weight = 100.0, date = day1)); compose.waitForIdle()
        shown(record(100.0, day1)); shown(orm(100.0)); shown(fmtVolume(800.0))
        gone(record(60.0, day1)); gone(orm(60.0)); gone(fmtVolume(480.0))
        // fecha distinta (mismo peso): el récord muestra la nueva fecha
        reload(w("Push", weight = 100.0, date = day3)); compose.waitForIdle()
        assertTrue("las dos fechas deben diferir para que la prueba sea válida", fmtDay(day1) != fmtDay(day3))
        shown(record(100.0, day3)); gone(record(100.0, day1))
    }

    // ─── R5-03: el mapa muscular sigue al CONTENIDO del catálogo con cardinalidad constante ───
    private fun muscleHistory() = vm.workoutHistory.add(w("Mapa", exercise = "Ejercicio X", sets = 3, weight = 50.0, reps = 10, id = "m1"))
    private fun map() = compose.onNodeWithContentDescription("Mapa muscular", substring = true)

    @Test fun muscleMapFollowsCustomExerciseGroupChangeWithSameCardinality() {
        muscleHistory()
        vm.customExercises.add(CustomExercise(id = "c1", userId = "u", name = "Ejercicio X", mainGroup = "Pecho"))
        tall { ProgressTab(vm, onOpenPRBoard = {}, onExerciseProgress = {}) }
        map().assertContentDescriptionContains("Pecho 3 series"); map().assertContentDescriptionContains("Espalda 0 series")
        // X pasa de Pecho a Espalda: mismo historial, mismo nº de ejercicios personalizados
        compose.runOnUiThread { vm.customExercises.clear(); vm.customExercises.add(CustomExercise(id = "c1", userId = "u", name = "Ejercicio X", mainGroup = "Espalda")) }
        compose.waitForIdle()
        assertEquals(1, vm.customExercises.size)
        map().assertContentDescriptionContains("Pecho 0 series"); map().assertContentDescriptionContains("Espalda 3 series")
    }

    @Test fun muscleMapFollowsGlobalCatalogGroupChangeWithSameCardinality() {
        muscleHistory()
        ExerciseRepository.replaceCached(listOf(ExerciseDefinition(name = "Ejercicio X", mainGroup = "Pecho")))
        tall { ProgressTab(vm, onOpenPRBoard = {}, onExerciseProgress = {}) }
        map().assertContentDescriptionContains("Pecho 3 series")
        compose.runOnUiThread { ExerciseRepository.replaceCached(listOf(ExerciseDefinition(name = "Ejercicio X", mainGroup = "Espalda"))) }
        compose.waitForIdle()
        assertEquals(1, ExerciseRepository.getCached().size)
        map().assertContentDescriptionContains("Pecho 0 series"); map().assertContentDescriptionContains("Espalda 3 series")
    }
}
