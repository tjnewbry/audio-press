package io.github.tjnewbry.audiopress.app

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroVoice
import java.io.IOException

/**
 * Plays the short sample of a voice from the app's assets, one at a time, for the voice screen. [onPlayingChanged] gets the id
 * of the voice playing, or null when none is. Use on the main thread.
 */
class SamplePlayer(
    private val context: Context,
    private val onPlayingChanged: (voiceId: String?) -> Unit,
) {
    private var player: MediaPlayer? = null

    fun play(
        voice: KokoroVoice,
        quality: VoiceQuality,
    ) {
        stop()
        try {
            val file = context.assets.openFd(voice.sampleAsset(quality))
            val next = MediaPlayer()
            player = next
            next.setDataSource(file.fileDescriptor, file.startOffset, file.length)
            file.close()
            next.setOnPreparedListener { if (player === it) it.start() }
            next.setOnCompletionListener { if (player === it) stop() }
            next.setOnErrorListener { failed, _, _ ->
                if (player === failed) stop()
                true
            }
            next.prepareAsync()
            onPlayingChanged(voice.id)
        } catch (e: IOException) {
            Log.w(TAG, "Cannot play the sample of ${voice.id}", e)
            stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot play the sample of ${voice.id}", e)
            stop()
        }
    }

    fun stop() {
        player?.let {
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            it.release()
        }
        player = null
        onPlayingChanged(null)
    }

    private companion object {
        const val TAG = "AudioPressVoice"
    }
}
