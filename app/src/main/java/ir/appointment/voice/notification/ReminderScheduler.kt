package ir.appointment.voice.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Schedules a reminder 30 minutes before an appointment using AlarmManager.
 *
 * Deliberately uses `setAndAllowWhileIdle` (inexact, "best effort within a short
 * window") rather than `setExactAndAllowWhileIdle`, so no special
 * SCHEDULE_EXACT_ALARM permission / settings round-trip is required. A reminder
 * firing within a couple of minutes of the target time is perfectly fine for an
 * appointment use case.
 */
object ReminderScheduler {

    private const val LEAD_TIME_MILLIS = 30 * 60 * 1000L // 30 minutes before

    fun schedule(context: Context, appointmentId: Long, appointmentTimeMillis: Long, label: String) {
        val triggerAt = appointmentTimeMillis - LEAD_TIME_MILLIS
        val now = System.currentTimeMillis()
        // If the lead time has already passed but the appointment itself is still
        // in the future, remind immediately-ish (a few seconds out) instead of skipping.
        val effectiveTrigger = when {
            triggerAt > now -> triggerAt
            appointmentTimeMillis > now -> now + 5_000L
            else -> return // appointment already in the past, nothing to schedule
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            putExtra(ReminderBroadcastReceiver.EXTRA_APPOINTMENT_ID, appointmentId)
            putExtra(ReminderBroadcastReceiver.EXTRA_LABEL, label)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appointmentId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, effectiveTrigger, pendingIntent)
        } catch (_: SecurityException) {
            // Extremely defensive; setAndAllowWhileIdle normally needs no permission.
        }
    }

    fun cancel(context: Context, appointmentId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderBroadcastReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appointmentId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
