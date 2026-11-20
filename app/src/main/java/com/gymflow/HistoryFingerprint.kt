package com.gymflow

/**
 * Huella de CONTENIDO del historial (R4-03). Las pantallas cachean cálculos con `remember(clave)`; usar `history.size` como clave no
 * detecta que un entreno existente cambie (peso, fecha, nombre, ejercicios, series) sin cambiar el nº de entrenos. La huella recorre
 * todos los campos relevantes (FNV‑1a de 64 bits sobre los caracteres, no `String.hashCode`, para que "Aa"/"BB" no colisionen) y se
 * recalcula en cada composición que lee la lista, así que también detecta mutaciones directas (`clear()+addAll()`), no solo recargas
 * hechas por el ViewModel. Coste: O(series totales) por composición.
 */
object HistoryFingerprint {
    private const val PRIME = 0x100000001b3L
    private const val BASIS = -0x340d631b7bdddcdbL   // 0xcbf29ce484222325

    private fun mix(h: Long, v: Long): Long {
        var x = h
        for (i in 0 until 8) { x = (x xor ((v ushr (i * 8)) and 0xff)) * PRIME }
        return x
    }
    private fun mix(h: Long, s: String?): Long {
        if (s == null) return mix(h, -1L)
        var x = mix(h, s.length.toLong())
        for (c in s) x = (x xor c.code.toLong()) * PRIME
        return x
    }

    fun of(history: List<WorkoutSession>): Long {
        var h = mix(BASIS, history.size.toLong())
        for (w in history) {
            h = mix(h, w.id); h = mix(h, w.userId); h = mix(h, w.name); h = mix(h, w.date.time); h = mix(h, w.durationSeconds.toLong())
            h = mix(h, w.exercises.size.toLong())
            for (e in w.exercises) {
                h = mix(h, e.id); h = mix(h, e.exerciseId); h = mix(h, e.exerciseName); h = mix(h, e.notes)
                h = mix(h, e.sets.size.toLong())
                for (s in e.sets) {
                    h = mix(h, s.id); h = mix(h, java.lang.Double.doubleToLongBits(s.weight)); h = mix(h, s.repetitions.toLong())
                    h = mix(h, s.timeSeconds.toLong()); h = mix(h, if (s.isCompleted) 1L else 0L); h = mix(h, s.setType)
                    h = mix(h, s.rpe?.let { java.lang.Double.doubleToLongBits(it) } ?: -1L)
                }
            }
        }
        return h
    }
}
