package com.gymflow

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** R4-03: la clave de caché de Inicio/Progreso cambia con el CONTENIDO del historial aunque el nº de entrenos no cambie. */
class HistoryFingerprintTest {
    private fun set(id: String, w: Double = 60.0, r: Int = 8) = ExerciseSet(id = id, weight = w, repetitions = r, isCompleted = true)
    private fun workout(id: String = "w1", name: String = "Push", date: Long = 1_000, uid: String = "A", w: Double = 60.0) =
        WorkoutSession(id = id, userId = uid, name = name, date = Date(date),
            exercises = mutableListOf(WorkoutExercise(id = "e1", exerciseName = "Press banca", sets = mutableListOf(set("s1", w), set("s2", w)))),
            durationSeconds = 3000)
    private fun fp(vararg w: WorkoutSession) = HistoryFingerprint.of(w.toList())

    @Test fun `contenido identico da la misma huella`() = assertEquals(fp(workout(), workout("w2")), fp(workout(), workout("w2")))

    @Test fun `mismo numero de entrenos pero otro peso cambia la huella`() = assertNotEquals(fp(workout()), fp(workout(w = 62.5)))
    @Test fun `mismo numero de entrenos pero otra fecha cambia la huella`() = assertNotEquals(fp(workout()), fp(workout(date = 2_000)))
    @Test fun `mismo numero de entrenos pero otro nombre cambia la huella`() = assertNotEquals(fp(workout()), fp(workout(name = "Pull")))
    @Test fun `otro ejercicio o serie cambia la huella`() {
        val a = workout(); val b = workout().also { it.exercises[0].sets.add(set("s3")) }
        val c = workout().also { it.exercises[0].sets[0].isCompleted = false }
        val d = workout().also { it.exercises[0].sets[0].rpe = 9.0 }
        val e = workout().also { it.exercises.add(WorkoutExercise(id = "e2", exerciseName = "Remo")) }
        listOf(b, c, d, e).forEach { assertNotEquals(fp(a), fp(it)) }
    }

    @Test fun `cambio de cuenta con la misma cardinalidad cambia la huella`() = assertNotEquals(fp(workout(uid = "A")), fp(workout(id = "x1", uid = "B")))

    @Test fun `mutacion en el sitio tambien se detecta porque se recalcula`() {
        val w = workout(); val before = fp(w)
        w.name = "Editado"; assertNotEquals(before, fp(w))
        val mid = fp(w); w.exercises[0].sets[1].weight = 99.0; assertNotEquals(mid, fp(w))
    }

    @Test fun `patron de recarga clear mas add con sustituto del mismo id cambia la huella`() {
        val list = mutableListOf(workout(name = "AUDIT OLD WORKOUT"))
        val before = HistoryFingerprint.of(list)
        list.clear(); list.add(workout(name = "AUDIT NEW WORKOUT"))      // mismo id, mismo tamaño
        assertEquals(1, list.size); assertNotEquals(before, HistoryFingerprint.of(list))
    }

    @Test fun `cadenas con el mismo hashCode no colisionan`() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(fp(workout(name = "Aa")), fp(workout(name = "BB")))
    }

    @Test fun `el orden importa y vacio difiere de uno`() {
        assertNotEquals(fp(workout("a"), workout("b")), fp(workout("b"), workout("a")))
        assertNotEquals(HistoryFingerprint.of(emptyList()), fp(workout()))
    }
}
