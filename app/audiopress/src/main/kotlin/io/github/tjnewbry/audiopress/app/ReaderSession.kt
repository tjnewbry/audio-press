package io.github.tjnewbry.audiopress.app

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

sealed interface SpeechState {
    data object Starting : SpeechState

    data object Ready : SpeechState

    data class Unavailable(
        val message: String,
    ) : SpeechState
}

/**
 * An open book: its [paragraphs], the text-to-speech that reads them, the current position, and the time
 * estimate. Create it on the main thread and call [close] when the book is closed, which pauses (and so saves
 * the position) and releases the speech engine. [saveProgress] is called on a background thread.
 */
class ReaderSession(
    context: Context,
    val book: Book,
    val paragraphs: List<StoredParagraph>,
    initialPosition: Position?,
    private val saveProgress: (Position) -> Unit,
) {
    private val utterances =
        splitIntoUtterances(paragraphs.map { it.text }, minOf(TextToSpeech.getMaxSpeechInputLength() - 100, MAX_UTTERANCE_CHARS))
    private val estimator = TimeEstimator(utterances.map { it.words.size })
    private val saver = Executors.newSingleThreadExecutor { Thread(it, "save-progress") }

    var speech: SpeechState by mutableStateOf(SpeechState.Starting)
        private set
    var player: PlayerState by mutableStateOf(PlayerState())
        private set

    private var readerPlayer: ReaderPlayer? = null
    private val speaker =
        TtsSpeaker(
            context,
            onUtteranceStart = { readerPlayer?.onUtteranceStart(it) },
            onRange = { id, start, end -> readerPlayer?.onRange(id, start, end) },
            onUtteranceDone = { readerPlayer?.onUtteranceDone(it) },
            onUtteranceError = { readerPlayer?.onUtteranceError(it) },
            onReady = { problem -> speech = if (problem == null) SpeechState.Ready else SpeechState.Unavailable(problem) },
        )

    init {
        readerPlayer =
            ReaderPlayer(
                utterances,
                speaker,
                initialPosition,
                onMeasured = estimator::record,
                onSave = { position -> if (!saver.isShutdown) saver.execute { runCatching { saveProgress(position) } } },
                onChange = { player = it },
            )
        player = readerPlayer?.state ?: PlayerState()
    }

    /** The paragraph being read, or the one where reading resumes. */
    val currentParagraph: Int get() = utterances.getOrNull(player.utterance)?.paragraph ?: 0

    /** The current word as a range of characters in the current paragraph's text, or null before anything has been read. */
    val currentWord: IntRange?
        get() {
            val utterance = utterances.getOrNull(player.utterance) ?: return null
            if (player.status == PlaybackStatus.STOPPED || player.wordEnd <= player.wordStart) return null
            return (utterance.start + player.wordStart) until (utterance.start + player.wordEnd)
        }

    /** Time before the current word, estimated from word counts and refined by measured speaking time. */
    fun elapsedSeconds(): Double {
        val utterance = utterances.getOrNull(player.utterance) ?: return 0.0
        val wordsBefore = utterance.words.count { it.first < player.wordStart }
        return estimator.elapsedSeconds(player.utterance, wordsBefore)
    }

    fun totalSeconds(): Double = estimator.totalSeconds()

    fun togglePlayback() {
        readerPlayer?.toggle()
    }

    /** Starts reading from the beginning of paragraph [index]. */
    fun jumpTo(index: Int) {
        readerPlayer?.jumpToParagraph(index)
    }

    fun close() {
        readerPlayer?.pause()
        speaker.shutdown()
        saver.shutdown() // lets the last save finish
    }
}
