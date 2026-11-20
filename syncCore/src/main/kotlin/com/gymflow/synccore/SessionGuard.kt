package com.gymflow.synccore

import java.util.concurrent.atomic.AtomicLong

/** Sesión capturada al iniciar una operación larga: la cuenta Y la generación de sesión de ese momento. */
data class SessionToken(val uid: String, val generation: Long)

/**
 * Contrato de sesión para operaciones largas (R5-02). Comparar solo el UID no detecta A→B→A: si la sesión pasa por B y vuelve a A entre
 * dos comprobaciones, el UID coincide aunque la operación haya vivido un cambio de cuenta. Por eso cada cambio del estado de autenticación
 * llama a [bump] (el AuthStateListener) y la operación solo sigue válida si su [SessionToken] conserva la generación Y el UID activo.
 *
 * Límite honesto: esto reduce la ventana, no la elimina. Entre la última comprobación dentro de la transacción y el commit de Room,
 * FirebaseAuth puede cambiar en otro hilo (Room no coordina estado externo). Lo que queda en esa ventana es el agregado de la cuenta
 * capturada, persistido SIEMPRE con su propio userId (nunca bajo la cuenta nueva) e idempotente en el reintento.
 */
class SessionGuard {
    private val generation = AtomicLong()

    /** Llamar en cada cambio de autenticación (alta, baja, cambio de usuario). */
    fun bump(): Long = generation.incrementAndGet()

    fun capture(activeUid: String?): SessionToken? = activeUid?.let { SessionToken(it, generation.get()) }

    fun isCurrent(token: SessionToken, activeUid: String?): Boolean = token.uid == activeUid && token.generation == generation.get()
}
