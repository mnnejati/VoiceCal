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

    private val weekdayIndex = mapOf(
        "شنبه" to 0, "یکشنبه" to 1, "دوشنبه" to 2, "سه شنبه" to 3, "سه‌شنبه" to 3,
        "چهارشنبه" to 4, "پنجشنبه" to 5, "پنج شنبه" to 5, "پنج‌شنبه" to 5, "جمعه" to 6
    )

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

    // Ordinal day-of-month words: "هفتم شهریور" (the 7th of Shahrivar), "دوازدهم مرداد", etc.
    private val ordinalDayNumbers = mapOf(
        "یکم" to 1, "اول" to 1, "دوم" to 2, "سوم" to 3, "چهارم" to 4, "پنجم" to 5,
        "ششم" to 6, "هفتم" to 7, "هشتم" to 8, "نهم" to 9, "دهم" to 10,
        "یازدهم" to 11, "دوازدهم" to 12, "سیزدهم" to 13, "چهاردهم" to 14, "پانزدهم" to 15,
        "شانزدهم" to 16, "هفدهم" to 17, "هجدهم" to 18, "نوزدهم" to 19, "بیستم" to 20,
        "بیست و یکم" to 21, "بیست و دوم" to 22, "بیست و سوم" to 23, "بیست و چهارم" to 24,
        "بیست و پنجم" to 25, "بیست و ششم" to 26, "بیست و هفتم" to 27, "بیست و هشتم" to 28,
        "بیست و نهم" to 29, "سی ام" to 30, "سی‌ام" to 30, "سی و یکم" to 31
    )

    fun extract(text: String): ExtractedAppointment {
        val normalized = normalizeDigits(text.trim())

        val person = extractAfterKeyword(normalized, listOf("با آقای", "با خانم", "با دکتر", "با"))
            ?: extractPersonByTitle(normalized)
        val location = extractAfterKeyword(normalized, listOf("در محل", "در آدرس", "در"))
            ?: extractLocationAfterGoVerb(normalized)

        // Longer names first — "شنبه" is a suffix of "پنجشنبه"/"یکشنبه"/"دوشنبه" etc,
        // so without this ordering the short form would incorrectly match first.
        val spokenWeekday = weekdays.sortedByDescending { it.length }.firstOrNull { normalized.contains(it) }

        var jy: Int? = null
        var jm: Int? = null
        var jd: Int? = null
        var displayDate: String? = null

        // 1) Numeric date: 1403/5/12 or 1403-5-12
        val numericDateRegex = Regex("""(1[34]\d{2})[/\-](\d{1,2})[/\-](\d{1,2})""")
        val numericMatch = numericDateRegex.find(normalized)

        // 2) "12 مرداد 1403" or "12 مرداد" (numeric day)
        val monthNamePattern = jalaliMonths.joinToString("|")
        val dayMonthYearRegex = Regex("""(\d{1,2})\s*(?:ام)?\s*($monthNamePattern)(?:\s+(1[34]\d{2}))?""")
        val dayMonthMatch = dayMonthYearRegex.find(normalized)

        // 2b) "هفتم شهریور" / "دوازدهم مرداد" (ordinal-word day, no digits)
        val ordinalDayPattern = ordinalDayNumbers.keys.sortedByDescending { it.length }.joinToString("|")
        val ordinalDayMonthRegex = Regex("""($ordinalDayPattern)\s+($monthNamePattern)(?:\s+(1[34]\d{2}))?""")
        val ordinalDayMonthMatch = ordinalDayMonthRegex.find(normalized)

        // 2c) "N روز دیگر" / "N روز بعد" (relative day offset, digit or word count)
        val relativeDayDigitRegex = Regex("""(\d{1,2})\s*روز\s*(دیگر|دیگه|بعد)""")
        val relativeDayWordPattern = wordNumbers.keys.sortedByDescending { it.length }.joinToString("|")
        val relativeDayWordRegex = Regex("""($relativeDayWordPattern)\s*روز\s*(دیگر|دیگه|بعد)""")
        val relativeDayDigitMatch = relativeDayDigitRegex.find(normalized)
        val relativeDayWordMatch = relativeDayWordRegex.find(normalized)

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
            ordinalDayMonthMatch != null -> {
                jd = ordinalDayNumbers[ordinalDayMonthMatch.groupValues[1]]
                jm = jalaliMonths.indexOf(ordinalDayMonthMatch.groupValues[2]) + 1
                jy = ordinalDayMonthMatch.groupValues[3].toIntOrNull()
            }
            relativeDayDigitMatch != null -> {
                val offset = relativeDayDigitMatch.groupValues[1].toIntOrNull() ?: 0
                val (y, m, d) = PersianCalendar.todayJalali()
                val shifted = shiftJalaliDay(y, m, d, offset)
                jy = shifted.first; jm = shifted.second; jd = shifted.third
            }
            relativeDayWordMatch != null -> {
                val offset = wordNumbers[relativeDayWordMatch.groupValues[1]] ?: 0
                val (y, m, d) = PersianCalendar.todayJalali()
                val shifted = shiftJalaliDay(y, m, d, offset)
                jy = shifted.first; jm = shifted.second; jd = shifted.third
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
            normalized.contains("امروز") || normalized.contains("امشب") -> {
                val (y, m, d) = PersianCalendar.todayJalali()
                jy = y; jm = m; jd = d
                displayDate = if (normalized.contains("امشب")) "امشب" else "امروز"
            }
            spokenWeekday != null -> {
                val targetIdx = weekdayIndex[spokenWeekday]
                if (targetIdx != null) {
                    val (ty, tm, td) = PersianCalendar.todayJalali()
                    val todayName = PersianCalendar.weekdayName(ty, tm, td)
                    val todayIdx = weekdayIndex[todayName] ?: 0
                    var offset = (targetIdx - todayIdx + 7) % 7
                    if (offset == 0) offset = 7 // "سه‌شنبه" alone means the upcoming one, not today
                    val shifted = shiftJalaliDay(ty, tm, td, offset)
                    jy = shifted.first; jm = shifted.second; jd = shifted.third
                }
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

        // Time extraction: "ساعت 5"، "ساعت پنج و نیم"، "ساعت 17:30"، یا محاوره‌ای بدون
        // گفتنِ «ساعت» مثل "یازده و ربع"، "نه و نیم"، "یک و ربع"
        var hour: Int? = null
        var minute: Int? = null
        var displayTime: String? = null

        val timeColonRegex = Regex("""ساعت\s*(\d{1,2})[:٫](\d{2})""")
        val timeColonMatch = timeColonRegex.find(normalized)

        val timeNumRegex = Regex("""ساعت\s*(\d{1,2})""")
        val timeNumMatch = timeNumRegex.find(normalized)

        val timeWordRegex = Regex("""ساعت\s*([\u0600-\u06FF]+(?:\s+و\s+[\u0600-\u06FF]+)?)""")
        val timeWordMatch = timeWordRegex.find(normalized)

        // Fallback for bare colloquial phrasing with no "ساعت" at all — only matches
        // when a recognized hour-word is directly followed by "و" + a fraction word
        // (نیم/ربع/...), which keeps false positives low (a random "دو و سه" won't
        // match since "سه" isn't a fraction word).
        val hourWordPattern = wordNumbers.keys
            .filter { (wordNumbers[it] ?: -1) in 1..12 }
            .sortedByDescending { it.length }
            .joinToString("|")
        val bareTimeWordRegex = Regex("""(?<!ساعت\s)($hourWordPattern)\s+و\s+(نیم|ربع\s+کم|کم\s+ربع|ربع)""")
        val bareTimeWordMatch = bareTimeWordRegex.find(normalized)

        when {
            timeColonMatch != null -> {
                hour = timeColonMatch.groupValues[1].toIntOrNull()
                minute = timeColonMatch.groupValues[2].toIntOrNull()
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
            bareTimeWordMatch != null -> {
                hour = wordNumbers[bareTimeWordMatch.groupValues[1]]
                val fraction = bareTimeWordMatch.groupValues[2]
                minute = when {
                    fraction.contains("نیم") -> 30
                    fraction.contains("کم") -> -15 // handled below
                    else -> 15 // bare "ربع"
                }
            }
            timeNumMatch != null -> {
                hour = timeNumMatch.groupValues[1].toIntOrNull()
                minute = 0
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

    private fun extractAfterKeyword(text: String, keywords: List<String>, maxWords: Int = 2): String? {
        for (kw in keywords) {
            var searchFrom = 0
            while (true) {
                val idx = text.indexOf(kw, searchFrom)
                if (idx < 0) break

                // Require real word boundaries so "با" doesn't match inside "باید", etc.
                val precededByBoundary = idx == 0 || text[idx - 1].isWhitespace()
                val followedByBoundary = idx + kw.length >= text.length || text[idx + kw.length].isWhitespace()
                if (precededByBoundary && followedByBoundary) {
                    val after = text.substring(idx + kw.length).trim()
                    if (after.isNotEmpty()) {
                        val stopWords = setOf(
                            "در", "با", "ساعت", "روز", "تاریخ", "فردا", "امروز", "پس‌فردا",
                            "باید", "برم", "بروم", "میرم", "می‌روم", "قراره", "قرار",
                            "دارم", "داره", "دارد", "دارند", "دارید", "داریم", "نوبت"
                        )
                        val words = after.split(Regex("\\s+"))
                        val collected = mutableListOf<String>()
                        for (w in words) {
                            val clean = w.trim(',', '.', '،')
                            if (clean.isEmpty()) continue
                            if (stopWords.contains(clean)) break
                            collected.add(clean)
                            if (collected.size >= maxWords) break
                        }
                        if (collected.isNotEmpty()) return collected.joinToString(" ")
                    }
                }
                searchFrom = idx + kw.length
            }
        }
        return null
    }

    /**
     * Fallback for location when no "در" keyword is present, e.g. "باید بروم دندانپزشکی":
     * captures 1-2 words right after a "go to" verb, stopping before time/date words.
     */
    /**
     * Fallback for a person mentioned by title without the "با" preposition, e.g.
     * "نوبت دکتر احمدی دارم" (no "با" at all — "با" extraction alone can't catch this).
     * A title word (دکتر/آقای/خانم/استاد/مهندس) is a strong standalone signal of a
     * person's name, wherever it appears in the sentence.
     */
    private fun extractPersonByTitle(text: String): String? {
        val titles = listOf("دکتر", "آقای", "خانم", "استاد", "مهندس")
        for (title in titles) {
            val name = extractAfterKeyword(text, listOf(title), maxWords = 2)
            if (name != null) return "$title $name"
        }
        return null
    }

    private fun extractLocationAfterGoVerb(text: String): String? {
        val goVerbs = listOf("می‌روم", "میرم", "برم", "بروم", "می‌رم")
        return extractAfterKeyword(text, goVerbs, maxWords = 2)
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
