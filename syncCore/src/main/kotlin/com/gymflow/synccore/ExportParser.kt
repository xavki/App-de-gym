package com.gymflow.synccore

import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Tipos de agregado que se importan. */
enum class Kind { WORKOUT, ROUTINE, MEASUREMENT, CUSTOM_EXERCISE, ACHIEVEMENT }

enum class ImportError { TOO_LARGE, NOT_JSON, TOO_DEEP, NOT_OBJECT, MISSING_SCHEMA, UNSUPPORTED_SCHEMA, BAD_FIELD, TOO_MANY }

/** Error que invalida el ARCHIVO entero. */
class ImportFileException(val code: ImportError, message: String) : Exception(message)

data class ItemIssue(val kind: Kind, val ref: String, val reason: String)

class ImportAggregate(val kind: Kind, val rootId: String, val label: String, val records: List<Rec>) {
    val root: Rec get() = records.first()
}

data class ParsedExport(
    val schemaVersion: Int,
    val exportedAt: Long?,
    val fileUserId: String?,
    val aggregates: List<ImportAggregate>,
    val invalid: List<ItemIssue>,
    val warnings: List<String>,
    val sourceHash: String,
)

data class ImportLimits(
    val maxBytes: Int = 20_000_000,
    val maxDepth: Int = 12,
    val maxWorkouts: Int = 50_000,
    val maxExercisesPerWorkout: Int = 200,
    val maxSetsPerExercise: Int = 500,
    val maxRoutines: Int = 5_000,
    val maxMeasurements: Int = 50_000,
    val maxCustomExercises: Int = 5_000,
    val maxAchievements: Int = 500,
    val maxString: Int = 20_000,
    /** Un agregado debe caber en UNA petición atómica del servidor (máx. 2000 cambios). */
    val maxRowsPerAggregate: Int = 1_500,
)

private class ItemException(message: String) : Exception(message)

/**
 * Lee y VALIDA el JSON que exporta GymFlow (schemaVersion 1) y lo convierte en registros con los nombres de campo de las entidades Room.
 * No confía en nada del archivo: el userId del archivo es solo informativo (la identidad es la sesión activa, [activeUid]).
 *
 * Campos que el formato actual NO contiene (se derivan, nunca se inventan datos de negocio):
 *  - createdAt/updatedAt de ejercicios y series: se toman del registro padre. createdAt del entreno = startedAt.
 *  - id de las series de rutina: se derivan de forma estable (rutinaEjercicio + posición).
 *  - supersetGroup de los ejercicios de rutina, deletedAt y createdAt originales: no se recuperan.
 */
object ExportParser {
    const val SCHEMA_VERSION = 1
    private val ISO = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")
    private val ACH_KEY = Regex("^[A-Za-z0-9_.-]{1,64}$")

