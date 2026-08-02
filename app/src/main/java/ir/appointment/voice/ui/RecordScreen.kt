package ir.appointment.voice.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.appointment.voice.data.RecognitionMode
import ir.appointment.voice.ui.components.PulsingMicIcon
import ir.appointment.voice.ui.theme.AccentTeal
import ir.appointment.voice.ui.theme.DangerRed
import ir.appointment.voice.ui.theme.DeepIndigo
import ir.appointment.voice.ui.theme.VividPurple
import ir.appointment.voice.viewmodel.AppointmentViewModel
import ir.appointment.voice.viewmodel.RecordingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RecordScreen(
    viewModel: AppointmentViewModel,
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit,
    onShowAppointments: () -> Unit,
    onPickAlarmSound: (current: String, onPicked: (String) -> Unit) -> Unit
) {
    val recordingState by viewModel.recordingState.collectAsState()
    val preview by viewModel.pendingPreview.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()
    val recognitionMode by viewModel.recognitionMode.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val alarmSoundUri by viewModel.alarmSoundUri.collectAsState()
    val alarmDurationSeconds by viewModel.alarmDurationSeconds.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(userMessage) {
        val msg = userMessage ?: return@LaunchedEffect
        val job = launch { snackbarHostState.showSnackbar(msg.text) }
        delay(msg.durationMillis)
        snackbarHostState.currentSnackbarData?.dismiss()
        job.join()
        viewModel.consumeUserMessage()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeepIndigo, VividPurple)))
    ) {
        IconButton(
            onClick = { showSettings = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 8.dp)
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "تنظیمات", tint = Color.White)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 40.dp)) {
                Text(
                    text = "دستیار قرار ملاقات",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "دکمه‌ی زیر را نگه دارید و قرار ملاقاتتان را بگویید",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (recognitionMode == RecognitionMode.ONLINE) "حالت: آنلاین (Groq)" else "حالت: آفلاین (Vosk)",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PulsingMicIcon(active = recordingState == RecordingState.RECORDING)

                Spacer(Modifier.height(20.dp))

                when (recordingState) {
                    RecordingState.RECORDING -> {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = "در حال ضبط... برای پایان، انگشت را بردارید",
                                modifier = Modifier.padding(16.dp),
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp
                            )
                        }
                    }
                    RecordingState.TRANSCRIBING -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (recognitionMode == RecognitionMode.ONLINE) "در حال ارسال و تبدیل گفتار به متن..." else "در حال تشخیص گفتار آفلاین...",
                                color = Color.White
                            )
                        }
                    }
                    RecordingState.IDLE -> {}
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val buttonColor by animateColorAsState(
                    targetValue = if (recordingState == RecordingState.RECORDING) DangerRed else AccentTeal,
                    label = "record_button_color"
                )
                val busy = recordingState == RecordingState.TRANSCRIBING

                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(64.dp)
                        .background(if (busy) Color.Gray else buttonColor, RoundedCornerShape(32.dp))
                        .pointerInput(hasMicPermission, busy) {
                            detectTapGestures(
                                onPress = {
                                    if (busy) return@detectTapGestures
                                    if (!hasMicPermission) {
                                        onRequestPermission()
                                        return@detectTapGestures
                                    }
                                    viewModel.startRecording()
                                    try {
                                        awaitRelease()
                                    } finally {
                                        viewModel.stopRecordingAndProcess()
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (recordingState == RecordingState.RECORDING) "Recording... Release" else "Start Talk",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(16.dp))

                OutlinedButton(
                    onClick = onShowAppointments,
                    modifier = Modifier.fillMaxWidth(0.8f).height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("نمایش قرار ملاقات‌ها", fontSize = 16.sp)
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "Developed by M.Nejati",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 10.5.sp
                )

                Spacer(Modifier.height(14.dp))
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }

    preview?.let { pending ->
        AppointmentPreviewDialog(
            preview = pending,
            onConfirm = { edited -> viewModel.confirmPreview(edited) },
            onDiscard = { viewModel.discardPreview() }
        )
    }

    if (showSettings) {
        SettingsDialog(
            currentMode = recognitionMode,
            currentApiKey = apiKey,
            currentAlarmSoundUri = alarmSoundUri,
            currentAlarmDurationSeconds = alarmDurationSeconds,
            onPickAlarmSound = onPickAlarmSound,
            onSave = { mode, key, soundUri, durationSeconds ->
                viewModel.updateSettings(mode, key)
                viewModel.updateAlarmSettings(soundUri, durationSeconds)
                showSettings = false
            },
            onDismiss = { showSettings = false }
        )
    }
}
