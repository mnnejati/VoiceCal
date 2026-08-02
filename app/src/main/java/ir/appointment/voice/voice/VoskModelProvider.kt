package ir.appointment.voice.voice

import android.content.Context
import org.vosk.Model
import org.vosk.android.StorageService

/**
 * Loads the offline Vosk Persian (fa) speech model once per process.
 *
 * The model itself is NOT bundled in source control (it's a ~50MB set of files) —
 * it must be placed by the developer as a RAW (unzipped) folder at:
 *   app/src/main/assets/model-fa-fa/
 *
 * Download the small Farsi model from https://alphacephei.com/vosk/models
 * (e.g. "vosk-model-small-fa-0.42.zip"), UNZIP it, rename the extracted folder
 * to exactly "model-fa-fa", and copy that whole folder (not a zip!) into
 * app/src/main/assets/. [StorageService.unpack] reads the asset folder named
 * "<sourcePath>" and extracts it into app-private storage on first run.
 *
 * A Gradle task in app/build.gradle.kts automatically creates the `uuid` file
 * Vosk requires inside the model folder if it's missing (recent official model
 * downloads don't include one, which otherwise causes "Failed to unpack the
 * model" errors).
 */
object VoskModelProvider {

    private const val ASSET_MODEL_NAME = "model-fa-fa"

    sealed class ModelState {
        data object Loading : ModelState()
        data class Ready(val model: Model) : ModelState()
        data class Error(val message: String) : ModelState()
    }

    @Volatile
    private var cached: Model? = null

    fun load(context: Context, onResult: (ModelState) -> Unit) {
        val existing = cached
        if (existing != null) {
            onResult(ModelState.Ready(existing))
            return
        }

        onResult(ModelState.Loading)
        try {
            StorageService.unpack(
                context,
                ASSET_MODEL_NAME,
                "model",
                { model ->
                    cached = model
                    onResult(ModelState.Ready(model))
                },
                { exception ->
                    onResult(
                        ModelState.Error(
                            "بارگذاری موتور تشخیص گفتار آفلاین ناموفق بود. مطمئن شوید پوشه‌ی مدل فارسی " +
                                "(model-fa-fa، به‌صورت پوشه‌ی خام نه فایل zip) داخل app/src/main/assets قرار دارد. جزئیات: " +
                                (exception.message ?: exception.toString())
                        )
                    )
                }
            )
        } catch (e: Exception) {
            onResult(ModelState.Error("خطا در بارگذاری مدل: ${e.message}"))
        }
    }
}
