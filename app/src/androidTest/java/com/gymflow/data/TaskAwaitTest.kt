package com.gymflow.data

import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** R3-03 con Tasks REALES de Google Play services (TaskCompletionSource / CancellationToken). PENDIENTE DE EJECUCIÓN en emulador desechable. */
@RunWith(AndroidJUnit4::class)
class TaskAwaitTest {
    @Test fun completedTaskReturnsValue() = runBlocking {
        val tcs = TaskCompletionSource<Int>(); tcs.setResult(5)
        assertEquals(5, withTimeout(5000) { tcs.task.await() })
    }

    @Test fun failedTaskRethrows() = runBlocking {
        val tcs = TaskCompletionSource<Int>(); tcs.setException(IllegalStateException("boom"))
        val r = runCatching { withTimeout(5000) { tcs.task.await() } }
        assertEquals("boom", r.exceptionOrNull()?.message)
    }

    @Test fun taskCancelledBeforeAwaitCancelsPromptly() = runBlocking {
        val cts = CancellationTokenSource(); val tcs = TaskCompletionSource<Int>(cts.token)
        cts.cancel()
        while (!tcs.task.isCanceled) Thread.sleep(10)
        val r = runCatching { withTimeout(5000) { tcs.task.await() } }
        assertTrue("debe cancelar, no agotar el timeout: ${r.exceptionOrNull()}", r.exceptionOrNull() is CancellationException && r.exceptionOrNull() !is kotlinx.coroutines.TimeoutCancellationException)
    }

    @Test fun taskCancelledWhileSuspendedWakesTheAwaiter() = runBlocking {
        val cts = CancellationTokenSource(); val tcs = TaskCompletionSource<Int>(cts.token)
        val job = async(start = CoroutineStart.UNDISPATCHED) { runCatching { withTimeout(5000) { tcs.task.await() } } }
        cts.cancel()
        val r = job.await()
        assertTrue(r.exceptionOrNull() is CancellationException && r.exceptionOrNull() !is kotlinx.coroutines.TimeoutCancellationException)
    }

    @Test fun callerCancellationStopsWaitingAndLateResultIsIgnored() = runBlocking {
        val tcs = TaskCompletionSource<Int>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { tcs.task.await() }
        withTimeout(5000) { job.cancelAndJoin() }
        tcs.trySetResult(1)                                   // resultado tardío: sin efecto ni excepción
        assertTrue(job.isCancelled)
    }
}
