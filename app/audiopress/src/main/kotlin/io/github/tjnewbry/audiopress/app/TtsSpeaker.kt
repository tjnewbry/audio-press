package io.github.tjnewbry.audiopress.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * [SpeechOutput] backed by Android's `TextToSpeech`. Speech engine callbacks arrive on a binder thread;
 * they are passed on through the main thread, where the rest of the app runs.
 *
 * [onReady] is called once, with null when speech works or with a message for the user when it cannot.
 */
class TtsSpeaker(
    context: Context,
    private val onUtteranceDone: (String) -> Unit,
    private val onUtteranceError: (String) -> Unit,
    onReady: (problem: String?) -> Unit,
) : SpeechOutput {
    private val main = Handler(Looper.getMainLooper())
    private val tts: TextToSpeech

    init {
        tts =
            TextToSpeech(context.applicationContext) { status ->
                val problem = describeInit(status)
                Log.i(TAG, "TextToSpeech init status=$status problem=$problem")
                main.post { onReady(problem) }
            }
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit

                override fun onDone(utteranceId: String) {
                    main.post { onUtteranceDone(utteranceId) }
                }

                @Deprecated("Replaced by onError(String, Int), which is also implemented.")
                override fun onError(utteranceId: String) {
                    main.post { onUtteranceError(utteranceId) }
                }

                override fun onError(
                    utteranceId: String,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "TextToSpeech error $errorCode for $utteranceId")
                    main.post { onUtteranceError(utteranceId) }
                }
            },
        )
    }

    override fun speak(
        text: String,
        utteranceId: String,
    ) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle.EMPTY, utteranceId)
    }

    override fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }

    private fun describeInit(status: Int): String? {
        if (status != TextToSpeech.SUCCESS) return "This device has no working text-to-speech engine."
        val usable = listOf(Locale.getDefault(), Locale.US).firstOrNull { tts.setLanguage(it) >= TextToSpeech.LANG_AVAILABLE }
        return if (usable == null) "This device has no text-to-speech voice for your language." else null
    }

    private companion object {
        const val TAG = "AudioPressTts"
    }
}
