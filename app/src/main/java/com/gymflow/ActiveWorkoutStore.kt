package com.gymflow

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Date
import java.util.UUID

/**
 * Borrador del entreno en curso, en archivos privados de la app (no en Room).
 *
 * Por qué no Room: un entreno a medias no debe sincronizarse ni aparecer en el historial, y una
 * tabla nueva exigiría una migración. Cada usuario tiene su archivo (una cuenta no pisa el
 * borrador de otra). La escritura es atómica (temporal con fsync + renombrado) y la versión
 * anterior se conserva como copia (.bak): si el archivo principal se daña se usa la copia y el
 * dañado se aparta (.corrupt), nunca se borra.
 *
 * Finalizar va en dos pasos para no perder el entreno si Room falla o Android mata el proceso:
 * se escribe un "pendiente de guardar" ([PendingFinish]) y se quita el borrador; después se guarda
 * en Room con el id del borrador y solo entonces se borra el pendiente. Si algo falla, el
 * pendiente se vuelve a guardar al abrir la app (mismo id: no duplica).
 */
data class ActiveWorkoutDraft(
    val draftId: String,
    /** Usuario al que pertenece (null en pruebas sin sesión): no se recupera con otra cuenta. */
    val uid: String?,
    val session: WorkoutSession,
    /** Momento de inicio (reloj de pared): la duración se recalcula al recuperar. */
    val startedAtMs: Long,
    val exerciseIndex: Int,
    val savedAtMs: Long,
)

/** Entreno que el usuario ya finalizó y que aún no consta como guardado en Room. */
data class PendingFinish(
    val workoutId: String,
    val uid: String,
    val routineId: String?,
    /** Con la duración final ya fijada. */
    val session: WorkoutSession,
    val startedAtMs: Long,
)

class ActiveWorkoutStore(private val dir: File) {
    private val gson = Gson()
    private val legacy = File(dir, LEGACY)

    /** Sin usuario se usa el nombre de siempre (el de los borradores de versiones anteriores). */
    private fun draftFile(uid: String?) = if (uid == null) legacy else File(dir, "active_workout_${key(uid)}.json")

    /** Serializa en el hilo que llama (copia coherente del estado) y devuelve el texto a escribir. */
    fun encode(draft: ActiveWorkoutDraft): String = gson.toJson(draft)

    @Synchronized fun write(uid: String?, json: String) {
        writeAtomic(draftFile(uid), json)
        // El borrador de una versión anterior (archivo común) ya está en el archivo del usuario
        if (uid != null && readValid(legacy)?.uid == uid) legacy.delete()
    }

    /** Borrador del usuario; también el de una versión anterior de la app si es suyo. */
    @Synchronized fun read(uid: String? = null): ActiveWorkoutDraft? {
        val own = newest(draftFile(uid))?.takeIf { it.uid == uid }
        if (own != null || uid == null) return own
        return newest(legacy)?.takeIf { it.uid == uid }
    }

    @Synchronized fun clear(uid: String? = null) {
        val files = if (uid != null && newest(legacy)?.uid == uid) listOf(draftFile(uid), legacy) else listOf(draftFile(uid))
        files.forEach(::deleteWithCopies)
    }

    fun exists(uid: String? = null) = read(uid) != null

    // ── Finalizados pendientes de guardar en Room ─────────────────────────────

    @Synchronized fun addPendingFinish(p: PendingFinish) = writeAtomic(pendingFile(p.workoutId), gson.toJson(p))

    @Synchronized fun pendingFinishes(uid: String): List<PendingFinish> =
        dir.listFiles { f -> f.name.startsWith(PENDING_PREFIX) && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> listOf(f, sibling(f, ".bak")).firstNotNullOfOrNull { readPending(it) } }
            .filter { it.uid == uid }

    @Synchronized fun removePendingFinish(workoutId: String) = deleteWithCopies(pendingFile(workoutId))

    // ── Internos ──────────────────────────────────────────────────────────────

    private fun pendingFile(workoutId: String) = File(dir, "$PENDING_PREFIX${key(workoutId)}.json")
    private fun sibling(f: File, suffix: String) = File(f.parentFile, f.name + suffix)
    private fun deleteWithCopies(f: File) { f.delete(); sibling(f, ".tmp").delete(); sibling(f, ".bak").delete() }

