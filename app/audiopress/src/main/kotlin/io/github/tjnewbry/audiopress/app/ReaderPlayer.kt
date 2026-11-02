package io.github.tjnewbry.audiopress.app

/** Something that speaks one utterance at a time. The Android implementation wraps `TextToSpeech`. */
interface SpeechOutput {
    fun speak(
        text: String,
        utteranceId: String,
    )

    fun stop()

    /** Speaks at [rate] times the normal rate (1 is normal), for what is spoken from now on. */
    fun setRate(rate: Float) {}
}

enum class PlaybackStatus { STOPPED, PLAYING, PAUSED }

/**
 * [utterance] is the one being spoken, or the one that resumes. [wordStart] to [wordEnd] is the current word as
 * character offsets in that utterance's text (the word where playback resumes, when paused).
 */
data class PlayerState(
    val status: PlaybackStatus = PlaybackStatus.STOPPED,
    val utterance: Int = 0,
    val wordStart: Int = 0,
    val wordEnd: Int = 0,
    /** True while playing but waiting for the next clip of a book that is still being baked. */
    val waiting: Boolean = false,
)

/**
 * Speaks [utterances] in order, one at a time, and tracks the current word. Android's speech engine cannot pause,
 * so pausing stops it and resuming speaks again from the word that was being said. It reports the position to
 * [onSave] when an utterance starts, on pause, when the book ends, and at most every [SAVE_INTERVAL_MS] while
 * words go by. Call everything from one thread; [clock] is in milliseconds.
 *
 * [start] is the saved position to resume from. A book with a saved position starts paused there.
 */
class ReaderPlayer(
    private val utterances: List<Utterance>,
    private val output: SpeechOutput,
    start: Position? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onMeasured: (words: Int, seconds: Double) -> Unit = { _, _ -> },
    private val onSave: (Position) -> Unit = {},
    private val onChange: (PlayerState) -> Unit = {},
) : Playback {
    final override var state = initialState(start)
        private set(value) {
            field = value
            onChange(value)
        }

    private var sequence = 0
    private var speed = 1f
    private var spokenFrom = 0
    private var startedAt: Long? = null
    private var lastSavedAt = Long.MIN_VALUE

    /** Where playback is, as a paragraph and a character offset in it. */
    override val position: Position
        get() {
            val utterance = utterances.getOrNull(state.utterance) ?: return Position(0, 0)
            return Position(utterance.paragraph, utterance.start + state.wordStart)
        }

    override fun toggle() {
        if (state.status == PlaybackStatus.PLAYING) pause() else play()
    }

    override fun play() {
        if (utterances.isNotEmpty() && state.status != PlaybackStatus.PLAYING) speakFrom(state.utterance, state.wordStart)
    }

    override fun pause() {
        if (state.status != PlaybackStatus.PLAYING) return
        sequence++ // a callback from the stopped utterance must not move playback on
        output.stop()
        state = state.copy(status = PlaybackStatus.PAUSED)
        save()
    }

    override fun jumpTo(position: Position) {
        utteranceIndexAt(utterances, position)?.let { speakFrom(it, 0) }
    }

    override fun setSpeed(speed: Float) {
        if (speed == this.speed) return
        this.speed = speed
        output.setRate(speed)
        // The engine cannot change the rate of what it is saying, so carry on from the current word at the new rate.
        if (state.status == PlaybackStatus.PLAYING) speakFrom(state.utterance, state.wordStart)
    }

    override fun moveTo(position: Position) {
        if (state.status == PlaybackStatus.PLAYING) {
            sequence++ // callbacks from the utterance being stopped must not move playback on
            output.stop()
        }
        state = initialState(position)
    }

    /** The engine began speaking the utterance with [utteranceId]. Callbacks for old utterances are ignored. */
    fun onUtteranceStart(utteranceId: String) {
        if (isCurrent(utteranceId)) startedAt = clock()
    }

    /** The engine is about to say the characters [start] to [end] of the text it was given. */
    fun onRange(
        utteranceId: String,
        start: Int,
        end: Int,
    ) {
        if (!isCurrent(utteranceId)) return
        val offset = spokenFrom + start
        val word = utterances[state.utterance].words.firstOrNull { offset <= it.last } ?: return
        state = state.copy(wordStart = word.first, wordEnd = word.last + 1)
        if (clock() - lastSavedAt >= SAVE_INTERVAL_MS) save()
    }

    /** The engine finished the utterance with [utteranceId]. */
    fun onUtteranceDone(utteranceId: String) {
        if (!isCurrent(utteranceId)) return
        val began = startedAt
        if (spokenFrom == 0 && began != null) {
            // Measured at the speed it was spoken at; the estimate is for normal speed, so it is scaled back.
            onMeasured(utterances[state.utterance].words.size, (clock() - began) / MS_PER_SECOND * speed)
        }
        val next = state.utterance + 1
        if (next < utterances.size) {
            speakFrom(next, 0)
        } else {
            state = PlayerState() // the book is finished: back to the beginning, ready to start again
            save()
        }
    }

    /** The engine failed on the utterance with [utteranceId]; playback stops where it was. */
    fun onUtteranceError(utteranceId: String) {
        if (!isCurrent(utteranceId)) return
        state = state.copy(status = PlaybackStatus.PAUSED)
        save()
    }

    private fun isCurrent(utteranceId: String) = state.status == PlaybackStatus.PLAYING && utteranceId == idFor(sequence)

    private fun speakFrom(
        index: Int,
        offset: Int,
    ) {
        sequence++
        spokenFrom = offset
        startedAt = null
        val utterance = utterances[index]
        val word = utterance.words.firstOrNull { offset <= it.last } ?: IntRange(offset, offset - 1)
        state = PlayerState(PlaybackStatus.PLAYING, index, word.first, word.last + 1)
        save()
        output.speak(utterance.text.substring(offset), idFor(sequence))
    }

    private fun save() {
        lastSavedAt = clock()
        onSave(position)
    }

    private fun initialState(start: Position?): PlayerState {
        if (start == null || utterances.isEmpty() || start == Position(0, 0)) return PlayerState()
        val (index, offset) = locate(utterances, start)
        val word = utterances[index].words.firstOrNull { offset <= it.last }
        return PlayerState(PlaybackStatus.PAUSED, index, word?.first ?: 0, (word?.last ?: -1) + 1)
    }

    private fun idFor(sequence: Int) = "utterance-$sequence"

    companion object {
        const val SAVE_INTERVAL_MS = 3000L
        private const val MS_PER_SECOND = 1000.0
    }
}
