package com.gymflow.data

import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Espera un [Task] de Google Play services sin añadir dependencias (el proyecto no usa kotlinx-coroutines-play-services).
 * Si la corrutina se cancela, el resultado tardío se ignora; los errores del Task se propagan tal cual.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
}
