package ir.appointment.voice.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single reminder item extracted from a voice note — either a
 * classic appointment (person + location) or a general task/reminder (e.g.
 * "قرص بخورم", "قسط وام را بپردازم") where [title] is the main content.
 *
 * [sortTimestamp] is a best-effort Gregorian epoch-millis value computed from the
 * extracted Jalali date/time so the list can be sorted from nearest to farthest.
 * If no usable date/time could be extracted, [sortTimestamp] is null and the row
 * is shown at the end of the list.
 */
@Entity(tableName = "appointments")
data class AppointmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Raw recognized text (Persian) from the voice input.
    val rawText: String,

    // A short summary of WHAT needs to be done — the main field for general
    // tasks/reminders ("قرص فلان را بخورم", "قسط وام را بپردازم"). Optional for
    // classic appointments where person/location already say enough.
    val title: String? = null,

    // Extracted fields (any of these may be null if not present in the speech).
    val personName: String?,
    val location: String?,

    val jalaliYear: Int?,
    val jalaliMonth: Int?,
    val jalaliDay: Int?,
    val weekdayName: String?,

    val hour: Int?,
    val minute: Int?,

    // Human readable Persian summary shown in the preview / list.
    val displayDate: String?,
    val displayTime: String?,

    // Absolute path to the saved audio file on device storage.
    val audioFilePath: String,

    // Best-effort Gregorian sort key (epoch millis). Null => unknown date, sorts last.
    val sortTimestamp: Long?,

    val createdAt: Long = System.currentTimeMillis()
)
