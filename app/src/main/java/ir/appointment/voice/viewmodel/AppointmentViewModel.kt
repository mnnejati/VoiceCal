package ir.appointment.voice.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.appointment.voice.data.AppDatabase
import ir.appointment.voice.data.AppointmentEntity
import ir.appointment.voice.data.AppointmentRepository
import ir.appointment.voice.data.RecognitionMode
import ir.appointment.voice.data.SettingsStore
import ir.appointment.voice.notification.ReminderScheduler
import ir.appointment.voice.voice.AudioCompressor
import ir.appointment.voice.voice.AudioPlayerManager
import ir.appointment.voice.voice.ExtractedAppointment
import ir.appointment.voice.voice.OfflineVoskTranscriber
import ir.appointment.voice.voice.GroqAppointmentExtractor
import ir.appointment.voice.voice.GroqWhisperTranscriber
import ir.appointment.voice.voice.PersianCalendar
import ir.appointment.voice.voice.PersianInfoExtractor
import ir.appointment.voice.voice.VoiceCaptureEngine
import ir.appointment.voice.voice.VoskModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model

enum class RecordingState { IDLE, RECORDING, TRANSCRIBING }

class AppointmentViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppointmentRepository(
        AppDatabase.getInstance(application).appointmentDao()
    )
    private val settings = SettingsStore(application)

    private var captureEngine: VoiceCaptureEngine? = null
    private var loadedOfflineModel: Model? = null
    private val audioPlayer = AudioPlayerManager()

    private val _pendingDeleteIds = MutableStateFlow<Set<Long>>(emptySet())

    private val allAppointments: StateFlow<List<AppointmentEntity>> =
        repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val appointments: StateFlow<List<AppointmentEntity>> =
        combine(allAppointments, _pendingDeleteIds) { all, pending ->
            all.filterNot { pending.contains(it.id) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState

    private val _pendingPreview = MutableStateFlow<PendingPreview?>(null)
    val pendingPreview: StateFlow<PendingPreview?> = _pendingPreview

    private val _playingId = MutableStateFlow<Long?>(null)
    val playingId: StateFlow<Long?> = _playingId

    data class UserMessage(val text: String, val durationMillis: Long = 3000L)

    private val _userMessage = MutableStateFlow<UserMessage?>(null)
    val userMessage: StateFlow<UserMessage?> = _userMessage

    private val _editingAppointment = MutableStateFlow<AppointmentEntity?>(null)
    val editingAppointment: StateFlow<AppointmentEntity?> = _editingAppointment

    private val _recognitionMode = MutableStateFlow(settings.recognitionMode)
    val recognitionMode: StateFlow<RecognitionMode> = _recognitionMode

    private val _apiKey = MutableStateFlow(settings.groqApiKey)
    val apiKey: StateFlow<String> = _apiKey

    private val _alarmSoundUri = MutableStateFlow(settings.alarmSoundUri)
    val alarmSoundUri: StateFlow<String> = _alarmSoundUri

    private val _alarmDurationSeconds = MutableStateFlow(settings.alarmDurationSeconds)
    val alarmDurationSeconds: StateFlow<Int> = _alarmDurationSeconds

    private val _alarmEnabled = MutableStateFlow(settings.alarmEnabled)
    val alarmEnabled: StateFlow<Boolean> = _alarmEnabled

    private val undoJobs = mutableMapOf<Long, Job>()

    data class PendingPreview(val extracted: ExtractedAppointment, val audioFilePath: String)

    fun updateSettings(mode: RecognitionMode, key: String) {
        settings.recognitionMode = mode
        settings.groqApiKey = key
        _recognitionMode.value = mode
        _apiKey.value = key
    }

    fun updateAlarmSettings(enabled: Boolean, soundUri: String, durationSeconds: Int) {
        settings.alarmEnabled = enabled
        settings.alarmSoundUri = soundUri
        settings.alarmDurationSeconds = durationSeconds
        _alarmEnabled.value = enabled
        _alarmSoundUri.value = soundUri
        _alarmDurationSeconds.value = settings.alarmDurationSeconds
    }

    fun startRecording() {
        if (_recordingState.value != RecordingState.IDLE) return

        val engine = VoiceCaptureEngine(getApplication())
        captureEngine = engine
        try {
            engine.start()
        } catch (e: Exception) {
            _userMessage.value = UserMessage("امکان دسترسی به میکروفون نبود. دوباره تلاش کنید.")
            captureEngine = null
            return
        }
        _recordingState.value = RecordingState.RECORDING
    }

    fun stopRecordingAndProcess() {
        if (_recordingState.value != RecordingState.RECORDING) return
        _recordingState.value = RecordingState.TRANSCRIBING

        val engine = captureEngine
        captureEngine = null

        viewModelScope.launch {
            val audioPath = withContext(Dispatchers.IO) { engine?.stop() }
            if (audioPath == null) {
                _userMessage.value = UserMessage("صدایی ضبط نشد. دوباره امتحان کنید.")
                _recordingState.value = RecordingState.IDLE
                return@launch
            }

            val transcriptionResult = when (_recognitionMode.value) {
                RecognitionMode.ONLINE -> {
                    val key = _apiKey.value
                    if (key.isBlank()) {
                        _recordingState.value = RecordingState.IDLE
                        _userMessage.value = UserMessage("ابتدا از تنظیمات، کلید API رایگان Groq را وارد کنید.")
                        return@launch
                    }
                    withContext(Dispatchers.IO) { GroqWhisperTranscriber(key).transcribe(audioPath) }
                }
                RecognitionMode.OFFLINE -> {
                    val model = loadedOfflineModel ?: loadOfflineModelBlocking()
                    if (model == null) {
                        _recordingState.value = RecordingState.IDLE
                        return@launch // loadOfflineModelBlocking already surfaced an error message
                    }
                    withContext(Dispatchers.IO) { OfflineVoskTranscriber(model).transcribe(audioPath) }
                }
            }

            val text = transcriptionResult.getOrNull().orEmpty()
            if (transcriptionResult.isFailure) {
                _userMessage.value = UserMessage(transcriptionResult.exceptionOrNull()?.message ?: "خطای تشخیص گفتار.")
            }

            if (text.isNotBlank()) {
                val extracted = extractAppointmentInfo(text)
                if (extracted.jalaliYear == null && extracted.location == null &&
                    extracted.personName == null && extracted.displayTime == null
                ) {
                    _userMessage.value = UserMessage("چیز قابل‌فهمی تشخیص داده نشد. لطفاً واضح‌تر و شامل تاریخ/ساعت/محل صحبت کنید.")
                }
                _pendingPreview.value = PendingPreview(extracted, audioPath)
            } else if (transcriptionResult.isSuccess) {
                _userMessage.value = UserMessage("صدایی شنیده نشد یا قابل‌تشخیص نبود. دوباره امتحان کنید.")
                java.io.File(audioPath).delete()
            } else {
                java.io.File(audioPath).delete()
            }
            _recordingState.value = RecordingState.IDLE
        }
    }

    /**
     * Combines the LLM-based extractor (great at ordinals, relative dates, and
     * telling locations apart from person names) with the regex extractor
     * (fully offline, catches things the LLM occasionally misses) into one
     * hybrid result: for each field, the LLM's value wins if present, and any
     * field the LLM left null is filled in from the regex pass instead of
     * being dropped. In OFFLINE mode, or if the LLM call fails entirely, only
     * the regex result is used.
     */
    private suspend fun extractAppointmentInfo(text: String): ExtractedAppointment {
        val regexResult = PersianInfoExtractor.extract(text)

        if (_recognitionMode.value != RecognitionMode.ONLINE || _apiKey.value.isBlank()) {
            return rejectPastDate(regexResult)
        }

        val llmResult = withContext(Dispatchers.IO) {
            GroqAppointmentExtractor(_apiKey.value).extract(text)
        }.getOrNull() ?: return rejectPastDate(regexResult)

        val jy = llmResult.jalaliYear ?: regexResult.jalaliYear ?: run {
            val jmv = llmResult.jalaliMonth ?: regexResult.jalaliMonth
            val jdv = llmResult.jalaliDay ?: regexResult.jalaliDay
            if (jmv != null && jdv != null) PersianCalendar.todayJalali().first else null
        }
        val jm = llmResult.jalaliMonth ?: regexResult.jalaliMonth
        val jd = llmResult.jalaliDay ?: regexResult.jalaliDay
        val hour = llmResult.hour ?: regexResult.hour
        val minute = llmResult.minute ?: regexResult.minute
        val location = llmResult.location ?: regexResult.location
        val person = llmResult.personName ?: regexResult.personName

        val weekday = if (jy != null && jm != null && jd != null) PersianCalendar.weekdayName(jy, jm, jd) else null
        val displayDate = if (jy != null && jm != null && jd != null) {
            "$jd ${PersianCalendar.jalaliMonthNames.getOrNull(jm - 1) ?: ""} $jy"
        } else null
        val displayTime = if (hour != null) String.format("%02d:%02d", hour, minute ?: 0) else null
        val sortTs = if (jy != null && jm != null && jd != null) PersianCalendar.toEpochMillis(jy, jm, jd, hour, minute) else null

        return rejectPastDate(
            ExtractedAppointment(
                rawText = text,
                personName = person,
                location = location,
                jalaliYear = jy,
                jalaliMonth = jm,
                jalaliDay = jd,
                weekdayName = weekday,
                hour = hour,
                minute = minute,
                displayDate = displayDate,
                displayTime = displayTime,
                sortTimestamp = sortTs
            )
        )
    }

    /**
     * Users don't usually dictate an appointment that's already in the past, so a
     * resolved date earlier than today is almost certainly a misrecognition (wrong
     * year carried over, ordinal misheard, etc). In that case we drop just the date
     * fields (keep time/location/person, which are still likely correct) so the
     * user fills the date in manually in the preview instead of silently saving a
     * wrong date. Comparison is at day granularity — an earlier time TODAY is fine.
     */
    private fun rejectPastDate(extracted: ExtractedAppointment): ExtractedAppointment {
        val jy = extracted.jalaliYear
        val jm = extracted.jalaliMonth
        val jd = extracted.jalaliDay
        if (jy == null || jm == null || jd == null) return extracted

        val today = PersianCalendar.todayJalali()
        val candidateNum = jy * 10000 + jm * 100 + jd
        val todayNum = today.first * 10000 + today.second * 100 + today.third
        if (candidateNum >= todayNum) return extracted

        _userMessage.value = UserMessage(
            "تاریخ تشخیص‌داده‌شده در گذشته بود، پس نادیده گرفته شد — لطفاً تاریخ درست را دستی وارد کنید."
        )
        return extracted.copy(
            jalaliYear = null,
            jalaliMonth = null,
            jalaliDay = null,
            weekdayName = null,
            displayDate = null,
            sortTimestamp = null
        )
    }

    /** Loads the offline model synchronously (suspended) the first time offline mode is used. */
    private suspend fun loadOfflineModelBlocking(): Model? {
        return withContext(Dispatchers.IO) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                VoskModelProvider.load(getApplication()) { state ->
                    when (state) {
                        is VoskModelProvider.ModelState.Ready -> {
                            loadedOfflineModel = state.model
                            if (cont.isActive) cont.resume(state.model) { }
                        }
                        is VoskModelProvider.ModelState.Error -> {
                            _userMessage.value = UserMessage(state.message)
                            if (cont.isActive) cont.resume(null) { }
                        }
                        is VoskModelProvider.ModelState.Loading -> { /* wait for a terminal callback */ }
                    }
                }
            }
        }
    }

    fun cancelRecording() {
        captureEngine?.cancel()
        captureEngine = null
        _recordingState.value = RecordingState.IDLE
    }

    fun discardPreview() {
        _pendingPreview.value?.let { java.io.File(it.audioFilePath).delete() }
        _pendingPreview.value = null
    }

    fun confirmPreview(edited: ExtractedAppointment) {
        val preview = _pendingPreview.value ?: return
        viewModelScope.launch {
            val finalAudioPath = withContext(Dispatchers.IO) { compressForStorage(preview.audioFilePath) }

            val id = repository.save(
                AppointmentEntity(
                    rawText = edited.rawText,
                    personName = edited.personName,
                    location = edited.location,
                    jalaliYear = edited.jalaliYear,
                    jalaliMonth = edited.jalaliMonth,
                    jalaliDay = edited.jalaliDay,
                    weekdayName = edited.weekdayName,
                    hour = edited.hour,
                    minute = edited.minute,
                    displayDate = edited.displayDate,
                    displayTime = edited.displayTime,
                    audioFilePath = finalAudioPath,
                    sortTimestamp = edited.sortTimestamp
                )
            )
            _pendingPreview.value = null
            _userMessage.value = UserMessage("قرار ملاقات ذخیره شد.", durationMillis = 900L)

            if (edited.sortTimestamp != null) {
                val label = buildLabel(edited.displayDate, edited.displayTime, edited.location, edited.personName)
                ReminderScheduler.schedule(getApplication(), id, edited.sortTimestamp, label)
            }
        }
    }

    fun startEdit(appointment: AppointmentEntity) {
        _editingAppointment.value = appointment
    }

    fun cancelEdit() {
        _editingAppointment.value = null
    }

    fun saveEdit(updated: AppointmentEntity) {
        viewModelScope.launch {
            repository.update(updated)
            _editingAppointment.value = null
            _userMessage.value = UserMessage("تغییرات ذخیره شد.", durationMillis = 900L)

            ReminderScheduler.cancel(getApplication(), updated.id)
            if (updated.sortTimestamp != null) {
                val label = buildLabel(updated.displayDate, updated.displayTime, updated.location, updated.personName)
                ReminderScheduler.schedule(getApplication(), updated.id, updated.sortTimestamp, label)
            }
        }
    }

    fun requestDelete(appointment: AppointmentEntity, undoWindowMillis: Long = 4000L) {
        _pendingDeleteIds.value = _pendingDeleteIds.value + appointment.id
        if (_playingId.value == appointment.id) {
            audioPlayer.stop()
            _playingId.value = null
        }
        ReminderScheduler.cancel(getApplication(), appointment.id)

        val job = viewModelScope.launch {
            kotlinx.coroutines.delay(undoWindowMillis)
            repository.deleteWithAudio(appointment)
            _pendingDeleteIds.value = _pendingDeleteIds.value - appointment.id
            undoJobs.remove(appointment.id)
        }
        undoJobs[appointment.id] = job
    }

    fun undoDelete(appointment: AppointmentEntity) {
        undoJobs.remove(appointment.id)?.cancel()
        _pendingDeleteIds.value = _pendingDeleteIds.value - appointment.id
        if (appointment.sortTimestamp != null) {
            val label = buildLabel(appointment.displayDate, appointment.displayTime, appointment.location, appointment.personName)
            ReminderScheduler.schedule(getApplication(), appointment.id, appointment.sortTimestamp, label)
        }
    }

    /**
     * Compresses the temporary WAV recording into a much smaller AAC file for
     * long-term storage. Falls back to keeping the original WAV if compression
     * fails for any reason (never lose the recording over a storage optimization).
     */
    private fun compressForStorage(wavPath: String): String {
        val aacPath = wavPath.removeSuffix(".wav") + ".m4a"
        val success = AudioCompressor.compressWavToAac(wavPath, aacPath)
        return if (success) {
            java.io.File(wavPath).delete()
            aacPath
        } else {
            wavPath
        }
    }

    private fun buildLabel(date: String?, time: String?, location: String?, person: String?): String =
        buildString {
            append(date ?: "")
            if (!time.isNullOrBlank()) append(" ساعت $time")
            if (!location.isNullOrBlank()) append(" - $location")
            if (!person.isNullOrBlank()) append(" - با $person")
        }.ifBlank { "قرار ملاقات" }

    fun togglePlay(appointment: AppointmentEntity) {
        if (_playingId.value == appointment.id) {
            audioPlayer.stop()
            _playingId.value = null
            return
        }
        try {
            audioPlayer.play(appointment.audioFilePath) {
                _playingId.value = null
            }
            _playingId.value = appointment.id
        } catch (e: Exception) {
            _playingId.value = null
            _userMessage.value = UserMessage("پخش فایل صوتی ممکن نشد.")
        }
    }

    fun stopPlayback() {
        audioPlayer.stop()
        _playingId.value = null
    }

    fun consumeUserMessage() {
        _userMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
        captureEngine?.cancel()
        captureEngine = null
    }
}
