package com.gymflow

import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.gymflow.data.GymRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Date
import java.util.UUID

/**
 * Recorrido visual de la renovación en el EMULADOR, con datos de demostración:
 * pantallas reales de la app (mismos composables y ViewModel), sin iniciar sesión, sin Firebase
 * ni servidor. Las capturas son de la pantalla completa (barras del sistema incluidas) y se
 * guardan en /sdcard/Android/data/com.gymflow/files/capturas.
 *
 *   adb shell am instrument -w -e class com.gymflow.VisualTourTest com.gymflow.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class VisualTourTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ui: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val outDir by lazy { File(ctx.getExternalFilesDir(null), "capturas").apply { mkdirs() } }

    private var dark by mutableStateOf(false)
    private var route by mutableStateOf("home")
    private var fontScale by mutableFloatStateOf(1f)
    private lateinit var vm: GymFlowViewModel

    // ─── Datos de demostración ────────────────────────────────────────────────
    private val catalog = listOf(
        "Barbell Bench Press - Medium Grip" to "Pecho", "Incline Dumbbell Press" to "Pecho",
        "Barbell Squat" to "Piernas", "Romanian Deadlift" to "Piernas", "Leg Press" to "Piernas",
        "Wide-Grip Lat Pulldown" to "Espalda", "Close-Grip Front Lat Pulldown" to "Espalda", "Seated Cable Rows" to "Espalda",
        "Standing Military Press" to "Hombros", "Side Lateral Raise" to "Hombros",
        "Barbell Curl" to "Bíceps", "Triceps Pushdown" to "Tríceps", "Plank" to "Core",
        "Standing Calf Raises" to "Gemelos", "Barbell Hip Thrust" to "Glúteos",
    ).map { (n, g) -> ExerciseDefinition(name = n, mainGroup = g, musclesUsed = musclesUsed[n] ?: g, equipment = "Barra") }

    // Músculos tal como vienen en el catálogo real (principal primero)
    private val musclesUsed get() = mapOf(
        "Barbell Bench Press - Medium Grip" to "Pecho, Hombros, Tríceps", "Incline Dumbbell Press" to "Pecho, Hombros, Tríceps",
        "Barbell Squat" to "Cuádriceps, Gemelos, Glúteos, Isquiotibiales, Espalda Baja",
        "Romanian Deadlift" to "Isquiotibiales, Gemelos, Glúteos, Espalda Baja",
        "Leg Press" to "Cuádriceps, Gemelos, Glúteos, Isquiotibiales",
        "Wide-Grip Lat Pulldown" to "Dorsales, Bíceps, Espalda Media, Hombros",
        "Close-Grip Front Lat Pulldown" to "Dorsales, Bíceps, Espalda Media, Hombros",
        "Seated Cable Rows" to "Espalda Media, Bíceps, Dorsales, Hombros",
        "Standing Military Press" to "Hombros, Tríceps", "Side Lateral Raise" to "Hombros",
        "Barbell Curl" to "Bíceps, Antebrazo", "Triceps Pushdown" to "Tríceps", "Plank" to "Abdominales",
        "Standing Calf Raises" to "Gemelos", "Barbell Hip Thrust" to "Glúteos, Gemelos, Isquiotibiales",
    )

    private fun sets(w: Double, reps: List<Int>, warmup: Boolean = false) = buildList {
        if (warmup) add(ExerciseSet(weight = w * 0.5, repetitions = 10, setType = SetType.WARMUP, isCompleted = true))
        reps.forEach { add(ExerciseSet(weight = w, repetitions = it, isCompleted = true)) }
    }.toMutableList()

    private val plans = mapOf(
        "Empuje" to listOf("Barbell Bench Press - Medium Grip" to 60.0, "Incline Dumbbell Press" to 22.0, "Standing Military Press" to 35.0, "Triceps Pushdown" to 20.0),
        "Tirón" to listOf("Wide-Grip Lat Pulldown" to 50.0, "Seated Cable Rows" to 45.0, "Barbell Curl" to 25.0),
        "Pierna" to listOf("Barbell Squat" to 80.0, "Romanian Deadlift" to 70.0, "Leg Press" to 140.0),
    )

    private fun routines() = plans.map { (name, ex) ->
        WorkoutSession(id = UUID.randomUUID().toString(), name = name, exercises = ex.map { (n, w) ->
            WorkoutExercise(exerciseName = n, sets = sets(w + 10, listOf(8, 8, 8)))
        }.toMutableList())
    }

    private fun history(): List<WorkoutSession> {
        val now = System.currentTimeMillis()
        val names = plans.keys.toList()
        return (0 until 36).map { i ->
            val daysAgo = 84 - i * 2 - (i % 3)
            val name = names[i % 3]
            val prog = i / 3 * 1.25
            WorkoutSession(
                id = UUID.randomUUID().toString(), name = name, date = Date(now - daysAgo * 86_400_000L),
                durationSeconds = 3000 + (i % 5) * 300,
                exercises = plans.getValue(name).mapIndexed { j, (n, w) ->
                    WorkoutExercise(exerciseName = n, sets = sets(w + prog, listOf(8, 8, 7 + (i % 2)), warmup = j == 0))
                }.toMutableList()
            )
        }
    }

    @Before fun setUp() {
        // Catálogo de demostración en la base local del emulador (aislada): sin Firestore
        val repo = GymRepository.get(ctx)
        runBlocking {
            repo.saveGlobalCatalog(catalog.map { UUID.nameUUIDFromBytes(it.name.toByteArray()).toString() to it })
            ExerciseRepository.loadExercises(repo, Firebase.firestore)
            ExerciseNamesEs.load(ctx)
        }
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            vm = GymFlowViewModel(Firebase.auth, Firebase.firestore, repo, ctx.applicationContext)
        }
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                GymFlowTheme(dark = dark) {
                    Surface(color = BackgroundDark, contentColor = TextPrimary) { Host() }
                }
            }
        }
        rule.waitForIdle()
        // Sin usuario no se carga nada de la base; los datos de demostración se ponen en memoria
        rule.runOnUiThread {
            vm.routines.clear(); vm.routines.addAll(routines())
            vm.workoutHistory.clear(); vm.workoutHistory.addAll(history())
        }
        rule.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun Host() {
        when (route) {
            "home" -> HomeScreen(vm, onCreateRoutine = {}, onRoutineClick = {}, onContinueWorkout = { route = "workout" },
                onDeleteRoutine = {}, onCopyRoutine = {}, onLogout = {}, onExerciseProgress = { route = "exprogress:$it" })
            "routine" -> RoutineDetailScreen(vm.routines.first(), false, false, {}, {}, {}, { }, {}, vm)
            "edit" -> EditRoutineScreen(vm.routines.first(), null, {}, {}, {}, {}, {})
            "select" -> ExerciseSelectionScreen(
                exercises = ExerciseRepository.getCached(), alreadySelected = emptyList(),
                onExercisesSelected = {}, onBack = {}, onInfoClick = { route = "info:$it" }, state = vm.exerciseSearch
            )
            "workout" -> vm.activeWorkout?.let { WorkoutScreen(it, vm.totalSeconds, onFinish = {}, onMinimize = {}, onInfoClick = {}, viewModel = vm) }
            "summary" -> WorkoutSummaryScreen(WorkoutSummaryData("Empuje", 3420, vm.routines.first().exercises, 4280, 12), onDone = {})
            else -> when {
                route.startsWith("info:") -> ExerciseInfoScreen(route.removePrefix("info:"), onBack = { route = "select" })
                route.startsWith("exprogress:") -> ExerciseProgressScreen(route.removePrefix("exprogress:"), vm, onBack = { route = "home" })
            }
        }
    }

    // ─── Utilidades ───────────────────────────────────────────────────────────
    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(400)
        val bmp: Bitmap = ui.takeScreenshot() ?: error("sin captura")
        File(outDir, "${if (dark) "oscuro" else "claro"}_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun toEnd() = repeat(3) {
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performTouchInput { swipeUp() }; rule.waitForIdle()
    }
    private fun go(r: String) { rule.runOnUiThread { route = r }; rule.waitForIdle() }
    private fun tab(label: String) { rule.onAllNodesWithText(label).onFirst().performClick(); rule.waitForIdle() }
    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onFirst() = this[0]
    private fun exists(text: String) = assertTrue("no aparece: $text", rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty())

    // ─── Recorrido con capturas en los dos temas ──────────────────────────────
    @Test fun recorridoEnLosDosTemas() {
        for (d in listOf(false, true)) {
            rule.runOnUiThread { dark = d }
            go("home")
            tab("Inicio"); shot("01_inicio")
            tab("Entrenar"); shot("02_entrenar")
            tab("Progreso"); shot("03_progreso")
            tab("Perfil"); shot("04_perfil")
            rule.onNodeWithText("Calendario").performClick(); shot("05_calendario")
            rule.onNodeWithContentDescription("Volver al perfil").performClick()
            rule.onNodeWithContentDescription("Ajustes").performClick(); shot("06_ajustes")
            rule.onNodeWithText("Cerrar").performClick()
            tab("Inicio")
            go("routine"); shot("07_rutina")
            go("edit"); shot("08_editar_rutina")
            go("select"); shot("09_ejercicios")
            go("summary"); shot("10_resumen_entreno")
            rule.runOnUiThread { vm.startWorkout(vm.routines.first()) }
            go("workout"); shot("11_entreno")
            rule.runOnUiThread { vm.cancelRest(); vm.activeWorkout = null }
        }
        // Estados vacíos (tema claro)
        rule.runOnUiThread { dark = false; vm.workoutHistory.clear(); vm.routines.clear() }
        go("home"); tab("Inicio"); shot("12_inicio_vacio")
        tab("Progreso"); shot("13_progreso_vacio")
        tab("Entrenar"); shot("14_entrenar_vacio")
        // Texto grande (escala 1,5) con datos
        rule.runOnUiThread { vm.routines.addAll(routines()); vm.workoutHistory.addAll(history()); fontScale = 1.5f }
        tab("Inicio"); shot("15_inicio_texto_grande")
        tab("Progreso"); shot("16_progreso_texto_grande")
        rule.runOnUiThread { fontScale = 1f }
    }

    // ─── Registro de series: marcar con un toque y escribir el peso llegan al entreno ──
    @Test fun registroDeSeries() {
        rule.runOnUiThread {
            val r = vm.routines.first()
            r.exercises[0].sets.add(0, ExerciseSet(weight = 40.0, repetitions = 10, setType = SetType.WARMUP))
            vm.startWorkout(r)
        }
        go("workout")
        shot("70_series_antes")
        rule.onAllNodesWithContentDescription("Marcar serie como hecha")[0].performClick()
        rule.waitForIdle()
        val sets = vm.activeWorkout!!.exercises[0].sets
        assertTrue("la marca de hecha debe llegar al entreno", sets[0].isCompleted)
        shot("71_series_marcada")
        rule.runOnUiThread { dark = true }
        shot("72_series_oscuro")
        rule.runOnUiThread { dark = false; vm.cancelRest(); vm.discardWorkout() }
    }

    // ─── Horizontal: se ejecuta con el emulador girado (adb: user_rotation 1) ───
    @Test fun horizontal() {
        val tag = if (ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "horizontal" else "vertical"
        go("home")
        tab("Inicio"); shot("50_${tag}_inicio")
        tab("Progreso"); shot("51_${tag}_progreso")
        rule.runOnUiThread { vm.startWorkout(vm.routines.first()) }
        go("workout"); shot("52_${tag}_entreno")
        rule.runOnUiThread { vm.cancelRest(); vm.activeWorkout = null }
        go("select"); shot("53_${tag}_ejercicios")
    }

    // ─── Cronómetro: empieza, sigue al cerrar el diálogo y al cambiar de ejercicio, avisa ──
    @Test fun cronometroDeDescanso() {
        rule.runOnUiThread { vm.defaultRestSeconds = 15; vm.startWorkout(vm.routines.first()) }
        go("workout")
        rule.onNodeWithContentDescription("Descanso").performClick()
        rule.waitUntil(3_000) { vm.restStatus == RestTimer.Status.RUNNING }
        assertTrue("empieza desde la duración configurada", vm.restSecondsLeft in 14..15)
        shot("20_descanso_dialogo")
        rule.onNodeWithText("Cerrar").performClick()
        rule.runOnUiThread { vm.activeWorkoutExerciseIndex = 1 } // cambio de ejercicio con el descanso en marcha
        Thread.sleep(2_500)
        assertEquals(RestTimer.Status.RUNNING, vm.restStatus)
        assertTrue("sigue contando con el diálogo cerrado", vm.restSecondsLeft in 10..13)
        shot("21_descanso_chip")
        // Reiniciar vuelve a la duración completa
        rule.onAllNodesWithContentDescription("Descanso", substring = true)[0].performClick()
        rule.onNodeWithText("Reiniciar").performClick()
        rule.waitUntil(2_000) { vm.restSecondsLeft >= 14 }
        rule.onNodeWithText("Cerrar").performClick()
        // Llega a cero: termina
        rule.waitUntil(20_000) { vm.restStatus == RestTimer.Status.FINISHED }
        rule.onNodeWithText("¡Listo!").assertExistsCompat()
        shot("22_descanso_terminado")
        // Uno nuevo sustituye al terminado
        rule.runOnUiThread { vm.startRest() }
        rule.waitUntil(2_000) { vm.restStatus == RestTimer.Status.RUNNING && vm.restSecondsLeft >= 14 }
        rule.runOnUiThread { vm.cancelRest() }
        assertEquals(RestTimer.Status.IDLE, vm.restStatus)
    }

    // ─── Buscador: se conserva al ir al detalle y volver; busca en español sin tildes ──
    @Test fun buscadorPersistente() {
        go("select")
        rule.onNode(hasSetTextAction()).performTextInput("jalon")
        rule.waitForIdle()
        rule.onNodeWithText("Jalón al pecho agarre abierto").assertExistsCompat()
        rule.onNodeWithText("Jalón al pecho agarre cerrado").assertExistsCompat()
        assertEquals(0, rule.onAllNodesWithText("Sentadilla con barra").fetchSemanticsNodes().size)
        shot("30_buscador_jalon")
        go("info:Wide-Grip Lat Pulldown"); shot("31_detalle_ejercicio")
        go("select") // volver
        rule.onNode(hasSetTextAction() and hasText("jalon")).assertExistsCompat()
        rule.onNodeWithText("Jalón al pecho agarre abierto").assertExistsCompat()
        shot("32_buscador_al_volver")
        // También por el nombre antiguo en inglés
        rule.runOnUiThread { vm.exerciseSearch.query = "lat pulldown" }
        rule.waitForIdle()
        rule.onNodeWithText("Jalón al pecho agarre abierto").assertExistsCompat()
    }

    // ─── Estadísticas: las cifras de la pantalla son las del cálculo ───────────
    @Test fun estadisticasConDatosDePrueba() {
        go("home"); tab("Progreso")
        rule.onNodeWithText("Todo").performClick()
        val s = TrainingStats.summary(vm.workoutHistory, TrainingStats.Period.ALL, System.currentTimeMillis(), vm.workoutHistory)
        exists("${s.workouts}"); exists(fmtVolume(s.totalVolumeKg)); exists("${s.workSets}")
        assertEquals(36, s.workouts)
        shot("40_progreso_todo")
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToNode(hasText("Sentadilla con barra"))
        shot("40b_progreso_records")
        rule.onNodeWithText("Sentadilla con barra").performClick() // récord → progreso del ejercicio
        rule.waitForIdle()
        assertTrue(route.startsWith("exprogress:"))
        shot("41_progreso_ejercicio")
    }

    // ─── Mapa muscular: cifras = cálculo, colores en los dos temas, vertical/horizontal ──
    @Test fun mapaMuscular() {
        val now = System.currentTimeMillis()
        fun w(daysAgo: Double, vararg ex: Pair<String, Int>) = WorkoutSession(
            id = UUID.randomUUID().toString(), name = "Semana", date = Date(now - (daysAgo * 86_400_000).toLong()), durationSeconds = 3600,
            exercises = ex.map { (n, k) -> WorkoutExercise(exerciseName = n, sets = sets(50.0, List(k) { 8 }, warmup = true)) }.toMutableList()
        )
        val week = listOf(
            w(0.5, "Barbell Bench Press - Medium Grip" to 5, "Incline Dumbbell Press" to 4, "Standing Military Press" to 3, "Triceps Pushdown" to 3),
            w(2.5, "Wide-Grip Lat Pulldown" to 4, "Seated Cable Rows" to 4, "Barbell Curl" to 3, "Side Lateral Raise" to 3),
            w(4.5, "Barbell Squat" to 5, "Romanian Deadlift" to 3, "Standing Calf Raises" to 2, "Plank" to 2),
            w(5.5, "Running, Treadmill" to 1),
            w(10.0, "Leg Press" to 6, "Barbell Hip Thrust" to 4), // fuera de los 7 días
        )
        rule.runOnUiThread { vm.workoutHistory.clear(); vm.workoutHistory.addAll(week) }
        val a = MuscleActivity.compute(week, muscleCatalog(ExerciseRepository.getCached(), emptyList()))
        // Lo que se espera del catálogo: principal de cada ejercicio, calentamientos fuera
        assertEquals(mapOf("chest" to 9, "shoulders" to 6, "triceps" to 3, "back" to 8, "biceps" to 3,
            "quads" to 5, "hamstrings" to 3, "calves" to 2, "abs" to 2), a.sets)
        assertEquals(1, a.cardioSets)
        val tag = if (ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "horizontal" else "vertical"
        for (d in listOf(false, true)) {
            rule.runOnUiThread { dark = d }
            go("home"); tab("Progreso")
            rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction())
                .performScrollToNode(hasText("Series por grupo (músculo principal de cada ejercicio).")) 
            // La descripción del lienzo lleva las mismas cifras que el cálculo
            MuscleActivity.GROUPS.forEach { (id, name) ->
                rule.onNode(androidx.compose.ui.test.hasContentDescription("$name ${a.sets[id] ?: 0} series", substring = true)).assertExistsCompat()
            }
            exists("Cardio: 1 serie (no se dibuja)")
            toEnd(); shot("80_mapa_${tag}")
            if (tag == "vertical") {
                rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction())
                    .performScrollToNode(androidx.compose.ui.test.hasContentDescription("Mapa muscular", substring = true))
                shot("81_mapa_${tag}_figuras")
            }
        }
        // Texto grande en tema claro
        rule.runOnUiThread { dark = false; fontScale = 1.3f }
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction())
            .performScrollToNode(hasText("Series por grupo (músculo principal de cada ejercicio)."))
        toEnd(); shot("82_mapa_${tag}_texto_grande")
        // Sin entrenos en la semana: todo en gris
        rule.runOnUiThread { fontScale = 1f; vm.workoutHistory.clear(); vm.workoutHistory.addAll(week.takeLast(1)) }
        go("home"); tab("Inicio"); tab("Progreso")
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction())
            .performScrollToNode(hasText("Series por grupo (músculo principal de cada ejercicio)."))
        toEnd(); shot("83_mapa_${tag}_sin_actividad")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() { assertExists() }
}

/** Giro de pantalla con la actividad real: el ViewModel (y el entreno en curso) sobreviven. */
@RunWith(AndroidJUnit4::class)
class RotationTest {
    /** La app real (sin sesión solo muestra el login) en los dos temas: barras del sistema incluidas. */
    @Test fun appRealEnLosDosTemas() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.getExternalFilesDir(null), "capturas").apply { mkdirs() }
        for (dark in listOf(false, true)) {
            ThemePrefs.setDark(ctx, dark)
            ActivityScenario.launch(MainActivity::class.java).use {
                Thread.sleep(2_500)
                val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(out, "${if (dark) "oscuro" else "claro"}_60_app_real_login.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        ThemePrefs.setDark(ctx, false)
    }

    @Test fun elEntrenoSobreviveAlGiro() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var before: GymFlowViewModel? = null
            val noFactory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = error("ya debería existir")
            }
            scenario.onActivity { a ->
                before = ViewModelProvider(a, noFactory)[GymFlowViewModel::class.java]
                before!!.startWorkout(WorkoutSession(name = "Prueba giro", exercises = mutableListOf(WorkoutExercise(exerciseName = "Barbell Squat"))))
                before!!.startRest(60)
            }
            scenario.recreate() // como un giro de pantalla
            scenario.onActivity { a ->
                val after = ViewModelProvider(a, noFactory)[GymFlowViewModel::class.java]
                assertSame(before, after)
                assertNotNull(after.activeWorkout)
                assertEquals("Prueba giro", after.activeWorkout!!.name)
                assertEquals(RestTimer.Status.RUNNING, after.restStatus)
                after.cancelRest(); after.activeWorkout = null
            }
        }
    }
}
