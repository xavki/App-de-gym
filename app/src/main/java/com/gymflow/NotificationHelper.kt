package com.gymflow

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object NotificationHelper {

    const val CHANNEL_ID   = "gymflow_reminders"
    const val CHANNEL_NAME = "Recordatorios de Rutina"

    // ── Canal de notificaciones (llamar en onCreate de MainActivity) ────────────
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos para tus rutinas programadas"
                enableVibration(true)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    // ── Programar alarma para un ScheduledRoutine ──────────────────────────────
    fun scheduleAlarm(context: Context, schedule: ScheduledRoutine) {
        val triggerMs = nextTriggerMs(schedule) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context, schedule)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            // Fallback a alarma inexacta si no se tiene el permiso
            am.set(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    // ── Cancelar alarma de un schedule ────────────────────────────────────────
    fun cancelAlarm(context: Context, schedule: ScheduledRoutine) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildPendingIntent(context, schedule))
    }

    // ── PendingIntent con datos de la rutina ──────────────────────────────────
    fun buildPendingIntent(context: Context, schedule: ScheduledRoutine): PendingIntent {
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra("schedule_id",       schedule.id)
            putExtra("routine_name",      schedule.routineName)
            putExtra("recurrence_type",   schedule.recurrenceType)
            putExtra("interval_days",     schedule.intervalDays)
            putExtra("week_day",          schedule.weekDay)
            putExtra("end_date",          schedule.endDate)
            putExtra("hour_of_day",       schedule.hourOfDay)
            putExtra("minute",            schedule.minute)
        }
        return PendingIntent.getBroadcast(
            context,
            schedule.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ── Calcular el próximo disparo en ms ─────────────────────────────────────
    fun nextTriggerMs(schedule: ScheduledRoutine): Long? {
        val now = System.currentTimeMillis()

        return when (schedule.recurrenceType) {
            RecurrenceType.ONCE -> {
                val t = dayAtTime(schedule.startDate, schedule.hourOfDay, schedule.minute)
                if (t > now) t else null
            }
            RecurrenceType.EVERY_N_DAYS -> {
                // Empezar desde startDate, avanzar de N en N días hasta encontrar uno futuro
                var t = dayAtTime(schedule.startDate, schedule.hourOfDay, schedule.minute)
                val end = if (schedule.endDate > 0) schedule.endDate else Long.MAX_VALUE
                while (t <= now && t <= end) t += schedule.intervalDays * 86_400_000L
                if (t > now && t <= end) t else null
            }
            RecurrenceType.WEEKLY_DAY -> {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_WEEK, schedule.weekDay)
                    set(Calendar.HOUR_OF_DAY, schedule.hourOfDay)
                    set(Calendar.MINUTE, schedule.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= now) add(Calendar.WEEK_OF_YEAR, 1)
                }
                val end = if (schedule.endDate > 0) schedule.endDate else Long.MAX_VALUE
                if (cal.timeInMillis <= end) cal.timeInMillis else null
            }
            else -> null
        }
    }

    // ── Fija hora/minuto sobre un día dado ────────────────────────────────────
    private fun dayAtTime(dayMs: Long, hour: Int, min: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = dayMs
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}
