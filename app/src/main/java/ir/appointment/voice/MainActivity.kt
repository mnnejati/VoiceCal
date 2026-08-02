package ir.appointment.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import ir.appointment.voice.notification.NotificationHelper
import ir.appointment.voice.ui.AppointmentListScreen
import ir.appointment.voice.ui.RecordScreen
import ir.appointment.voice.ui.theme.AppointmentVoiceTheme
import ir.appointment.voice.viewmodel.AppointmentViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AppointmentViewModel by viewModels()

    private var onRingtonePicked: ((String) -> Unit)? = null

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled reactively via hasMicPermission() check below */ }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* reminders simply won't show a notification if denied; recording still works */ }

    private val pickRingtoneLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        onRingtonePicked?.invoke(uri?.toString() ?: "")
    }

    private fun launchRingtonePicker(currentUri: String, onPicked: (String) -> Unit) {
        onRingtonePicked = onPicked
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            if (currentUri.isNotBlank()) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentUri))
            }
        }
        pickRingtoneLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NotificationHelper.ensureChannel(this)
        requestNotificationPermissionIfNeeded()

        setContent {
            AppointmentVoiceTheme {
                var screen by remember { mutableStateOf(Screen.RECORD) }
                var micGranted by remember { mutableStateOf(hasMicPermission()) }

                when (screen) {
                    Screen.RECORD -> RecordScreen(
                        viewModel = viewModel,
                        hasMicPermission = micGranted,
                        onRequestPermission = {
                            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                            micGranted = hasMicPermission()
                        },
                        onShowAppointments = { screen = Screen.LIST },
                        onPickAlarmSound = { current, onPicked -> launchRingtonePicker(current, onPicked) }
                    )
                    Screen.LIST -> {
                        BackHandler { screen = Screen.RECORD }
                        AppointmentListScreen(
                            viewModel = viewModel,
                            onBack = { screen = Screen.RECORD }
                        )
                    }
                }
            }
        }
    }

    /** Reminders need POST_NOTIFICATIONS on API 33+; ask once, up front, so the first
     * appointment reminder isn't silently swallowed. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private enum class Screen { RECORD, LIST }
}
