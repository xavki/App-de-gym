package com.gymflow.data

import android.content.Context
import android.net.Uri
import com.google.gson.GsonBuilder
import com.gymflow.oneRMBrzycki
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Exporta los datos del usuario para el panel web (y como copia de seguridad).
 *  • JSON: todo, anidado (entreno → ejercicios → series). Lleva schemaVersion.
 *  • CSV : una fila por serie, lista para Excel / pandas.
 */
object DataExporter {

    const val SCHEMA_VERSION = 1

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun ts(ms: Long?) = ms?.let { iso.format(Date(it)) }

    fun suggestedName(ext: String): String =
        "gymflow-export-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".$ext"

    suspend fun exportJson(context: Context, repo: GymRepository, uid: String, target: Uri) {
        val s = repo.rawSnapshot(uid)
        val setsByEx = s.workoutSets.groupBy { it.workoutExerciseId }
        val exByWorkout = s.workoutExercises.groupBy { it.workoutId }
        val rSetsByEx = s.routineSets.groupBy { it.routineExerciseId }
        val rExByRoutine = s.routineExercises.groupBy { it.routineId }

        val root = linkedMapOf(
            "schemaVersion" to SCHEMA_VERSION,
            "exportedAt" to ts(System.currentTimeMillis()),
            "userId" to uid,
            "units" to mapOf("weight" to "kg", "length" to "cm", "time" to "s"),
            "workouts" to s.workouts.map { w ->
                linkedMapOf(
                    "id" to w.id, "routineId" to w.routineId, "name" to w.name,
                    "startedAt" to ts(w.startedAt), "durationSeconds" to w.durationSeconds,
                    "bodyweightKg" to w.bodyweightKg, "notes" to w.notes, "updatedAt" to ts(w.updatedAt),
                    "exercises" to exByWorkout[w.id].orEmpty().map { e ->
                        linkedMapOf(
                            "id" to e.id, "exerciseId" to e.exerciseId, "name" to e.exerciseName,
                            "position" to e.position, "notes" to e.notes, "supersetGroup" to e.supersetGroup,
                            "sets" to setsByEx[e.id].orEmpty().map { st ->
                                linkedMapOf(
                                    "id" to st.id, "position" to st.position, "type" to st.setType,
                                    "weightKg" to st.weightKg, "reps" to st.reps, "timeSeconds" to st.timeSeconds,
                                    "rpe" to st.rpe, "completed" to st.completed
                                )
                            }
                        )
                    }
                )
            },
            "routines" to s.routines.map { r ->
                linkedMapOf(
                    "id" to r.id, "name" to r.name, "updatedAt" to ts(r.updatedAt),
                    "exercises" to rExByRoutine[r.id].orEmpty().map { e ->
                        linkedMapOf(
                            "id" to e.id, "exerciseId" to e.exerciseId, "name" to e.exerciseName,
                            "position" to e.position, "notes" to e.notes,
                            "sets" to rSetsByEx[e.id].orEmpty().map { st ->
                                linkedMapOf(
                                    "position" to st.position, "type" to st.setType,
                                    "targetWeightKg" to st.targetWeightKg, "targetReps" to st.targetReps,
                                    "targetTimeSeconds" to st.targetTimeSeconds
                                )
                            }
                        )
                    }
                )
            },
            "bodyMeasurements" to s.bodyMeasurements.map { m ->
                linkedMapOf(
                    "id" to m.id, "measuredAt" to ts(m.measuredAt), "weightKg" to m.weightKg,
                    "bodyFatPct" to m.bodyFatPct, "chestCm" to m.chestCm, "waistCm" to m.waistCm,
                    "hipsCm" to m.hipsCm, "bicepCm" to m.bicepCm, "thighCm" to m.thighCm
                )
            },
            "customExercises" to s.exercises.map { e ->
                linkedMapOf(
                    "id" to e.id, "name" to e.name, "mainGroup" to e.mainGroup,
                    "musclesUsed" to e.musclesUsed, "equipment" to e.equipment, "notes" to e.notes
                )
            },
            "achievements" to s.achievements.map { mapOf("key" to it.achievementKey, "unlockedAt" to ts(it.unlockedAt)) }
        )

        val json = GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(root)
        write(context, target, json)
    }

    suspend fun exportCsv(context: Context, repo: GymRepository, uid: String, target: Uri) {
        val s = repo.rawSnapshot(uid)
        val workouts = s.workouts.associateBy { it.id }
        val exercises = s.workoutExercises.associateBy { it.id }

        val sb = StringBuilder()
        sb.append("workout_id,started_at,workout_name,duration_s,bodyweight_kg,")
            .append("exercise_position,exercise_id,exercise_name,")
            .append("set_position,set_type,weight_kg,reps,time_s,rpe,completed,e1rm_kg\n")

        s.workoutSets
            .mapNotNull { st ->
                val e = exercises[st.workoutExerciseId] ?: return@mapNotNull null
                val w = workouts[e.workoutId] ?: return@mapNotNull null
                Triple(w, e, st)
            }
            .sortedWith(compareBy({ it.first.startedAt }, { it.second.position }, { it.third.position }))
            .forEach { (w, e, st) ->
                // Brzycki solo es fiable hasta ~12 repeticiones
                val e1rm = if (st.weightKg > 0 && st.reps in 1..12) "%.1f".format(Locale.US, oneRMBrzycki(st.weightKg, st.reps)) else ""
                sb.append(listOf(
                    w.id, ts(w.startedAt), csv(w.name), w.durationSeconds, w.bodyweightKg ?: "",
                    e.position + 1, e.exerciseId ?: "", csv(e.exerciseName),
                    st.position + 1, st.setType, st.weightKg, st.reps, st.timeSeconds, st.rpe ?: "",
                    st.completed, e1rm
                ).joinToString(",")).append('\n')
            }

        write(context, target, sb.toString())
    }

    private fun csv(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private fun write(context: Context, target: Uri, content: String) {
        context.contentResolver.openOutputStream(target, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: error("No se pudo abrir el archivo de destino")
    }
}
