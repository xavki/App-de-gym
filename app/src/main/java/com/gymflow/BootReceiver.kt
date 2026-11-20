package com.gymflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.gymflow.data.GymRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val uid = Firebase.auth.currentUser?.uid ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                GymRepository.get(context).schedules(uid).forEach { schedule ->
                    // Solo reprogramar si la siguiente ocurrencia es futura
                    if (NotificationHelper.nextTriggerMs(schedule) != null) {
                        NotificationHelper.scheduleAlarm(context, schedule)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
