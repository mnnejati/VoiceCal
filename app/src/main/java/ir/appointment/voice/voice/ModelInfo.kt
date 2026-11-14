package ir.appointment.voice.voice

import ir.appointment.voice.data.OnlineProvider

/** Single source of truth for which online models are in use — referenced both by
 * the actual API calls and by the "About" screen, so they can never drift apart. */
object ModelInfo {
    // --- Groq (speech-to-text) ---
    const val TRANSCRIPTION_MODEL_ID = "whisper-large-v3"
    const val TRANSCRIPTION_MODEL_DISPLAY = "Whisper Large v3 (Groq)"

    // --- Groq (smart extraction, used with ANY speech-to-text provider if a Groq key is set) ---
    const val EXTRACTION_MODEL_ID = "openai/gpt-oss-120b"
    const val EXTRACTION_MODEL_DISPLAY = "GPT-OSS 120B (Groq)"

    // --- Google Gemini (speech-to-text) ---
    // Google renames/retires Gemini models often, and free-tier quotas are counted PER MODEL.
    // GeminiTranscriber tries these in order and moves to the next one when a model is
    // unknown/retired (404) or its free quota is exhausted (429). To adapt to a new model
    // name, just edit this list — nothing else needs to change.
    val GEMINI_MODEL_CANDIDATES = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.8-flash",
        "gemini-2.5-flash"
    )
    const val GEMINI_MODEL_DISPLAY = "Gemini Flash (Google)"

    // --- ElevenLabs (speech-to-text) ---
    const val ELEVENLABS_MODEL_ID = "scribe_v2"
    const val ELEVENLABS_MODEL_DISPLAY = "Scribe v2 (ElevenLabs)"

    fun transcriptionDisplay(provider: OnlineProvider): String = when (provider) {
        OnlineProvider.GROQ -> TRANSCRIPTION_MODEL_DISPLAY
        OnlineProvider.GEMINI -> GEMINI_MODEL_DISPLAY
        OnlineProvider.ELEVENLABS -> ELEVENLABS_MODEL_DISPLAY
    }

    /** Short provider name for the main-screen mode label. */
    fun providerShortName(provider: OnlineProvider): String = when (provider) {
        OnlineProvider.GROQ -> "Groq"
        OnlineProvider.GEMINI -> "Gemini"
        OnlineProvider.ELEVENLABS -> "ElevenLabs"
    }
}
