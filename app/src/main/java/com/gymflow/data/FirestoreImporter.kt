package com.gymflow.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.gymflow.BodyMeasurement
import com.gymflow.CustomExercise
import com.gymflow.ExerciseDefinition
import com.gymflow.RecurrenceType
import com.gymflow.ScheduledRoutine
import com.gymflow.WorkoutSession
import com.gymflow.synccore.LegacyItem
import com.gymflow.synccore.Tables
import com.gymflow.synccore.runLegacyImport

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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo descargar el catálogo: ${e.message}")
            emptyList()
        }
    }

    /**
     * Permiso EXPLÍCITO del usuario para leer SUS datos antiguos de Firestore. Solo se obtiene con [LegacyConsent.grant], que se llama
     * únicamente desde una acción de pantalla (botón + diálogo). Nada en el arranque ni en el login puede fabricarlo (ver LegacyImportGuardTest).
     */
    class LegacyConsent private constructor(val uid: String) {
        companion object { fun grant(uid: String) = LegacyConsent(uid) }
    }

    /** ¿Hay algo que ofrecer? (no se ha importado ya para esta cuenta). No lee Firestore. */
    fun isOffered(context: Context, uid: String): Boolean = !prefs(context).getBoolean("firestore_import_done_$uid", false)

    /**
     * Copia a Room los datos del usuario en Firestore. Requiere [consent] de ESA cuenta y que la cuenta activa siga siendo la misma.
     * Dos fases: (1) se lee TODO desde el servidor sin tocar Room; (2) se guarda. Si la lectura falla no se escribe nada.
     * Los ids de Firestore se convierten en UUID estables y lo que ya existe se salta → reintentar o importar antes/después de la
     * sincronización no duplica. Lo importado lleva la fecha antigua IMPORTED_AT para que cualquier edición real gane.
     */
    suspend fun importUserDataWithConsent(context: Context, repo: GymRepository, db: FirebaseFirestore, uid: String, consent: LegacyConsent, stillActive: () -> Boolean): LegacyOutcome {
        require(consent.uid == uid) { "El consentimiento es de otra cuenta" }
        val key = "firestore_import_done_$uid"
        if (prefs(context).getBoolean(key, false)) return LegacyOutcome(true, 0, emptyList())
        try {
            if (!stillActive()) return LegacyOutcome(false, 0, emptyList())
            ensureCatalog(repo, db)                 // catálogo global (no es dato personal), para enlazar exerciseId por nombre
            val user = db.collection("users").document(uid)
            // FASE 1: lectura completa. Source.SERVER: sin conexión Firestore devolvería su caché (quizá vacía)
            val customs = user.collection("custom_exercises").get(Source.SERVER).await().toObjects(CustomExercise::class.java)
            val routines = user.collection("routines").get(Source.SERVER).await().toObjects(WorkoutSession::class.java)
            val workouts = user.collection("workout_history").get(Source.SERVER).await().documents
            val measures = user.collection("body_measurements").get(Source.SERVER).await().toObjects(BodyMeasurement::class.java)
            val schedules = user.collection("schedules").get(Source.SERVER).await().documents
            val achievements = user.collection("achievements").get(Source.SERVER).await().documents
            if (!stillActive()) return LegacyOutcome(false, 0, emptyList())   // la cuenta cambió mientras se leía: no se guarda nada
            // FASE 2: guardado INCREMENTAL, agregado a agregado (cada uno en su transacción). No es globalmente atómico: si uno falla,
            // lo ya escrito se conserva y el reintento lo reconoce (ids estables). Una colisión de id con otra cuenta se omite y se informa.
            val old = GymRepository.IMPORTED_AT
            val id = GymRepository::stableUuid
            val steps = linkedMapOf<LegacyItem, suspend () -> Unit>()
            customs.map { it.copy(id = id(it.id)) }.forEach { c -> steps[LegacyItem(Tables.EXERCISES, c.id)] = { repo.saveCustomExercise(uid, c, at = old) } }
            routines.map { it.copy(id = id(it.id)) }.forEach { r -> steps[LegacyItem(Tables.ROUTINES, r.id)] = { repo.saveRoutine(uid, r, at = old) } }
            workouts.forEach { doc ->
                val w = doc.toObject(WorkoutSession::class.java) ?: return@forEach
                val workoutId = id(doc.id)
                steps[LegacyItem(Tables.WORKOUTS, workoutId)] = {
                    repo.saveWorkout(uid = uid, session = w, routineId = null, workoutId = workoutId, startedAt = w.date.time, onlyLoggedSets = false, createdAt = w.date.time)
                }
            }
            measures.map { it.copy(id = id(it.id)) }.forEach { m -> steps[LegacyItem(Tables.BODY_MEASUREMENTS, m.id)] = { repo.saveMeasurement(uid, m, at = old) } }
            schedules.forEach { doc ->
                val scheduleId = id(doc.getString("id") ?: doc.id)
                steps[LegacyItem(Tables.SCHEDULES, scheduleId)] = {
                    repo.saveSchedule(uid, ScheduledRoutine(
                        id = scheduleId, routineId = doc.getString("routineId") ?: "", routineName = doc.getString("routineName") ?: "",
                        startDate = doc.getLong("startDate") ?: 0L, hourOfDay = (doc.getLong("hourOfDay") ?: 8L).toInt(), minute = (doc.getLong("minute") ?: 0L).toInt(),
                        recurrenceType = doc.getString("recurrenceType") ?: RecurrenceType.ONCE, intervalDays = (doc.getLong("intervalDays") ?: 1L).toInt(),
                        weekDay = (doc.getLong("weekDay") ?: 2L).toInt(), endDate = doc.getLong("endDate") ?: 0L, userId = uid
                    ), at = old)
                }
            }
            // Los logros son también agregados (id estable que incluye el uid): comparten la misma política, revalidación y transacción
            achievements.forEach { doc ->
                val akey = doc.getString("id") ?: return@forEach
                steps[LegacyItem(Tables.ACHIEVEMENTS, GymRepository.stableUuid("$uid:achievement:$akey"))] = {
                    repo.unlockAchievement(uid, akey, doc.getDate("unlockedAt")?.time ?: old)
                }
            }
            val report = runLegacyImport(
                uid, steps.keys.toList(),
                ownerOf = { repo.legacyOwner(it.table, it.id) },
                write = { steps.getValue(it)() },
                stillActive = stillActive,
                atomically = { block -> repo.inTransaction(block) },     // la cuenta y el propietario se revalidan DENTRO de esta transacción
                onComplete = {
                    // última revalidación ya hecha por runLegacyImport; la marca es por uid
                    prefs(context).edit().putBoolean(key, true).apply()
                    Log.i(TAG, "Importación desde Firestore completada")
                },
            )
            if (!report.complete) Log.w(TAG, "Importación incompleta (se puede reintentar)")
            return LegacyOutcome(report.complete, report.written, report.collisions)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e                                  // la cancelación no es un fallo de importación: se propaga al llamador
        } catch (e: Exception) {
            Log.w(TAG, "Importación incompleta (se puede reintentar)")
            return LegacyOutcome(false, 0, emptyList())
        }
    }

    /** Resultado para la UI: [complete] = terminó sin fallos; [collisions] = registros omitidos por tener su id otra cuenta. */
    data class LegacyOutcome(val complete: Boolean, val written: Int, val collisions: List<LegacyItem>)
}
