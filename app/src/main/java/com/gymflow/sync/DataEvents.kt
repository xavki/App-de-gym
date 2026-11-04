package com.gymflow.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Aviso de que los datos locales (Room) cambiaron por una importación, una resolución de conflicto o una incorporación,
 * para que las pantallas de entrenos, rutinas y estadísticas se recarguen. Los avisos seguidos se funden en uno (sin bucles).
 */
object DataEvents {
    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changed: SharedFlow<Unit> = _changed.asSharedFlow()
    fun notifyChanged() { _changed.tryEmit(Unit) }
}
