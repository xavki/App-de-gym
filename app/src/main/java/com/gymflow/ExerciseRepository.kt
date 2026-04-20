package com.gymflow

import com.google.firebase.firestore.FirebaseFirestore

object ExerciseRepository {

    // Cache en memoria para no llamar Firestore cada vez
    private var cachedExercises: List<ExerciseDefinition> = emptyList()

    fun loadExercises(
        db: FirebaseFirestore,
        onResult: (List<ExerciseDefinition>) -> Unit
    ) {
        // Si ya están cargados, devuelve el cache
        if (cachedExercises.isNotEmpty()) {
            onResult(cachedExercises)
            return
        }

        db.collection("exercises")
            .get()
            .addOnSuccessListener { snapshot ->
                val exercises = snapshot.documents.mapNotNull { doc ->
                    try {
                        ExerciseDefinition(
                            name          = doc.getString("name")         ?: return@mapNotNull null,
                            mainGroup     = doc.getString("mainGroup")    ?: "",
                            musclesUsed   = doc.getString("musclesUsed")  ?: "",
                            instructions  = doc.getString("instructions") ?: "",
                            gifUrl        = doc.getString("gifUrl"),
                            imageUrl      = doc.getString("imageUrl"),
                            subCategory   = doc.getString("subCategory"),
                            difficulty    = doc.getString("difficulty"),
                            equipment     = doc.getString("equipment"),
                            nameEs        = doc.getString("nameEs"),
                            instructionsEs = doc.getString("instructionsEs")
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.sortedBy { it.name }

                cachedExercises = exercises
                onResult(exercises)
            }
            .addOnFailureListener {
                // Si falla Firestore, devuelve lista vacía
                onResult(emptyList())
            }
    }

    // Para acceso sincrónico cuando ya está cacheado
    fun getCached(): List<ExerciseDefinition> = cachedExercises
}
