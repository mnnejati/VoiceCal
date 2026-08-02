package ir.appointment.voice.data

import android.content.Context

enum class RecognitionMode { ONLINE, OFFLINE }

/**
 * NOTE on security: the API key is stored in plain SharedPreferences for
 * simplicity. This is adequate for a personal, single-user local app, but if
 * you plan wider distribution, swap this for androidx.security's
 * EncryptedSharedPreferences so the key isn't readable via a rooted-device or
 * backup-extraction attack.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var recognitionMode: RecognitionMode
        get() = if (prefs.getString(KEY_MODE, RecognitionMode.ONLINE.name) == RecognitionMode.OFFLINE.name) {
            RecognitionMode.OFFLINE
        } else {
            RecognitionMode.ONLINE
        }
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

    var groqApiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value.trim()).apply()
        }

    companion object {
        private const val KEY_MODE = "recognition_mode"
        private const val KEY_API_KEY = "groq_api_key"
    }
}