    private fun writeAtomic(target: File, json: String) {
        val tmp = sibling(target, ".tmp")
        FileOutputStream(tmp).use { out -> out.write(json.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        // La versión buena anterior pasa a ser la copia; una dañada no sustituye a la copia buena
        val previousOk = target.exists() && (readValid(target) != null || readPending(target) != null)
        if (previousOk) {
            val bak = sibling(target, ".bak")
            if (!target.renameTo(bak)) { bak.delete(); target.renameTo(bak) }
        }
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    private fun readValid(f: File): ActiveWorkoutDraft? = parse(f, ActiveWorkoutDraft::class.java)?.let(::sanitize)
    private fun readPending(f: File): PendingFinish? = parse(f, PendingFinish::class.java)?.let(::sanitize)

    /** La versión más reciente que se pueda leer entre el archivo, su temporal y su copia. */
    private fun newest(f: File): ActiveWorkoutDraft? {
        if (f.exists() && readValid(f) == null) quarantine(f)
        return listOf(f, sibling(f, ".tmp"), sibling(f, ".bak")).mapNotNull(::readValid).maxByOrNull { it.savedAtMs }
    }

    /** Un archivo ilegible no se borra: se aparta para poder revisarlo. */
    private fun quarantine(f: File) {
        val dest = sibling(f, ".corrupt")
        dest.delete()
        f.renameTo(dest)
    }

    private fun <T> parse(f: File, type: Class<T>): T? = try {
        if (!f.exists() || f.length() == 0L) null else gson.fromJson(f.readText(Charsets.UTF_8), type)
    } catch (_: Exception) { null }

    companion object {
        private const val LEGACY = "active_workout.json"
        private const val PENDING_PREFIX = "finished_workout_"

        /** Más tiempo que esto sin cambios: al recuperar, la duración no cuenta el tiempo fuera. */
        const val STALE_MS = 6 * 60 * 60 * 1000L

        private fun key(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)

        /**
         * Gson no pasa por los constructores de Kotlin: un archivo incompleto puede traer nulos en
         * campos no nulos. Se rellenan con valores vacíos en vez de descartar todo el entreno.
         * Sin id, sin sesión o sin lista de ejercicios no hay nada que recuperar.
         */
        @Suppress("SENSELESS_COMPARISON")
        fun sanitize(d: ActiveWorkoutDraft): ActiveWorkoutDraft? {
            if (d.draftId == null || d.session == null || d.session.exercises == null) return null
            return d.copy(session = sanitize(d.session))
        }

        @Suppress("SENSELESS_COMPARISON")
        fun sanitize(p: PendingFinish): PendingFinish? {
            if (p.workoutId.isNullOrBlank() || p.uid == null || p.session == null || p.session.exercises == null) return null
            return p.copy(session = sanitize(p.session))
        }

        @Suppress("USELESS_ELVIS")
        private fun sanitize(s: WorkoutSession): WorkoutSession = s.copy(
            id = s.id ?: UUID.randomUUID().toString(), userId = s.userId ?: "", name = s.name ?: "",
            date = s.date ?: Date(),
            exercises = s.exercises.filterNotNull().map { ex ->
                ex.copy(
                    id = ex.id ?: UUID.randomUUID().toString(), exerciseId = ex.exerciseId ?: "",
                    exerciseName = ex.exerciseName ?: "",
                    sets = (ex.sets ?: mutableListOf()).filterNotNull().map { st ->
                        st.copy(id = st.id ?: UUID.randomUUID().toString(), setType = st.setType ?: SetType.NORMAL)
                    }.toMutableList()
                )
            }.toMutableList()
        )

        /**
         * Inicio a usar al recuperar. Si el borrador lleva horas sin tocarse (se quedó olvidado),
         * la duración se congela en la última actividad en vez de sumar horas o días.
         */
        fun resumeStartMs(d: ActiveWorkoutDraft, nowMs: Long): Long =
            if (nowMs - d.savedAtMs > STALE_MS) nowMs - (d.savedAtMs - d.startedAtMs).coerceAtLeast(0) else d.startedAtMs
    }
}
