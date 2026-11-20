package com.gymflow

import java.util.Calendar

/**
 * Estadísticas de entrenamiento calculadas en el móvil con los datos locales (sin servidor).
 *
 * Mismas reglas que el panel web (panel/src/stats.ts), para que las cifras coincidan:
 * - Serie efectiva: no es de calentamiento y tiene peso, repeticiones o tiempo.
 * - Volumen = peso × repeticiones de las series efectivas.
 * - 1RM estimado: fórmula de Brzycki, solo con series de 1 a 12 repeticiones (fuera de ese
 *   rango la estimación no es fiable). Es una ESTIMACIÓN; el "peso máximo" es lo realmente levantado.
 * - Grupo muscular: el grupo principal del ejercicio en el catálogo (o el del ejercicio
 *   personalizado). Cada serie cuenta una sola vez, en ese grupo; sin grupo → "Sin grupo".
 */
object TrainingStats {

    enum class Period(val label: String, val days: Int?) {
        WEEK("Semana", 7), MONTH("Mes", 30), QUARTER("3 meses", 90), YEAR("Año", 365), ALL("Todo", null)
    }

    fun isWorkSet(s: ExerciseSet) = s.setType != SetType.WARMUP && (s.weight > 0 || s.repetitions > 0 || s.timeSeconds > 0)
    fun setVolume(s: ExerciseSet) = if (isWorkSet(s)) s.weight * s.repetitions else 0.0
    /** Brzycki; 0 si no hay peso o las repeticiones no están entre 1 y 12. */
    fun e1rm(weight: Double, reps: Int) = if (weight > 0 && reps in 1..12) weight * (36.0 / (37.0 - reps)) else 0.0

    fun volume(w: WorkoutSession) = w.exercises.sumOf { ex -> ex.sets.sumOf(::setVolume) }
    fun workSets(w: WorkoutSession) = w.exercises.sumOf { ex -> ex.sets.count(::isWorkSet) }

    /** Entrenos dentro del periodo que termina en [nowMs] (incluido el día de hoy). */
    fun inPeriod(history: List<WorkoutSession>, period: Period, nowMs: Long): List<WorkoutSession> {
        val days = period.days ?: return history
        val from = daysBefore(startOfDay(nowMs), days - 1)
        return history.filter { it.date.time in from..endOfDay(nowMs) }
    }

    data class Summary(
        val workouts: Int,
        val totalVolumeKg: Double,
        val workSets: Int,
        val totalSeconds: Long,
        /** Media de segundos por entreno (solo los que tienen duración registrada). */
        val avgSeconds: Long,
        /** Entrenos por semana en el periodo (null si el periodo no tiene semanas completas que medir). */
        val perWeek: Double?,
    )

    fun summary(workouts: List<WorkoutSession>, period: Period, nowMs: Long, history: List<WorkoutSession> = workouts): Summary {
        val timed = workouts.filter { it.durationSeconds > 0 }
        val spanDays = period.days ?: history.minOfOrNull { it.date.time }?.let { calendarDays(it, nowMs) + 1 }
        return Summary(
            workouts = workouts.size,
            totalVolumeKg = workouts.sumOf(::volume),
            workSets = workouts.sumOf(::workSets),
            totalSeconds = timed.sumOf { it.durationSeconds.toLong() },
            avgSeconds = if (timed.isEmpty()) 0 else timed.sumOf { it.durationSeconds.toLong() } / timed.size,
            perWeek = spanDays?.takeIf { it >= 7 }?.let { workouts.size / (it / 7.0) },
        )
    }

    data class Bucket(val startMs: Long, val workouts: Int, val volumeKg: Double, val sets: Int)

    /** Volumen por semana (lunes) o por mes si el periodo es largo, como el panel. */
    fun buckets(workouts: List<WorkoutSession>, period: Period, nowMs: Long, history: List<WorkoutSession> = workouts): Pair<Boolean, List<Bucket>> {
        val firstMs = when (val d = period.days) {
            null -> history.minOfOrNull { it.date.time } ?: nowMs
            else -> daysBefore(startOfDay(nowMs), d - 1)
        }
        val monthly = (endOfDay(nowMs) - firstMs) / (7 * DAY) > 26
        val starts = mutableListOf<Long>()
        val c = Calendar.getInstance().apply { timeInMillis = if (monthly) startOfMonth(firstMs) else startOfWeek(firstMs) }
        while (c.timeInMillis <= endOfDay(nowMs)) {
            starts += c.timeInMillis
            if (monthly) c.add(Calendar.MONTH, 1) else c.add(Calendar.DAY_OF_MONTH, 7)
        }
        val acc = starts.associateWith { Triple(0, 0.0, 0) }.toMutableMap()
        for (w in workouts) {
            val key = starts.lastOrNull { it <= w.date.time } ?: continue
            val (n, v, s) = acc.getValue(key)
            acc[key] = Triple(n + 1, v + volume(w), s + workSets(w))
        }
        return monthly to starts.map { k -> acc.getValue(k).let { (n, v, s) -> Bucket(k, n, v, s) } }
    }

