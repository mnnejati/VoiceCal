package ir.appointment.voice.voice

import java.io.File
import kotlin.math.sqrt

/**
 * Simple energy-based silence detector run on the raw captured WAV before it's
 * ever sent for transcription. Whisper-family models (and, less often, Vosk) are
 * known to "hallucinate" plausible-looking text when fed silence or pure
 * background noise instead of correctly reporting nothing was said — catching
 * this locally avoids that entirely, and also saves a wasted API call.
 */
object AudioAnalysis {

    // 16-bit PCM samples range ±32768. Room tone/background noise from a phone
    // mic is typically well under this; normal speech is easily an order of
    // magnitude higher. Chosen conservatively so quiet speech isn't rejected.
    private const val SILENCE_RMS_THRESHOLD = 150.0
    private const val WAV_HEADER_SIZE = 44

    /** True if the recording is (close enough to) silent that transcription shouldn't even be attempted. */
    fun isSilent(wavPath: String): Boolean {
        return try {
            val file = File(wavPath)
            if (!file.exists() || file.length() <= WAV_HEADER_SIZE) return true

            val bytes = file.readBytes()
            var sumSquares = 0.0
            var sampleCount = 0
            var i = WAV_HEADER_SIZE
            while (i + 1 < bytes.size) {
                val lo = bytes[i].toInt() and 0xFF
                val hi = bytes[i + 1].toInt() // signed high byte -> correct sign extension
                val sample = (hi shl 8) or lo
                sumSquares += sample.toDouble() * sample.toDouble()
                sampleCount++
                i += 2
            }
            if (sampleCount == 0) return true

            val rms = sqrt(sumSquares / sampleCount)
            rms < SILENCE_RMS_THRESHOLD
        } catch (e: Exception) {
            // If analysis itself fails for any reason, don't block the user —
            // just let transcription attempt it normally.
            false
        }
    }
}
