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

    /** Empty string means "use the system default alarm sound". */
    var alarmSoundUri: String
        get() = prefs.getString(KEY_ALARM_SOUND, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_ALARM_SOUND, value).apply()
        }

    /** How long the alarm sound rings for when a reminder fires. */
    var alarmDurationSeconds: Int
        get() = prefs.getInt(KEY_ALARM_DURATION, 15)
        set(value) {
            prefs.edit().putInt(KEY_ALARM_DURATION, value.coerceIn(3, 120)).apply()
        }

    /** Master on/off switch for reminders. When false, no alarm/notification fires at all. */
    var alarmEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALARM_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ALARM_ENABLED, value).apply()
        }

    companion object {
        private const val KEY_MODE = "recognition_mode"
        private const val KEY_API_KEY = "groq_api_key"
        private const val KEY_ALARM_SOUND = "alarm_sound_uri"
        private const val KEY_ALARM_DURATION = "alarm_duration_seconds"
        private const val KEY_ALARM_ENABLED = "alarm_enabled"
    }
}
