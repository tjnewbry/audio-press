package io.github.tjnewbry.audiopress.bake

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.tjnewbry.audiopress.core.CancellationToken
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Synthesizes text to WAV files with Android's `TextToSpeech.synthesizeToFile`, one call at a time. Use a
 * `TextToSpeech` of its own (not the one that speaks aloud), because this takes over its
 * `UtteranceProgressListener`. Set the voice, language, speech rate, and pitch on [tts] before baking, and pass
 * a [settingsKey] to [BookBaker.bake] that names them.
 */
class TtsFileSynthesizer(
    private val tts: TextToSpeech,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : SpeechSynthesizer {
    private class Pending(
        val id: String,
    ) {
        val finished = CountDownLatch(1)

        @Volatile
        var succeeded = false
    }

    @Volatile
    private var pending: Pending? = null

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = finish(utteranceId, succeeded = true)

                @Deprecated("Replaced by onError(String, Int)")
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) = finish(utteranceId, succeeded = false)

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) = finish(utteranceId, succeeded = false)

                override fun onStop(
                    utteranceId: String?,
                    interrupted: Boolean,
                ) = finish(utteranceId, succeeded = false)
            },
        )
    }

    private fun finish(
        utteranceId: String?,
        succeeded: Boolean,
    ) {
        val current = pending ?: return
        if (current.id != utteranceId) return
        current.succeeded = succeeded
        current.finished.countDown()
    }

    override fun synthesize(
        text: String,
        target: File,
        cancellation: CancellationToken?,
    ): SynthesisResult {
        val current = Pending(UUID.randomUUID().toString())
        pending = current
        try {
            if (tts.synthesizeToFile(text, Bundle(), target, current.id) != TextToSpeech.SUCCESS) return SynthesisResult.FAILED
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (!current.finished.await(POLL_MS, TimeUnit.MILLISECONDS)) {
                if (cancellation?.isCancelled == true) {
                    tts.stop()
                    current.finished.await(STOP_WAIT_MS, TimeUnit.MILLISECONDS)
                    return SynthesisResult.CANCELLED
                }
                if (System.nanoTime() > deadline) {
                    tts.stop()
                    return SynthesisResult.FAILED
                }
            }
            return when {
                cancellation?.isCancelled == true && !current.succeeded -> SynthesisResult.CANCELLED
                current.succeeded -> SynthesisResult.SUCCESS
                else -> SynthesisResult.FAILED
            }
        } finally {
            pending = null
        }
    }

    /** Releases the `TextToSpeech` this wraps. Call it when the bake is over, if you created the `TextToSpeech` for baking. */
    fun shutdown() {
        tts.shutdown()
    }

    companion object {
        /** Long enough for the biggest utterance (3,900 characters) on a slow voice. */
        const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L
        private const val POLL_MS = 100L
        private const val STOP_WAIT_MS = 2000L

        /**
         * Starts a `TextToSpeech` for baking and calls [onReady] (on the main thread) with the synthesizer, or with
         * null if the speech engine could not start. [configure] runs first, to set the voice, rate, and pitch.
         */
        fun open(
            context: Context,
            configure: (TextToSpeech) -> Unit = {},
            onReady: (TtsFileSynthesizer?) -> Unit,
        ) {
            val holder = arrayOfNulls<TextToSpeech>(1)
            holder[0] =
                TextToSpeech(context.applicationContext) { status ->
                    val engine = holder[0]
                    if (status == TextToSpeech.SUCCESS && engine != null) {
                        configure(engine)
                        onReady(TtsFileSynthesizer(engine))
                    } else {
                        engine?.shutdown()
                        onReady(null)
                    }
                }
        }
    }
}
