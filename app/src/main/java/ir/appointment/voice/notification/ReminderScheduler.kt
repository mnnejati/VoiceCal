package ir.appointment.voice.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import ir.appointment.voice.data.SettingsStore

/**
 * Schedules a reminder some configurable number of minutes before an
 * appointment/task using AlarmManager.
 *
 * Uses `setExactAndAllowWhileIdle` (precise, fires within a second or two of the
 * target time) whenever the app has been granted the SCHEDULE_EXACT_ALARM
 * privilege — a special "app op" the user must explicitly allow via system
 * settings on Android 12+ (see [canScheduleExact] / the Settings screen's
 * "دقت بالای آلارم" button). If it hasn't been granted, this transparently falls
 * back to `setAndAllowWhileIdle` (inexact, best-effort within a short window) so
 * reminders still work — just without to-the-second precision.
 */
object ReminderScheduler {

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true // no restriction before Android 12
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    fun schedule(context: Context, appointmentId: Long, appointmentTimeMillis: Long, label: String) {
        val leadMillis = SettingsStore(context).reminderLeadMinutes * 60_000L
        val triggerAt = appointmentTimeMillis - leadMillis
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
            if (canScheduleExact(context)) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, effectiveTrigger, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, effectiveTrigger, pendingIntent)
            }
        } catch (_: SecurityException) {
            // Permission was revoked between the check and the call (rare race) —
            // fall back to inexact rather than losing the reminder entirely.
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, effectiveTrigger, pendingIntent)
            } catch (_: SecurityException) {
            }
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