    /** Series efectivas por grupo muscular, de más a menos. [groupOf] da el grupo de un ejercicio por su nombre. */
    fun setsByGroup(workouts: List<WorkoutSession>, groupOf: (String) -> String?): List<Pair<String, Int>> {
        val m = linkedMapOf<String, Int>()
        for (w in workouts) for (ex in w.exercises) {
            val n = ex.sets.count(::isWorkSet)
            if (n == 0) continue
            val g = groupOf(ex.exerciseName)?.takeIf { it.isNotBlank() } ?: "Sin grupo"
            m[g] = (m[g] ?: 0) + n
        }
        return m.entries.map { it.key to it.value }.sortedByDescending { it.second }
    }

    data class Session(val dateMs: Long, val topWeight: Double, val bestE1rm: Double, val volumeKg: Double, val reps: Int)

    /** Sesiones de un ejercicio (por su nombre guardado), en orden de fecha. */
    fun exerciseSessions(history: List<WorkoutSession>, name: String): List<Session> =
        history.sortedBy { it.date.time }.mapNotNull { w ->
            val sets = w.exercises.filter { it.exerciseName == name }.flatMap { it.sets }.filter(::isWorkSet)
            if (sets.isEmpty()) null else Session(
                dateMs = w.date.time,
                topWeight = sets.maxOf { it.weight },
                bestE1rm = sets.maxOf { e1rm(it.weight, it.repetitions) },
                volumeKg = sets.sumOf(::setVolume),
                reps = sets.sumOf { it.repetitions },
            )
        }

    data class Record(
        val exerciseName: String,
        val topWeight: Double, val topWeightReps: Int, val topWeightDateMs: Long,
        val bestE1rm: Double,
        val bestSetVolume: Double, val bestSetWeight: Double, val bestSetReps: Int,
        val sessions: Int,
    )

    /** Récords de siempre por ejercicio (no dependen del periodo: un récord es un récord). */
    fun records(history: List<WorkoutSession>): List<Record> {
        val byName = linkedMapOf<String, MutableList<Pair<Long, ExerciseSet>>>()
        val sessions = mutableMapOf<String, Int>()
        for (w in history) for (ex in w.exercises) {
            val sets = ex.sets.filter(::isWorkSet)
            if (sets.isEmpty()) continue
            sessions[ex.exerciseName] = (sessions[ex.exerciseName] ?: 0) + 1
            byName.getOrPut(ex.exerciseName) { mutableListOf() } += sets.map { w.date.time to it }
        }
        return byName.mapNotNull { (name, sets) ->
            val weighted = sets.filter { it.second.weight > 0 }
            if (weighted.isEmpty()) return@mapNotNull null
            val top = weighted.maxWith(compareBy<Pair<Long, ExerciseSet>> { it.second.weight }.thenBy { it.second.repetitions })
            val bestVol = weighted.maxBy { it.second.weight * it.second.repetitions }
            Record(
                exerciseName = name,
                topWeight = top.second.weight, topWeightReps = top.second.repetitions, topWeightDateMs = top.first,
                bestE1rm = weighted.maxOf { e1rm(it.second.weight, it.second.repetitions) },
                bestSetVolume = bestVol.second.weight * bestVol.second.repetitions,
                bestSetWeight = bestVol.second.weight, bestSetReps = bestVol.second.repetitions,
                sessions = sessions[name] ?: 0,
            )
        }.sortedByDescending { it.sessions }
    }

    /** Días seguidos con entreno hasta hoy (o hasta ayer si hoy aún no se ha entrenado). */
    fun streak(dates: List<Long>, nowMs: Long): Int {
        if (dates.isEmpty()) return 0
        val sorted = dates.map(::startOfDay).distinct().sortedDescending()
        val today = startOfDay(nowMs)
        val yesterday = daysBefore(today, 1)
        if (sorted.first() != today && sorted.first() != yesterday) return 0
        var streak = 0
        var expected = if (sorted.first() == today) today else yesterday
        for (day in sorted) {
            if (day == expected) { streak++; expected = daysBefore(expected, 1) } else break
        }
        return streak
    }

    // ─── Fechas (hora local) ─────────────────────────────────────────────────
    // Días naturales en la zona del móvil: con cambio de hora un día dura 23 o 25 h, así que
    // se suma y resta con Calendar y nunca en múltiplos de 24 h.
    const val DAY = 86_400_000L
    fun startOfDay(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    fun endOfDay(ms: Long) = daysBefore(startOfDay(ms), -1) - 1
    /** Inicio del día natural que está [n] días antes del que empieza en [dayStartMs] (negativo: después). */
    fun daysBefore(dayStartMs: Long, n: Int) =
        Calendar.getInstance().apply { timeInMillis = dayStartMs; add(Calendar.DAY_OF_MONTH, -n) }.timeInMillis
    /** Días naturales de [fromMs] a [toMs] (0 si es el mismo día). */
    fun calendarDays(fromMs: Long, toMs: Long): Int {
        val a = Calendar.getInstance().apply { timeInMillis = fromMs }
        val b = Calendar.getInstance().apply { timeInMillis = toMs }
        fun epochDay(c: Calendar) = java.time.LocalDate.of(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)).toEpochDay()
        return (epochDay(b) - epochDay(a)).toInt()
    }
    fun startOfWeek(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = startOfDay(ms)
        val dow = (get(Calendar.DAY_OF_WEEK) + 5) % 7 // lunes = 0
        add(Calendar.DAY_OF_MONTH, -dow)
    }.timeInMillis
    fun startOfMonth(ms: Long) = Calendar.getInstance().apply { timeInMillis = startOfDay(ms); set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
}
