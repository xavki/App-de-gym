package com.gymflow

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.*

object RoutineRepository {
    private val db: FirebaseFirestore get() = Firebase.firestore

    fun saveRoutine(uid: String, routine: WorkoutSession, onComplete: (Boolean) -> Unit = {}) {
        db.collection("users").document(uid).collection("routines").document(routine.id)
            .set(routine)
            .addOnCompleteListener { onComplete(it.isSuccessful) }
    }

    fun saveHistory(uid: String, routine: WorkoutSession) {
        val db = Firebase.firestore
        
        // 1. Guardar el resumen de la sesión completa
        val sessionHistoryRef = db.collection("users").document(uid).collection("workout_history").document()
        val sessionCopy = routine.copy(
            id = sessionHistoryRef.id,
            date = Date()
        )
        sessionHistoryRef.set(sessionCopy)

        // 2. Guardar el historial individual por ejercicio (para la pantalla de info)
        routine.exercises.forEach { ex ->
            val completedSets = ex.sets.filter { it.isCompleted }
            if (completedSets.isNotEmpty()) {
                val exerciseHistoryRef = db.collection("users").document(uid).collection("exercise_history").document()
                val entry = ExerciseHistoryEntry(
                    id = exerciseHistoryRef.id,
                    userId = uid,
                    exerciseName = ex.exerciseName,
                    date = Date(),
                    sets = completedSets.map { it.copy(isCompleted = true) }
                )
                exerciseHistoryRef.set(entry)
            }
        }
    }

    fun deleteRoutine(uid: String, routineId: String, onComplete: (Boolean) -> Unit) {
        db.collection("users").document(uid).collection("routines").document(routineId)
            .delete()
            .addOnCompleteListener { onComplete(it.isSuccessful) }
    }

    fun loadRoutines(uid: String, onResult: (List<WorkoutSession>) -> Unit) {
        db.collection("users").document(uid).collection("routines").get()
            .addOnSuccessListener { snapshot ->
                onResult(snapshot.toObjects(WorkoutSession::class.java))
            }
            .addOnFailureListener { onResult(emptyList()) }
    }

    fun loadWorkoutHistory(uid: String, onResult: (List<WorkoutSession>) -> Unit) {
        db.collection("users").document(uid).collection("workout_history")
            .orderBy("date", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snapshot ->
                onResult(snapshot.toObjects(WorkoutSession::class.java))
            }
            .addOnFailureListener { onResult(emptyList()) }
    }
}
