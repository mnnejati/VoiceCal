package ir.appointment.voice.notification

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import ir.appointment.voice.MainActivity

class AlarmSoundService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val stopHandler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null

    private var currentTitle: String = "یادآوری قرار ملاقات"
    private var currentText: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAlarm(userInitiated = true)
            return START_NOT_STICKY
        }

        currentTitle = intent?.getStringExtra(EXTRA_TITLE) ?: "یادآوری قرار ملاقات"
        currentText = intent?.getStringExtra(EXTRA_TEXT) ?: ""
        val soundUriStr = intent?.getStringExtra(EXTRA_SOUND_URI).orEmpty()
        val durationSeconds = (intent?.getIntExtra(EXTRA_DURATION_SECONDS, 15) ?: 15).coerceIn(3, 120)

        NotificationHelper.ensureChannel(this)
        // Always show the foreground notification first, regardless of whether the
        // sound ends up playing — so the user still sees a reminder even if audio
        // setup fails for any reason (missing permission, unsupported file, etc).
        startForeground(NOTIFICATION_ID, buildOngoingNotification(currentTitle, currentText))
        playSoundWithFallback(soundUriStr)

        stopRunnable?.let { stopHandler.removeCallbacks(it) }
        val runnable = Runnable { stopAlarm(userInitiated = false) }
        stopRunnable = runnable
        stopHandler.postDelayed(runnable, durationSeconds * 1000L)

        return START_NOT_STICKY
    }

    /** Tries the user's chosen sound; on ANY failure, falls back to the default alarm
     * sound, then the default notification sound, so a broken/inaccessible custom
     * sound never results in total silence. */
    private fun playSoundWithFallback(soundUriStr: String) {
        val candidates = buildList {
            if (soundUriStr.isNotBlank()) add(Uri.parse(soundUriStr))
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { add(it) }
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.let { add(it) }
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)?.let { add(it) }
        }

        for (uri in candidates) {
            if (tryPlay(uri)) return
        }
        // Every candidate failed (extremely unlikely) — the notification is still
        // shown, so the user isn't left with no indication at all.
    }

    private fun tryPlay(uri: Uri): Boolean {
        return try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmSoundService, uri)
                isLooping = true
                prepare()
                start()
            }
            true
        } catch (_: Exception) {
            mediaPlayer?.release()
            mediaPlayer = null
            false
        }
    }

    /**
     * [userInitiated] = true when the person tapped "توقف": stop everything and
     * clear the notification immediately, since they've clearly seen it.
     * [userInitiated] = false when the configured duration simply ran out: stop the
     * sound, but leave a normal (non-ongoing) notification behind so the person
     * still sees it later if the phone was silent, away, or the screen was off —
     * they can swipe it away themselves once they've seen it.
     */
    private fun stopAlarm(userInitiated: Boolean) {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        mediaPlayer?.release()
        mediaPlayer = null
        stopRunnable?.let { stopHandler.removeCallbacks(it) }

        if (userInitiated) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } else {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildLingeringNotification(currentTitle, currentText))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
        }
        stopSelf()
    }

    /** Shown while the alarm is actively ringing: ongoing (can't be swiped) + a Stop action. */
    private fun buildOngoingNotification(title: String, text: String): android.app.Notification {
        val stopIntent = Intent(this, AlarmSoundService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return baseNotificationBuilder(title, text)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_media_pause, "توقف", stopPendingIntent)
            .build()
    }

    /** Shown after the ring duration ends on its own: a normal, swipe-to-dismiss notification. */
    private fun buildLingeringNotification(title: String, text: String): android.app.Notification {
        return baseNotificationBuilder(title, "$text (زمان آلارم تمام شد)")
            .setOngoing(false)
            .setAutoCancel(false) // stays until swiped, not just tapped
            .build()
    }

    private fun baseNotificationBuilder(title: String, text: String): NotificationCompat.Builder {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = android.app.PendingIntent.getActivity(
            this, 0, openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        mediaPlayer?.release()
        mediaPlayer = null
        stopRunnable?.let { stopHandler.removeCallbacks(it) }
    }

    companion object {
        private const val NOTIFICATION_ID = 9001
        const val ACTION_STOP = "ir.appointment.voice.action.STOP_ALARM"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SOUND_URI = "sound_uri"
        const val EXTRA_DURATION_SECONDS = "duration_seconds"
    }
}
