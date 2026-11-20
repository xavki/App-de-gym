package com.gymflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

class TrainingStatsTest {

    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 28, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun daysAgo(d: Int) = Date(now - d * TrainingStats.DAY)
    private fun set(w: Double, r: Int, type: String = SetType.NORMAL, t: Int = 0) =
        ExerciseSet(weight = w, repetitions = r, timeSeconds = t, setType = type, isCompleted = true)
    private fun workout(daysAgo: Int, vararg ex: Pair<String, List<ExerciseSet>>, seconds: Int = 3600) = WorkoutSession(
        name = "W", date = daysAgo(daysAgo), durationSeconds = seconds,
        exercises = ex.map { (n, s) -> WorkoutExercise(exerciseName = n, sets = s.toMutableList()) }.toMutableList(),
    )

    @Test fun `los calentamientos no cuentan para series ni volumen`() {
        val w = workout(0, "Bench Press" to listOf(set(40.0, 10, SetType.WARMUP), set(80.0, 5), set(80.0, 5)))
        assertEquals(2, TrainingStats.workSets(w))
        assertEquals(800.0, TrainingStats.volume(w), 0.001)
    }

    @Test fun `las series vacias no cuentan y las de tiempo si`() {
        val w = workout(0, "Plank" to listOf(set(0.0, 0, t = 60), set(0.0, 0)))
        assertEquals(1, TrainingStats.workSets(w))
        assertEquals(0.0, TrainingStats.volume(w), 0.001)
    }

    @Test fun `1RM de Brzycki solo entre 1 y 12 repeticiones (igual que el panel)`() {
        assertEquals(100.0, TrainingStats.e1rm(100.0, 1), 0.001)
        assertEquals(100.0 * 36 / 32, TrainingStats.e1rm(100.0, 5), 0.001)
        assertEquals(0.0, TrainingStats.e1rm(100.0, 13), 0.001)
        assertEquals(0.0, TrainingStats.e1rm(0.0, 5), 0.001)
        // Misma fórmula que la calculadora existente de la app
        assertEquals(oneRMBrzycki(87.5, 8), TrainingStats.e1rm(87.5, 8), 0.001)
    }

    @Test fun `filtro de periodo incluye hoy y deja fuera lo anterior`() {
        val h = listOf(workout(0), workout(6), workout(7), workout(40))
        assertEquals(2, TrainingStats.inPeriod(h, TrainingStats.Period.WEEK, now).size)
        assertEquals(3, TrainingStats.inPeriod(h, TrainingStats.Period.MONTH, now).size)
        assertEquals(4, TrainingStats.inPeriod(h, TrainingStats.Period.ALL, now).size)
    }

    @Test fun `resumen con media de duracion y frecuencia semanal`() {
        val h = listOf(workout(1, seconds = 3000), workout(3, seconds = 0), workout(10, seconds = 4200))
        val month = TrainingStats.inPeriod(h, TrainingStats.Period.MONTH, now)
        val s = TrainingStats.summary(month, TrainingStats.Period.MONTH, now, h)
        assertEquals(3, s.workouts)
        assertEquals(7200L, s.totalSeconds)
        assertEquals(3600L, s.avgSeconds) // el entreno sin duración no baja la media
        assertEquals(3 / (30 / 7.0), s.perWeek!!, 0.0001)
    }

    @Test fun `sin datos, ceros y sin frecuencia`() {
        val s = TrainingStats.summary(emptyList(), TrainingStats.Period.ALL, now, emptyList())
        assertEquals(0, s.workouts)
        assertEquals(0.0, s.totalVolumeKg, 0.0)
        assertEquals(0L, s.avgSeconds)
        assertNull(s.perWeek)
        assertTrue(TrainingStats.records(emptyList()).isEmpty())
    }

    @Test fun `buckets semanales suman lo mismo que el total`() {
        val h = listOf(
            workout(1, "Squat" to listOf(set(100.0, 5))),
            workout(9, "Squat" to listOf(set(100.0, 5), set(100.0, 5))),
            workout(20, "Squat" to listOf(set(90.0, 8))),
        )
        val (monthly, b) = TrainingStats.buckets(h, TrainingStats.Period.MONTH, now)
        assertEquals(false, monthly)
        assertEquals(h.sumOf(TrainingStats::volume), b.sumOf { it.volumeKg }, 0.001)
        assertEquals(3, b.sumOf { it.workouts })
        val (yearly, by) = TrainingStats.buckets(h, TrainingStats.Period.YEAR, now)
        assertEquals(true, yearly) // periodos largos: por meses
        assertEquals(3, by.sumOf { it.workouts })
    }

    @Test fun `series por grupo sin contar dos veces`() {
        val h = listOf(workout(0,
            "Bench Press" to listOf(set(80.0, 5), set(80.0, 5), set(40.0, 10, SetType.WARMUP)),
            "Barbell Curl" to listOf(set(30.0, 10)),
            "Mi ejercicio" to listOf(set(10.0, 10)),
        ))
        val groups = mapOf("Bench Press" to "Pecho", "Barbell Curl" to "Bíceps")
        val r = TrainingStats.setsByGroup(h) { groups[it] }
        assertEquals(listOf("Pecho" to 2, "Bíceps" to 1, "Sin grupo" to 1), r)
        assertEquals(h.sumOf(TrainingStats::workSets), r.sumOf { it.second })
    }

    @Test fun `peso maximo real y 1RM estimado son cosas distintas`() {
        val h = listOf(
            workout(10, "Bench Press" to listOf(set(100.0, 1))),
            workout(2, "Bench Press" to listOf(set(90.0, 6), set(95.0, 3))),
        )
        val rec = TrainingStats.records(h).single()
        assertEquals(100.0, rec.topWeight, 0.001)          // lo realmente levantado
        assertEquals(1, rec.topWeightReps)
        assertEquals(TrainingStats.e1rm(90.0, 6), rec.bestE1rm, 0.001) // 90×6 estima más que 100×1
        assertTrue(rec.bestE1rm > rec.topWeight)
        assertEquals(540.0, rec.bestSetVolume, 0.001)
        assertEquals(2, rec.sessions)
    }

    @Test fun `progreso por ejercicio en orden de fecha`() {
        val h = listOf(
            workout(2, "Squat" to listOf(set(110.0, 5))),
            workout(9, "Squat" to listOf(set(100.0, 5), set(100.0, 5))),
            workout(5, "Bench Press" to listOf(set(80.0, 5))),
        )
        val s = TrainingStats.exerciseSessions(h, "Squat")
        assertEquals(listOf(100.0, 110.0), s.map { it.topWeight })
        assertEquals(1000.0, s.first().volumeKg, 0.001)
        assertEquals(10, s.first().reps)
    }
}
