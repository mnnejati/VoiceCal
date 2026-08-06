package ir.appointment.voice.voice

/** Single source of truth for which online models are in use — referenced both by
 * the actual API calls and by the "About" screen, so they can never drift apart. */
object ModelInfo {
    const val TRANSCRIPTION_MODEL_ID = "whisper-large-v3"
    const val TRANSCRIPTION_MODEL_DISPLAY = "Whisper Large v3 (Groq)"

    const val EXTRACTION_MODEL_ID = "openai/gpt-oss-120b"
    const val EXTRACTION_MODEL_DISPLAY = "GPT-OSS 120B (Groq)"
}
