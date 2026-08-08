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
                {"title": string|null, "jalali_year": integer|null, "jalali_month": integer 1-12|null, "jalali_day": integer 1-31|null, "hour": integer 0-23|null, "minute": integer 0-59|null, "location": string|null, "person": string|null}

                TITLE — new, important:
                - "title" is a short (a few words) summary of WHAT needs to be done: "خوردن قرص فلان",
                  "پرداخت قسط وام", "تحویل کارها به شرکت", "عمل جراحی پلک", "زنگ زدن به دکتر حجتی",
                  "رفتن به کوه با دوستان دانشگاه". This is NOT the same as location/person — it's the actual
                  task/purpose. Almost every sentence should get a title; only leave it null if the sentence is
                  truly just a bare date/time with nothing describing what it's for.
                - Keep it as a short phrase, not a full repeated sentence — drop date/time words from it (those
                  already have their own fields) but keep the actual content/action.

                DATE RULES:
                - Resolve EVERY date expression to an absolute Jalali date using today's date above as the anchor —
                  relative words ("امروز"=+0 days, "امشب"=+0 days [tonight is still TODAY's date, just implies
                  evening], فردا=+1 day, پس‌فردا=+2 days, "N روز دیگر/بعد"=+N days (colloquial "دیگه" counts the
                  same as "دیگر"), "هفته‌ی دیگر/آینده" alone=+7 days),
                  ordinal/written day-of-month words (هفتم=7, دوازدهم=12, بیست و یکم=21, سی‌ام=30), numeric days
                  (digits or Persian digits), and weekday names alone (e.g. "سه‌شنبه" with no date said) resolved
                  to the NEXT occurrence of that weekday from today.
                - "<weekday> هفته آینده/دیگر" (e.g. "شنبه هفته‌ی آینده") means the occurrence of that weekday
                  AFTER the immediate upcoming one — skip one more week beyond the normal next-occurrence.
                - "آخر هفته" (end of week) means Iran's weekend, i.e. the nearest upcoming Friday (جمعه) — or
                  today, if today already is Friday.
                - "N ماه جاری" or "N ام ماه جاری" means day N of the CURRENT Jalali month/year (e.g. "۲۳ ماه
                  جاری" with today being mid-Mordad 1403 means 1403/5/23).
                - "آخر ماه" (end of month) means the LAST valid day of the current Jalali month (30 or 31, or
                  29/30 for Esfand depending on leap year — use your best judgement, doesn't need to be exact
                  to the day if uncertain about leap years, just use 29 or 30 for Esfand and 30/31 otherwise).
                - "امشب" (tonight) ALWAYS means today's date — never leave jalali_year/month/day null just
                  because the sentence says "امشب" instead of "امروز"; they resolve to the exact same date.
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
                - Colloquial Persian very often drops the word "ساعت" entirely and just says the hour +
                  fraction directly — treat these exactly the same as if "ساعت" were there:
                  "یازده و ربع" = 11:15, "نه و نیم" = 9:30, "یک و ربع" = 1:15, "هشت و نیم شب" = 20:30.

                SPEECH-TO-TEXT ARTIFACT CORRECTION — important:
                - The sentence you receive came from automatic speech recognition, which sometimes splits a
                  single Persian word into two fragments with a stray space, mishears a name phonetically, or
                  garbles a place name into something that isn't a real place at all. When a "location" or
                  "person" candidate looks garbled/implausible rather than a real word, output your
                  best-corrected version instead of parroting the raw fragments — use your own knowledge of
                  real Persian given names AND real Iranian cities/districts/neighborhoods to pick the most
                  phonetically-plausible real correction. If you don't recognize ANY real place/name close to
                  what was heard, keep the original text rather than guessing wildly.
                  Examples: "در مونگاه"/"در مانگاه" -> "درمانگاه" (a clinic, LOCATION). "عبول فضل" -> "ابوالفضل"
                  (a common given name, PERSON). "زهرین شهر" (not a real place) -> "رزین‌شهر" (a real district
                  in Karaj), because "زهرین‌شهر" doesn't exist but is phonetically almost identical to the real
                  "رزین‌شهر". Use context too: after "برم"/"رفتن به" -> likely a place; after "با" -> likely a
                  person.

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

                "یازده و ربع باید برم درمونگاه" ->
                {"jalali_year":null,"jalali_month":null,"jalali_day":null,"hour":11,"minute":15,"location":"درمانگاه","person":null}

                "نه و نیم با عبول فضل قرار دارم" ->
                {"jalali_year":null,"jalali_month":null,"jalali_day":null,"hour":9,"minute":30,"location":null,"person":"ابوالفضل"}

                "امشب ساعت ۹ با علی قرار دارم" (assume today is ${today.first}/${today.second}/${today.third}) ->
                a JSON with jalali_year/month/day set to EXACTLY today's date above (never null), "hour":9, "minute":0, "location":null, "person":"علی", "title":null

                "آخر هفته باید ساعت ۸ قرص فلان را بخورم" ->
                a JSON with the date resolved to the nearest upcoming Friday, "hour":8, "minute":0, "location":null, "person":null, "title":"خوردن قرص فلان"

                "۲۳ ماه جاری باید قسط وام بانک ملی را بپردازم" ->
                a JSON with jalali_day:23, jalali_month/year set to the CURRENT month/year from today's date above, "hour":null, "minute":null, "location":null, "person":null, "title":"پرداخت قسط وام بانک ملی"

                "پنج شنبه ساعت یازده باید برم شرکت برای تحویل کارها به آقای رضایی" ->
                a JSON with the date resolved to the next Thursday, "hour":11, "minute":0, "location":"شرکت", "person":"آقای رضایی", "title":"تحویل کارها"

                "شنبه هفته آینده یک عمل جراحی پلک دارم در رزین‌شهر" ->
                a JSON with the date resolved to the Saturday AFTER the immediate upcoming one (extra week), "hour":null, "minute":null, "location":"رزین‌شهر", "person":null, "title":"عمل جراحی پلک"

                "جمعه صبح ساعت ۶ میخوام برم کوه صفه با دوستان دانشگاه" ->
                a JSON with the date resolved to the next Friday, "hour":6, "minute":0, "location":"کوه صفه", "person":"دوستان دانشگاه", "title":"رفتن به کوه"

                "چهارشنبه ساعت ۶ عصر باید زنگ بزنم دکتر حجتی" ->
                a JSON with the date resolved to the next Wednesday, "hour":18, "minute":0, "location":null, "person":"دکتر حجتی", "title":"زنگ زدن به دکتر حجتی"
            """.trimIndent()

            val requestBody = JSONObject().apply {
                // llama-3.3-70b-versatile was deprecated by Groq (June 2026); gpt-oss-120b
                // is Groq's official recommended replacement — it also outperforms on
                // MMLU/GPQA benchmarks, is cheaper per token, and runs faster (MoE
                // architecture with far fewer active parameters per token).
                put("model", ModelInfo.EXTRACTION_MODEL_ID)
                put("temperature", 0)
                // gpt-oss-120b is a reasoning model; "low" keeps latency down since this
                // is a simple structured-extraction task, not a task that benefits from
                // deep reasoning.
                put("reasoning_effort", "low")
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
            var jy = fields.optIntOrNull("jalali_year")
            val jm = fields.optIntOrNull("jalali_month")
            val jd = fields.optIntOrNull("jalali_day")
            val hour = fields.optIntOrNull("hour")
            val minute = fields.optIntOrNull("minute")
            val location = fields.optStringOrNull("location")
            val person = fields.optStringOrNull("person")
            val title = fields.optStringOrNull("title")

            // People almost never state the year out loud ("دوازدهم مرداد" not
            // "دوازدهم مرداد ۱۴۰۳") — default to the current Jalali year whenever a
            // month+day were resolved but no year was given.
            if (jy == null && jm != null && jd != null) {
                jy = today.first
            }

            val weekday = if (jy != null && jm != null && jd != null) PersianCalendar.weekdayName(jy, jm, jd) else null
            val displayDate = if (jy != null && jm != null && jd != null) {
                "$jd ${PersianCalendar.jalaliMonthNames.getOrNull(jm - 1) ?: ""} $jy"
            } else null
            val displayTime = if (hour != null) String.format("%02d:%02d", hour, minute ?: 0) else null
            val sortTs = if (jy != null && jm != null && jd != null) PersianCalendar.toEpochMillis(jy, jm, jd, hour, minute) else null

            Result.success(
                ExtractedAppointment(
                    rawText = text,
                    title = title,
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

    /** Unlike optString(), correctly returns null (not the literal string "null")
     * when the JSON value is a real JSON null instead of a string. */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key) || !has(key)) return null
        val value = optString(key, "")
        return value.ifBlank { null }?.takeIf { it != "null" }
    }
}
