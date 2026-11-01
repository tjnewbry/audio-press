package io.github.tjnewbry.audiopress.app

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlin.concurrent.thread

sealed interface TextState {
    data object Loading : TextState

    data class Ready(
        val paragraphs: List<String>,
    ) : TextState

    data class Failed(
        val message: String,
    ) : TextState
}

sealed interface SpeechState {
    data object Starting : SpeechState

    data object Ready : SpeechState

    data class Unavailable(
        val message: String,
    ) : SpeechState
}

/** Builds the sample transcript with the engine and reads it aloud. */
class ReaderViewModel(
    application: Application,
) : AndroidViewModel(application) {
    var text: TextState by mutableStateOf(TextState.Loading)
        private set
    var speech: SpeechState by mutableStateOf(SpeechState.Starting)
        private set
    var player: PlayerState by mutableStateOf(PlayerState())
        private set

    private var paragraphPlayer: ParagraphPlayer? = null
    private val speaker =
        TtsSpeaker(
            application,
            onUtteranceDone = { paragraphPlayer?.onUtteranceDone(it) },
            onUtteranceError = { paragraphPlayer?.onUtteranceError(it) },
            onReady = { problem -> speech = if (problem == null) SpeechState.Ready else SpeechState.Unavailable(problem) },
        )

    init {
        thread(name = "convert") {
            text =
                try {
                    val paragraphs = SampleTextSource().use { convertToParagraphs(it, "sample") }
                    Log.i(TAG, "Transcript ready: ${paragraphs.size} paragraphs")
                    paragraphPlayer = ParagraphPlayer(paragraphs, speaker) { player = it }
                    TextState.Ready(paragraphs)
                } catch (e: Exception) {
                    Log.e(TAG, "Conversion failed", e)
                    TextState.Failed(e.message ?: "The text could not be prepared.")
                }
        }
    }

    fun togglePlayback() {
        paragraphPlayer?.toggle()
    }

    override fun onCleared() {
        speaker.shutdown()
    }

    private companion object {
        const val TAG = "AudioPress"
    }
}
