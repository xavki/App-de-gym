package com.gymflow.synccore

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val UID = "uid-synthetic-0001"
const val BASE_MS = 1_760_000_000_000L          // fecha sintética fija (múltiplo de 1000)

fun uuid(tag: String): String = UUID.nameUUIDFromBytes(tag.toByteArray()).toString()

/** Entreno sintético con la misma forma que guarda GymRepository.saveWorkout (ids de hijos derivados del id del entreno). */
fun workoutRecs(i: Int, uid: String = UID, exCount: Int = 2, setCount: Int = 3, startedAt: Long = BASE_MS + i * 86_400_000L, name: String = "Entreno $i", weight: Double = 60.0 + i): List<Rec> {
    val wid = uuid("w$i")
    val out = mutableListOf<Rec>()
    out += Rec(Tables.WORKOUTS, wid, null, buildJsonObject {
        put("id", wid); put("userId", uid); put("routineId", JsonNull); put("name", name); put("startedAt", startedAt)
        put("durationSeconds", 3000 + i); put("bodyweightKg", 80.5); put("notes", "nota $i"); put("createdAt", startedAt); put("updatedAt", startedAt)
    }, 0, true)
    for (e in 0 until exCount) {
        val eid = stableUuid("$wid:ex:$e")
        out += Rec(Tables.WORKOUT_EXERCISES, eid, null, buildJsonObject {
            put("id", eid); put("workoutId", wid); put("exerciseId", JsonNull); put("exerciseName", "Ejercicio $e"); put("mainGroup", if (e == 0) JsonPrimitive("Pecho") else JsonNull)
            put("position", e); put("notes", ""); put("supersetGroup", JsonNull); put("createdAt", startedAt); put("updatedAt", startedAt)
        }, 0, true)
        for (s in 0 until setCount) {
            val sid = stableUuid("$wid:set:$e:$s")
            out += Rec(Tables.WORKOUT_SETS, sid, null, buildJsonObject {
                put("id", sid); put("workoutExerciseId", eid); put("position", s); put("setType", "NORMAL"); put("weightKg", weight + s)
                put("reps", 8 + s); put("timeSeconds", 0); put("rpe", JsonNull); put("completed", true); put("createdAt", startedAt); put("updatedAt", startedAt)
            }, 0, true)
        }
    }
    return out
}

/** Rutina sintética; los ids de las series son "aleatorios" (distintos en cada dispositivo si [setTag] cambia), como en la app real. */
fun routineRecs(i: Int, name: String = "Rutina $i", uid: String = UID, setTag: String = "o", exCount: Int = 2, setCount: Int = 2, updatedAt: Long = BASE_MS): List<Rec> {
    val rid = uuid("r$i")
    val out = mutableListOf<Rec>()
    out += Rec(Tables.ROUTINES, rid, null, buildJsonObject { put("id", rid); put("userId", uid); put("name", name); put("createdAt", updatedAt); put("updatedAt", updatedAt) }, 0, true)
    for (e in 0 until exCount) {
        val eid = uuid("re$i-$e")
        out += Rec(Tables.ROUTINE_EXERCISES, eid, null, buildJsonObject {
            put("id", eid); put("routineId", rid); put("exerciseId", JsonNull); put("exerciseName", "Ej $e"); put("mainGroup", "Espalda"); put("position", e)
            put("notes", ""); put("supersetGroup", JsonNull); put("createdAt", updatedAt); put("updatedAt", updatedAt)
        }, 0, true)
        for (s in 0 until setCount) {
            val sid = uuid("rs$setTag$i-$e-$s")
            out += Rec(Tables.ROUTINE_SETS, sid, null, buildJsonObject {
                put("id", sid); put("routineExerciseId", eid); put("position", s); put("setType", "NORMAL"); put("targetWeightKg", 40.0 + s)
                put("targetReps", 10); put("targetTimeSeconds", 0); put("createdAt", updatedAt); put("updatedAt", updatedAt)
            }, 0, true)
        }
    }
    return out
}

fun achievementRec(key: String, uid: String = UID, at: Long = BASE_MS): Rec {
    val id = stableUuid("$uid:achievement:$key")
    return Rec(Tables.ACHIEVEMENTS, id, null, buildJsonObject { put("id", id); put("userId", uid); put("achievementKey", key); put("unlockedAt", at); put("createdAt", at); put("updatedAt", at) }, 0, true)
}

fun measurementRec(i: Int, uid: String = UID): Rec {
    val id = uuid("m$i")
    return Rec(Tables.BODY_MEASUREMENTS, id, null, buildJsonObject {
        put("id", id); put("userId", uid); put("measuredAt", BASE_MS + i * 1000L); put("weightKg", 80.0 + i); put("bodyFatPct", 15.0); put("chestCm", 100.0)
        put("waistCm", 85.0); put("hipsCm", 95.0); put("bicepCm", 35.0); put("thighCm", 55.0); put("createdAt", BASE_MS); put("updatedAt", BASE_MS)
    }, 0, true)
}

