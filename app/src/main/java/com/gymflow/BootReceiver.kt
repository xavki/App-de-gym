package com.gymflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val uid = Firebase.auth.currentUser?.uid ?: return
        ScheduleRepository.loadSchedules(uid) { schedules ->
            schedules.forEach { schedule ->
                // Solo reprogramar si la siguiente ocurrencia es futura
                if (NotificationHelper.nextTriggerMs(schedule) != null) {
                    NotificationHelper.scheduleAlarm(context, schedule)
                }
            }
        }
    }
}
