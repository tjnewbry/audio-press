package io.github.tjnewbry.audiopress.app

/** Something that speaks one utterance at a time. The Android implementation wraps `TextToSpeech`. */
interface SpeechOutput {
    fun speak(
        text: String,
        utteranceId: String,
    )

    fun stop()
}

enum class PlaybackStatus { STOPPED, PLAYING, PAUSED }

/** [index] is the paragraph being spoken, or the one that resumes when playback continues. */
data class PlayerState(
    val status: PlaybackStatus = PlaybackStatus.STOPPED,
    val index: Int = 0,
)

/**
 * Speaks paragraphs in order, one utterance each. Android's speech engine cannot pause, so pausing stops
 * the current paragraph and resuming starts it again from its beginning. Call everything on one thread.
 */
class ParagraphPlayer(
    private val paragraphs: List<String>,
    private val output: SpeechOutput,
    private val onChange: (PlayerState) -> Unit,
) {
    var state = PlayerState()
        private set(value) {
            field = value
            onChange(value)
        }

    private var sequence = 0

    fun toggle() {
        when (state.status) {
            PlaybackStatus.PLAYING -> pause()
            PlaybackStatus.STOPPED, PlaybackStatus.PAUSED -> play()
        }
    }

    fun play() {
        if (paragraphs.isNotEmpty()) speak(state.index)
    }

    fun pause() {
        if (state.status != PlaybackStatus.PLAYING) return
        sequence++ // a callback from the stopped utterance must not move playback on
        output.stop()
        state = state.copy(status = PlaybackStatus.PAUSED)
    }

    /** The speech engine finished the utterance with [utteranceId]. Callbacks for old utterances are ignored. */
    fun onUtteranceDone(utteranceId: String) {
        if (!isCurrent(utteranceId)) return
        val next = state.index + 1
        if (next < paragraphs.size) speak(next) else state = PlayerState()
    }

    /** The speech engine failed on the utterance with [utteranceId]; playback stops where it was. */
    fun onUtteranceError(utteranceId: String) {
        if (isCurrent(utteranceId)) state = state.copy(status = PlaybackStatus.STOPPED)
    }

    private fun isCurrent(utteranceId: String) = state.status == PlaybackStatus.PLAYING && utteranceId == idFor(sequence)

    private fun speak(index: Int) {
        sequence++
        state = PlayerState(PlaybackStatus.PLAYING, index)
        output.speak(paragraphs[index], idFor(sequence))
    }

    private fun idFor(sequence: Int) = "utterance-$sequence"
}
