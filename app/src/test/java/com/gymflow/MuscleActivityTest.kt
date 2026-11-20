package com.gymflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

class MuscleActivityTest {

    private val now = Date(1_793_000_000_000L)
    private fun daysAgo(d: Double) = Date(now.time - (d * 86_400_000L).toLong())
    private fun sets(n: Int, warmups: Int = 0) =
        (List(warmups) { ExerciseSet(weight = 20.0, repetitions = 10, setType = SetType.WARMUP, isCompleted = true) } +
            List(n) { ExerciseSet(weight = 50.0, repetitions = 8, isCompleted = true) }).toMutableList()
    private fun workout(d: Double, vararg ex: Pair<String, MutableList<ExerciseSet>>) = WorkoutSession(
        date = daysAgo(d), exercises = ex.map { (n, s) -> WorkoutExercise(exerciseName = n, sets = s) }.toMutableList()
    )

    // Valores tal como están en el catálogo real (musclesUsed: principal primero)
    private val catalog = mapOf(
        "Leg Press" to ("Piernas" to "Cuádriceps, Gemelos, Glúteos, Isquiotibiales"),
        "Barbell Curl" to ("Bíceps" to "Bíceps, Antebrazo"),
        "Side Lateral Raise" to ("Hombros" to "Hombros"),
        "Lying Leg Curls" to ("Piernas" to "Isquiotibiales"),
        "Leg Extensions" to ("Piernas" to "Cuádriceps"),
        "Standing Military Press" to ("Hombros" to "Hombros, Tríceps"),
        "Press militar con mancuernas" to ("Hombros" to "Hombros, tríceps"),
        "Remo con mancuerna" to ("Espalda" to "Dorsal, bíceps"),
        "Elevaciones posteriores" to ("Hombros" to "Deltoides posterior"),
        "Peso muerto rumano" to ("Piernas" to "Isquios"),
        "Plank" to ("Core" to "Abdominales"),
        "Zancadas" to ("Piernas" to "Piernas"),
        "Bicycling" to ("Cardio" to "Cuádriceps, Gemelos"),
    )

    @Test fun `cada ejercicio suma en su musculo principal y solo en uno`() {
        val a = MuscleActivity.compute(listOf(workout(1.0,
            "Leg Press" to sets(4), "Barbell Curl" to sets(3), "Side Lateral Raise" to sets(2),
            "Lying Leg Curls" to sets(3), "Leg Extensions" to sets(2), "Standing Military Press" to sets(3),
        )), catalog, now)
        assertEquals(mapOf("quads" to 6, "biceps" to 3, "shoulders" to 5, "hamstrings" to 3), a.sets)
        // Errores del cálculo anterior que ya no ocurren
        assertNull(a.sets["chest"])      // "Leg Press" / "Military Press" contenían "press"
        assertNull(a.sets["calves"])     // "Lateral Raise" contenía "raise"
        assertNull(a.sets["triceps"])    // "Leg Extensions" contenía "extension"
    }

    @Test fun `nombres en espanol del catalogo`() {
        val a = MuscleActivity.compute(listOf(workout(2.0,
            "Press militar con mancuernas" to sets(3), "Remo con mancuerna" to sets(4),
            "Elevaciones posteriores" to sets(2), "Peso muerto rumano" to sets(3), "Plank" to sets(2),
        )), catalog, now)
        assertEquals(mapOf("shoulders" to 5, "back" to 4, "hamstrings" to 3, "abs" to 2), a.sets)
    }

    @Test fun `intensidad relativa al grupo con mas series`() {
        val a = MuscleActivity.compute(listOf(workout(1.0, "Leg Press" to sets(8), "Barbell Curl" to sets(2))), catalog, now)
        assertEquals(1f, a.intensity.getValue("quads"), 0.0001f)
        assertEquals(0.25f, a.intensity.getValue("biceps"), 0.0001f)
    }

