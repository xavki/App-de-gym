package com.gymflow.synccore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

/**
 * "Vista de exportación" de un agregado: SOLO los campos que el formato de copia JSON contiene, con la precisión del formato
 * (fechas al segundo). Sirve para comparar lo importado con lo local sin falsos conflictos por datos que el formato no lleva
 * (createdAt, grupo muscular derivado, ids de series de rutina, supersetGroup de rutinas…).
 */
object AggregateView {
    private fun secs(r: Rec, f: String): JsonElement = r.long(f)?.let { JsonPrimitive(Math.floorDiv(it, 1000L)) } ?: JsonNull
    private fun pick(r: Rec, vararg fields: String): Map<String, JsonElement> = fields.associateWith { r.data[it] ?: JsonNull }

    private fun live(rs: List<Rec>) = rs.filter { !it.deleted }

    fun of(kind: Kind, root: Key, idx: RecIndex): JsonObject? {
        val r = idx.byKey[root]?.takeIf { !it.deleted } ?: return null
        return when (kind) {
            Kind.WORKOUT -> JsonObject(pick(r, "id", "routineId", "name", "durationSeconds", "bodyweightKg", "notes") + ("startedAt" to secs(r, "startedAt")) +
                ("exercises" to JsonArray(live(idx.children(root)).sortedWith(compareBy({ it.long("position") }, { it.id })).map { e ->
                    JsonObject(pick(e, "id", "exerciseId", "exerciseName", "position", "notes", "supersetGroup") +
                        ("sets" to JsonArray(live(idx.children(e.key)).sortedWith(compareBy({ it.long("position") }, { it.id })).map { s ->
                            JsonObject(pick(s, "id", "position", "setType", "weightKg", "reps", "timeSeconds", "rpe", "completed"))
                        })))
                })))
            Kind.ROUTINE -> JsonObject(pick(r, "id", "name") +
                ("exercises" to JsonArray(live(idx.children(root)).sortedWith(compareBy({ it.long("position") }, { it.id })).map { e ->
                    JsonObject(pick(e, "id", "exerciseId", "exerciseName", "position", "notes") +
                        ("sets" to JsonArray(live(idx.children(e.key)).sortedWith(compareBy({ it.long("position") }, { it.id })).map { s ->
                            JsonObject(pick(s, "position", "setType", "targetWeightKg", "targetReps", "targetTimeSeconds"))   // sin id: el formato no lo exporta
                        })))
                })))
            Kind.MEASUREMENT -> JsonObject(pick(r, "id", "weightKg", "bodyFatPct", "chestCm", "waistCm", "hipsCm", "bicepCm", "thighCm") + ("measuredAt" to secs(r, "measuredAt")))
            Kind.CUSTOM_EXERCISE -> JsonObject(pick(r, "id", "name", "mainGroup", "musclesUsed", "equipment", "notes"))
            Kind.ACHIEVEMENT -> JsonObject(pick(r, "achievementKey") + ("unlockedAt" to secs(r, "unlockedAt")))
        }
    }

    fun equal(a: JsonObject, b: JsonObject) = Content.canonical(a) == Content.canonical(b)

    /** Rutas de los campos que difieren (máximo [limit]), p. ej. "exercises[0].sets[2].reps". */
    fun diff(a: JsonElement?, b: JsonElement?, limit: Int = 12): List<String> = mutableListOf<String>().also { walk(a, b, "", it, limit) }

    private fun walk(a: JsonElement?, b: JsonElement?, path: String, out: MutableList<String>, limit: Int) {
        if (out.size >= limit) return
        val na = a.takeIf { it !is JsonNull }; val nb = b.takeIf { it !is JsonNull }
        when {
            na is JsonObject && nb is JsonObject -> (na.keys + nb.keys).sorted().forEach { walk(na[it], nb[it], if (path.isEmpty()) it else "$path.$it", out, limit) }
            na is JsonArray && nb is JsonArray -> {
                if (na.size != nb.size) out += "$path (${na.size} ≠ ${nb.size} elementos)"
                for (i in 0 until minOf(na.size, nb.size)) walk(na[i], nb[i], "$path[$i]", out, limit)
            }
            na == null && nb == null -> {}
            na == null || nb == null || Content.canonical(na) != Content.canonical(nb) -> out += path.ifEmpty { "(raíz)" }
        }
    }
}