    fun parse(bytes: ByteArray, activeUid: String, limits: ImportLimits = ImportLimits()): ParsedExport {
        if (bytes.size > limits.maxBytes) throw ImportFileException(ImportError.TOO_LARGE, "El archivo supera ${limits.maxBytes / 1_000_000} MB")
        val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        checkDepth(text, limits.maxDepth)
        val root: JsonElement = try { Json.parseToJsonElement(text) } catch (e: SerializationException) {
            throw ImportFileException(ImportError.NOT_JSON, "El archivo no es un JSON válido")
        } catch (e: IllegalArgumentException) {
            throw ImportFileException(ImportError.NOT_JSON, "El archivo no es un JSON válido")
        }
        val obj = root as? JsonObject ?: throw ImportFileException(ImportError.NOT_OBJECT, "El JSON no tiene el formato de una copia de GymFlow")
        val schema = (obj["schemaVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?: throw ImportFileException(ImportError.MISSING_SCHEMA, "Falta schemaVersion: no es una copia de GymFlow")
        if (schema != SCHEMA_VERSION.toLong()) throw ImportFileException(ImportError.UNSUPPORTED_SCHEMA, "Versión de copia $schema no compatible (se admite $SCHEMA_VERSION)")

        val exportedAt = (obj["exportedAt"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { runCatching { iso(it) }.getOrNull() }
        // Unidades: si el archivo declara otras, los números significarían otra cosa. Se rechaza el archivo entero.
        (obj["units"])?.let { u ->
            val uo = u as? JsonObject ?: throw ImportFileException(ImportError.BAD_FIELD, "«units» debe ser un objeto")
            for ((k, expected) in listOf("weight" to "kg", "length" to "cm", "time" to "s")) {
                val got = (uo[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (got != null && got != expected) throw ImportFileException(ImportError.BAD_FIELD, "Unidades no admitidas ($k = $got): solo $expected")
                if (uo[k] != null && uo[k] !is JsonNull && got == null) throw ImportFileException(ImportError.BAD_FIELD, "Unidad «$k» no válida")
            }
        }
        val fileUid = (obj["userId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(200)

        fun arr(name: String, max: Int): List<JsonElement> {
            val v = obj[name] ?: return emptyList()
            val a = v as? JsonArray ?: throw ImportFileException(ImportError.BAD_FIELD, "«$name» debe ser una lista")
            if (a.size > max) throw ImportFileException(ImportError.TOO_MANY, "«$name» tiene ${a.size} elementos (máximo $max)")
            return a
        }
        val workouts = arr("workouts", limits.maxWorkouts)
        val routines = arr("routines", limits.maxRoutines)
        val measures = arr("bodyMeasurements", limits.maxMeasurements)
        val customs = arr("customExercises", limits.maxCustomExercises)
        val achievements = arr("achievements", limits.maxAchievements)

        val aggs = mutableListOf<ImportAggregate>()
        val invalid = mutableListOf<ItemIssue>()
        fun <T> build(kind: Kind, items: List<JsonElement>, f: (JsonObject) -> ImportAggregate) {
            items.forEachIndexed { i, el ->
                try {
                    val agg = f(el as? JsonObject ?: throw ItemException("no es un objeto"))
                    if (agg.records.map { it.key }.toSet().size != agg.records.size) throw ItemException("ID repetido dentro del registro")
                    if (agg.records.size > limits.maxRowsPerAggregate) throw ItemException("demasiadas filas en un solo registro (${agg.records.size})")
                    aggs += agg
                } catch (e: ItemException) {
                    invalid += ItemIssue(kind, "#$i", e.message ?: "inválido")
                }
            }
        }
        build<Unit>(Kind.WORKOUT, workouts) { workout(it, limits, activeUid) }
        build<Unit>(Kind.ROUTINE, routines) { routine(it, limits, activeUid) }
        build<Unit>(Kind.MEASUREMENT, measures) { measurement(it, limits, activeUid) }
        build<Unit>(Kind.CUSTOM_EXERCISE, customs) { custom(it, limits, activeUid) }
        build<Unit>(Kind.ACHIEVEMENT, achievements) { achievement(it, limits, activeUid) }

        // IDs duplicados dentro del archivo: ambiguos, se invalidan TODOS los agregados implicados
        val owners = mutableMapOf<Key, MutableSet<Int>>()
        aggs.forEachIndexed { i, a -> a.records.forEach { owners.getOrPut(it.key) { mutableSetOf() } += i } }
        val dupAggs = mutableSetOf<Int>()
        for ((_, o) in owners) {
            // el mismo id dos veces dentro de un agregado también cuenta: se detecta al construirlo; aquí, entre agregados
            if (o.size > 1) dupAggs += o
        }
        val rootCounts = aggs.groupingBy { it.root.key }.eachCount()
        aggs.forEachIndexed { i, a -> if (rootCounts.getValue(a.root.key) > 1) dupAggs += i }
        dupAggs.sorted().forEach { invalid += ItemIssue(aggs[it].kind, aggs[it].rootId, "ID duplicado dentro del archivo") }
        val valid = aggs.filterIndexed { i, _ -> i !in dupAggs }

        val warnings = mutableListOf<String>()
        if (fileUid == null) warnings += "El archivo no indica a qué cuenta pertenece."
        val routineIds = valid.filter { it.kind == Kind.ROUTINE }.map { it.rootId }.toSet()
        val dangling = valid.filter { it.kind == Kind.WORKOUT }.count { w -> w.root.str("routineId")?.let { it !in routineIds } == true }
        if (dangling > 0) warnings += "$dangling entrenamiento(s) hacen referencia a una rutina que no está en el archivo (se conserva el vínculo)."

        return ParsedExport(schema.toInt(), exportedAt, fileUid, valid, invalid, warnings, Content.sha256(bytes))
    }

    // ───────────── agregados ─────────────

    private fun workout(o: JsonObject, l: ImportLimits, uid: String): ImportAggregate {
        val id = uuid(o, "id")
        val startedAt = time(o, "startedAt")
        val updatedAt = timeOpt(o, "updatedAt") ?: startedAt
        val recs = mutableListOf<Rec>()
        recs += rec(Tables.WORKOUTS, id, buildJsonObject {
            put("id", id); put("userId", uid); putNullable("routineId", uuidOpt(o, "routineId"))
            put("name", string(o, "name", l, max = 200)); put("startedAt", startedAt)
            put("durationSeconds", int(o, "durationSeconds", 0, 604_800))
            putNullableD("bodyweightKg", dblOpt(o, "bodyweightKg", 0.0, 1000.0))
            put("notes", stringOpt(o, "notes", l) ?: "")
            put("createdAt", startedAt); put("updatedAt", updatedAt)
        })
        val exs = list(o, "exercises", l.maxExercisesPerWorkout)
        val exPositions = mutableSetOf<Int>()
        for (e in exs) {
            val eo = e as? JsonObject ?: throw ItemException("ejercicio no es un objeto")
            val eid = uuid(eo, "id")
            val pos = int(eo, "position", 0, 10_000)
            if (!exPositions.add(pos)) throw ItemException("posición de ejercicio repetida ($pos)")
            recs += rec(Tables.WORKOUT_EXERCISES, eid, buildJsonObject {
                put("id", eid); put("workoutId", id); putNullable("exerciseId", uuidOpt(eo, "exerciseId"))
                put("exerciseName", string(eo, "name", l, max = 300)); putNullable("mainGroup", stringOpt(eo, "mainGroup", l))
                put("position", pos); put("notes", stringOpt(eo, "notes", l) ?: ""); putNullable("supersetGroup", stringOpt(eo, "supersetGroup", l))
                put("createdAt", startedAt); put("updatedAt", updatedAt)
            })
            val setPositions = mutableSetOf<Int>()
            for (s in list(eo, "sets", l.maxSetsPerExercise)) {
                val so = s as? JsonObject ?: throw ItemException("serie no es un objeto")
                val sid = uuid(so, "id")
                val sp = int(so, "position", 0, 10_000)
                if (!setPositions.add(sp)) throw ItemException("posición de serie repetida ($sp)")
                recs += rec(Tables.WORKOUT_SETS, sid, buildJsonObject {
                    put("id", sid); put("workoutExerciseId", eid); put("position", sp)
                    put("setType", string(so, "type", l, max = 40))
                    put("weightKg", dbl(so, "weightKg", 0.0, 5000.0)); put("reps", int(so, "reps", 0, 100_000))
                    put("timeSeconds", int(so, "timeSeconds", 0, 604_800)); putNullableD("rpe", dblOpt(so, "rpe", 0.0, 10.0))
                    put("completed", bool(so, "completed")); put("createdAt", startedAt); put("updatedAt", updatedAt)
                })
            }
        }
        return ImportAggregate(Kind.WORKOUT, id, recs.first().str("name") ?: id, recs)
    }

    private fun routine(o: JsonObject, l: ImportLimits, uid: String): ImportAggregate {
        val id = uuid(o, "id")
        val updatedAt = timeOpt(o, "updatedAt") ?: 1L
        val recs = mutableListOf<Rec>()
        recs += rec(Tables.ROUTINES, id, buildJsonObject {
            put("id", id); put("userId", uid); put("name", string(o, "name", l, max = 200)); put("createdAt", updatedAt); put("updatedAt", updatedAt)
        })
        val positions = mutableSetOf<Int>()
        for (e in list(o, "exercises", l.maxExercisesPerWorkout)) {
            val eo = e as? JsonObject ?: throw ItemException("ejercicio no es un objeto")
            val eid = uuid(eo, "id")
            val pos = int(eo, "position", 0, 10_000)
            if (!positions.add(pos)) throw ItemException("posición de ejercicio repetida ($pos)")
            recs += rec(Tables.ROUTINE_EXERCISES, eid, buildJsonObject {
                put("id", eid); put("routineId", id); putNullable("exerciseId", uuidOpt(eo, "exerciseId"))
                put("exerciseName", string(eo, "name", l, max = 300)); putNullable("mainGroup", stringOpt(eo, "mainGroup", l))
                put("position", pos); put("notes", stringOpt(eo, "notes", l) ?: ""); put("supersetGroup", JsonNull)
                put("createdAt", updatedAt); put("updatedAt", updatedAt)
            })
            val sp = mutableSetOf<Int>()
            for (s in list(eo, "sets", l.maxSetsPerExercise)) {
                val so = s as? JsonObject ?: throw ItemException("serie no es un objeto")
                val p = int(so, "position", 0, 10_000)
                if (!sp.add(p)) throw ItemException("posición de serie repetida ($p)")
                val sid = stableUuid("$eid:set:$p")      // el formato actual no exporta el id de las series de rutina
                recs += rec(Tables.ROUTINE_SETS, sid, buildJsonObject {
                    put("id", sid); put("routineExerciseId", eid); put("position", p); put("setType", string(so, "type", l, max = 40))
                    put("targetWeightKg", dbl(so, "targetWeightKg", 0.0, 5000.0)); put("targetReps", int(so, "targetReps", 0, 100_000))
                    put("targetTimeSeconds", int(so, "targetTimeSeconds", 0, 604_800)); put("createdAt", updatedAt); put("updatedAt", updatedAt)
                })
            }
        }
        return ImportAggregate(Kind.ROUTINE, id, recs.first().str("name") ?: id, recs)
    }

    private fun measurement(o: JsonObject, l: ImportLimits, uid: String): ImportAggregate {
        val id = uuid(o, "id")
        val at = time(o, "measuredAt")
        val r = rec(Tables.BODY_MEASUREMENTS, id, buildJsonObject {
            put("id", id); put("userId", uid); put("measuredAt", at)
            for (f in listOf("weightKg", "bodyFatPct", "chestCm", "waistCm", "hipsCm", "bicepCm", "thighCm")) put(f, dbl(o, f, 0.0, 1000.0))
            put("createdAt", at); put("updatedAt", at)
        })
        return ImportAggregate(Kind.MEASUREMENT, id, id, listOf(r))
    }

    private fun custom(o: JsonObject, l: ImportLimits, uid: String): ImportAggregate {
        val id = uuid(o, "id")
        val name = string(o, "name", l, max = 300)
        val r = rec(Tables.EXERCISES, id, buildJsonObject {
            put("id", id); put("userId", uid); put("name", name); put("nameEs", name); put("mainGroup", string(o, "mainGroup", l, max = 100, allowEmpty = true))
            put("musclesUsed", stringOpt(o, "musclesUsed", l) ?: ""); putNullable("equipment", stringOpt(o, "equipment", l))
            put("instructions", ""); put("instructionsEs", JsonNull); put("subCategory", JsonNull); put("difficulty", JsonNull)
            put("imageUrl", JsonNull); put("gifUrl", JsonNull); put("notes", stringOpt(o, "notes", l) ?: ""); put("isCustom", true)
            put("createdAt", 1L); put("updatedAt", 1L)
        })
        return ImportAggregate(Kind.CUSTOM_EXERCISE, id, name, listOf(r))
    }

    private fun achievement(o: JsonObject, l: ImportLimits, uid: String): ImportAggregate {
        val key = string(o, "key", l, max = 64)
        if (!ACH_KEY.matches(key)) throw ItemException("clave de logro no válida")
        val at = time(o, "unlockedAt")
        val id = stableUuid("$uid:achievement:$key")
        val r = rec(Tables.ACHIEVEMENTS, id, buildJsonObject {
            put("id", id); put("userId", uid); put("achievementKey", key); put("unlockedAt", at); put("createdAt", at); put("updatedAt", at)
        })
        return ImportAggregate(Kind.ACHIEVEMENT, id, key, listOf(r))
    }

    // ───────────── validación de campos ─────────────

    private fun rec(table: String, id: String, data: JsonObject) = Rec(table, id, null, data, rev = 0, dirty = true)

    private fun field(o: JsonObject, f: String): JsonElement? = o[f]?.takeIf { it !is JsonNull }
    private fun prim(o: JsonObject, f: String): JsonPrimitive? = field(o, f)?.let { it as? JsonPrimitive ?: throw ItemException("«$f» no es un valor simple") }

    private fun uuid(o: JsonObject, f: String): String {
        val p = prim(o, f)?.takeIf { it.isString } ?: throw ItemException("falta «$f» (UUID)")
        if (!UUID_RE.matches(p.content)) throw ItemException("«$f» no es un UUID válido")
        return p.content.lowercase()
    }
    private fun uuidOpt(o: JsonObject, f: String): String? = if (field(o, f) == null) null else uuid(o, f)

    private fun string(o: JsonObject, f: String, l: ImportLimits, max: Int, allowEmpty: Boolean = false): String {
        val p = prim(o, f)?.takeIf { it.isString } ?: throw ItemException("falta «$f» (texto)")
        if (p.content.length > minOf(max, l.maxString)) throw ItemException("«$f» demasiado largo")
        if (!allowEmpty && p.content.isBlank()) throw ItemException("«$f» está vacío")
        return p.content
    }
    private fun stringOpt(o: JsonObject, f: String, l: ImportLimits): String? {
        val p = prim(o, f) ?: return null
        if (!p.isString) throw ItemException("«$f» debe ser texto")
        if (p.content.length > l.maxString) throw ItemException("«$f» demasiado largo")
        return p.content
    }
    private fun int(o: JsonObject, f: String, min: Long, max: Long): Int {
        val p = prim(o, f)?.takeIf { !it.isString } ?: throw ItemException("falta «$f» (número entero)")
        val v = p.longOrNull ?: throw ItemException("«$f» no es un entero")
        if (v < min || v > max) throw ItemException("«$f» fuera de rango ($v)")
        return v.toInt()
    }
    private fun dbl(o: JsonObject, f: String, min: Double, max: Double): Double = dblOpt(o, f, min, max) ?: throw ItemException("falta «$f» (número)")
    private fun dblOpt(o: JsonObject, f: String, min: Double, max: Double): Double? {
        val p = prim(o, f) ?: return null
        if (p.isString) throw ItemException("«$f» debe ser numérico")
        val v = p.doubleOrNull ?: throw ItemException("«$f» no es un número")
        if (v.isNaN() || v.isInfinite() || v < min || v > max) throw ItemException("«$f» fuera de rango ($v)")
        return v
    }
    private fun bool(o: JsonObject, f: String): Boolean =
        prim(o, f)?.takeIf { !it.isString }?.booleanOrNull ?: throw ItemException("falta «$f» (booleano)")

    private fun iso(s: String): Long {
        if (!ISO.matches(s)) throw ItemException("fecha no válida: $s")
        val ms = try { Instant.parse(s).toEpochMilli() } catch (e: Exception) { throw ItemException("fecha no válida: $s") }
        if (ms < 0L || ms > 4_102_444_800_000L) throw ItemException("fecha fuera de rango: $s")   // 1970..2100
        return ms
    }
    private fun time(o: JsonObject, f: String): Long = iso(prim(o, f)?.takeIf { it.isString }?.content ?: throw ItemException("falta «$f» (fecha)"))
    private fun timeOpt(o: JsonObject, f: String): Long? {
        val p = prim(o, f) ?: return null
        if (!p.isString) throw ItemException("«$f» debe ser una fecha en texto")
        return iso(p.content)
    }

    private fun list(o: JsonObject, f: String, max: Int): List<JsonElement> {
        val v = field(o, f) ?: return emptyList()
        val a = v as? JsonArray ?: throw ItemException("«$f» debe ser una lista")
        if (a.size > max) throw ItemException("«$f» tiene demasiados elementos (${a.size})")
        return a
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(k: String, v: String?) { if (v == null) put(k, JsonNull) else put(k, v) }
    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableD(k: String, v: Double?) { if (v == null) put(k, JsonNull) else put(k, v) }

    /** Profundidad máxima de anidamiento (llaves/corchetes fuera de cadenas) ANTES de analizar, para no agotar la pila. */
    private fun checkDepth(text: String, max: Int) {
        var depth = 0; var inStr = false; var esc = false
        for (c in text) {
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{', '[' -> { depth++; if (depth > max) throw ImportFileException(ImportError.TOO_DEEP, "El JSON está demasiado anidado") }
                '}', ']' -> depth--
            }
        }
    }
}
