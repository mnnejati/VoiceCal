package ir.appointment.voice.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Records microphone audio to 16kHz mono 16-bit PCM held in RAM while the button
 * is held, then writes it out as a WAV file once recording stops. This is the
 * ONLY audio client during capture (no simultaneous speech-recognizer session),
 * which is what makes recording itself always reliable. Transcription (online
 * via [GroqWhisperTranscriber] or offline via [OfflineVoskTranscriber]) runs
 * as a separate step afterwards, on the already-saved file.
 */
class VoiceCaptureEngine(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val pcmBuffer = ByteArrayOutputStream()

    var currentFilePath: String? = null
        private set

    /** Emits a normalized 0f..1f loudness level a few times per second while
     * recording, for a live waveform display. Computing this is cheap (a single
     * pass over each small audio chunk already being read) and callbacks are
     * throttled so they can't flood the UI thread. */
    @Volatile var onAmplitude: ((Float) -> Unit)? = null

    private var lastAmplitudeEmitAt = 0L
    private val amplitudeEmitIntervalMs = 60L // ~16 updates/sec, smooth but cheap

    @SuppressLint("MissingPermission") // caller guarantees RECORD_AUDIO was granted
    fun start() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = if (minBufferSize > 0) minBufferSize * 2 else SAMPLE_RATE * 2

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        pcmBuffer.reset()
        audioRecord = record

        val dir = File(context.filesDir, "voice_notes").apply { mkdirs() }
        val fileName = "voice_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
        currentFilePath = File(dir, fileName).absolutePath

        isRunning.set(true)
        record.startRecording()

        captureThread = thread(start = true, name = "voice-capture") {
            val chunk = ByteArray(bufferSize)
            while (isRunning.get()) {
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) {
                    synchronized(pcmBuffer) {
                        pcmBuffer.write(chunk, 0, read)
                    }
                    emitAmplitudeIfDue(chunk, read)
                }
            }
        }
    }

    private fun emitAmplitudeIfDue(chunk: ByteArray, length: Int) {
        val callback = onAmplitude ?: return
        val now = System.currentTimeMillis()
        if (now - lastAmplitudeEmitAt < amplitudeEmitIntervalMs) return
        lastAmplitudeEmitAt = now

        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val lo = chunk[i].toInt() and 0xFF
            val hi = chunk[i + 1].toInt()
            val sample = kotlin.math.abs((hi shl 8) or lo)
            if (sample > peak) peak = sample
            i += 2
        }
        val normalized = (peak / 32768f).coerceIn(0f, 1f)
        callback(normalized)
    }

    /** Stops capture and writes the WAV file. Returns its path, or null if nothing was captured. */
    fun stop(): String? {
        isRunning.set(false)
        captureThread?.join(1500)
        captureThread = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null

        val path = currentFilePath
        val bytes = synchronized(pcmBuffer) { pcmBuffer.toByteArray() }
        return if (path != null && bytes.isNotEmpty()) {
            writeWavFile(path, bytes)
            path
        } else {
            path?.let { File(it).delete() }
            null
        }
    }

    fun cancel() {
        isRunning.set(false)
        captureThread?.join(1500)
        captureThread = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
        currentFilePath?.let { File(it).delete() }
        currentFilePath = null
        synchronized(pcmBuffer) { pcmBuffer.reset() }
    }

    private fun writeWavFile(path: String, pcmData: ByteArray) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val header = ByteArray(44)

        fun writeString(offset: Int, s: String) {
            s.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() }
        }
        fun writeInt(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value shr 8) and 0xff).toByte()
            header[offset + 2] = ((value shr 16) and 0xff).toByte()
            header[offset + 3] = ((value shr 24) and 0xff).toByte()
        }
        fun writeShort(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value shr 8) and 0xff).toByte()
        }

        writeString(0, "RIFF")
        writeInt(4, 36 + dataSize)
        writeString(8, "WAVE")
        writeString(12, "fmt ")
        writeInt(16, 16)
        writeShort(20, 1)
        writeShort(22, channels)
        writeInt(24, SAMPLE_RATE)
        writeInt(28, byteRate)
        writeShort(32, channels * bitsPerSample / 8)
        writeShort(34, bitsPerSample)
        writeString(36, "data")
        writeInt(40, dataSize)

        FileOutputStream(path).use { out ->
            out.write(header)
            out.write(pcmData)
        }
    }
}
