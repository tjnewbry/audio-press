package io.github.tjnewbry.audiopress.app

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.IOException

/** [ClipPlayer] backed by `MediaPlayer`, which plays the baked WAV files without any extra library. Use on the main thread. */
class MediaClipPlayer : ClipPlayer {
    override var onCompleted: ((Int) -> Unit)? = null
    override var onError: ((Int) -> Unit)? = null

    private var player: MediaPlayer? = null
    private var startedFromMs = 0

    override val positionMs: Int
        get() =
            try {
                player?.currentPosition ?: startedFromMs
            } catch (e: IllegalStateException) {
                startedFromMs // not prepared yet
            }

    override fun start(
        file: File,
        fromMs: Int,
        id: Int,
    ) {
        release()
        startedFromMs = fromMs
        val next = MediaPlayer()
        player = next
        try {
            next.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            next.setDataSource(file.path)
            next.setOnPreparedListener {
                if (player === it) {
                    if (fromMs > 0) it.seekTo(fromMs)
                    it.start()
                }
            }
            next.setOnCompletionListener { if (player === it) onCompleted?.invoke(id) }
            next.setOnErrorListener { failed, what, extra ->
                Log.w(TAG, "MediaPlayer error $what/$extra for ${file.name}")
                if (player === failed) onError?.invoke(id)
                true
            }
            next.prepareAsync()
        } catch (e: IOException) {
            Log.w(TAG, "Cannot play ${file.name}", e)
            onError?.invoke(id)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot play ${file.name}", e)
            onError?.invoke(id)
        }
    }

    override fun pause(): Int {
        val at = positionMs
        release()
        return at
    }

    private fun release() {
        player?.let {
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            it.release()
        }
        player = null
    }

    private companion object {
        const val TAG = "AudioPressBake"
    }
}
