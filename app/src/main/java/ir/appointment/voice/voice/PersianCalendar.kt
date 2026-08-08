package ir.appointment.voice.voice

import java.util.Calendar
import java.util.TimeZone

/**
 * Minimal Jalali (Persian solar) -> Gregorian date converter.
 * Standard well-known algorithm, no external library needed.
 */
object PersianCalendar {

    val jalaliMonthNames = listOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    private val weekdayNamesBySaturdayFirst = listOf(
        "شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه"
    )

    /** Reliable weekday name computed from the actual date, independent of what was said in speech. */
    fun weekdayName(jy: Int, jm: Int, jd: Int): String? {
        return try {
            val (gy, gm, gd) = jalaliToGregorian(jy, jm, jd)
            val cal = Calendar.getInstance(TimeZone.getDefault())
            cal.clear()
            cal.set(gy, gm - 1, gd)
            // Calendar.DAY_OF_WEEK: SUNDAY=1 ... SATURDAY=7. Persian week starts Saturday,
            // so SATURDAY(7)->0, SUNDAY(1)->1, MONDAY(2)->2 ... FRIDAY(6)->6.
            val idx = cal.get(Calendar.DAY_OF_WEEK) % 7
            weekdayNamesBySaturdayFirst[idx]
        } catch (e: Exception) {
            null
        }
    }

    /** Returns Triple(gregorianYear, gregorianMonth[1-12], gregorianDay). */
    fun jalaliToGregorian(jy: Int, jm: Int, jd: Int): Triple<Int, Int, Int> {
        var jy2 = jy
        jy2 += 1595
        var days = -355668 + (365 * jy2) + ((jy2 / 33) * 8) + (((jy2 % 33) + 3) / 4) + jd +
            if (jm < 7) (jm - 1) * 31 else ((jm - 7) * 30) + 186

        var gy = 400 * (days / 146097)
        days %= 146097
        if (days > 36524) {
            gy += 100 * (--days / 36524)
            days %= 36524
            if (days >= 365) days++
        }
        gy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            gy += (days - 1) / 365
            days = (days - 1) % 365
        }

        var gd = days + 1
        val gregorianMonthDays = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        val leap = (gy % 4 == 0 && gy % 100 != 0) || gy % 400 == 0
        if (leap) gregorianMonthDays[1] = 29

        var gm = 0
        for (i in 0..11) {
            if (gd <= gregorianMonthDays[i]) {
                gm = i + 1
                break
            }
            gd -= gregorianMonthDays[i]
        }
        return Triple(gy, gm, gd)
    }

    /**
     * Converts a Jalali date + optional time to epoch millis (device default timezone).
     * Returns null if conversion fails.
     */
    fun toEpochMillis(jy: Int, jm: Int, jd: Int, hour: Int?, minute: Int?): Long? {
        return try {
            val (gy, gm, gd) = jalaliToGregorian(jy, jm, jd)
            val cal = Calendar.getInstance(TimeZone.getDefault())
            cal.clear()
            cal.set(gy, gm - 1, gd, hour ?: 9, minute ?: 0, 0)
            cal.timeInMillis
        } catch (e: Exception) {
            null
        }
    }

    /** Returns Triple(jy, jm, jd) for "today" in the device's current date. */
    fun todayJalali(): Triple<Int, Int, Int> {
        val cal = Calendar.getInstance()
        return gregorianToJalali(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    /** Last valid day-of-month for a given Jalali year/month (handles Esfand's 29 vs 30 days). */
    fun lastDayOfMonth(jy: Int, jm: Int): Int {
        if (jm in 1..6) return 31
        if (jm in 7..11) return 30
        // Esfand (month 12): round-trip day 30 through Gregorian and back — if it
        // survives as month 12 day 30, this Jalali year has a 30-day Esfand;
        // otherwise it rolled into next year's Farvardin, meaning Esfand has 29.
        return try {
            val (gy, gm, gd) = jalaliToGregorian(jy, 12, 30)
            val (backJy, backJm, backJd) = gregorianToJalali(gy, gm, gd)
            if (backJy == jy && backJm == 12 && backJd == 30) 30 else 29
        } catch (e: Exception) {
            29
        }
    }

    fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gDaysInMonth = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        val leap = (gy % 4 == 0 && gy % 100 != 0) || gy % 400 == 0
        if (leap) gDaysInMonth[1] = 29

        var gy2 = gy - 1600
        val gm2 = gm - 1
        val gd2 = gd - 1

        var gDayNo = 365 * gy2 + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400
        for (i in 0 until gm2) gDayNo += gDaysInMonth[i]
        gDayNo += gd2

        var jDayNo = gDayNo - 79

        val jNp = jDayNo / 12053
        jDayNo %= 12053

        var jy = 979 + 33 * jNp + 4 * (jDayNo / 1461)
        jDayNo %= 1461

        if (jDayNo >= 366) {
            jy += (jDayNo - 1) / 365
            jDayNo = (jDayNo - 1) % 365
        }

        val jDaysInMonth = intArrayOf(31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 30, 29)
        var jm = 0
        var jd = 0
        for (i in 0..11) {
            if (jDayNo < jDaysInMonth[i]) {
                jm = i + 1
                jd = jDayNo + 1
                break
            }
            jDayNo -= jDaysInMonth[i]
        }
        if (jm == 0) {
            // Falls through only for the 366th day of a leap Jalali year, which
            // the fixed 365-day month table above has no slot for — that day is
            // Esfand 30.
            jm = 12
            jd = 30
        }
        return Triple(jy, jm, jd)
    }
}
