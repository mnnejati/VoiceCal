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
            val systemPrompt = """
                You extract appointment details from a single Persian sentence.
                Today's Jalali (Persian solar/Hijri-Shamsi) date is: year=${today.first}, month=${today.second}, day=${today.third}.
                Resolve ANY relative date expression (فردا, پس‌فردا, دو روز دیگر, هفته‌ی دیگر, etc.) and ANY ordinal or written-out day/month (هفتم, دوازدهم, بیست و یکم, etc.) into an absolute Jalali date using today's date above.
                Output ONLY a single JSON object, nothing else, no markdown fences, with exactly these keys:
                {"jalali_year": integer or null, "jalali_month": integer 1-12 or null, "jalali_day": integer 1-31 or null, "hour": integer 0-23 or null, "minute": integer 0-59 or null, "location": string or null, "person": string or null}
                Rules:
                - "location" must be ONLY the bare place name (e.g. "دندانپزشکی", "کافه نادری", "دفتر شرکت") — never a verb phrase like "بروم دندانپزشکی" and never a person's name.
                - "person" must be ONLY a person's name or title (e.g. "دکتر احمدی", "سارا") — never a place.
                - If a field truly isn't mentioned or can't be resolved, use null. Do not invent values.
                - hour/minute: if only "عصر" (afternoon/evening) or "شب" (night) is said with a 1-11 hour, convert to 24-hour time (add 12).
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
