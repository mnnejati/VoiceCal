package ir.appointment.voice.voice

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * One-shot WAV -> AAC (in an .m4a container) compressor, using the standard
 * MediaCodec encoder + MediaMuxer pipeline built into Android (no extra
 * dependencies). Applied only once, at the moment a recording is actually
 * confirmed and saved — the temporary WAV used during recording/preview/
 * transcription is untouched, so neither the online upload nor the offline
 * Vosk decoder (which both expect raw WAV) are affected by this at all.
 */
object AudioCompressor {

    private const val SAMPLE_RATE = VoiceCaptureEngine.SAMPLE_RATE
    private const val BIT_RATE = 32_000 // 32kbps mono AAC — plenty for clear voice, ~8-10x smaller than 16-bit PCM
    private const val WAV_HEADER_SIZE = 44

    /** Returns true on success (output file written); on any failure, no output file is left behind. */
    fun compressWavToAac(wavPath: String, outputPath: String): Boolean {
        val wavFile = File(wavPath)
        if (!wavFile.exists() || wavFile.length() <= WAV_HEADER_SIZE) return false

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null

        return try {
            val pcm = wavFile.readBytes().copyOfRange(WAV_HEADER_SIZE, wavFile.length().toInt())

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var trackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            var offset = 0
            var presentationTimeUs = 0L
            var inputDone = false
            var outputDone = false
            val chunkSize = 4096

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)!!
                        inBuffer.clear()
                        val remaining = pcm.size - offset
                        val toWrite = minOf(chunkSize, remaining).coerceAtLeast(0)
                        if (toWrite == 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            inBuffer.put(pcm, offset, toWrite)
                            codec.queueInputBuffer(inIndex, 0, toWrite, presentationTimeUs, 0)
                            // 16-bit mono => 2 bytes/sample; advance the timestamp by this chunk's real duration.
                            val numSamples = toWrite / 2
                            presentationTimeUs += (numSamples.toLong() * 1_000_000L) / SAMPLE_RATE
                            offset += toWrite
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outBuffer = codec.getOutputBuffer(outIndex)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size != 0 && muxerStarted) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    // INFO_TRY_AGAIN_LATER and other negative codes: just loop again.
                }
            }

            true
        } catch (e: Exception) {
            File(outputPath).delete()
            false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }
}
