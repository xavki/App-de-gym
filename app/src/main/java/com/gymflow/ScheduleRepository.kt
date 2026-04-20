package com.gymflow

import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

object ScheduleRepository {

    private val db = Firebase.firestore

    private fun col(uid: String) =
        db.collection("users").document(uid).collection("schedules")

    fun loadSchedules(uid: String, onResult: (List<ScheduledRoutine>) -> Unit) {
        col(uid).get()
            .addOnSuccessListener { snap ->
                val list = snap.documents.mapNotNull { doc ->
                    try {
                        ScheduledRoutine(
                            id            = doc.getString("id") ?: doc.id,
                            routineId     = doc.getString("routineId") ?: "",
                            routineName   = doc.getString("routineName") ?: "",
                            startDate     = doc.getLong("startDate") ?: 0L,
                            hourOfDay     = (doc.getLong("hourOfDay") ?: 8L).toInt(),
                            minute        = (doc.getLong("minute") ?: 0L).toInt(),
                            recurrenceType= doc.getString("recurrenceType") ?: RecurrenceType.ONCE,
                            intervalDays  = (doc.getLong("intervalDays") ?: 1L).toInt(),
                            weekDay       = (doc.getLong("weekDay") ?: 2L).toInt(),
                            endDate       = doc.getLong("endDate") ?: 0L,
                            userId        = uid
                        )
                    } catch (e: Exception) { null }
                }
                onResult(list)
            }
            .addOnFailureListener { onResult(emptyList()) }
    }

    fun saveSchedule(uid: String, s: ScheduledRoutine, onResult: (Boolean) -> Unit) {
        val data = mapOf(
            "id"             to s.id,
            "routineId"      to s.routineId,
            "routineName"    to s.routineName,
            "startDate"      to s.startDate,
            "hourOfDay"      to s.hourOfDay,
            "minute"         to s.minute,
            "recurrenceType" to s.recurrenceType,
            "intervalDays"   to s.intervalDays,
            "weekDay"        to s.weekDay,
            "endDate"        to s.endDate,
            "userId"         to uid
        )
        col(uid).document(s.id).set(data)
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    fun deleteSchedule(uid: String, scheduleId: String, onResult: (Boolean) -> Unit) {
        col(uid).document(scheduleId).delete()
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }
}
