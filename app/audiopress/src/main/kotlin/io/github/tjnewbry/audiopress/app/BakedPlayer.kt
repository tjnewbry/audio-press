package io.github.tjnewbry.audiopress.app

import java.io.File

/** Plays one audio file at a time. The Android implementation wraps `MediaPlayer`. */
interface ClipPlayer {
    /** Called when the clip started with [start] ended, with the id [start] was given. */
    var onCompleted: ((id: Int) -> Unit)?

    /** Called when the clip started with [start] could not be played. */
    var onError: ((id: Int) -> Unit)?

    /** The position in the current clip, in milliseconds. */
    val positionMs: Int

    /** Plays [file] from [fromMs], replacing anything playing. */
    fun start(
        file: File,
        fromMs: Int,
        id: Int,
    )

    /** Stops playing and returns the position reached, so the clip can be continued later. */
    fun pause(): Int
}

/**
 * Plays [clips] (one per utterance, in order, with real durations) and tracks the current utterance. There is
 * no word timing in baked audio, so the current word is never set. Positions are reported as the start of the
 * utterance. Call from one thread; the callbacks of [clipPlayer] must come on it too.
 */
class BakedPlayer(
    private val utterances: List<Utterance>,
    private val clips: List<BakedClip>,
    private val clipPlayer: ClipPlayer,
    start: Position? = null,
    private val onSave: (Position) -> Unit = {},
    private val onChange: (PlayerState) -> Unit = {},
) : Playback {
    init {
        require(clips.size == utterances.size) { "${clips.size} clips for ${utterances.size} utterances" }
    }

    private val msBefore: LongArray =
        LongArray(clips.size + 1).also { sums -> clips.forEachIndexed { i, clip -> sums[i + 1] = sums[i] + clip.durationMs } }

    private var pausedAtMs = 0
    private var sequence = 0

    final override var state = stateAt(start)
        private set(value) {
            field = value
            onChange(value)
        }

    init {
        clipPlayer.onCompleted = { id -> if (id == sequence) onClipCompleted() }
        clipPlayer.onError = { id -> if (id == sequence) onClipFailed() }
    }

    override val position: Position
        get() {
            val utterance = utterances.getOrNull(state.utterance) ?: return Position(0, 0)
            return Position(utterance.paragraph, utterance.start)
        }

    val totalMs: Long get() = msBefore.last()

    /** The real time before the current point, in milliseconds. */
    val elapsedMs: Long
        get() {
            val into = if (state.status == PlaybackStatus.PLAYING) clipPlayer.positionMs else pausedAtMs
            return msBefore[state.utterance.coerceIn(0, clips.size)] + into
        }

    override fun toggle() {
        if (state.status == PlaybackStatus.PLAYING) pause() else play()
    }

    override fun play() {
        if (clips.isNotEmpty() && state.status != PlaybackStatus.PLAYING) startClip(state.utterance, pausedAtMs)
    }

    override fun pause() {
        if (state.status != PlaybackStatus.PLAYING) return
        sequence++ // a completion from the clip being stopped must not move playback on
        pausedAtMs = clipPlayer.pause()
        state = state.copy(status = PlaybackStatus.PAUSED)
        onSave(position)
    }

    override fun jumpToParagraph(paragraph: Int) {
        val index = utterances.indexOfFirst { it.paragraph >= paragraph }
        if (index >= 0) startClip(index, 0)
    }

    override fun moveTo(position: Position) {
        if (state.status == PlaybackStatus.PLAYING) {
            sequence++
            clipPlayer.pause()
        }
        setStopped(position)
    }

    private fun setStopped(position: Position) {
        pausedAtMs = 0
        state = stateAt(position)
    }

    private fun stateAt(position: Position?): PlayerState {
        if (position == null || clips.isEmpty() || position == Position(0, 0)) return PlayerState()
        return PlayerState(PlaybackStatus.PAUSED, locate(utterances, position).first)
    }

    private fun startClip(
        index: Int,
        fromMs: Int,
    ) {
        sequence++
        pausedAtMs = fromMs
        state = PlayerState(PlaybackStatus.PLAYING, index)
        onSave(position)
        clipPlayer.start(clips[index].file, fromMs, sequence)
    }

    private fun onClipCompleted() {
        val next = state.utterance + 1
        if (next < clips.size) {
            startClip(next, 0)
        } else {
            pausedAtMs = 0
            state = PlayerState() // the book is finished: back to the beginning
            onSave(Position(0, 0))
        }
    }

    private fun onClipFailed() {
        pausedAtMs = 0
        state = state.copy(status = PlaybackStatus.PAUSED)
        onSave(position)
    }
}
