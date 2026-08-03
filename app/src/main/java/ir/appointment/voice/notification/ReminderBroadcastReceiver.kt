package ir.appointment.voice.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import ir.appointment.voice.data.SettingsStore

class ReminderBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appointmentId = intent.getLongExtra(EXTRA_APPOINTMENT_ID, -1L)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "قرار ملاقات"
        if (appointmentId < 0) return

        val settings = SettingsStore(context)
        if (!settings.alarmEnabled) return

        val serviceIntent = Intent(context, AlarmSoundService::class.java).apply {
            putExtra(AlarmSoundService.EXTRA_TITLE, "یادآوری قرار ملاقات")
            putExtra(AlarmSoundService.EXTRA_TEXT, label)
            putExtra(AlarmSoundService.EXTRA_SOUND_URI, settings.alarmSoundUri)
            putExtra(AlarmSoundService.EXTRA_DURATION_SECONDS, settings.alarmDurationSeconds)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    companion object {
        const val EXTRA_APPOINTMENT_ID = "appointment_id"
        const val EXTRA_LABEL = "label"
    }
}