fun customExerciseRec(i: Int, name: String = "Mi ejercicio $i", uid: String = UID): Rec {
    val id = uuid("x$i")
    return Rec(Tables.EXERCISES, id, null, buildJsonObject {
        put("id", id); put("userId", uid); put("name", name); put("nameEs", name); put("mainGroup", "Piernas"); put("musclesUsed", "Cuádriceps"); put("equipment", "Barra")
        put("instructions", ""); put("instructionsEs", JsonNull); put("subCategory", JsonNull); put("difficulty", JsonNull); put("imageUrl", JsonNull); put("gifUrl", JsonNull)
        put("notes", "n"); put("isCustom", true); put("createdAt", BASE_MS); put("updatedAt", BASE_MS)
    }, 0, true)
}

fun Rec.with(field: String, v: JsonElement) = copy(data = JsonObject(data + (field to v)))
fun Rec.withStr(field: String, v: String) = with(field, JsonPrimitive(v))
fun Rec.withNum(field: String, v: Number) = with(field, JsonPrimitive(v))
fun Rec.softDeleted(at: Long = BASE_MS + 5000) = copy(deletedAt = at, dirty = true).with("deletedAt", JsonPrimitive(at)).with("updatedAt", JsonPrimitive(at))
fun Rec.edited(field: String, v: JsonElement, at: Long) = with(field, v).with("updatedAt", JsonPrimitive(at)).copy(dirty = true)

/** Exportación JSON con el MISMO formato que DataExporter.exportJson (schemaVersion 1) a partir de filas locales. */
fun exportJson(recs: List<Rec>, uid: String = UID, schema: Int = 1, exportedAt: Long = BASE_MS): String {
    val idx = RecIndex(recs)
    fun iso(ms: Long) = Instant.ofEpochMilli(Math.floorDiv(ms, 1000L) * 1000L).toString()
    fun live(l: List<Rec>) = l.filter { !it.deleted }.sortedWith(compareBy({ it.long("position") }, { it.id }))
    fun el(r: Rec, f: String): JsonElement = r.data[f] ?: JsonNull
    val workouts = recs.filter { it.table == Tables.WORKOUTS && !it.deleted }.sortedBy { it.long("startedAt") }.map { w ->
        buildJsonObject {
            put("id", w.id); put("routineId", el(w, "routineId")); put("name", el(w, "name")); put("startedAt", iso(w.long("startedAt")!!))
            put("durationSeconds", el(w, "durationSeconds")); put("bodyweightKg", el(w, "bodyweightKg")); put("notes", el(w, "notes")); put("updatedAt", iso(w.long("updatedAt")!!))
            put("exercises", JsonArray(live(idx.children(w.key)).map { e ->
                buildJsonObject {
                    put("id", e.id); put("exerciseId", el(e, "exerciseId")); put("name", el(e, "exerciseName")); put("mainGroup", el(e, "mainGroup"))
                    put("position", el(e, "position")); put("notes", el(e, "notes")); put("supersetGroup", el(e, "supersetGroup"))
                    put("sets", JsonArray(live(idx.children(e.key)).map { s ->
                        buildJsonObject {
                            put("id", s.id); put("position", el(s, "position")); put("type", el(s, "setType")); put("weightKg", el(s, "weightKg")); put("reps", el(s, "reps"))
                            put("timeSeconds", el(s, "timeSeconds")); put("rpe", el(s, "rpe")); put("completed", el(s, "completed"))
                        }
                    }))
                }
            }))
        }
    }
    val routines = recs.filter { it.table == Tables.ROUTINES && !it.deleted }.map { r ->
        buildJsonObject {
            put("id", r.id); put("name", el(r, "name")); put("updatedAt", iso(r.long("updatedAt")!!))
            put("exercises", JsonArray(live(idx.children(r.key)).map { e ->
                buildJsonObject {
                    put("id", e.id); put("exerciseId", el(e, "exerciseId")); put("name", el(e, "exerciseName")); put("mainGroup", el(e, "mainGroup")); put("position", el(e, "position")); put("notes", el(e, "notes"))
                    put("sets", JsonArray(live(idx.children(e.key)).map { s ->
                        buildJsonObject { put("position", el(s, "position")); put("type", el(s, "setType")); put("targetWeightKg", el(s, "targetWeightKg")); put("targetReps", el(s, "targetReps")); put("targetTimeSeconds", el(s, "targetTimeSeconds")) }
                    }))
                }
            }))
        }
    }
    val root = buildJsonObject {
        put("schemaVersion", schema); put("exportedAt", iso(exportedAt)); put("userId", uid)
        put("units", buildJsonObject { put("weight", "kg"); put("length", "cm"); put("time", "s") })
        put("workouts", JsonArray(workouts)); put("routines", JsonArray(routines))
        put("bodyMeasurements", JsonArray(recs.filter { it.table == Tables.BODY_MEASUREMENTS && !it.deleted }.map { m ->
            buildJsonObject {
                put("id", m.id); put("measuredAt", iso(m.long("measuredAt")!!))
                for (f in listOf("weightKg", "bodyFatPct", "chestCm", "waistCm", "hipsCm", "bicepCm", "thighCm")) put(f, el(m, f))
            }
        }))
        put("customExercises", JsonArray(recs.filter { it.table == Tables.EXERCISES && !it.deleted }.map { e ->
            buildJsonObject { put("id", e.id); put("name", el(e, "name")); put("mainGroup", el(e, "mainGroup")); put("musclesUsed", el(e, "musclesUsed")); put("equipment", el(e, "equipment")); put("notes", el(e, "notes")) }
        }))
        put("achievements", JsonArray(recs.filter { it.table == Tables.ACHIEVEMENTS && !it.deleted }.map { a ->
            buildJsonObject { put("key", el(a, "achievementKey")); put("unlockedAt", iso(a.long("unlockedAt")!!)) }
        }))
    }
    return root.toString()
}

