package ir.appointment.voice.voice

/** Result of extracting appointment info from a Persian utterance. */
data class ExtractedAppointment(
    val rawText: String,
    val personName: String?,
    val location: String?,
    val jalaliYear: Int?,
    val jalaliMonth: Int?,
    val jalaliDay: Int?,
    val weekdayName: String?,
    val hour: Int?,
    val minute: Int?,
    val displayDate: String?,
    val displayTime: String?,
    val sortTimestamp: Long?
)

/**
 * Rule-based extractor for Persian appointment speech.
 * Not a full NLU system, but covers common everyday phrasing:
 *  - relative days: امروز، فردا، پس‌فردا
 *  - weekday names: شنبه ... جمعه
 *  - explicit Jalali dates: "دوم مرداد" ، "12 مرداد 1403" ، "1403/5/12"
 *  - time: "ساعت 5"، "ساعت پنج و نیم"، "ساعت 17:30"، با صبح/ظهر/عصر/شب
 *  - location: بعد از کلمه‌ی "در"
 *  - person: بعد از کلمه‌ی "با"
 */
object PersianInfoExtractor {

    private val weekdays = listOf("شنبه", "یکشنبه", "دوشنبه", "سه شنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "پنج شنبه", "پنج‌شنبه", "جمعه")

    private val jalaliMonths = PersianCalendar.jalaliMonthNames

    private val wordNumbers = mapOf(
        "صفر" to 0, "یک" to 1, "دو" to 2, "سه" to 3, "چهار" to 4, "پنج" to 5,
        "شش" to 6, "هفت" to 7, "هشت" to 8, "نه" to 9, "ده" to 10,
        "یازده" to 11, "دوازده" to 12, "سیزده" to 13, "چهارده" to 14, "پانزده" to 15,
        "شانزده" to 16, "هفده" to 17, "هجده" to 18, "نوزده" to 19, "بیست" to 20,
        "بیست و یک" to 21, "بیست و دو" to 22, "بیست و سه" to 23, "بیست و چهار" to 24,
        "بیست و پنج" to 25, "بیست و شش" to 26, "بیست و هفت" to 27, "بیست و هشت" to 28,
        "بیست و نه" to 29, "سی" to 30, "سی و یک" to 31
    )

    fun extract(text: String): ExtractedAppointment {
        val normalized = normalizeDigits(text.trim())

        val person = extractAfterKeyword(normalized, listOf("با آقای", "با خانم", "با دکتر", "با"))
        val location = extractAfterKeyword(normalized, listOf("در محل", "در آدرس", "در"))

        val spokenWeekday = weekdays.firstOrNull { normalized.contains(it) }

        var jy: Int? = null
        var jm: Int? = null
        var jd: Int? = null
        var displayDate: String? = null

        // 1) Numeric date: 1403/5/12 or 1403-5-12
        val numericDateRegex = Regex("""(1[34]\d{2})[/\-](\d{1,2})[/\-](\d{1,2})""")
        val numericMatch = numericDateRegex.find(normalized)

        // 2) "12 مرداد 1403" or "12 مرداد"
        val monthNamePattern = jalaliMonths.joinToString("|")
        val dayMonthYearRegex = Regex("""(\d{1,2})\s*(?:ام)?\s*($monthNamePattern)(?:\s+(1[34]\d{2}))?""")
        val dayMonthMatch = dayMonthYearRegex.find(normalized)

        when {
            numericMatch != null -> {
                jy = numericMatch.groupValues[1].toIntOrNull()
                jm = numericMatch.groupValues[2].toIntOrNull()
                jd = numericMatch.groupValues[3].toIntOrNull()
            }
            dayMonthMatch != null -> {
                jd = dayMonthMatch.groupValues[1].toIntOrNull()
                jm = jalaliMonths.indexOf(dayMonthMatch.groupValues[2]) + 1
                jy = dayMonthMatch.groupValues[3].toIntOrNull()
            }
            normalized.contains("پس فردا") || normalized.contains("پس‌فردا") -> {
                val (y, m, d) = PersianCalendar.todayJalali()
                val shifted = shiftJalaliDay(y, m, d, 2)
                jy = shifted.first; jm = shifted.second; jd = shifted.third
                displayDate = "پس‌فردا"
            }
            normalized.contains("فردا") -> {
                val (y, m, d) = PersianCalendar.todayJalali()
                val shifted = shiftJalaliDay(y, m, d, 1)
                jy = shifted.first; jm = shifted.second; jd = shifted.third
                displayDate = "فردا"
            }
            normalized.contains("امروز") -> {
                val (y, m, d) = PersianCalendar.todayJalali()
                jy = y; jm = m; jd = d
                displayDate = "امروز"
            }
        }

        if (jy == null && jd != null) {
            // month/day known but year missing -> assume current jalali year
            jy = PersianCalendar.todayJalali().first
        }

        if (displayDate == null && jy != null && jm != null && jd != null) {
            displayDate = "$jd ${jalaliMonths.getOrNull((jm) - 1) ?: ""} $jy"
        }

        // Weekday must reflect the actual resolved date (never trust mis-heard speech
        // if we can compute it ourselves). Fall back to the spoken word only when no
        // date could be resolved at all.
        val weekday = if (jy != null && jm != null && jd != null) {
            PersianCalendar.weekdayName(jy, jm, jd) ?: spokenWeekday
        } else {
            spokenWeekday
        }

        // Time extraction: "ساعت 5"، "ساعت پنج و نیم"، "ساعت 17:30"
        var hour: Int? = null
        var minute: Int? = null
        var displayTime: String? = null

        val timeColonRegex = Regex("""ساعت\s*(\d{1,2})[:٫](\d{2})""")
        val timeColonMatch = timeColonRegex.find(normalized)

        val timeNumRegex = Regex("""ساعت\s*(\d{1,2})""")
        val timeNumMatch = timeNumRegex.find(normalized)

        val timeWordRegex = Regex("""ساعت\s*([\u0600-\u06FF]+(?:\s+و\s+[\u0600-\u06FF]+)?)""")
        val timeWordMatch = timeWordRegex.find(normalized)

        when {
            timeColonMatch != null -> {
                hour = timeColonMatch.groupValues[1].toIntOrNull()
                minute = timeColonMatch.groupValues[2].toIntOrNull()
            }
            timeNumMatch != null -> {
                hour = timeNumMatch.groupValues[1].toIntOrNull()
                minute = 0
            }
            timeWordMatch != null -> {
                val phrase = timeWordMatch.groupValues[1]
                val baseWord = phrase.split(" و ").first().trim()
                hour = wordNumbers[baseWord]
                minute = when {
                    phrase.contains("نیم") -> 30
                    phrase.contains("ربع") && phrase.contains("کم") -> -15 // handled below
                    phrase.contains("ربع") -> 15
                    else -> 0
                }
            }
        }

        if (hour != null) {
            // Adjust for بعد از ظهر / عصر / شب (PM) vs صبح (AM)
            val isPm = normalized.contains("بعد از ظهر") || normalized.contains("عصر") || normalized.contains("شب")
            if (isPm && hour in 1..11) hour += 12
            if (minute != null && minute!! < 0) {
                // "ربع کم" = quarter to -> reduce hour by 1, minute = 45
                hour = (hour!! - 1).let { if (it < 0) 23 else it }
                minute = 45
            }
            val mm = (minute ?: 0)
            displayTime = String.format("%02d:%02d", hour, mm)
        }

        val sortTs = if (jy != null && jm != null && jd != null) {
            PersianCalendar.toEpochMillis(jy, jm, jd, hour, minute)
        } else null

        return ExtractedAppointment(
            rawText = text.trim(),
            personName = person,
            location = location,
            jalaliYear = jy,
            jalaliMonth = jm,
            jalaliDay = jd,
            weekdayName = weekday,
            hour = hour,
            minute = minute,
            displayDate = displayDate,
            displayTime = displayTime,
            sortTimestamp = sortTs
        )
    }

    private fun shiftJalaliDay(y: Int, m: Int, d: Int, offsetDays: Int): Triple<Int, Int, Int> {
        val (gy, gm, gd) = PersianCalendar.jalaliToGregorian(y, m, d)
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(gy, gm - 1, gd)
        cal.add(java.util.Calendar.DAY_OF_MONTH, offsetDays)
        return PersianCalendar.gregorianToJalali(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun extractAfterKeyword(text: String, keywords: List<String>): String? {
        for (kw in keywords) {
            val idx = text.indexOf(kw)
            if (idx >= 0) {
                val after = text.substring(idx + kw.length).trim()
                if (after.isEmpty()) continue
                // Take up to 4 words, stop at common stop-words/keywords
                val stopWords = setOf("در", "با", "ساعت", "روز", "تاریخ", "فردا", "امروز", "پس‌فردا")
                val words = after.split(Regex("\\s+"))
                val collected = mutableListOf<String>()
                for (w in words) {
                    val clean = w.trim(',', '.', '،')
                    if (clean.isEmpty()) continue
                    if (stopWords.contains(clean)) break
                    collected.add(clean)
                    if (collected.size >= 4) break
                }
                if (collected.isNotEmpty()) return collected.joinToString(" ")
            }
        }
        return null
    }

    private fun normalizeDigits(input: String): String {
        val persianDigits = "۰۱۲۳۴۵۶۷۸۹"
        val arabicDigits = "٠١٢٣٤٥٦٧٨٩"
        val sb = StringBuilder()
        for (ch in input) {
            val pIdx = persianDigits.indexOf(ch)
            val aIdx = arabicDigits.indexOf(ch)
            when {
                pIdx >= 0 -> sb.append(pIdx)
                aIdx >= 0 -> sb.append(aIdx)
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
