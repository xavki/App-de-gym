package com.gymflow

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Aviso de fin de descanso.
 *
 * - App en primer plano: sonido de notificación del sistema + vibración ([playInApp]).
 * - App en segundo plano o pantalla bloqueada: alarma del sistema que publica una notificación
 *   con sonido ([scheduleBackground] → [RestAlarmReceiver]).
 *
 * Ambos usan el uso de audio "notificación": respetan el modo del móvil (en silencio no suena;
 * en vibración solo vibra) y "No molestar". Sin el permiso de alarmas exactas (Android 12+)
 * la alarma puede llegar con algo de retraso: lo decide el sistema.
 */
object RestAlert {

    const val CHANNEL_ID = "gymflow_rest_timer"
    private const val REQUEST_CODE = 0x5E57
    private const val NOTIFICATION_ID = 0x5E57

    private val audio = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Fin del descanso", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Avisa cuando termina el descanso entre series"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audio)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200, 100, 400)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    /** Sonido + vibración con la app abierta. Quien llama garantiza que es una vez por descanso. */
    fun playInApp(context: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return vibratePattern(context)
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
            if (ringtone != null) {
                ringtone.audioAttributes = audio
                ringtone.play()
                // Los sonidos de notificación son cortos; se corta por si el elegido fuera largo
                Handler(Looper.getMainLooper()).postDelayed({ ringtone.stop() }, 3000)
            }
        } catch (_: Exception) { /* sin sonido disponible: queda la vibración */ }
        vibratePattern(context)
    }

    /** Programa el aviso para cuando la app no está en primer plano. */
    fun scheduleBackground(context: Context, inMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + inMs.coerceAtLeast(0)
        val pi = pendingIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancelBackground(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(context))
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, RestAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    internal fun showNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val openPi = PendingIntent.getActivity(
            context, REQUEST_CODE, open ?: Intent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Descanso terminado")
            .setContentText("Siguiente serie cuando quieras")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    }
}

/** Fin del descanso con la app en segundo plano: notificación con sonido del canal. */
class RestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = RestAlert.showNotification(context)
}
