package ir.appointment.voice.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
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
    onSave: (RecognitionMode, String) -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(currentMode) }
    var apiKey by remember { mutableStateOf(currentApiKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تنظیمات تشخیص گفتار", fontWeight = FontWeight.Bold) },
        text = {
            Column {
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(mode, apiKey) }) {
                Text("ذخیره", color = AccentTeal, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}
