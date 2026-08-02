package ir.appointment.voice.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appointmentId = intent.getLongExtra(EXTRA_APPOINTMENT_ID, -1L)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "قرار ملاقات"
        if (appointmentId < 0) return

        NotificationHelper.show(
            context = context,
            appointmentId = appointmentId,
            title = "یادآوری قرار ملاقات",
            text = label
        )
    }

    companion object {
        const val EXTRA_APPOINTMENT_ID = "appointment_id"
        const val EXTRA_LABEL = "label"
    }
}