// ───────────── almacenes en memoria ─────────────

/** Base local en memoria con transacciones de todo o nada (copia y restauración) y fallos inyectables. */
class MemStore : RecStore {
    /** Identidad con la que se abrió la operación y la «viva» (cambia si el usuario cambia de cuenta). */
    override var boundIdentity: String = UID
    var live: String? = UID
    override fun liveIdentity(): String? = live
    /** Filas que pertenecen a OTRA cuenta del mismo dispositivo (aislamiento entre usuarios, H2). */
    val foreign = linkedMapOf<Key, String>()
    private val lookup = OwnerLookup { t, i -> foreign[Key(t, i)]?.let { Owner.User(it) } ?: if (Key(t, i) in rows) Owner.User(UID) else Owner.Absent }
    private fun guardWrite(rec: Rec) = OwnerGuard.checkWrite(UID, rec, lookup)
    val rows = linkedMapOf<Key, Rec>()
    val conflictGroups = linkedMapOf<Key, ConflictGroup>()
    val archived = mutableListOf<Triple<ConflictGroup, String, Long>>()
    var metaState = SyncMeta()
    val mutex = Mutex()
    /** Si > 0, la escritura n-ésima dentro de una transacción lanza (simula un fallo a mitad). */
    var failOnWrite = 0
    private var writes = 0

    constructor(initial: List<Rec> = emptyList()) { initial.forEach { rows[it.key] = it } }

    private inner class Tx : RecTx, SyncTx {
        private fun tick() { if (failOnWrite > 0 && ++writes >= failOnWrite) { throw IllegalStateException("fallo inyectado") } }
        override suspend fun all(): List<Rec> = rows.values.toList()
        override suspend fun insert(rec: Rec) { tick(); guardWrite(rec); check(rec.key !in rows) { "ya existe ${rec.key}" }; rows[rec.key] = rec }
        override suspend fun records(): List<Rec> = rows.values.toList()
        override suspend fun put(rec: Rec) { tick(); guardWrite(rec); rows[rec.key] = rec }
        override suspend fun remove(table: String, id: String) { tick(); OwnerGuard.checkDelete(UID, table, id, lookup); rows.remove(Key(table, id)) }
        override suspend fun conflicts(): List<ConflictGroup> = conflictGroups.values.toList()
        override suspend fun saveConflict(group: ConflictGroup) { tick(); conflictGroups[group.root] = group }
        override suspend fun removeConflict(root: Key) { conflictGroups.remove(root) }
        override suspend fun archive(group: ConflictGroup, resolution: String, at: Long) { archived += Triple(group, resolution, at) }
        override suspend fun meta(): SyncMeta = metaState
        override suspend fun setMeta(meta: SyncMeta) { metaState = meta }
    }

    private suspend fun <T> tx(block: suspend Tx.() -> T): T {
        val r = LinkedHashMap(rows); val c = LinkedHashMap(conflictGroups); val a = archived.toList(); val m = metaState
        writes = 0
        try { return Tx().block() } catch (e: Throwable) {
            rows.clear(); rows.putAll(r); conflictGroups.clear(); conflictGroups.putAll(c); archived.clear(); archived.addAll(a); metaState = m
            throw e
        } finally { writes = 0 }
    }

    override suspend fun <T> transaction(block: suspend RecTx.() -> T): T = tx { block() }
    suspend fun <T> transactionSync(block: suspend SyncTx.() -> T): T = tx { block() }
}

/** Adaptador para usar un MemStore como SyncStore (las dos interfaces tienen un `transaction` con receptor distinto). */
class SyncView(val m: MemStore) : SyncStore {
    override val boundIdentity: String get() = m.boundIdentity
    override fun liveIdentity(): String? = m.liveIdentity()
    override suspend fun <T> transaction(block: suspend SyncTx.() -> T): T = m.transactionSync(block) }
