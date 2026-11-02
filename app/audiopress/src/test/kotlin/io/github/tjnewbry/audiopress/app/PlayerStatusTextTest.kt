package io.github.tjnewbry.audiopress.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerStatusTextTest {
    private val baking = BakeState.Baking(3, 7)
    private val baked = BakeState.Baked(1_000)
    private val notBaked = BakeState.NotBaked(3, 7)

    private fun text(
        mode: PlaybackMode,
        bake: BakeState,
        waiting: Boolean = false,
    ) = playerStatusText(mode, bake, waiting, elapsedMs = 65_000, totalMs = 130_000)

    @Test
    fun `live speech shows estimated times, whatever the bake is doing`() {
        assertEquals("1:05 / 2:10 (estimated, live speech)", text(PlaybackMode.LIVE, notBaked))
        assertEquals("1:05 / 2:10 (estimated, live speech)", text(PlaybackMode.LIVE, baking))
        assertEquals("1:05 / 2:10 (estimated, live speech)", text(PlaybackMode.LIVE, baked))
    }

    @Test
    fun `baked audio of a finished book says baked audio`() {
        assertEquals("1:05 / 2:10 (baked audio)", text(PlaybackMode.BAKED, baked))
    }

    @Test
    fun `baked audio of a book still being baked says so, and waiting overrides it`() {
        assertEquals("1:05 / 2:10 (listening while baking)", text(PlaybackMode.BAKED, baking))
        assertEquals("Waiting for audio…", text(PlaybackMode.BAKED, baking, waiting = true))
    }

    @Test
    fun `the text follows a toggle between baked and live without the bar being touched`() {
        val states = listOf(PlaybackMode.BAKED, PlaybackMode.LIVE, PlaybackMode.BAKED).map { text(it, baked) }

        assertEquals(
            listOf("1:05 / 2:10 (baked audio)", "1:05 / 2:10 (estimated, live speech)", "1:05 / 2:10 (baked audio)"),
            states,
        )
    }

    @Test
    fun `the text follows a bake that finishes, and one that is cancelled or fails`() {
        // Listening while baking, then the bake finishes: baked audio.
        assertEquals("1:05 / 2:10 (listening while baking)", text(PlaybackMode.BAKED, baking))
        assertEquals("1:05 / 2:10 (baked audio)", text(PlaybackMode.BAKED, baked))
        // Cancelled or failed: the session moves to live speech (see SwitchingPlayback.refreshMode), and the text says so.
        assertEquals("1:05 / 2:10 (estimated, live speech)", text(PlaybackMode.LIVE, notBaked))
        assertEquals("1:05 / 2:10 (estimated, live speech)", text(PlaybackMode.LIVE, BakeState.Failed("x", 3, 7)))
    }

    @Test
    fun `a waiting flag with no bake running does not claim to wait`() {
        assertEquals("1:05 / 2:10 (baked audio)", text(PlaybackMode.BAKED, baked, waiting = true))
    }
}
