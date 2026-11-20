package com.gymflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Date

class ActiveWorkoutStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun draft() = ActiveWorkoutDraft(
        draftId = "d-1", uid = "u-1",
        session = WorkoutSession(
            id = "r-1", name = "Empuje", date = Date(1_000),
            exercises = mutableListOf(
                WorkoutExercise(
                    id = "e-1", exerciseName = "Bench Press", notes = "subir 2,5 kg",
                    sets = mutableListOf(
                        ExerciseSet(id = "s-1", weight = 40.0, repetitions = 10, setType = SetType.WARMUP, isCompleted = true),
                        ExerciseSet(id = "s-2", weight = 82.5, repetitions = 8, isCompleted = true, rpe = 8.5),
                        ExerciseSet(id = "s-3", weight = 82.5, repetitions = 0),
                    )
                ),
                WorkoutExercise(id = "e-2", exerciseName = "Plank", sets = mutableListOf(ExerciseSet(id = "s-4", timeSeconds = 60))),
            )
        ),
        startedAtMs = 123_456, exerciseIndex = 1, savedAtMs = 200_000,
    )

    @Test fun `guarda y recupera ejercicios, series, pesos, reps, tipos, marcas y notas`() {
        val store = ActiveWorkoutStore(tmp.root)
        store.write("u-1", store.encode(draft()))
        val back = store.read("u-1")!!
        assertEquals(draft(), back)
        val ex = back.session.exercises[0]
        assertEquals("subir 2,5 kg", ex.notes)
        assertEquals(SetType.WARMUP, ex.sets[0].setType)
        assertEquals(82.5, ex.sets[1].weight, 0.0)
        assertEquals(8.5, ex.sets[1].rpe!!, 0.0)
        assertTrue(ex.sets[1].isCompleted)
        assertFalse(ex.sets[2].isCompleted)
        assertEquals(60, back.session.exercises[1].sets[0].timeSeconds)
        assertEquals(1, back.exerciseIndex)
    }

    @Test fun `escritura atomica, sin temporales y cada guardado sustituye al anterior`() {
        val store = ActiveWorkoutStore(tmp.root)
        store.write("u-1", store.encode(draft()))
        store.write("u-1", store.encode(draft().copy(exerciseIndex = 0)))
        assertEquals(0, store.read("u-1")!!.exerciseIndex)
        // Sin temporales: el borrador y la copia de la versión anterior
        val files = tmp.root.list()!!.sorted()
        assertEquals(2, files.size)
        assertTrue(files[0].endsWith(".json") && files[1].endsWith(".json.bak"))
    }

    @Test fun `borrar elimina el borrador`() {
        val store = ActiveWorkoutStore(tmp.root)
        store.write("u-1", store.encode(draft()))
        store.clear("u-1")
        assertFalse(store.exists("u-1"))
        assertNull(store.read("u-1"))
        assertEquals(0, tmp.root.list()!!.size)
    }

    @Test fun `archivo dañado o vacio no rompe la app`() {
        val store = ActiveWorkoutStore(tmp.root)
        File(tmp.root, "active_workout.json").writeText("{ esto no es json")
        assertNull(store.read())
        File(tmp.root, "active_workout.json").writeText("")
        assertNull(store.read())
    }

    @Test fun `instrucciones traducidas solo de ejercicios del catalogo y sin huecos`() {
        val names = ExerciseNamesEs.parse(File("src/main/assets/exercise_names_es.json").readText())
        val instr = ExerciseNamesEs.parse(File("src/main/assets/exercise_instructions_es.json").readText())
        assertTrue(instr.isNotEmpty())
        instr.forEach { (k, v) ->
            assertTrue("no existe en el catálogo: $k", k in names)
            assertTrue("vacía: $k", v.length > 40)
        }
        ExerciseNamesEs.loadInstructionsFrom(File("src/main/assets/exercise_instructions_es.json").readText())
        val traducida = ExerciseDefinition(name = "Plank", instructions = "Get into a prone position")
        assertTrue(traducida.displayInstructions().startsWith("Colócate boca abajo"))
        // Sin instrucciones originales en el catálogo: no se inventa traducción
        val sinTraducir = ExerciseDefinition(name = "Iron Cross", instructions = "Original text")
        assertEquals("Original text", sinTraducir.displayInstructions()) // se conserva el original
    }

    // ── Regresiones de la auditoría de borradores ─────────────────────────────

    @Test fun `cada usuario tiene su borrador y uno no pisa el de otro`() {
        val store = ActiveWorkoutStore(tmp.root)
        store.write("u-1", store.encode(draft()))
        store.write("u-2", store.encode(draft().copy(draftId = "d-2", uid = "u-2")))
        assertEquals("d-1", store.read("u-1")!!.draftId)
        assertEquals("d-2", store.read("u-2")!!.draftId)
        assertNull(store.read("u-3"))
        store.clear("u-2")
        assertEquals("d-1", store.read("u-1")!!.draftId)
    }

    @Test fun `el borrador de la version anterior de la app se recupera tras actualizar`() {
        // Así lo escribía la versión anterior: un único active_workout.json
        File(tmp.root, "active_workout.json").writeText(ActiveWorkoutStore(tmp.root).encode(draft()))
        val store = ActiveWorkoutStore(tmp.root)
        assertEquals(draft(), store.read("u-1"))
        assertNull("no es de otra cuenta", store.read("u-2"))
        // Al volver a guardarlo pasa al archivo del usuario y el antiguo desaparece
        store.write("u-1", store.encode(draft().copy(exerciseIndex = 0)))
        assertFalse(File(tmp.root, "active_workout.json").exists())
        assertEquals(0, store.read("u-1")!!.exerciseIndex)
    }

    @Test fun `archivo principal danado se recupera de la copia y no se borra`() {
        val store = ActiveWorkoutStore(tmp.root)
        store.write("u-1", store.encode(draft().copy(savedAtMs = 1)))
        store.write("u-1", store.encode(draft().copy(savedAtMs = 2)))
        val main = tmp.root.listFiles()!!.first { it.name.endsWith(".json") }
        main.writeText("{\"draftId\":\"d-1\",\"session\":{\"exer") // cortado a medias
        val back = store.read("u-1")
        assertNotNull("no se pierde el entreno", back)
        assertEquals(1L, back!!.savedAtMs) // la versión anterior, completa
        assertTrue("el dañado se aparta para revisarlo", File(main.path + ".corrupt").exists())
    }

    @Test fun `datos incompletos dentro del borrador no tiran todo el entreno`() {
        val json = """{"draftId":"d-1","uid":"u-1","startedAtMs":5,"exerciseIndex":0,"savedAtMs":9,
            "session":{"id":"r-1","name":"Pierna","exercises":[null,
              {"id":"e-1","exerciseName":"Barbell Squat","sets":[null,{"id":"s-1","weight":100.0,"repetitions":5}]},
              {"id":"e-2","exerciseName":"Leg Press"}]}}"""
        File(tmp.root, "active_workout.json").writeText(json)
        val back = ActiveWorkoutStore(tmp.root).read("u-1")!!
        assertEquals(2, back.session.exercises.size)
        assertEquals(1, back.session.exercises[0].sets.size)
        assertEquals(100.0, back.session.exercises[0].sets[0].weight, 0.0)
        assertEquals(SetType.NORMAL, back.session.exercises[0].sets[0].setType)
        assertTrue(back.session.exercises[1].sets.isEmpty())
    }

    @Test fun `finalizado pendiente sobrevive hasta que Room lo confirma`() {
        val store = ActiveWorkoutStore(tmp.root)
        val p = PendingFinish("w-1", "u-1", "r-1", draft().session.copy(durationSeconds = 3600), 1_000)
        store.addPendingFinish(p)
        store.clear("u-1") // quitar el borrador no toca el pendiente
        // Proceso nuevo (Android cerró la app antes de guardar en Room)
        val again = ActiveWorkoutStore(tmp.root)
        assertEquals(listOf(p), again.pendingFinishes("u-1"))
        assertTrue("solo los del usuario", again.pendingFinishes("u-2").isEmpty())
        // Repetir el paso no crea otro pendiente
        again.addPendingFinish(p)
        assertEquals(1, again.pendingFinishes("u-1").size)
        again.removePendingFinish("w-1")
        assertTrue(again.pendingFinishes("u-1").isEmpty())
        assertEquals(0, tmp.root.list()!!.size)
    }

    @Test fun `empezar un entreno nuevo no borra un finalizado pendiente`() {
        val store = ActiveWorkoutStore(tmp.root)
        store.addPendingFinish(PendingFinish("w-1", "u-1", null, draft().session, 1_000))
        store.write("u-1", store.encode(draft().copy(draftId = "d-nuevo")))
        store.clear("u-1")
        assertEquals("w-1", store.pendingFinishes("u-1").single().workoutId)
    }

    @Test fun `borrador antiguo no suma las horas que estuvo olvidado`() {
        val d = draft().copy(startedAtMs = 0, savedAtMs = 30 * 60_000L) // 30 min de entreno
        val now = d.savedAtMs + 3 * 24 * 3_600_000L                      // recuperado 3 días después
        assertEquals(30 * 60_000L, now - ActiveWorkoutStore.resumeStartMs(d, now))
        // Recuperado al poco (Android cerró la app en mitad del entreno): cuenta desde el inicio real
        val soon = d.savedAtMs + 5 * 60_000L
        assertEquals(0L, ActiveWorkoutStore.resumeStartMs(d, soon))
    }
}
