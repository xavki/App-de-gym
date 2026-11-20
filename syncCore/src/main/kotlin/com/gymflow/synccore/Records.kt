package com.gymflow.synccore

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Tablas de usuario que se importan y sincronizan (mismos nombres que en Room y en el servidor). */
object Tables {
    const val EXERCISES = "exercises"
    const val ROUTINES = "routines"
    const val ROUTINE_EXERCISES = "routine_exercises"
    const val ROUTINE_SETS = "routine_sets"
    const val WORKOUTS = "workouts"
    const val WORKOUT_EXERCISES = "workout_exercises"
    const val WORKOUT_SETS = "workout_sets"
    const val BODY_MEASUREMENTS = "body_measurements"
    const val SCHEDULES = "schedules"
    const val ACHIEVEMENTS = "achievements"

    /** Padres antes que hijos. */
    val ALL = listOf(EXERCISES, ROUTINES, ROUTINE_EXERCISES, ROUTINE_SETS, WORKOUTS, WORKOUT_EXERCISES, WORKOUT_SETS, BODY_MEASUREMENTS, SCHEDULES, ACHIEVEMENTS)

    /** hijo → (tabla padre, campo del hijo que contiene el id del padre) */
    val PARENT: Map<String, Pair<String, String>> = mapOf(
        ROUTINE_EXERCISES to (ROUTINES to "routineId"),
        ROUTINE_SETS to (ROUTINE_EXERCISES to "routineExerciseId"),
        WORKOUT_EXERCISES to (WORKOUTS to "workoutId"),
        WORKOUT_SETS to (WORKOUT_EXERCISES to "workoutExerciseId"),
    )
}

data class Key(val table: String, val id: String)

/**
 * Una fila sincronizable, independiente de Room: [data] es el JSON de la entidad (mismos nombres de campo).
 * [rev]  = última revisión del SERVIDOR que este dispositivo ha visto de la fila (0 = nunca sincronizada).
 * [dirty] = cambio local pendiente de subir.
 */
data class Rec(
    val table: String,
    val id: String,
    val deletedAt: Long?,
    val data: JsonObject,
    val rev: Long = 0,
    val dirty: Boolean = false,
) {
    val key get() = Key(table, id)
    val deleted get() = deletedAt != null
    fun str(field: String): String? = (data[field] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    fun long(field: String): Long? = (data[field] as? JsonPrimitive)?.longOrNull
}

object Content {
    /** Metadatos que NO forman parte del contenido: pueden diferir entre dispositivos sin que los datos difieran. */
    val META_FIELDS = setOf("createdAt", "updatedAt", "userId", "deletedAt")

    /** Texto canónico: claves ordenadas, números normalizados (62.50 = 62.5), null = ausente. */
    fun canonical(e: JsonElement): String = StringBuilder().also { write(e, it) }.toString()

    private fun write(e: JsonElement, sb: StringBuilder) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonObject -> {
                sb.append('{')
                var first = true
                for ((k, v) in e.entries.filter { it.value !is JsonNull }.sortedBy { it.key }) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(JsonPrimitive(k).toString()).append(':')
                    write(v, sb)
                }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                e.forEachIndexed { i, v -> if (i > 0) sb.append(','); write(v, sb) }
                sb.append(']')
            }
            is JsonPrimitive -> {
                val num = if (!e.isString) runCatching { BigDecimal(e.content) }.getOrNull() else null
                if (num != null) sb.append(if (num.signum() == 0) "0" else num.stripTrailingZeros().toPlainString()) else sb.append(e.toString())
            }
        }
    }

    fun businessData(r: Rec): JsonObject = JsonObject(r.data.filterKeys { it !in META_FIELDS })

    /** Hash del contenido de negocio + estado borrado/vivo. Ignora createdAt/updatedAt/userId/rev/dirty. */
    fun key(r: Rec): String = sha256(r.table + "|" + r.deleted + "|" + canonical(businessData(r)))

    fun same(a: Rec, b: Rec): Boolean = a.table == b.table && a.id == b.id && key(a) == key(b)

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Huella del estado local completo (tabla, id, borrado, contenido): detecta cambios entre vista previa y confirmación. */
    fun fingerprint(recs: Collection<Rec>): String =
        sha256(recs.map { "${it.table}|${it.id}|${key(it)}|${it.rev}|${it.dirty}" }.sorted().joinToString("\n"))
}

/** Mismo algoritmo que GymRepository.stableUuid de la app: ids derivados reproducibles. */
fun stableUuid(raw: String): String =
    runCatching { UUID.fromString(raw).toString() }.getOrNull() ?: UUID.nameUUIDFromBytes(raw.toByteArray()).toString()

val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** Índice por clave con navegación padre/hijo (agregados: rutina→ejercicios→series, entreno→ejercicios→series). */
class RecIndex(recs: Collection<Rec>) {
    val byKey: Map<Key, Rec> = recs.associateBy { it.key }
    private val childrenOf: Map<Key, List<Rec>> = recs.filter { it.table in Tables.PARENT }
        .groupBy { r -> val (pt, f) = Tables.PARENT.getValue(r.table); Key(pt, r.str(f) ?: "") }

    operator fun get(table: String, id: String): Rec? = byKey[Key(table, id)]
    fun children(parent: Key): List<Rec> = childrenOf[parent].orEmpty()

    /** Raíz del agregado de una fila (ella misma si es raíz); null si el padre no se encuentra. */
    fun rootOf(r: Rec): Key? {
        var cur = r
        var guard = 0
        while (cur.table in Tables.PARENT && guard++ < 4) {
            val (pt, f) = Tables.PARENT.getValue(cur.table)
            cur = this[pt, cur.str(f) ?: return null] ?: return null
        }
        return cur.key
    }

    /** Todas las filas del agregado cuya raíz es [root] (raíz incluida). */
    fun aggregate(root: Key): List<Rec> {
        val top = byKey[root] ?: return emptyList()
        val out = mutableListOf(top)
        fun walk(k: Key) { children(k).forEach { out += it; walk(it.key) } }
        walk(root)
        return out
    }
}
