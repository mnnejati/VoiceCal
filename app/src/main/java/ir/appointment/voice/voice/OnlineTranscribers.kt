package ir.appointment.voice.voice

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID

private fun HttpURLConnection.readResponseBody(code: Int): String =
    (if (code in 200..299) inputStream else errorStream)
        ?.bufferedReader()?.use { it.readText() } ?: ""

/**
 * Online speech-to-text via Google Gemini (generateContent with inline audio).
 *
 * Tries the models in [ModelInfo.GEMINI_MODEL_CANDIDATES] in order. A model that is unknown or
 * retired (404), temporarily overloaded (5xx) or whose free quota is used up (429 — free-tier
 * quotas are counted per model, so the next model may still have quota) just moves on to the
 * next candidate. Problems that no other model can fix (bad API key, unsupported region,
 * network failure) stop immediately with a clear message.
 */
class GeminiTranscriber(private val apiKey: String) : TranscriptionEngine {

    private sealed class CallResult {
        data class Text(val text: String) : CallResult()
        data class TryNext(val message: String) : CallResult()
        data class Fatal(val message: String) : CallResult()
    }

    override suspend fun transcribe(wavFilePath: String): Result<String> {
        val file = File(wavFilePath)
        if (!file.exists()) return Result.failure(IllegalStateException("فایل صوتی یافت نشد"))
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("کلید API جمینای تنظیم نشده است"))

        return try {
            val audioBase64 = Base64.getEncoder().encodeToString(file.readBytes())
            var lastMessage = "پاسخی از Gemini دریافت نشد."
            for (model in ModelInfo.GEMINI_MODEL_CANDIDATES) {
                when (val result = callModel(model, audioBase64)) {
                    is CallResult.Text -> return Result.success(TranscriptRepair.repair(result.text))
                    is CallResult.TryNext -> lastMessage = result.message
                    is CallResult.Fatal -> return Result.failure(Exception(result.message))
                }
            }
            Result.failure(Exception(lastMessage))
        } catch (e: Exception) {
            Result.failure(Exception("خطا در ارتباط با Gemini: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun callModel(model: String, audioBase64: String): CallResult {
        val requestBody = JSONObject().apply {
            put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray()
                    .put(JSONObject().put("text", PROMPT))
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", "audio/wav")
                        .put("data", audioBase64)))
                )
            ))
            put("generationConfig", JSONObject().put("temperature", 0))
        }

        val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(requestBody.toString().toByteArray()) }

        val code = conn.responseCode
        val body = conn.readResponseBody(code)

        if (code in 200..299) return parseSuccess(body)

        return when {
            body.contains("API_KEY_INVALID") || body.contains("API key not valid") ->
                CallResult.Fatal("کلید API جمینای نامعتبر است. آن را در تنظیمات دوباره بررسی کنید.")
            body.contains("location is not supported", ignoreCase = true) ->
                CallResult.Fatal("Gemini از موقعیت جغرافیایی شما پشتیبانی نمی‌کند (فیلترشکن لازم است).")
            code == 401 || code == 403 ->
                CallResult.Fatal("دسترسی به Gemini رد شد (کلید نامعتبر یا بدون مجوز).")
            code == 404 -> CallResult.TryNext("مدل «$model» در Gemini یافت نشد (احتمالاً منسوخ شده).")
            code == 429 -> CallResult.TryNext("سهمیه‌ی رایگان Gemini برای امروز تمام شده است.")
            code >= 500 -> CallResult.TryNext("سرور Gemini موقتاً در دسترس نیست (کد $code).")
            else -> CallResult.TryNext("خطای Gemini (کد $code).")
        }
    }

    private fun parseSuccess(body: String): CallResult {
        val json = JSONObject(body)
        val blockReason = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return if (blockReason.isNotBlank()) {
                CallResult.Fatal("Gemini این صدا را پردازش نکرد ($blockReason).")
            } else {
                CallResult.Text("") // nothing recognized -> handled as "no speech" by the caller
            }
        }
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.optBoolean("thought", false)) continue
                    append(part.optString("text", ""))
                }
            }
        }.trim()
        return CallResult.Text(if (text == EMPTY_MARKER) "" else text)
    }

    private companion object {
        const val EMPTY_MARKER = "[EMPTY]"
        val PROMPT = """
            Transcribe this audio recording exactly as spoken. The language is Persian (Farsi), spoken in a casual, colloquial style. Write the transcript in Persian script.
            Rules:
            - Output ONLY the transcript text: no introduction, no translation, no explanations, no timestamps, no quotation marks.
            - Do not correct grammar and do not add words that were not said.
            - If the recording contains no intelligible speech (only silence or noise), output exactly: $EMPTY_MARKER
        """.trimIndent()
    }
}

/**
 * Online speech-to-text via ElevenLabs Scribe (multipart upload to /v1/speech-to-text).
 */
class ElevenLabsTranscriber(private val apiKey: String) : TranscriptionEngine {

    override suspend fun transcribe(wavFilePath: String): Result<String> {
        val file = File(wavFilePath)
        if (!file.exists()) return Result.failure(IllegalStateException("فایل صوتی یافت نشد"))
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("کلید API ElevenLabs تنظیم نشده است"))

        return try {
            val boundary = "----VoiceCalBoundary${UUID.randomUUID()}"
            val conn = (URL("https://api.elevenlabs.io/v1/speech-to-text")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("xi-api-key", apiKey)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                    out.write("$value\r\n".toByteArray())
                }
                field("model_id", ModelInfo.ELEVENLABS_MODEL_ID)
                field("language_code", "fas") // Persian
                field("tag_audio_events", "false") // no "(laughter)" / "(music)" tags inside the text

                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n".toByteArray())
                out.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
                FileInputStream(file).use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }

            val code = conn.responseCode
            val body = conn.readResponseBody(code)

            if (code in 200..299) {
                val text = JSONObject(body).optString("text", "").trim()
                Result.success(TranscriptRepair.repair(text))
            } else {
                Result.failure(Exception(friendlyError(code, body)))
            }
        } catch (e: Exception) {
            Result.failure(Exception("خطا در ارتباط با ElevenLabs: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun friendlyError(code: Int, body: String): String = when {
        body.contains("quota_exceeded") ->
            "سهمیه‌ی رایگان ماهانه‌ی ElevenLabs تمام شده است."
        body.contains("unusual_activity") ->
            "ElevenLabs استفاده‌ی رایگان را به‌دلیل «فعالیت غیرعادی» مسدود کرده است (معمولاً هنگام استفاده از VPN)."
        code == 401 || code == 403 ->
            "کلید API ElevenLabs نامعتبر است. آن را در تنظیمات دوباره بررسی کنید."
        code == 429 -> "تعداد درخواست‌های ElevenLabs زیاد است؛ کمی بعد دوباره امتحان کنید."
        code >= 500 -> "سرور ElevenLabs موقتاً در دسترس نیست. بعداً دوباره امتحان کنید."
        else -> "خطای ElevenLabs (کد $code)."
    }
}
