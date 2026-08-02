package ir.appointment.voice.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single appointment extracted from a voice note.
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
