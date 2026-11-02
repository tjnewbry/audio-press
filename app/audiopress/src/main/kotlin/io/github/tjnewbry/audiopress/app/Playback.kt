package io.github.tjnewbry.audiopress.app

/** What the reader needs from a player, whether it speaks live or plays baked audio. */
interface Playback {
    val state: PlayerState

    /** Where playback is, as a paragraph and a character offset in it. */
    val position: Position

    fun toggle()

    fun play()

    fun pause()

    /** Starts playing from the utterance that holds [position] (the next one, if that paragraph has no text). */
    fun jumpTo(position: Position)

    /** Stops if playing, and sets the place where [play] will continue, without starting. */
    fun moveTo(position: Position)

    /**
     * Goes to the utterance that holds [position], [offsetMs] milliseconds into its audio where the player can do that,
     * and plays from there if [play], else waits there paused. Live speech always starts at the utterance.
     */
    fun seekTo(
        position: Position,
        offsetMs: Long,
        play: Boolean,
    ) {
        if (play) jumpTo(position) else moveTo(position)
    }
}

enum class PlaybackMode { LIVE, BAKED }

/**
 * Plays live while a book is not baked and plays the baked audio once it is, carrying the position across. The
 * mode only changes when the user starts playback (play or tap a line), never in the middle of a sentence; the
 * position is shared, so progress saved in one mode resumes in the other.
 */
class SwitchingPlayback(
    private val live: Playback,
    private val baked: () -> Playback?,
    private val onModeChange: (PlaybackMode) -> Unit = {},
) {
    var mode: PlaybackMode = if (baked() != null) PlaybackMode.BAKED else PlaybackMode.LIVE
        private set

    /** When true the book is read with live speech even though baked audio exists; takes effect when playback next starts. */
    var preferLive: Boolean = false

    /**
     * True after a seek into a part of a book that is still being baked: that part is read with live speech, so playback
     * stays live until a seek or jump lands in baked audio (or the book is fully baked).
     */
    var liveForNow: Boolean = false

    private val active: Playback get() = if (mode == PlaybackMode.BAKED) baked() ?: live else live

    val state: PlayerState get() = active.state
    val position: Position get() = active.position

    fun toggle() {
        if (active.state.status == PlaybackStatus.PLAYING) {
            active.pause()
        } else {
            chooseMode()
            active.play()
        }
    }

    fun jumpTo(position: Position) {
        chooseMode()
        active.jumpTo(position)
    }

    fun seekTo(
        position: Position,
        offsetMs: Long,
        play: Boolean,
    ) {
        chooseMode()
        active.seekTo(position, offsetMs, play)
    }

    /** Switches to the mode that now applies (when the user chose live or baked, or a bake ended), pausing, at the same place. */
    fun refreshMode() = chooseMode()

    fun pause() = active.pause()

    private fun chooseMode() {
        val wanted = if (baked() != null && !preferLive && !liveForNow) PlaybackMode.BAKED else PlaybackMode.LIVE
        if (wanted == mode) return
        val from = active
        val where = from.position
        from.pause()
        mode = wanted
        onModeChange(wanted) // before moveTo, so the new player's state change is the one the screen follows
        active.moveTo(where)
    }
}
