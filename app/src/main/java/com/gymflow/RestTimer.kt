package com.gymflow

/**
 * Cronómetro de descanso (lógica pura, sin Android: se prueba en el PC con un reloj falso).
 *
 * - El tiempo se calcula contra un reloj monotónico ([now], en la app `SystemClock.elapsedRealtime`,
 *   que sigue contando con la pantalla bloqueada): restar segundos en un bucle se desfasa y se para
 *   si el bucle no se ejecuta (diálogo cerrado, app en segundo plano…).
 * - Cada [start] es un descanso nuevo ([generation]) que sustituye al anterior: nunca hay dos.
 * - [tick] devuelve true UNA sola vez por descanso, cuando llega a cero: es cuando debe sonar el aviso.
 *   Si el aviso se ha delegado a la alarma del sistema (app en segundo plano), no vuelve a sonar al volver.
 */
class RestTimer(private val now: () -> Long) {

    enum class Status { IDLE, RUNNING, PAUSED, FINISHED }

    var status: Status = Status.IDLE
        private set
    /** Duración con la que empezó el descanso actual (para "reiniciar"). */
    var durationMs: Long = 0
        private set
    /** Sube con cada descanso nuevo: identifica el descanso para el aviso y la alarma. */
    var generation: Int = 0
        private set
    /** El aviso de este descanso lo dará la alarma del sistema (la app estaba en segundo plano). */
    var alertDelegated: Boolean = false
        private set

    private var endAtMs: Long = 0          // RUNNING: instante del reloj en que llega a cero
    private var pausedRemainingMs: Long = 0 // PAUSED: lo que quedaba al pausar
    private var alerted = false

    /** Empieza un descanso nuevo de [seconds] segundos, sustituyendo al que hubiera. */
    fun start(seconds: Int) {
        durationMs = seconds.coerceAtLeast(1) * 1000L
        generation++
        endAtMs = now() + durationMs
        status = Status.RUNNING
        alerted = false
        alertDelegated = false
    }

    /** Vuelve a empezar el descanso actual desde su duración (o desde [fallbackSeconds] si no había). */
    fun restart(fallbackSeconds: Int) =
        start(if (durationMs > 0) (durationMs / 1000).toInt() else fallbackSeconds)

    fun pause() {
        if (status != Status.RUNNING) return
        pausedRemainingMs = (endAtMs - now()).coerceAtLeast(0)
        status = Status.PAUSED
    }

    fun resume() {
        if (status != Status.PAUSED) return
        endAtMs = now() + pausedRemainingMs
        status = Status.RUNNING
    }

    fun cancel() {
        status = Status.IDLE
        alertDelegated = false
    }

    /** Suma (o resta, con valor negativo) segundos al descanso en curso o pausado. */
    fun add(seconds: Int) {
        val delta = seconds * 1000L
        when (status) {
            Status.RUNNING -> endAtMs = maxOf(now(), endAtMs + delta)
            Status.PAUSED -> pausedRemainingMs = (pausedRemainingMs + delta).coerceAtLeast(0)
            else -> {}
        }
    }

    fun remainingMs(): Long = when (status) {
        Status.RUNNING -> (endAtMs - now()).coerceAtLeast(0)
        Status.PAUSED -> pausedRemainingMs
        else -> 0
    }

    /** Segundos que se muestran: redondeo hacia arriba (con 0,4 s quedan "00:01", no "00:00"). */
    fun remainingSeconds(): Int = ((remainingMs() + 999) / 1000).toInt()

    /**
     * Actualiza el estado. Devuelve true solo en el momento en que el descanso llega a cero
     * y el aviso debe darlo la app (una vez por descanso; nunca si se delegó a la alarma).
     */
    fun tick(): Boolean {
        if (status != Status.RUNNING || now() < endAtMs) return false
        status = Status.FINISHED
        if (alerted || alertDelegated) return false
        alerted = true
        return true
    }

    /** La app pasa a segundo plano con el descanso en marcha: el aviso lo dará la alarma. */
    fun delegateAlert() {
        if (status == Status.RUNNING) alertDelegated = true
    }

    /** La app vuelve a primer plano antes de que acabe: el aviso vuelve a darlo la app. */
    fun reclaimAlert() {
        if (status == Status.RUNNING) alertDelegated = false
    }
}
