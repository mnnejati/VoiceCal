package ir.appointment.voice.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.appointment.voice.data.RecognitionMode
import ir.appointment.voice.ui.theme.AccentTeal
import ir.appointment.voice.ui.theme.TextSecondary

@Composable
fun SettingsDialog(
    currentMode: RecognitionMode,
    currentApiKey: String,
    currentAlarmSoundUri: String,
    currentAlarmDurationSeconds: Int,
    onPickAlarmSound: (current: String, onPicked: (String) -> Unit) -> Unit,
    onSave: (RecognitionMode, String, String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(currentMode) }
    var apiKey by remember { mutableStateOf(currentApiKey) }
    var alarmSoundUri by remember { mutableStateOf(currentAlarmSoundUri) }
    var alarmDurationText by remember { mutableStateOf(currentAlarmDurationSeconds.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تنظیمات", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("تشخیص گفتار", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                Spacer(Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { mode = RecognitionMode.ONLINE }
                        .padding(vertical = 6.dp)
                ) {
                    RadioButton(selected = mode == RecognitionMode.ONLINE, onClick = { mode = RecognitionMode.ONLINE })
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text("آنلاین (Groq — رایگان)", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("دقت بالا و سریع، کاملاً رایگان؛ نیاز به اینترنت و یک کلید API رایگان دارد.", fontSize = 11.5.sp, color = TextSecondary)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { mode = RecognitionMode.OFFLINE }
                        .padding(vertical = 6.dp)
                ) {
                    RadioButton(selected = mode == RecognitionMode.OFFLINE, onClick = { mode = RecognitionMode.OFFLINE })
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text("آفلاین (Vosk، روی خود گوشی)", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("بدون نیاز به اینترنت؛ نیاز به دانلود دستی فایل مدل فارسی.", fontSize = 11.5.sp, color = TextSecondary)
                    }
                }

                if (mode == RecognitionMode.ONLINE) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("کلید API از Groq") },
                        placeholder = { Text("gsk_...") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "کلید رایگان را از console.groq.com/keys بسازید (نیازی به کارت بانکی نیست). فقط روی همین گوشی و به‌صورت محلی ذخیره می‌شود.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }

                Spacer(Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text("آلارم یادآوری", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                Spacer(Modifier.height(6.dp))

                OutlinedButton(
                    onClick = {
                        onPickAlarmSound(alarmSoundUri) { picked -> alarmSoundUri = picked }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (alarmSoundUri.isBlank()) "انتخاب آهنگ آلارم (پیش‌فرض سیستم)" else "تغییر آهنگ آلارم")
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = alarmDurationText,
                    onValueChange = { alarmDurationText = it.filter(Char::isDigit).take(3) },
                    label = { Text("مدت زمان پخش آلارم (ثانیه)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text("بین ۳ تا ۱۲۰ ثانیه؛ پیش‌فرض ۱۵ ثانیه.", fontSize = 11.sp, color = TextSecondary)

                Spacer(Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text("درباره‌ی برنامه", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                Spacer(Modifier.height(6.dp))
                Text("دستیار قرار ملاقات", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text("نسخه ۱.۰", fontSize = 11.5.sp, color = TextSecondary)
                Spacer(Modifier.height(6.dp))
                Text("توسعه‌دهنده: M.Nejati", fontSize = 12.5.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val duration = alarmDurationText.toIntOrNull()?.coerceIn(3, 120) ?: 15
                onSave(mode, apiKey, alarmSoundUri, duration)
            }) {
                Text("ذخیره", color = AccentTeal, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}
