package com.gymflow

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

object FirebaseService {
    val auth: FirebaseAuth get() = Firebase.auth
    val db: FirebaseFirestore get() = Firebase.firestore

    fun saveUser(uid: String, name: String, email: String): Task<Void> {
        val userData = hashMapOf(
            "uid" to uid,
            "name" to name,
            "email" to email,
            "createdAt" to com.google.firebase.Timestamp.now()
        )
        return db.collection("users").document(uid).set(userData)
    }

    fun getRoutines(uid: String) = db.collection("users").document(uid).collection("routines").get()

    fun saveRoutine(uid: String, routine: WorkoutSession) = 
        db.collection("users").document(uid).collection("routines").document(routine.id).set(routine)

    fun deleteRoutine(uid: String, routineId: String) = 
        db.collection("users").document(uid).collection("routines").document(routineId).delete()
}
