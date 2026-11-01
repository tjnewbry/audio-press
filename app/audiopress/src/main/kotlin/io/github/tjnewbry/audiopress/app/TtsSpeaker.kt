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
    private val onUtteranceStart: (String) -> Unit,
    private val onRange: (id: String, start: Int, end: Int) -> Unit,
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
                override fun onStart(utteranceId: String) {
                    main.post { onUtteranceStart(utteranceId) }
                }

                override fun onRangeStart(
                    utteranceId: String,
                    start: Int,
                    end: Int,
                    frame: Int,
                ) {
                    main.post { onRange(utteranceId, start, end) }
                }

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
        if (status != TextToSpeech.SUCCESS) return speechProblem(status, TextToSpeech.ERROR)
        val results = listOf(Locale.getDefault(), Locale.US).map { tts.setLanguage(it) }
        // Reading in English is fine when the phone's own language has no voice, so the best of the two counts.
        return speechProblem(status, results.maxOrNull() ?: TextToSpeech.ERROR, results.first())
    }

    private companion object {
        const val TAG = "AudioPressTts"
    }
}

const val NO_ENGINE_MESSAGE =
    "This phone has no working text-to-speech engine. Install or turn on one in Settings, under " +
        "Text-to-speech output, then open the book again."

/**
 * The message to show when speech cannot start, or null when it can. [initStatus] is what `TextToSpeech` reported
 * when it started; [bestLanguage] is the best result of trying the phone's language and then English (`setLanguage`
 * results, higher is better); [phoneLanguage] is the result for the phone's own language, used to say what is wrong.
 */
fun speechProblem(
    initStatus: Int,
    bestLanguage: Int,
    phoneLanguage: Int = bestLanguage,
): String? =
    when {
        initStatus != TextToSpeech.SUCCESS -> {
            NO_ENGINE_MESSAGE
        }

        bestLanguage >= TextToSpeech.LANG_AVAILABLE -> {
            null
        }

        phoneLanguage == TextToSpeech.LANG_MISSING_DATA -> {
            "The text-to-speech voice for your language is not installed. Download its voice data in Settings, under " +
                "Text-to-speech output."
        }

        else -> {
            "The text-to-speech engine does not support your language. Choose another engine or language in Settings, " +
                "under Text-to-speech output."
        }
    }
