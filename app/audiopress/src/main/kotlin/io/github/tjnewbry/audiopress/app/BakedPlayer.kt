package io.github.tjnewbry.audiopress.app

import java.io.File

/** Plays one audio file at a time. The Android implementation wraps `MediaPlayer`. */
interface ClipPlayer {
    /** Called when the clip started with [start] ended, with the id [start] was given. */
    var onCompleted: ((id: Int) -> Unit)?

    /** Called when the clip started with [start] could not be played. */
    var onError: ((id: Int) -> Unit)?

    /** Plays at [speed] times the normal speed (pitch kept), also the clip that is playing. */
    fun setSpeed(speed: Float) {}

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
 * Where the clips of a book come from: one clip per utterance, in order. While a book is still being baked a clip
 * may not be there yet, so [clip] can return null for a clip that will appear later.
 */
interface ClipSource {
    /** The number of clips the finished bake will have (one per utterance). */
    val count: Int

    /** Clip number [index], or null if it is not baked yet. */
    fun clip(index: Int): BakedClip?
}

/** The clips of a book that is fully baked. */
class StaticClips(
    private val clips: List<BakedClip>,
) : ClipSource {
    override val count: Int get() = clips.size

    override fun clip(index: Int): BakedClip? = clips.getOrNull(index)
}

/**
 * Plays the clips of [source] (one per utterance, in order) and tracks the current utterance. There is no word timing
 * in baked audio, so the current word is never set. Positions are reported as the start of the utterance. If the book
 * is still being baked and playback reaches a clip that is not there yet, the state is `waiting` until
 * [onClipsReady] says the clip has landed. Call from one thread; the callbacks of [clipPlayer] must come on it too.
 */
class BakedPlayer(
    private val utterances: List<Utterance>,
    private val source: ClipSource,
    private val clipPlayer: ClipPlayer,
    start: Position? = null,
    private val onSave: (Position) -> Unit = {},
    private val onChange: (PlayerState) -> Unit = {},
) : Playback {
    /** For a book that is fully baked. */
    constructor(
        utterances: List<Utterance>,
        clips: List<BakedClip>,
        clipPlayer: ClipPlayer,
        start: Position? = null,
        onSave: (Position) -> Unit = {},
        onChange: (PlayerState) -> Unit = {},
    ) : this(utterances, StaticClips(clips), clipPlayer, start, onSave, onChange)

    init {
        require(source.count == utterances.size) { "${source.count} clips for ${utterances.size} utterances" }
    }

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

    /** The real length of the book, as far as its clips are known, in milliseconds. */
    val totalMs: Long get() = msBefore(source.count)

    /** The real time before the current point, in milliseconds. */
    val elapsedMs: Long get() = msBefore(state.utterance.coerceIn(0, source.count)) + intoClipMs

    /** How far into the current clip playback is (or will resume), in milliseconds. */
    val intoClipMs: Int get() = if (state.status == PlaybackStatus.PLAYING && !state.waiting) clipPlayer.positionMs else pausedAtMs

    private fun msBefore(index: Int): Long = (0 until index).sumOf { source.clip(it)?.durationMs ?: 0L }

    override fun toggle() {
        if (state.status == PlaybackStatus.PLAYING) pause() else play()
    }

    override fun play() {
        if (source.count > 0 && state.status != PlaybackStatus.PLAYING) startClip(state.utterance, pausedAtMs)
    }

    override fun pause() {
        if (state.status != PlaybackStatus.PLAYING) return
        sequence++ // a completion from the clip being stopped must not move playback on
        if (!state.waiting) pausedAtMs = clipPlayer.pause()
        state = state.copy(status = PlaybackStatus.PAUSED, waiting = false)
        onSave(position)
    }

    override fun jumpTo(position: Position) {
        utteranceIndexAt(utterances, position)?.let { startClip(it, 0) }
    }

    override fun seekTo(
        position: Position,
        offsetMs: Long,
        play: Boolean,
    ) {
        val index = utteranceIndexAt(utterances, position) ?: return
        val length = source.clip(index)?.durationMs ?: 0L // a clip not baked yet starts from its beginning
        val into = offsetMs.coerceIn(0L, length).toInt()
        if (play) {
            startClip(index, into)
            return
        }
        if (state.status == PlaybackStatus.PLAYING) {
            sequence++ // a completion from the clip being stopped must not move playback on
            if (!state.waiting) clipPlayer.pause()
        }
        pausedAtMs = into
        state = PlayerState(PlaybackStatus.PAUSED, index)
        onSave(this.position)
    }

    override fun setSpeed(speed: Float) = clipPlayer.setSpeed(speed)

    override fun moveTo(position: Position) {
        if (state.status == PlaybackStatus.PLAYING) {
            sequence++
            clipPlayer.pause()
        }
        setStopped(position)
    }

    /** More clips may have landed: if playback is waiting for one, carry on. */
    fun onClipsReady() {
        if (state.status != PlaybackStatus.PLAYING || !state.waiting) return
        val clip = source.clip(state.utterance) ?: return
        sequence++
        state = state.copy(waiting = false)
        clipPlayer.start(clip.file, pausedAtMs, sequence)
    }

    /** The bake ended without the clip playback is waiting for; stop waiting and pause there. */
    fun stopWaiting() {
        if (state.waiting) pause()
    }

    private fun setStopped(position: Position) {
        pausedAtMs = 0
        state = stateAt(position)
    }

    private fun stateAt(position: Position?): PlayerState {
        if (position == null || source.count == 0 || position == Position(0, 0)) return PlayerState()
        return PlayerState(PlaybackStatus.PAUSED, locate(utterances, position).first)
    }

    private fun startClip(
        index: Int,
        fromMs: Int,
    ) {
        sequence++
        pausedAtMs = fromMs
        val clip = source.clip(index)
        state = PlayerState(PlaybackStatus.PLAYING, index, waiting = clip == null)
        onSave(position)
        clip?.let { clipPlayer.start(it.file, fromMs, sequence) }
    }

    private fun onClipCompleted() {
        val next = state.utterance + 1
        if (next < source.count) {
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
