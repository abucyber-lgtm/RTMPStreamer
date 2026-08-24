package com.example.rtmpstreamer.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Menjadwalkan waktu mulai streaming otomatis (misal tiap Jumat jam 11:45).
 * Saat alarm berbunyi, [ScheduleReceiver] akan membuka MainActivity dengan
 * extra EXTRA_AUTO_START = true, yang langsung memicu startStreaming().
 *
 * Catatan: di Android 12+ alarm presisi butuh izin "Alarms & reminders"
 * (SCHEDULE_EXACT_ALARM) yang harus diaktifkan manual oleh user di
 * pengaturan sistem jika app menyasar target SDK tinggi.
 */
object StreamSchedule {

    private const val REQUEST_CODE = 9001

    fun scheduleNext(context: Context, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduleReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                // Fallback: alarm tidak presisi kalau izin belum diberikan
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduleReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
