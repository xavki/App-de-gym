package com.gymflow.data

import com.google.android.gms.tasks.Task
import com.gymflow.synccore.awaitCallbacks

/**
 * Espera un [Task] de Google Play services sin añadir dependencias (el proyecto no usa kotlinx-coroutines-play-services).
 *
 * Comportamiento de cancelación (la lógica vive en `awaitCallbacks`, probada en la JVM):
 *  · Task completado → devuelve su resultado; Task con excepción → la relanza.
 *  · Task cancelado (`addOnCanceledListener`), ya antes de esperar o mientras se espera → lanza CancellationException de inmediato.
 *  · Corrutina llamadora cancelada → deja de esperar; un resultado tardío del Task se ignora.
 *  · Carreras: solo cuenta el primer desenlace.
 */
internal suspend fun <T> Task<T>.await(): T = awaitCallbacks { sink ->
    addOnSuccessListener { sink.success(it) }
    addOnFailureListener { sink.failure(it) }
    addOnCanceledListener { sink.cancelled() }
}
