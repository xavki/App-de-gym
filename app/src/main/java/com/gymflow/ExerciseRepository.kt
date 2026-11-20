package com.gymflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.firestore.FirebaseFirestore
import com.gymflow.data.FirestoreImporter
import com.gymflow.data.GymRepository

object ExerciseRepository {

    // Cache en memoria para no ir a la base de datos cada vez
    private var cachedExercises: List<ExerciseDefinition> = emptyList()

    /**
     * Revisión del catálogo en caché. Es estado observable: las pantallas que derivan datos del catálogo (grupo muscular de cada
     * ejercicio) la leen para recomponer y la usan como clave de `remember`; `getCached().size` no detecta un cambio de grupo
     * con el mismo número de ejercicios (R5-03).
     */
    var catalogRevision by mutableIntStateOf(0)
        private set

    /** Sustituye el catálogo en caché (carga inicial o actualización) y sube la revisión. */
    fun replaceCached(list: List<ExerciseDefinition>) {
        cachedExercises = list
        catalogRevision++
    }

    /** Catálogo desde Room; solo se descarga de Firestore la primera vez. */
    suspend fun loadExercises(repo: GymRepository, db: FirebaseFirestore): List<ExerciseDefinition> {
        if (cachedExercises.isEmpty()) {
            replaceCached(FirestoreImporter.ensureCatalog(repo, db).sortedBy { it.name })
        }
        return cachedExercises
    }

    // Para acceso sincrónico cuando ya está cacheado
    fun getCached(): List<ExerciseDefinition> = cachedExercises
}
