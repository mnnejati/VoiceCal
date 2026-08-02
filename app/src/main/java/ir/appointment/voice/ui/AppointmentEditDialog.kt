package ir.appointment.voice.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.appointment.voice.data.AppointmentEntity
import ir.appointment.voice.ui.theme.AccentTeal
import ir.appointment.voice.ui.theme.TextSecondary
import ir.appointment.voice.voice.PersianCalendar

/** Lets the user correct any field of an already-saved appointment. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentEditDialog(
    appointment: AppointmentEntity,
    onSave: (AppointmentEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var day by remember { mutableStateOf(appointment.jalaliDay?.toString() ?: "") }
    var monthIndex by remember { mutableStateOf(appointment.jalaliMonth?.let { it - 1 } ?: -1) }
    var year by remember { mutableStateOf(appointment.jalaliYear?.toString() ?: "") }
    var hour by remember { mutableStateOf(appointment.hour?.toString() ?: "") }
    var minute by remember { mutableStateOf(appointment.minute?.toString() ?: "") }
    var location by remember { mutableStateOf(appointment.location ?: "") }
    var person by remember { mutableStateOf(appointment.personName ?: "") }
    var monthMenuExpanded by remember { mutableStateOf(false) }

    fun buildUpdated(): AppointmentEntity {
        val jd = day.trim().toIntOrNull()
        val jm = if (monthIndex in 0..11) monthIndex + 1 else null
        val jy = year.trim().toIntOrNull()
        val h = hour.trim().toIntOrNull()
        val m = minute.trim().toIntOrNull()

        val weekday = if (jy != null && jm != null && jd != null) PersianCalendar.weekdayName(jy, jm, jd) else null
        val displayDate = if (jy != null && jm != null && jd != null) {
            "$jd ${PersianCalendar.jalaliMonthNames.getOrNull(jm - 1) ?: ""} $jy"
        } else null
        val displayTime = if (h != null) String.format("%02d:%02d", h, m ?: 0) else null
        val sortTs = if (jy != null && jm != null && jd != null) PersianCalendar.toEpochMillis(jy, jm, jd, h, m) else null

        return appointment.copy(
            personName = person.trim().ifBlank { null },
            location = location.trim().ifBlank { null },
            jalaliYear = jy,
            jalaliMonth = jm,
            jalaliDay = jd,
            weekdayName = weekday,
            hour = h,
            minute = m,
            displayDate = displayDate,
            displayTime = displayTime,
            sortTimestamp = sortTs
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ویرایش قرار ملاقات", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("تاریخ", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = day,
                        onValueChange = { day = it.filter(Char::isDigit).take(2) },
                        label = { Text("روز") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )

                    ExposedDropdownMenuBox(
                        expanded = monthMenuExpanded,
                        onExpandedChange = { monthMenuExpanded = it },
                        modifier = Modifier.weight(1.6f)
                    ) {
                        OutlinedTextField(
                            value = PersianCalendar.jalaliMonthNames.getOrNull(monthIndex) ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("ماه") },
                            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = monthMenuExpanded, onDismissRequest = { monthMenuExpanded = false }) {
                            PersianCalendar.jalaliMonthNames.forEachIndexed { idx, name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = { monthIndex = idx; monthMenuExpanded = false }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = year,
                        onValueChange = { year = it.filter(Char::isDigit).take(4) },
                        label = { Text("سال") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1.2f)
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text("ساعت", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hour,
                        onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                        label = { Text("ساعت") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minute,
                        onValueChange = { minute = it.filter(Char::isDigit).take(2) },
                        label = { Text("دقیقه") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("محل") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = person,
                    onValueChange = { person = it },
                    label = { Text("شخص") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(buildUpdated()) }) {
                Text("ذخیره تغییرات", color = AccentTeal, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("انصراف", color = androidx.compose.ui.graphics.Color.Gray)
            }
        }
    )
}
