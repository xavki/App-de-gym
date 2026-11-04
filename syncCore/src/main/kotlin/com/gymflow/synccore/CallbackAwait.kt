package com.gymflow.synccore

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Destino de los tres desenlaces posibles de una operación asíncrona con callbacks (éxito, error, cancelación de la propia operación). */
interface CallbackSink<in T> {
    fun success(value: T)
    fun failure(error: Throwable)
    /** La OPERACIÓN (no la corrutina llamadora) fue cancelada. */
    fun cancelled()
}

/**
 * Convierte una API de callbacks en una función suspendible con estas garantías (probadas en la JVM):
 *  · éxito → devuelve; error → lanza el error original;
 *  · operación cancelada (antes o durante la espera) → la corrutina llamadora recibe [CancellationException] de inmediato;
 *  · corrutina llamadora cancelada → deja de esperar al instante; un resultado tardío se ignora sin efecto;
 *  · solo el PRIMER desenlace cuenta (carreras entre finalización y cancelación): el resto se descarta.
 */
suspend fun <T> awaitCallbacks(register: (CallbackSink<T>) -> Unit): T = suspendCancellableCoroutine { cont ->
    val done = AtomicBoolean(false)
    register(object : CallbackSink<T> {
        override fun success(value: T) { if (done.compareAndSet(false, true)) cont.resumeWith(Result.success(value)) }
        override fun failure(error: Throwable) { if (done.compareAndSet(false, true)) cont.resumeWith(Result.failure(error)) }
        override fun cancelled() { if (done.compareAndSet(false, true)) cont.cancel(CancellationException("La operación asíncrona fue cancelada")) }
    })
}
