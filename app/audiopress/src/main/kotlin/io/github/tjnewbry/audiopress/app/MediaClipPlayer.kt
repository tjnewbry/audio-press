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

    // False from start() until the player is playing from the place asked for. Until then the position is that place, not the
    // player's own (which is still 0 or the old place while it prepares and seeks), so a seek never shows a jump back.
    private var settled = false

    override val positionMs: Int
        get() =
            try {
                if (settled) player?.currentPosition ?: startedFromMs else startedFromMs
            } catch (e: IllegalStateException) {
                startedFromMs
            }

    override fun start(
        file: File,
        fromMs: Int,
        id: Int,
    ) {
        release()
        startedFromMs = fromMs
        settled = false
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
                if (player !== it) return@setOnPreparedListener
                if (fromMs > 0) {
                    // To the exact millisecond, and only start (and report positions) once the seek is done.
                    it.setOnSeekCompleteListener { done ->
                        if (player === done) {
                            settled = true
                            done.start()
                        }
                    }
                    it.seekTo(fromMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                } else {
                    settled = true
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
