package ir.appointment.voice.voice

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uses a free Groq-hosted LLM (chat completions, OpenAI-compatible endpoint) to pull
 * structured appointment fields out of a Persian transcript. This is far more robust
 * than regex for the cases that trip up [PersianInfoExtractor]:
 *  - ordinal day words ("هفتم شهریور" = "the 7th of Shahrivar")
 *  - relative-date arithmetic ("دو روز دیگر" = "in two days")
 *  - correctly telling a place ("دندانپزشکی") apart from a person's name
 *
 * Falls back to [PersianInfoExtractor] (regex-based, fully offline) automatically
 * on any network/parsing failure, and is only used at all in ONLINE mode.
 */
class GroqAppointmentExtractor(private val apiKey: String) {

    suspend fun extract(text: String): Result<ExtractedAppointment> {
        return try {
            val today = PersianCalendar.todayJalali()
            val todayWeekday = PersianCalendar.weekdayName(today.first, today.second, today.third)
            val systemPrompt = """
                You are a precise information-extraction engine for Persian (Farsi) appointment voice notes.
                Today's Jalali (Persian solar/Hijri-Shamsi) date is: year=${today.first}, month=${today.second}, day=${today.third} (${todayWeekday}).

                TASK: read the single Persian sentence the user sends and output ONLY one JSON object — no
                markdown fences, no explanation, nothing before or after it — with exactly these keys:
                {"jalali_year": integer|null, "jalali_month": integer 1-12|null, "jalali_day": integer 1-31|null, "hour": integer 0-23|null, "minute": integer 0-59|null, "location": string|null, "person": string|null}

                DATE RULES:
                - Resolve EVERY date expression to an absolute Jalali date using today's date above as the anchor —
                  relative words (فردا=+1 day, پس‌فردا=+2 days, "N روز دیگر/بعد"=+N days, "هفته‌ی دیگر"=+7 days),
                  ordinal/written day-of-month words (هفتم=7, دوازدهم=12, بیست و یکم=21, سی‌ام=30), numeric days
                  (digits or Persian digits), and weekday names alone (e.g. "سه‌شنبه" with no date said) resolved
                  to the NEXT occurrence of that weekday from today.
                - If a month name is said without a day, or a day without a month, use whatever partial
                  information is available rather than leaving everything null.
                - The resolved date must NEVER be earlier than today (${today.first}/${today.second}/${today.third}).
                  People don't dictate appointments that already happened. If your first reading of the
                  sentence would place the date in the past, re-check — you likely misread an ordinal/weekday
                  and it almost certainly means the next future occurrence instead.

                TIME RULES:
                - Always output 24-hour "hour". "عصر" or "شب" with a 1-11 hour means add 12 (e.g. "۶ عصر" -> 18).
                  "صبح" or "ظهر" with 1-11 keeps it as-is (۶ صبح -> 6). Bare "ساعت ۱۷" is already 24-hour.
                - "و نیم" = 30 minutes, "ربع" = 15 minutes, "ربع به X" = (X-1):45.

                LOCATION vs PERSON — this is the most common mistake, be careful:
                - "location" = ONLY the bare place/business name (e.g. "دندانپزشکی", "کافه نادری", "دفتر شرکت",
                  "بیمارستان میلاد"). Strip any verb around it — "باید برم دندانپزشکی" -> location is just
                  "دندانپزشکی", never the full phrase "برم دندانپزشکی" or "باید برم دندانپزشکی".
                - "person" = ONLY a human name or title (e.g. "دکتر احمدی", "سارا", "آقای رضایی"). A profession
                  used as a place ("دندانپزشکی", "آرایشگاه") is a LOCATION, not a person, unless a proper name
                  immediately follows it (e.g. "دکتر احمدی" is a person).
                - Never put the same phrase in both fields. If genuinely only one of the two is mentioned, leave
                  the other null — do not guess a value for it.

                GENERAL:
                - Extract every field that IS mentioned, even if others are missing — partial results are
                  expected and normal, most sentences won't mention all four things (date, time, location, person).
                - Never invent a value for something not said in the sentence.

                EXAMPLES (input -> output JSON):
                "جلسه با آقای رضایی در دفتر ساعت 17:30 تاریخ 1403/5/12" ->
                {"jalali_year":1403,"jalali_month":5,"jalali_day":12,"hour":17,"minute":30,"location":"دفتر","person":"آقای رضایی"}

                "هفتم شهریور با دکتر احمدی قرار دارم" ->
                {"jalali_year":null,"jalali_month":6,"jalali_day":7,"hour":null,"minute":null,"location":null,"person":"دکتر احمدی"}

                "دو روز دیگر باید برم دندانپزشکی ساعت 6 عصر" (assume today is ${today.first}/${today.second}/${today.third}) ->
                a JSON with jalali_day/month/year computed as exactly two days after today, "hour":18, "minute":0, "location":"دندانپزشکی", "person":null

                "فردا ساعت ده صبح با علی کافه نادری" ->
                a JSON with the date resolved to tomorrow, "hour":10, "minute":0, "location":"کافه نادری", "person":"علی"

                "سه‌شنبه ساعت ۹ بیمارستان میلاد" ->
                a JSON with jalali date resolved to the next Tuesday from today, "hour":9, "minute":0, "location":"بیمارستان میلاد", "person":null
            """.trimIndent()

            val requestBody = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("temperature", 0)
                put("response_format", JSONObject().put("type", "json_object"))
                put(
                    "messages",
                    org.json.JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", systemPrompt))
                        put(JSONObject().put("role", "user").put("content", text))
                    }
                )
            }

            val url = URL("https://api.groq.com/openai/v1/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(requestBody.toString().toByteArray()) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                return Result.failure(Exception("خطای سرویس استخراج هوشمند (کد $code)"))
            }

            val content = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val fields = JSONObject(content)
            val jy = fields.optIntOrNull("jalali_year")
            val jm = fields.optIntOrNull("jalali_month")
            val jd = fields.optIntOrNull("jalali_day")
            val hour = fields.optIntOrNull("hour")
            val minute = fields.optIntOrNull("minute")
            val location = fields.optString("location", "").ifBlank { null }
            val person = fields.optString("person", "").ifBlank { null }

            val weekday = if (jy != null && jm != null && jd != null) PersianCalendar.weekdayName(jy, jm, jd) else null
            val displayDate = if (jy != null && jm != null && jd != null) {
                "$jd ${PersianCalendar.jalaliMonthNames.getOrNull(jm - 1) ?: ""} $jy"
            } else null
            val displayTime = if (hour != null) String.format("%02d:%02d", hour, minute ?: 0) else null
            val sortTs = if (jy != null && jm != null && jd != null) PersianCalendar.toEpochMillis(jy, jm, jd, hour, minute) else null

            Result.success(
                ExtractedAppointment(
                    rawText = text,
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
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)
}
