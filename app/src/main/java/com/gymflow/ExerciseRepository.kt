package com.gymflow

import com.google.firebase.firestore.FirebaseFirestore
import com.gymflow.data.FirestoreImporter
import com.gymflow.data.GymRepository

object ExerciseRepository {

    // Cache en memoria para no ir a la base de datos cada vez
    private var cachedExercises: List<ExerciseDefinition> = emptyList()

    /** Catálogo desde Room; solo se descarga de Firestore la primera vez. */
    suspend fun loadExercises(repo: GymRepository, db: FirebaseFirestore): List<ExerciseDefinition> {
        if (cachedExercises.isEmpty()) {
            cachedExercises = FirestoreImporter.ensureCatalog(repo, db).sortedBy { it.name }
        }
        return cachedExercises
    }

    // Para acceso sincrónico cuando ya está cacheado
    fun getCached(): List<ExerciseDefinition> = cachedExercises
}
