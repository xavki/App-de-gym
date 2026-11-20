package com.gymflow.synccore

/**
 * Publica recargas asíncronas en una lista de la UI y mantiene una REVISIÓN de contenido que las pantallas usan como clave de caché
 * (`remember(revision)`), en lugar de `list.size`, que no cambia cuando se modifica un elemento existente (R4-03).
 *
 *  · [publish]: solo la última recarga de la cuenta activa publica (ver [ReloadGate]). Si el contenido recargado es IGUAL al actual no se
 *    toca la lista ni la revisión (sin recomposiciones ni recálculos redundantes); si difiere —aunque el nº de elementos sea el mismo—
 *    se reemplaza y la revisión sube.
 *  · [clear]: invalida las recargas en vuelo, vacía la lista y sube la revisión (cierre de sesión / cambio de cuenta).
 * Hay que llamarla siempre desde el mismo hilo que muta la lista (el principal en la app).
 */
class ListPublisher<T>(private val gate: ReloadGate = ReloadGate()) {
    var revision: Long = 0
        private set

    fun begin(): Long = gate.begin()
    fun invalidate() = gate.invalidate()

    /** @return true si la recarga era vigente (haya cambiado o no el contenido). */
    fun publish(target: MutableList<T>, ticket: Long, startedForUid: String?, activeUid: String?, loaded: List<T>): Boolean {
        if (!gate.canPublish(ticket, startedForUid, activeUid)) return false
        if (target.toList() != loaded) {      // toList(): algunas listas de UI (SnapshotStateList) no definen igualdad por contenido
            target.clear(); target.addAll(loaded)
            revision++
        }
        return true
    }

    fun clear(target: MutableList<T>) {
        gate.invalidate()
        if (target.isNotEmpty()) target.clear()
        revision++
    }
}
