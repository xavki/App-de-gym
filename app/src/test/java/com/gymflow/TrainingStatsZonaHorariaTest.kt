package com.gymflow

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * Periodos en días naturales con cambio de hora (Europe/Madrid, 2026):
 * 29 de marzo (día de 23 h, 02:00 → 03:00) y 25 de octubre (día de 25 h, 03:00 → 02:00).
 */
class TrainingStatsZonaHorariaTest {

    private lateinit var previous: TimeZone

    @Before fun madrid() { previous = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid")) }
    @After fun restore() = TimeZone.setDefault(previous)

    private fun at(month: Int, day: Int, h: Int, min: Int = 0) =
        Calendar.getInstance().apply { clear(); set(2026, month, day, h, min, 0) }.timeInMillis
    private fun w(ms: Long) = WorkoutSession(date = Date(ms), exercises = mutableListOf(
        WorkoutExercise(exerciseName = "Barbell Squat", sets = mutableListOf(ExerciseSet(weight = 100.0, repetitions = 5, isCompleted = true)))))
    private fun included(ms: Long, p: TrainingStats.Period, now: Long) = TrainingStats.inPeriod(listOf(w(ms)), p, now).isNotEmpty()

    /** Primer día del periodo: el natural que toca, desde las 00:00, ni una hora antes ni después. */
    private fun checkStart(now: Long, p: TrainingStats.Period) {
        val first = Calendar.getInstance().apply { timeInMillis = TrainingStats.startOfDay(now); add(Calendar.DAY_OF_MONTH, -(p.days!! - 1)) }
        val y = first.get(Calendar.YEAR); val m = first.get(Calendar.MONTH); val d = first.get(Calendar.DAY_OF_MONTH)
        val dayBefore2330 = Calendar.getInstance().apply { clear(); set(y, m, d, 0, 0, 0); add(Calendar.MINUTE, -30) }.timeInMillis
        val first0030 = Calendar.getInstance().apply { clear(); set(y, m, d, 0, 30, 0) }.timeInMillis
        assertTrue("${p.label}: 00:30 del primer día ($d/${m + 1}) entra", included(first0030, p, now))
        assertFalse("${p.label}: 23:30 del día anterior no entra", included(dayBefore2330, p, now))
    }

    @Test fun `semana despues del cambio de octubre empieza a las 00_00 del primer dia`() {
        val now = at(Calendar.OCTOBER, 28, 10)
        assertTrue(included(at(Calendar.OCTOBER, 22, 0, 30), TrainingStats.Period.WEEK, now))
        assertFalse(included(at(Calendar.OCTOBER, 21, 23, 30), TrainingStats.Period.WEEK, now))
    }

    @Test fun `semana despues del cambio de marzo no incluye la noche anterior`() {
        val now = at(Calendar.APRIL, 2, 10)
        assertTrue(included(at(Calendar.MARCH, 27, 0, 30), TrainingStats.Period.WEEK, now))
        assertFalse(included(at(Calendar.MARCH, 26, 23, 30), TrainingStats.Period.WEEK, now))
    }

    @Test fun `semana mes tres meses y anio cruzando los dos cambios de hora`() {
        val nows = listOf(at(Calendar.OCTOBER, 28, 10), at(Calendar.APRIL, 2, 10), at(Calendar.NOVEMBER, 3, 23, 59), at(Calendar.MARCH, 30, 0, 15))
        for (now in nows) for (p in TrainingStats.Period.entries) if (p.days != null) checkStart(now, p)
    }

    @Test fun `el dia de 25 horas termina a las 24_00 y el de 23 horas tambien`() {
        // 25 de octubre: un entreno a las 23:30 es de ese día
        assertTrue(included(at(Calendar.OCTOBER, 25, 23, 30), TrainingStats.Period.WEEK, at(Calendar.OCTOBER, 25, 12)))
        // 29 de marzo: las 00:30 del 30 ya son del día siguiente
        assertFalse(included(at(Calendar.MARCH, 30, 0, 30), TrainingStats.Period.WEEK, at(Calendar.MARCH, 29, 12)))
        assertEquals(at(Calendar.OCTOBER, 26, 0) - 1, TrainingStats.endOfDay(at(Calendar.OCTOBER, 25, 12)))
        assertEquals(at(Calendar.MARCH, 30, 0) - 1, TrainingStats.endOfDay(at(Calendar.MARCH, 29, 12)))
    }

    @Test fun `entrenos por semana cuentan dias naturales`() {
        // Del 20 al 26 de octubre son 7 días naturales (aunque el 25 tenga 25 h): 2 entrenos → 2 por semana
        val h = listOf(w(at(Calendar.OCTOBER, 20, 18)), w(at(Calendar.OCTOBER, 26, 18)))
        assertEquals(2.0, TrainingStats.summary(h, TrainingStats.Period.ALL, at(Calendar.OCTOBER, 26, 20), h).perWeek!!, 1e-9)
    }

    @Test fun `la racha no se corta en el cambio de hora`() {
        for ((month, days) in listOf(Calendar.OCTOBER to (23..27), Calendar.MARCH to (27..31))) {
            val dates = days.map { at(month, it, 19) }
            assertEquals(5, TrainingStats.streak(dates, at(month, days.last, 21)))
        }
        // Sin entreno hoy ni ayer: 0
        assertEquals(0, TrainingStats.streak(listOf(at(Calendar.OCTOBER, 20, 19)), at(Calendar.OCTOBER, 26, 21)))
    }

    @Test fun `records agrupan por dia local y no por dia UTC`() {
        // 27/10 00:30 en Madrid es el 26/10 en UTC, igual que 26/10 12:00: son dos días locales distintos
        val h = listOf(w(at(Calendar.OCTOBER, 27, 0, 30)), w(at(Calendar.OCTOBER, 26, 12, 0)))
        assertEquals(2, computePRs(h).single().progressPoints.size)
    }
}
