package ir.appointment.voice.notification

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAlarm()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "یادآوری قرار ملاقات"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: ""
        val soundUriStr = intent?.getStringExtra(EXTRA_SOUND_URI).orEmpty()
        val durationSeconds = (intent?.getIntExtra(EXTRA_DURATION_SECONDS, 15) ?: 15).coerceIn(3, 120)

        NotificationHelper.ensureChannel(this)
        startForeground(NOTIFICATION_ID, buildNotification(title, text))
        playSound(soundUriStr)

        stopRunnable?.let { stopHandler.removeCallbacks(it) }
        val runnable = Runnable { stopAlarm() }
        stopRunnable = runnable
        stopHandler.postDelayed(runnable, durationSeconds * 1000L)

        return START_NOT_STICKY
    }

    private fun playSound(soundUriStr: String) {
        try {
            val uri: Uri = if (soundUriStr.isNotBlank()) {
                Uri.parse(soundUriStr)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
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
        } catch (_: Exception) {
            // If the chosen sound can't be played for any reason, fail silently —
            // the (silent) notification itself is still shown.
        }
    }

    private fun stopAlarm() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        mediaPlayer?.release()
        mediaPlayer = null
        stopRunnable?.let { stopHandler.removeCallbacks(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun buildNotification(title: String, text: String): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = android.app.PendingIntent.getActivity(
            this, 0, openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AlarmSoundService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "توقف", stopPendingIntent)
            .build()
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
