package com.gymflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cronómetro de descanso con un reloj falso: el tiempo avanza solo cuando el test lo dice. */
class RestTimerTest {

    private var clock = 1_000_000L
    private val timer = RestTimer { clock }
    private fun advance(ms: Long) { clock += ms }

    @Test fun `empieza desde la duracion configurada`() {
        timer.start(90)
        assertEquals(RestTimer.Status.RUNNING, timer.status)
        assertEquals(90, timer.remainingSeconds())
        advance(1_000)
        assertEquals(89, timer.remainingSeconds())
    }

    @Test fun `un descanso nuevo sustituye al anterior desde el principio`() {
        timer.start(60)
        advance(45_000)
        val first = timer.generation
        timer.start(60)
        assertEquals(60, timer.remainingSeconds())
        assertTrue(timer.generation > first)
        // El anterior no "dispara" su aviso a los 15 s
        advance(15_000)
        assertFalse(timer.tick())
        assertEquals(45, timer.remainingSeconds())
    }

    @Test fun `un descanso nuevo tras uno terminado vuelve a avisar`() {
        timer.start(10)
        advance(10_000)
        assertTrue(timer.tick())
        timer.start(10)
        assertEquals(10, timer.remainingSeconds())
        advance(10_000)
        assertTrue(timer.tick())
    }

    @Test fun `pausar congela el tiempo y reanudar continua donde estaba`() {
        timer.start(60)
        advance(20_000)
        timer.pause()
        advance(120_000) // pausado mucho tiempo
        assertEquals(RestTimer.Status.PAUSED, timer.status)
        assertEquals(40, timer.remainingSeconds())
        assertFalse(timer.tick())
        timer.resume()
        advance(10_000)
        assertEquals(30, timer.remainingSeconds())
    }

    @Test fun `reiniciar vuelve a la duracion del descanso actual`() {
        timer.start(75)
        advance(50_000)
        timer.restart(fallbackSeconds = 90)
        assertEquals(75, timer.remainingSeconds())
        assertEquals(RestTimer.Status.RUNNING, timer.status)
    }

    @Test fun `reiniciar sin descanso previo usa la duracion por defecto`() {
        timer.restart(fallbackSeconds = 90)
        assertEquals(90, timer.remainingSeconds())
    }

    @Test fun `cancelar para el descanso y no avisa`() {
        timer.start(30)
        timer.cancel()
        advance(60_000)
        assertEquals(RestTimer.Status.IDLE, timer.status)
        assertFalse(timer.tick())
        assertEquals(0, timer.remainingSeconds())
    }

    @Test fun `al llegar a cero avisa una sola vez`() {
        timer.start(5)
        advance(4_999)
        assertFalse(timer.tick())
        assertEquals(1, timer.remainingSeconds()) // con 1 ms quedan "00:01", no "00:00"
        advance(1)
        assertTrue(timer.tick())
        assertEquals(RestTimer.Status.FINISHED, timer.status)
        repeat(50) { advance(200); assertFalse(timer.tick()) }
        assertEquals(0, timer.remainingSeconds())
    }

    @Test fun `el tiempo real manda aunque el refresco se salte vueltas`() {
        // Diálogo cerrado, pantalla bloqueada…: no hay ticks durante 3 minutos
        timer.start(90)
        advance(180_000)
        assertTrue(timer.tick())
        assertEquals(0, timer.remainingSeconds())
    }

    @Test fun `sumar y restar segundos`() {
        timer.start(60)
        timer.add(15)
        assertEquals(75, timer.remainingSeconds())
        timer.add(-30)
        assertEquals(45, timer.remainingSeconds())
        timer.add(-600) // no baja de cero
        assertEquals(0, timer.remainingSeconds())
        assertTrue(timer.tick())
    }

    @Test fun `en segundo plano el aviso lo da la alarma y no se repite al volver`() {
        timer.start(30)
        advance(5_000)
        timer.delegateAlert() // app en segundo plano: la alarma del sistema avisará
        advance(60_000)
        assertFalse(timer.tick()) // la app no vuelve a sonar al volver
        assertEquals(RestTimer.Status.FINISHED, timer.status)
    }

    @Test fun `si se vuelve antes de que acabe, el aviso lo da la app`() {
        timer.start(30)
        timer.delegateAlert()
        advance(10_000)
        timer.reclaimAlert() // de vuelta en primer plano (la alarma se cancela)
        advance(20_000)
        assertTrue(timer.tick())
    }

    @Test fun `la duracion minima es un segundo`() {
        timer.start(0)
        assertEquals(1, timer.remainingSeconds())
    }
}
