package com.gymflow

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class NotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId     = intent.getStringExtra("schedule_id") ?: return
        val routineName    = intent.getStringExtra("routine_name") ?: "Tu rutina"
        val recurrenceType = intent.getStringExtra("recurrence_type") ?: RecurrenceType.ONCE
        val intervalDays   = intent.getIntExtra("interval_days", 1)
        val weekDay        = intent.getIntExtra("week_day", 2)
        val endDate        = intent.getLongExtra("end_date", 0L)
        val hourOfDay      = intent.getIntExtra("hour_of_day", 8)
        val minute         = intent.getIntExtra("minute", 0)

        // ── Mostrar notificación ──────────────────────────────────────────────
        showNotification(context, scheduleId, routineName)

        // ── Reprogramar siguiente ocurrencia si es recurrente ─────────────────
        if (recurrenceType != RecurrenceType.ONCE) {
            val nextSchedule = ScheduledRoutine(
                id             = scheduleId,
                routineName    = routineName,
                recurrenceType = recurrenceType,
                intervalDays   = intervalDays,
                weekDay        = weekDay,
                endDate        = endDate,
                hourOfDay      = hourOfDay,
                minute         = minute,
                // Para EVERY_N_DAYS avanzamos startDate un intervalo
                startDate      = System.currentTimeMillis()
            )
            NotificationHelper.scheduleAlarm(context, nextSchedule)
        }
    }

    private fun showNotification(context: Context, scheduleId: String, routineName: String) {
        // Intent para abrir la app al pulsar la notificación
        val openIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }

        val pendingOpen = PendingIntent.getActivity(
            context,
            scheduleId.hashCode(),
            openIntent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("¡Hora de entrenar! 💪")
            .setContentText("Tienes programada: $routineName")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Tienes programada: $routineName\nAbre GymFlow para empezar."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingOpen)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                nm.notify(scheduleId.hashCode(), notification)
            }
        } else {
            nm.notify(scheduleId.hashCode(), notification)
        }
    }
}