    @Test fun `periodo de 7 dias y calentamientos fuera`() {
        fun at(day: Int) = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, day, 12, 0, 0) }.time
        val ahora = at(28)
        val a = MuscleActivity.compute(listOf(
            WorkoutSession(date = at(22), exercises = mutableListOf(WorkoutExercise(exerciseName = "Barbell Curl", sets = sets(3, warmups = 2)))), // 7.º día
            WorkoutSession(date = at(21), exercises = mutableListOf(WorkoutExercise(exerciseName = "Leg Press", sets = sets(5)))),                // 8.º día
        ), catalog, ahora)
        assertEquals(mapOf("biceps" to 3), a.sets)
    }

    @Test fun `cardio aparte y fuera de la escala`() {
        val a = MuscleActivity.compute(listOf(workout(1.0, "Bicycling" to sets(10), "Barbell Curl" to sets(2))), catalog, now)
        assertEquals(10, a.cardioSets)
        assertNull(a.sets["cardio"]); assertNull(a.intensity["cardio"])
        assertEquals(1f, a.intensity.getValue("biceps"), 0.0001f)
    }

    @Test fun `sin grupo identificable no se asigna a ningun musculo`() {
        val a = MuscleActivity.compute(listOf(workout(1.0, "Zancadas" to sets(3))), catalog, now)
        assertTrue(a.sets.isEmpty()); assertEquals(3, a.unassignedSets)
    }

    @Test fun `fuera del catalogo gana la palabra clave mas larga`() {
        assertEquals("quads", MuscleActivity.groupFromName("Smith Leg Press"))
        assertEquals("hamstrings", MuscleActivity.groupFromName("Machine Leg Curl"))
        assertEquals("shoulders", MuscleActivity.groupFromName("Cable Lateral Raise"))
        assertEquals("chest", MuscleActivity.groupFromName("Incline Press"))
        assertNull(MuscleActivity.groupFromName("Ejercicio inventado"))
    }

    @Test fun `misma semana que la tarjeta de estadisticas en el borde de los 7 dias`() {
        // Ahora: 28/10 a las 10:00. Entreno del 21/10 a las 18:00: está a menos de 168 h, pero es el
        // octavo día natural, así que "Semana" en Progreso no lo cuenta y el mapa tampoco debe hacerlo.
        val ahora = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 28, 10, 0, 0); set(Calendar.MILLISECOND, 0) }.time
        val borde = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 21, 18, 0, 0) }.time
        val hoy = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 22, 12, 0, 0) }.time
        val h = listOf(
            WorkoutSession(date = borde, exercises = mutableListOf(WorkoutExercise(exerciseName = "Leg Press", sets = sets(4)))),
            WorkoutSession(date = hoy, exercises = mutableListOf(WorkoutExercise(exerciseName = "Barbell Curl", sets = sets(2)))),
        )
        val week = TrainingStats.inPeriod(h, TrainingStats.Period.WEEK, ahora.time)
        assertEquals(1, week.size)
        assertEquals(mapOf("biceps" to 2), MuscleActivity.compute(h, catalog, ahora).sets)
    }

    @Test fun `el total del mapa coincide con las series por grupo de la semana`() {
        val h = listOf(
            workout(0.5, "Leg Press" to sets(4, warmups = 1), "Plank" to sets(2), "Zancadas" to sets(3)),
            workout(3.0, "Bicycling" to sets(1), "Remo con mancuerna" to sets(4), "Ejercicio sin catalogo" to sets(2)),
            workout(9.0, "Barbell Curl" to sets(5)),
        )
        val a = MuscleActivity.compute(h, catalog, now)
        val week = TrainingStats.inPeriod(h, TrainingStats.Period.WEEK, now.time)
        val card = TrainingStats.setsByGroup(week) { catalog[it]?.first }.sumOf { it.second }
        assertEquals(card, a.sets.values.sum() + a.cardioSets + a.unassignedSets)
        assertEquals(5, a.unassignedSets) // "Zancadas" (solo "Piernas") y el que no está en el catálogo
    }

    @Test fun `todo grupo dibujado existe en el atlas en alguna vista`() {
        val drawn = (MuscleAtlas.front + MuscleAtlas.back).mapNotNull { it.group }.toSet()
        assertEquals(MuscleActivity.GROUPS.map { it.first }.toSet(), drawn)
        assertTrue("cardio no es una región", "cardio" !in drawn)
    }
}
