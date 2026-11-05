package com.gymflow

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
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
 * R6-01/R6-02: ventana normal + scroll semántico (performScrollToNode) + assertIsDisplayed; descripción del mapa por igualdad completa y subcadena explícita.
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

    /**
     * Montaje con la ventana NORMAL del dispositivo (R6-01): ya no se fuerza un contenedor de 3000 dp. Los elementos que quedan fuera del
     * viewport se alcanzan con la acción semántica de scroll del LazyColumn (performScrollToNode) y se exige assertIsDisplayed.
     */
    private fun mount(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent { GymFlowTheme(dark = true) { content() } }

    /** Desplaza el ÚNICO LazyColumn (el que expone ScrollToIndex; el scroll horizontal de los chips no lo tiene) hasta el nodo. */
    private fun scrollTo(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)

    private fun w(name: String, weight: Double = 100.0, date: Long = System.currentTimeMillis() - 60_000, id: String = "w1", reps: Int = 8,
                  exercise: String = "Press banca", sets: Int = 1) = WorkoutSession(
        id = id, userId = "u", name = name, date = Date(date), durationSeconds = 0,
        exercises = mutableListOf(WorkoutExercise(id = "e-$id", exerciseName = exercise,
            sets = MutableList(sets) { n -> ExerciseSet(id = "s-$id-$n", weight = weight, repetitions = reps, isCompleted = true) })))

    /** Mismo patrón de recarga que el ViewModel (clear + addAll en el hilo de UI), sin emitir ningún evento extra. */
    private fun reload(vararg list: WorkoutSession) = compose.runOnUiThread { vm.workoutHistory.clear(); vm.workoutHistory.addAll(list) }

    /** Coincidencia EXACTA del texto completo, tras desplazarse hasta él, y VISIBLE en la ventana (no basta con que exista). */
    private fun shown(text: String) { scrollTo(hasText(text)); compose.onNodeWithText(text).assertIsDisplayed() }

    /**
     * Ausencia del texto. Solo es una prueba válida cuando el texto antiguo vivía en el MISMO item del LazyColumn que otro texto nuevo que
     * se acaba de mostrar con [shown] (ese item está compuesto): si siguiera el valor antiguo, estaría en el árbol.
     */
    private fun gone(text: String) = compose.onAllNodesWithText(text).assertCountEquals(0)

    /** Posición vertical en la raíz; los nodos deben estar ya compuestos (mismo item o tras scrollTo). */
    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test fun homeShowsReplacedWorkoutWithSameCountAndSameId() {
        val old = w("AUDIT OLD WORKOUT", weight = 100.0); val new = w("AUDIT NEW WORKOUT", weight = 125.0)
        assertEquals(800.0, TrainingStats.volume(old), 0.0); assertEquals(1000.0, TrainingStats.volume(new), 0.0)     // valores exactos
        vm.workoutHistory.add(old)
        mount { HomeTab(vm, onContinueWorkout = {}, onGoTrain = {}, onGoProgress = {}) }
        // estado anterior (precondición): fila de «Actividad reciente» y tile «Volumen», visibles
        shown("AUDIT OLD WORKOUT"); shown("${fmtVolume(800.0)} · 1 series"); shown(fmtVolume(800.0))
        gone("AUDIT NEW WORKOUT")                                      // mismo item que el nombre antiguo: si estuviera, aparecería
        reload(new); compose.waitForIdle()
        // estado posterior: mismo nº de entrenos, mismo id, contenido distinto
        assertEquals(1, vm.workoutHistory.size)
        shown("AUDIT NEW WORKOUT"); shown("${fmtVolume(1000.0)} · 1 series"); shown(fmtVolume(1000.0))
        gone("AUDIT OLD WORKOUT"); gone("${fmtVolume(800.0)} · 1 series"); gone(fmtVolume(800.0))      // cada uno vive en un item ya compuesto arriba
    }

    @Test fun homeReflectsNameChangeAndDateChangeWithSameCount() {
        val now = System.currentTimeMillis(); val h = 3_600_000L
        vm.workoutHistory.addAll(listOf(w("Alfa", date = now - 1 * h, id = "a"), w("Beta", date = now - 2 * h, id = "b")))
        mount { HomeTab(vm, onContinueWorkout = {}, onGoTrain = {}, onGoProgress = {}) }
        shown("Alfa"); shown("Beta")                                   // visibles (la lista de actividad reciente es un único item)
        assertTrue("precondición: Alfa (más reciente) antes que Beta", top("Alfa") < top("Beta"))
        // fechas intercambiadas, mismos nombres y mismo número de entrenos: ahora Beta es el más reciente
        reload(w("Alfa", date = now - 2 * h, id = "a"), w("Beta", date = now - 1 * h, id = "b")); compose.waitForIdle()
        shown("Alfa"); shown("Beta")
        assertTrue("orden cronológico real tras el cambio de fecha", top("Beta") < top("Alfa"))
        // cambio de nombre
        reload(w("Alfa RENOMBRADA", date = now - 2 * h, id = "a"), w("Beta", date = now - 1 * h, id = "b")); compose.waitForIdle()
        shown("Alfa RENOMBRADA"); shown("Beta"); gone("Alfa")
        assertTrue(top("Beta") < top("Alfa RENOMBRADA"))
    }

    @Test fun progressRecordsReflectWeightChangeWithSameCount() {
        val day1 = System.currentTimeMillis() - 86_400_000L; val day3 = System.currentTimeMillis() - 3 * 86_400_000L
        val display = ExerciseNamesEs.display("Press banca")
        fun record(kg: Double, date: Long) = "1 sesiones · máx. ${fmtKg(kg)} × 8 · ${fmtDay(date)}"
        fun orm(kg: Double) = fmtKg(Math.round(TrainingStats.e1rm(kg, 8) * 10) / 10.0)
        assertEquals(480.0, TrainingStats.volume(w("x", weight = 60.0)) * 1.0, 0.0)          // 60 × 8
        vm.workoutHistory.add(w("Push", weight = 60.0, date = day1))
        mount { ProgressTab(vm, onOpenPRBoard = {}, onExerciseProgress = {}) }
        // estado anterior
        shown(fmtVolume(480.0)); shown(display); shown(record(60.0, day1)); shown(orm(60.0))
        // peso distinto, mismo nº de entrenos: récord, 1RM estimado y volumen exactos
        reload(w("Push", weight = 100.0, date = day1)); compose.waitForIdle()
        shown(fmtVolume(800.0)); gone(fmtVolume(480.0))                                       // tile «Volumen»
        shown(record(100.0, day1)); shown(orm(100.0)); gone(record(60.0, day1)); gone(orm(60.0))   // item del récord
        // fecha distinta (mismo peso): el récord muestra la nueva fecha
        reload(w("Push", weight = 100.0, date = day3)); compose.waitForIdle()
        assertTrue("las dos fechas deben diferir para que la prueba sea válida", fmtDay(day1) != fmtDay(day3))
        shown(record(100.0, day3)); gone(record(100.0, day1))
    }

    // ─── R5-03 / R6-02: el mapa muscular sigue al CONTENIDO del catálogo con cardinalidad constante ───
    private fun muscleHistory() = vm.workoutHistory.add(w("Mapa", exercise = "Ejercicio X", sets = 3, weight = 50.0, reps = 10, id = "m1"))
    private val mapMatcher get() = hasContentDescription("Mapa muscular", substring = true)
    private fun map() = compose.onNodeWithContentDescription("Mapa muscular", substring = true)

    private fun series(n: Int) = if (n == 1) "1 serie" else "$n series"

    /** Descripción COMPLETA esperada: mismo formato que la de producción (grupos en el orden de MuscleActivity.GROUPS). */
    private fun expectedMap(chest: Int, back: Int) = "Mapa muscular, últimos 7 días: " + MuscleActivity.GROUPS.joinToString(", ") { (id, name) ->
        "$name ${series(when (id) { "chest" -> chest; "back" -> back; else -> 0 })}"
    }

    /**
     * R6-02: assertContentDescriptionContains NO hereda el `substring = true` del selector; usa su propio parámetro. Se comprueba la
     * descripción completa (igualdad) y, además, los dos recuentos con subcadena EXPLÍCITA (`substring = true`).
     */
    private fun assertMap(chest: Int, back: Int) {
        scrollTo(mapMatcher); map().assertIsDisplayed()
        map().assertContentDescriptionEquals(expectedMap(chest, back))
        map().assertContentDescriptionContains("Pecho ${series(chest)}", substring = true)
        map().assertContentDescriptionContains("Espalda ${series(back)}", substring = true)
    }

    @Test fun muscleMapFollowsCustomExerciseGroupChangeWithSameCardinality() {
        muscleHistory()
        vm.customExercises.add(CustomExercise(id = "c1", userId = "u", name = "Ejercicio X", mainGroup = "Pecho"))
        mount { ProgressTab(vm, onOpenPRBoard = {}, onExerciseProgress = {}) }
        assertMap(chest = 3, back = 0)
        // X pasa de Pecho a Espalda: mismo historial, mismo nº de ejercicios personalizados
        compose.runOnUiThread { vm.customExercises.clear(); vm.customExercises.add(CustomExercise(id = "c1", userId = "u", name = "Ejercicio X", mainGroup = "Espalda")) }
        compose.waitForIdle()
        assertEquals(1, vm.customExercises.size)
        assertMap(chest = 0, back = 3)
    }

    @Test fun muscleMapFollowsGlobalCatalogGroupChangeWithSameCardinality() {
        muscleHistory()
        ExerciseRepository.replaceCached(listOf(ExerciseDefinition(name = "Ejercicio X", mainGroup = "Pecho")))
        mount { ProgressTab(vm, onOpenPRBoard = {}, onExerciseProgress = {}) }
        assertMap(chest = 3, back = 0)
        compose.runOnUiThread { ExerciseRepository.replaceCached(listOf(ExerciseDefinition(name = "Ejercicio X", mainGroup = "Espalda"))) }
        compose.waitForIdle()
        assertEquals(1, ExerciseRepository.getCached().size)
        assertMap(chest = 0, back = 3)
    }
}
