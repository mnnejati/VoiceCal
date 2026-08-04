package ir.appointment.voice.voice

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Transcribes an already-recorded WAV file (post-hoc, not live) into Persian text. */
interface TranscriptionEngine {
    suspend fun transcribe(wavFilePath: String): Result<String>
}

/**
 * Fixes common speech-recognition word-splitting mistakes seen in casual/colloquial
 * Persian (e.g. "درمونگاه" heard as "در" + "مونگاه"). Growable dictionary of known
 * bad splits -> correct word; applied to any transcript regardless of source engine.
 */
object TranscriptRepair {
    private val knownSplits = listOf(
        "در مونگاه" to "درمانگاه",
        "درمونگاه" to "درمانگاه",
        "بیمار ستان" to "بیمارستان",
        "دندان پزشکی" to "دندانپزشکی",
        "دندون پزشکی" to "دندانپزشکی",
        "آرایش گاه" to "آرایشگاه"
    )

    fun repair(text: String): String {
        var result = text
        for ((broken, fixed) in knownSplits) {
            result = result.replace(broken, fixed)
        }
        return result
    }
}

/**
 * Sends the finished recording to Groq's free Whisper Large v3 transcription
 * endpoint (OpenAI-compatible API). Groq's free tier currently allows roughly
 * 2,000 transcription requests/day at no cost — no credit card required.
 * Requires internet access and a free API key from console.groq.com/keys
 * (entered by the user in Settings — never hardcoded).
 */
class GroqWhisperTranscriber(private val apiKey: String) : TranscriptionEngine {

    override suspend fun transcribe(wavFilePath: String): Result<String> {
        return try {
            val file = File(wavFilePath)
            if (!file.exists()) return Result.failure(IllegalStateException("فایل صوتی یافت نشد"))
            if (apiKey.isBlank()) return Result.failure(IllegalStateException("کلید API تنظیم نشده است"))

            val boundary = "----AppointmentVoiceBoundary${UUID.randomUUID()}"
            val url = URL("https://api.groq.com/openai/v1/audio/transcriptions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            // Whisper supports an optional "prompt" that biases transcription toward
            // the vocabulary/spelling it contains, without forcing that exact text.
            // This measurably reduces mis-segmentation of common colloquial Persian
            // compound words (e.g. "درمونگاه" -> "در" + "مونگاه") and misspelled
            // names, by giving the model correctly-spelled reference examples.
            val biasPrompt = "قرار ملاقات، درمانگاه، بیمارستان، مطب دکتر، دندانپزشکی، دفتر کار، " +
                "کافه، رستوران، ابوالفضل، محمدرضا، علیرضا، فاطمه، دکتر احمدی، ساعت، فردا، پس‌فردا."

            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                    out.write("$value\r\n".toByteArray())
                }
                field("model", "whisper-large-v3")
                field("language", "fa")
                field("prompt", biasPrompt)

                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n".toByteArray())
                out.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
                FileInputStream(file).use { input -> input.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""

            if (code in 200..299) {
                val rawText = JSONObject(body).optString("text", "")
                Result.success(TranscriptRepair.repair(rawText))
            } else {
                Result.failure(Exception(friendlyHttpError(code, body)))
            }
        } catch (e: Exception) {
            Result.failure(Exception("خطا در ارتباط با سرویس آنلاین: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun friendlyHttpError(code: Int, body: String): String = when (code) {
        401 -> "کلید API نامعتبر است. آن را در تنظیمات دوباره بررسی کنید."
        429 -> "سقف رایگان روزانه‌ی Groq تمام شده. کمی بعد دوباره امتحان کنید."
        in 500..599 -> "سرور Groq موقتاً در دسترس نیست. بعداً دوباره امتحان کنید."
        else -> "خطای سرویس آنلاین (کد $code)."
    }
}

/** Runs the already-recorded WAV file through the offline Vosk model (no network needed). */
class OfflineVoskTranscriber(private val model: Model) : TranscriptionEngine {

    override suspend fun transcribe(wavFilePath: String): Result<String> {
        return try {
            val file = File(wavFilePath)
            if (!file.exists()) return Result.failure(IllegalStateException("فایل صوتی یافت نشد"))

            val recognizer = Recognizer(model, VoiceCaptureEngine.SAMPLE_RATE.toFloat())
            FileInputStream(file).use { input ->
                input.skip(44) // skip the 44-byte WAV header written by VoiceCaptureEngine
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    recognizer.acceptWaveForm(buffer, read)
                }
            }
            val text = JSONObject(recognizer.finalResult ?: "{}").optString("text", "")
            recognizer.close()
            Result.success(TranscriptRepair.repair(text))
        } catch (e: Exception) {
            Result.failure(Exception("خطا در تشخیص گفتار آفلاین: ${e.message ?: e.javaClass.simpleName}"))
        }
    }
}
