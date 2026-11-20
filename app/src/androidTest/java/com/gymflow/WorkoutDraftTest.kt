package com.gymflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.gymflow.data.GymRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Pruebas que necesitan que el proceso muera de verdad entre dos invocaciones. No pueden ir en la
 * batería normal (un solo proceso y orden de JUnit no garantizado; otros tests borran el borrador),
 * así que se excluyen con `-e notAnnotation com.gymflow.ProcesoReal` y se lanzan con
 * `app/scripts/prueba-proceso-muerto.sh <serial-del-emulador>`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class ProcesoReal

/**
 * Entreno en curso que sobrevive al cierre del proceso: fase 1, `am force-stop com.gymflow`
 * (proceso realmente terminado) y fase 2, que comprueba que corre en otro proceso.
 */
@RunWith(AndroidJUnit4::class)
class WorkoutDraftTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val draftFile get() = File(ctx.filesDir, "active_workout.json")

    private fun newVm(): GymFlowViewModel {
        var vm: GymFlowViewModel? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = GymFlowViewModel(Firebase.auth, Firebase.firestore, GymRepository.get(ctx), ctx.applicationContext)
        }
        return vm!!
    }

    private val pidFile get() = File(ctx.filesDir, "prueba_proceso_fase1.pid")

    @ProcesoReal @Test fun fase1GuardarEntrenoEnCurso() {
        draftFile.delete()
        pidFile.writeText(android.os.Process.myPid().toString())
        val vm = newVm()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.startWorkout(WorkoutSession(name = "Prueba de recuperación", exercises = mutableListOf(
                WorkoutExercise(exerciseName = "Barbell Squat", notes = "rodillas fuera", sets = mutableListOf(
                    ExerciseSet(weight = 60.0, repetitions = 10, setType = SetType.WARMUP, isCompleted = true),
                    ExerciseSet(weight = 0.0, repetitions = 0),
                )),
                WorkoutExercise(exerciseName = "Leg Press", sets = mutableListOf(ExerciseSet())),
            )))
            // Lo que haría la pantalla al escribir en la serie y cambiar de ejercicio
            vm.activeWorkout!!.exercises[0].sets[1].apply { weight = 102.5; repetitions = 5; isCompleted = true }
            vm.activeWorkoutExerciseIndex = 1
            vm.onAppBackground() // guarda en el acto, como al salir de la app
        }
        assertTrue("debe existir el borrador", draftFile.exists())
    }

    @ProcesoReal @Test fun fase2RecuperarTrasMatarElProceso() {
        assertTrue("falta el borrador de la fase 1", draftFile.exists())
        assertTrue("falta la marca de la fase 1", pidFile.exists())
        val pid1 = pidFile.readText().trim().toInt()
        pidFile.delete()
        assertTrue("la fase 2 debe correr en otro proceso (¿se hizo force-stop?)", pid1 != android.os.Process.myPid())
        val vm = newVm() // proceso nuevo: nada en memoria, solo el disco
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.restoreDraft(null) }
        val w = vm.activeWorkout
        assertNotNull("no se recuperó el entreno", w)
        assertEquals("Prueba de recuperación", w!!.name)
        assertEquals("rodillas fuera", w.exercises[0].notes)
        assertEquals(SetType.WARMUP, w.exercises[0].sets[0].setType)
        assertEquals(102.5, w.exercises[0].sets[1].weight, 0.0)
        assertEquals(5, w.exercises[0].sets[1].repetitions)
        assertTrue(w.exercises[0].sets[1].isCompleted)
        assertEquals(1, vm.activeWorkoutExerciseIndex)
        assertTrue(vm.workoutRecovered)
        Thread.sleep(1_500)
        assertTrue("la duración sigue desde el inicio real", vm.totalSeconds >= 1)
        // Descartar: no queda nada que recuperar
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.discardWorkout() }
        assertNull(vm.activeWorkout)
        assertFalse(draftFile.exists())
        val again = newVm()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { again.restoreDraft(null) }
        assertNull("un entreno descartado no vuelve", again.activeWorkout)
    }

    @Test fun otroUsuarioNoRecuperaElBorrador() {
        draftFile.delete()
        val vm = newVm()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.startWorkout(WorkoutSession(name = "De otro", exercises = mutableListOf(WorkoutExercise(exerciseName = "Plank"))))
        }
        val other = newVm()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { other.restoreDraft("otro-uid") }
        assertNull(other.activeWorkout)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.discardWorkout() }
    }

    /** Finalizar guarda con el id del borrador: repetir el guardado no crea dos entrenos. */
    @Test fun finalizarDosVecesNoDuplica() = runBlocking {
        val repo = GymRepository.get(ctx)
        val uid = "test-${UUID.randomUUID()}"
        val id = UUID.randomUUID().toString()
        val w = WorkoutSession(name = "Sin duplicar", exercises = mutableListOf(
            WorkoutExercise(exerciseName = "Barbell Squat", sets = mutableListOf(ExerciseSet(weight = 100.0, repetitions = 5, isCompleted = true)))
        ))
        repo.saveWorkout(uid, w, routineId = null, workoutId = id)
        repo.saveWorkout(uid, w, routineId = null, workoutId = id)
        assertEquals(1, repo.workouts(uid).size)
    }

    // ── Regresiones de la auditoría de borradores ─────────────────────────────

    private fun workoutRows(): Int = com.gymflow.data.GymDatabase.get(ctx).openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM workouts").use { it.moveToFirst(); it.getInt(0) }

    /** Estas pruebas no deben tocar ninguna cuenta real ni el servidor. */
    @org.junit.Before fun sinSesion() {
        assertNull("hay una sesión iniciada en el emulador: no se ejecuta con una cuenta real", Firebase.auth.currentUser)
    }

    @Test fun borradorNoSeGuardaNiSincronizaComoEntreno() {
        val before = workoutRows()
        val vm = newVm()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.startWorkout(WorkoutSession(name = "Solo borrador", exercises = mutableListOf(
                WorkoutExercise(exerciseName = "Plank", sets = mutableListOf(ExerciseSet(timeSeconds = 60, isCompleted = true))))))
            vm.onAppBackground()
        }
        assertTrue(draftFile.exists())
        // El borrador vive en un archivo: Room (lo único que se sincroniza) no cambia
        assertEquals(before, workoutRows())
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.discardWorkout() }
    }

    /** Android cerró la app tras pulsar Finalizar y antes de guardar en Room: se guarda al abrir, una sola vez. */
    @Test fun finalizadoPendienteSeGuardaUnaVezAlAbrir() = runBlocking {
        val repo = GymRepository.get(ctx)
        val uid = "test-${UUID.randomUUID()}"
        val id = UUID.randomUUID().toString()
        val session = WorkoutSession(name = "Pendiente", durationSeconds = 3_000, exercises = mutableListOf(
            WorkoutExercise(exerciseName = "Barbell Deadlift", sets = mutableListOf(ExerciseSet(weight = 140.0, repetitions = 3, isCompleted = true)))))
        ActiveWorkoutStore(ctx.filesDir).addPendingFinish(PendingFinish(id, uid, null, session, 1_000_000))
        val vm = newVm()
        vm.savePendingFinishes(uid)
        vm.savePendingFinishes(uid) // segunda apertura
        val saved = repo.workouts(uid)
        assertEquals(1, saved.size)
        assertEquals(id, saved[0].id)
        assertEquals(3_000, saved[0].durationSeconds)
        assertEquals(140.0, saved[0].exercises[0].sets[0].weight, 0.0)
        assertTrue(ActiveWorkoutStore(ctx.filesDir).pendingFinishes(uid).isEmpty())
    }

    /** Si ya llegó a Room (la app se cerró justo después de guardar) solo se retira el pendiente. */
    @Test fun finalizadoYaGuardadoNoSeDuplica() = runBlocking {
        val repo = GymRepository.get(ctx)
        val uid = "test-${UUID.randomUUID()}"
        val id = UUID.randomUUID().toString()
        val session = WorkoutSession(name = "Ya guardado", exercises = mutableListOf(
            WorkoutExercise(exerciseName = "Pullups", sets = mutableListOf(ExerciseSet(repetitions = 8, isCompleted = true)))))
        repo.saveWorkout(uid, session, routineId = null, workoutId = id)
        ActiveWorkoutStore(ctx.filesDir).addPendingFinish(PendingFinish(id, uid, null, session, 1_000_000))
        newVm().savePendingFinishes(uid)
        assertEquals(1, repo.workouts(uid).size)
        assertTrue(ActiveWorkoutStore(ctx.filesDir).pendingFinishes(uid).isEmpty())
    }
}
