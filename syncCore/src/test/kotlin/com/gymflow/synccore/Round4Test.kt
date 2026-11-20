package com.gymflow.synccore

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** R3-03 (cancelación de Task.await, modelada con la misma lógica de callbacks) y M-03 (recargas obsoletas). */
class Round4Test {
    private class Fake<T> { lateinit var sink: CallbackSink<T>; var registered = false }
    private fun <T> Fake<T>.reg(): (CallbackSink<T>) -> Unit = { sink = it; registered = true }

    @Test fun `1 tarea completada devuelve el valor`() = runBlocking {
        assertEquals(7, awaitCallbacks<Int> { it.success(7) })
    }

    @Test fun `2 tarea con excepcion relanza la misma excepcion`() = runBlocking {
        val boom = IllegalStateException("x")
        val e = assertFailsWith<IllegalStateException> { awaitCallbacks<Int> { it.failure(boom) } }
        assertEquals("x", e.message)      // el tipo y el mensaje originales (kotlinx puede recrear la instancia al recuperar la traza)
    }

    @Test fun `3 tarea cancelada antes de esperar cancela de inmediato`() = runBlocking {
        withTimeout(2000) { assertFailsWith<CancellationException> { awaitCallbacks<Int> { it.cancelled() } } }
        Unit
    }

    @Test fun `4 tarea cancelada mientras se espera despierta a la corrutina sin timeout`() = runBlocking {
        val f = Fake<Int>()
        val job = async(start = CoroutineStart.UNDISPATCHED) { runCatching { awaitCallbacks(f.reg()) } }
        assertTrue(f.registered); assertFalse(job.isCompleted)
        f.sink.cancelled()
        val r = withTimeout(2000) { job.await() }
        assertTrue(r.exceptionOrNull() is CancellationException)
    }

    @Test fun `5 cancelar la corrutina llamadora deja de esperar y no necesita al Task`() = runBlocking {
        val f = Fake<Int>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { awaitCallbacks(f.reg()) }
        withTimeout(2000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test fun `7 resultado tardio despues de cancelar se ignora sin excepcion`() = runBlocking {
        val f = Fake<Int>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { awaitCallbacks(f.reg()) }
        job.cancelAndJoin()
        f.sink.success(1); f.sink.failure(RuntimeException("tarde")); f.sink.cancelled()     // nada de esto debe lanzar
        assertTrue(job.isCancelled)
    }

    @Test fun `6 carrera entre finalizacion y cancelacion solo cuenta el primer desenlace`() = runBlocking {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(300) {
                val f = Fake<Int>()
                val job = async(start = CoroutineStart.UNDISPATCHED) { runCatching { awaitCallbacks(f.reg()) } }
                val go = CountDownLatch(1)
                val a = pool.submit { go.await(); f.sink.success(1) }
                val b = pool.submit { go.await(); f.sink.cancelled() }
                go.countDown(); a.get(); b.get()
                val r = withTimeout(2000) { job.await() }
                // o devolvió 1 o se canceló; nunca un estado mixto ni una excepción distinta
                assertTrue(r.getOrNull() == 1 || r.exceptionOrNull() is CancellationException)
            }
        } finally { pool.shutdownNow() }
    }

    // ───────────── M-03: recargas obsoletas ─────────────

    @Test fun `la recarga mas antigua que termina despues no publica`() {
        val g = ReloadGate()
        val old = g.begin(); val new = g.begin()
        assertFalse(g.canPublish(old, "A", "A"))     // terminó tarde pero ya hay una más nueva
        assertTrue(g.canPublish(new, "A", "A"))
    }

    @Test fun `una recarga de la cuenta A no publica tras cambiar a B ni tras cerrar sesion`() {
        val g = ReloadGate(); val t = g.begin()
        assertFalse(g.canPublish(t, "A", "B"))
        assertFalse(g.canPublish(t, "A", null))
        assertFalse(g.canPublish(t, null, null))
    }

    @Test fun `invalidar anula las recargas en vuelo`() {
        val g = ReloadGate(); val t = g.begin(); g.invalidate()
        assertFalse(g.canPublish(t, "A", "A"))
        assertTrue(g.canPublish(g.begin(), "A", "A"))
    }

    @Test fun `escenario completo recarga A lenta y cambio a B`() = runBlocking {
        val g = ReloadGate(); var shown: String? = null; var active: String? = "A"
        suspend fun reload(uid: String, data: String, latency: Long) {
            val t = g.begin(); delay(latency)
            if (g.canPublish(t, uid, active)) shown = data
        }
        val slowA = async { reload("A", "datos de A", 150) }
        delay(20); active = "B"; g.invalidate()
        reload("B", "datos de B", 10)
        slowA.await()
        assertEquals("datos de B", shown)
    }
}
