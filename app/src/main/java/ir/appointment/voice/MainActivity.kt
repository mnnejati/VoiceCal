package ir.appointment.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled reactively via hasMicPermission() check below */ }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* reminders simply won't show a notification if denied; recording still works */ }

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
                        onShowAppointments = { screen = Screen.LIST }
                    )
                    Screen.LIST -> AppointmentListScreen(
                        viewModel = viewModel,
                        onBack = { screen = Screen.RECORD }
                    )
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
