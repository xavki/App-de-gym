package com.gymflow.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Genera la misma exportación que la app (DataExporter.kt, schemaVersion 1) a
 * partir de los registros sincronizados, para que el panel web lea del servidor
 * exactamente lo mismo que leería de un archivo.
 */
class ExportService(private val sync: SyncRepository, private val clock: Clock) {

    fun export(uid: String): JsonObject {
        val live = sync.allRecords(uid).filter { it.deletedAt == null }.groupBy { it.table }
        fun table(name: String) = live[name].orEmpty().map { it.data }

        val custom = table("exercises")
        val customById = custom.associateBy { it.str("id") }
        val customByName = custom.associateBy { it.str("name") }
        fun groupOf(e: JsonObject): JsonElement {
            val group = e.str("mainGroup")
                ?: (e.str("exerciseId")?.let(customById::get) ?: customByName[e.str("exerciseName")])?.str("mainGroup")
            return group?.let(::JsonPrimitive) ?: JsonNull
        }

        // Igual que las consultas de la app: un hijo solo cuenta si su padre está vivo
        val workouts = table("workouts").sortedByDescending { it.long("startedAt") }
        val workoutIds = workouts.mapNotNull { it.str("id") }.toSet()
        val wExercises = table("workout_exercises").filter { it.str("workoutId") in workoutIds }
        val wExerciseIds = wExercises.mapNotNull { it.str("id") }.toSet()
        val wSetsByEx = table("workout_sets").filter { it.str("workoutExerciseId") in wExerciseIds }
            .groupBy { it.str("workoutExerciseId") }
        val wExByWorkout = wExercises.groupBy { it.str("workoutId") }

        val routines = table("routines").sortedBy { it.long("createdAt") }
        val routineIds = routines.mapNotNull { it.str("id") }.toSet()
        val rExercises = table("routine_exercises").filter { it.str("routineId") in routineIds }
        val rExerciseIds = rExercises.mapNotNull { it.str("id") }.toSet()
        val rSetsByEx = table("routine_sets").filter { it.str("routineExerciseId") in rExerciseIds }
            .groupBy { it.str("routineExerciseId") }
        val rExByRoutine = rExercises.groupBy { it.str("routineId") }

        return buildJsonObject {
            put("schemaVersion", 1)
            put("exportedAt", iso(clock.millis()))
            put("userId", uid)
            put("units", buildJsonObject { put("weight", "kg"); put("length", "cm"); put("time", "s") })
            put("workouts", buildJsonArray {
                for (w in workouts) add(buildJsonObject {
                    copy(w, "id"); copy(w, "routineId"); copy(w, "name")
                    put("startedAt", isoOf(w, "startedAt"))
                    copy(w, "durationSeconds"); copy(w, "bodyweightKg"); copy(w, "notes")
                    put("updatedAt", isoOf(w, "updatedAt"))
                    put("exercises", buildJsonArray {
                        for (e in wExByWorkout[w.str("id")].orEmpty().sortedBy { it.long("position") }) add(buildJsonObject {
                            copy(e, "id"); copy(e, "exerciseId"); copy(e, "exerciseName", "name")
                            put("mainGroup", groupOf(e))
                            copy(e, "position"); copy(e, "notes"); copy(e, "supersetGroup")
                            put("sets", buildJsonArray {
                                for (s in wSetsByEx[e.str("id")].orEmpty().sortedBy { it.long("position") }) add(buildJsonObject {
                                    copy(s, "id"); copy(s, "position"); copy(s, "setType", "type")
                                    copy(s, "weightKg"); copy(s, "reps"); copy(s, "timeSeconds"); copy(s, "rpe"); copy(s, "completed")
                                })
                            })
                        })
                    })
                })
            })
            put("routines", buildJsonArray {
                for (r in routines) add(buildJsonObject {
                    copy(r, "id"); copy(r, "name")
                    put("updatedAt", isoOf(r, "updatedAt"))
                    put("exercises", buildJsonArray {
                        for (e in rExByRoutine[r.str("id")].orEmpty().sortedBy { it.long("position") }) add(buildJsonObject {
                            copy(e, "id"); copy(e, "exerciseId"); copy(e, "exerciseName", "name")
                            put("mainGroup", groupOf(e))
                            copy(e, "position"); copy(e, "notes")
                            put("sets", buildJsonArray {
                                for (s in rSetsByEx[e.str("id")].orEmpty().sortedBy { it.long("position") }) add(buildJsonObject {
                                    copy(s, "position"); copy(s, "setType", "type")
                                    copy(s, "targetWeightKg"); copy(s, "targetReps"); copy(s, "targetTimeSeconds")
                                })
                            })
                        })
                    })
                })
            })
            put("bodyMeasurements", buildJsonArray {
                for (m in table("body_measurements").sortedBy { it.long("measuredAt") }) add(buildJsonObject {
                    copy(m, "id")
                    put("measuredAt", isoOf(m, "measuredAt"))
                    for (k in listOf("weightKg", "bodyFatPct", "chestCm", "waistCm", "hipsCm", "bicepCm", "thighCm")) copy(m, k)
                })
            })
            put("customExercises", buildJsonArray {
                for (e in custom.sortedBy { it.str("name") }) add(buildJsonObject {
                    for (k in listOf("id", "name", "mainGroup", "musclesUsed", "equipment", "notes")) copy(e, k)
                })
            })
            put("achievements", buildJsonArray {
                for (a in table("achievements")) add(buildJsonObject {
                    copy(a, "achievementKey", "key")
                    put("unlockedAt", isoOf(a, "unlockedAt"))
                })
            })
        }
    }

    /** Copia el campo [key] del registro con el nombre [target] (null si falta). */
    private fun JsonObjectBuilder.copy(from: JsonObject, key: String, target: String = key) {
        put(target, from[key] ?: JsonNull)
    }

    private fun isoOf(o: JsonObject, key: String): JsonElement = o.long(key)?.let { JsonPrimitive(iso(it)) } ?: JsonNull

    companion object {
        /** Mismo formato que la app: 2026-09-28T15:50:16Z (UTC, sin milisegundos). */
        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
