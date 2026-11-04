package com.gymflow.synccore

import java.util.concurrent.atomic.AtomicLong

/**
 * Evita que una recarga ASÍNCRONA publique datos obsoletos: (1) solo la última recarga iniciada puede publicar (las anteriores, que
 * terminen más tarde, se descartan) y (2) la cuenta con la que empezó debe seguir siendo la activa al publicar (nunca se muestran
 * datos de la cuenta A tras cambiar a B o cerrar sesión). [invalidate] anula todas las recargas en vuelo (p. ej. al cerrar sesión).
 */
class ReloadGate {
    private val seq = AtomicLong()
    fun begin(): Long = seq.incrementAndGet()
    fun invalidate() { seq.incrementAndGet() }
    fun canPublish(ticket: Long, startedForUid: String?, activeUid: String?): Boolean =
        startedForUid != null && startedForUid == activeUid && ticket == seq.get()
}
