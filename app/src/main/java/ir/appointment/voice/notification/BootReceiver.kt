package ir.appointment.voice.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import ir.appointment.voice.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(appContext).appointmentDao()
                val now = System.currentTimeMillis()
                val all = dao.observeAllOnce()
                all.filter { it.sortTimestamp != null && it.sortTimestamp > now }
                    .forEach { appointment ->
                        val label = buildString {
                            append(appointment.displayDate ?: "")
                            if (!appointment.displayTime.isNullOrBlank()) append(" ساعت ${appointment.displayTime}")
                            if (!appointment.location.isNullOrBlank()) append(" - ${appointment.location}")
                            if (!appointment.personName.isNullOrBlank()) append(" - با ${appointment.personName}")
                        }.ifBlank { "قرار ملاقات" }

                        ReminderScheduler.schedule(
                            appContext,
                            appointment.id,
                            appointment.sortTimestamp!!,
                            label
                        )
                    }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
