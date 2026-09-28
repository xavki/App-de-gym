package com.gymflow.data

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.gymflow.BodyMeasurement
import com.gymflow.CustomExercise
import com.gymflow.ExerciseDefinition
import com.gymflow.RecurrenceType
import com.gymflow.ScheduledRoutine
import com.gymflow.WorkoutSession
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Migración única desde Firestore a Room. Firestore ya no recibe escrituras de
 * datos de usuario: solo se lee aquí, una vez por usuario, para no perder el
 * historial previo. El catálogo global se sigue descargando de Firestore y se
 * cachea en Room para poder usarlo sin conexión.
 */
object FirestoreImporter {

    private const val TAG = "FirestoreImporter"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("GymFlowPrefs", Context.MODE_PRIVATE)

    /** Descarga el catálogo si Room aún no lo tiene. Devuelve lo que haya en local. */
    suspend fun ensureCatalog(repo: GymRepository, db: FirebaseFirestore): List<ExerciseDefinition> {
        val local = repo.globalExercises()
        if (local.isNotEmpty()) return local
        return try {
            val snap = db.collection("exercises").get().await()
            val items = snap.documents.mapNotNull { doc ->
                val name = doc.getString("name") ?: return@mapNotNull null
                doc.id to ExerciseDefinition(
                    name           = name,
                    mainGroup      = doc.getString("mainGroup") ?: "",
                    musclesUsed    = doc.getString("musclesUsed") ?: "",
                    instructions   = doc.getString("instructions") ?: "",
                    gifUrl         = doc.getString("gifUrl"),
                    imageUrl       = doc.getString("imageUrl"),
                    subCategory    = doc.getString("subCategory"),
                    difficulty     = doc.getString("difficulty"),
                    equipment      = doc.getString("equipment"),
                    nameEs         = doc.getString("nameEs"),
                    instructionsEs = doc.getString("instructionsEs")
                )
            }
            repo.saveGlobalCatalog(items)
            repo.globalExercises()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo descargar el catálogo: ${e.message}")
            emptyList()
        }
    }

    /** Copia a Room los datos del usuario en Firestore. Solo se marca como hecho si todo va bien. */
    suspend fun importUserDataIfNeeded(context: Context, repo: GymRepository, db: FirebaseFirestore, uid: String) {
        val key = "firestore_import_done_$uid"
        if (prefs(context).getBoolean(key, false)) return
        try {
            // Source.SERVER: sin conexión Firestore devolvería su caché (quizá vacía)
            // y daríamos la importación por hecha sin haber traído nada
            val user = db.collection("users").document(uid)

            // Catálogo primero, para poder enlazar exerciseId por nombre
            ensureCatalog(repo, db)

            user.collection("custom_exercises").get(Source.SERVER).await()
                .toObjects(CustomExercise::class.java)
                .forEach { repo.saveCustomExercise(uid, it) }

            user.collection("routines").get(Source.SERVER).await()
                .toObjects(WorkoutSession::class.java)
                .forEach { repo.saveRoutine(uid, it) }

            // El id de Firestore se convierte en UUID estable → reimportar no duplica
            user.collection("workout_history").get(Source.SERVER).await().documents.forEach { doc ->
                val w = doc.toObject(WorkoutSession::class.java) ?: return@forEach
                repo.saveWorkout(
                    uid = uid, session = w, routineId = null,
                    workoutId = GymRepository.stableUuid(doc.id),
                    startedAt = w.date.time, onlyLoggedSets = false, createdAt = w.date.time
                )
            }

            user.collection("body_measurements").get(Source.SERVER).await()
                .toObjects(BodyMeasurement::class.java)
                .forEach { repo.saveMeasurement(uid, it) }

            user.collection("schedules").get(Source.SERVER).await().documents.forEach { doc ->
                repo.saveSchedule(uid, ScheduledRoutine(
                    id             = doc.getString("id") ?: doc.id,
                    routineId      = doc.getString("routineId") ?: "",
                    routineName    = doc.getString("routineName") ?: "",
                    startDate      = doc.getLong("startDate") ?: 0L,
                    hourOfDay      = (doc.getLong("hourOfDay") ?: 8L).toInt(),
                    minute         = (doc.getLong("minute") ?: 0L).toInt(),
                    recurrenceType = doc.getString("recurrenceType") ?: RecurrenceType.ONCE,
                    intervalDays   = (doc.getLong("intervalDays") ?: 1L).toInt(),
                    weekDay        = (doc.getLong("weekDay") ?: 2L).toInt(),
                    endDate        = doc.getLong("endDate") ?: 0L,
                    userId         = uid
                ))
            }

            user.collection("achievements").get(Source.SERVER).await().documents.forEach { doc ->
                val id = doc.getString("id") ?: return@forEach
                val at = doc.getDate("unlockedAt")?.time ?: System.currentTimeMillis()
                repo.unlockAchievement(uid, id, at)
            }

            prefs(context).edit().putBoolean(key, true).apply()
            Log.i(TAG, "Importación desde Firestore completada para $uid")
        } catch (e: Exception) {
            // Se reintentará en el próximo arranque; lo ya importado se sobrescribe sin duplicar
            Log.w(TAG, "Importación incompleta: ${e.message}")
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
