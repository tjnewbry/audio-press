package io.github.tjnewbry.audiopress.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface SpeechState {
    data object Starting : SpeechState

    data object Ready : SpeechState

    data class Unavailable(
        val message: String,
    ) : SpeechState
}

/**
 * An open book: its [paragraphs] and the text-to-speech that reads them. Create it on the main thread and
 * call [close] when the book is closed, which stops speech and releases the speech engine.
 */
class ReaderSession(
    context: Context,
    val book: Book,
    val paragraphs: List<StoredParagraph>,
) {
    var speech: SpeechState by mutableStateOf(SpeechState.Starting)
        private set
    var player: PlayerState by mutableStateOf(PlayerState())
        private set

    private var paragraphPlayer: ParagraphPlayer? = null
    private val speaker =
        TtsSpeaker(
            context,
            onUtteranceDone = { paragraphPlayer?.onUtteranceDone(it) },
            onUtteranceError = { paragraphPlayer?.onUtteranceError(it) },
            onReady = { problem -> speech = if (problem == null) SpeechState.Ready else SpeechState.Unavailable(problem) },
        )

    init {
        paragraphPlayer = ParagraphPlayer(paragraphs.map { it.text }, speaker) { player = it }
    }

    fun togglePlayback() {
        paragraphPlayer?.toggle()
    }

    fun close() {
        paragraphPlayer?.pause()
        speaker.shutdown()
    }
}
