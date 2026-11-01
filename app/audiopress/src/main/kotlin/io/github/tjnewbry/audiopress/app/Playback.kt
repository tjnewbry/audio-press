package io.github.tjnewbry.audiopress.app

/** What the reader needs from a player, whether it speaks live or plays baked audio. */
interface Playback {
    val state: PlayerState

    /** Where playback is, as a paragraph and a character offset in it. */
    val position: Position

    fun toggle()

    fun play()

    fun pause()

    /** Starts playing from the beginning of [paragraph] (or the next one with text). */
    fun jumpToParagraph(paragraph: Int)

    /** Stops if playing, and sets the place where [play] will continue, without starting. */
    fun moveTo(position: Position)
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

    fun jumpToParagraph(paragraph: Int) {
        chooseMode()
        active.jumpToParagraph(paragraph)
    }

    fun pause() = active.pause()

    private fun chooseMode() {
        val wanted = if (baked() != null) PlaybackMode.BAKED else PlaybackMode.LIVE
        if (wanted == mode) return
        val from = active
        val where = from.position
        from.pause()
        mode = wanted
        onModeChange(wanted) // before moveTo, so the new player's state change is the one the screen follows
        active.moveTo(where)
    }
}
