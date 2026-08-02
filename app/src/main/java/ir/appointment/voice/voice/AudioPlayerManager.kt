package ir.appointment.voice.voice

import android.media.MediaPlayer

class AudioPlayerManager {
    private var player: MediaPlayer? = null

    fun play(filePath: String, onCompletion: () -> Unit) {
        stop()
        player = MediaPlayer().apply {
            setDataSource(filePath)
            setOnCompletionListener { onCompletion() }
            prepare()
            start()
        }
    }

    fun stop() {
        player?.apply {
            try {
                if (isPlaying) stop()
            } catch (_: Exception) {
            }
            release()
        }
        player = null
    }

    fun isPlaying(): Boolean = player?.isPlaying == true
}
