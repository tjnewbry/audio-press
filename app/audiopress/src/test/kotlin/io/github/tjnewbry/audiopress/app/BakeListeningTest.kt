package io.github.tjnewbry.audiopress.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BakeListeningTest {
    /** A book being baked: clips appear one at a time. */
    private class GrowingClips(
        override val count: Int,
    ) : ClipSource {
        val ready = mutableMapOf<Int, BakedClip>()

        fun land(index: Int) {
            ready[index] = BakedClip(File("clip$index.wav"), 1000L * (index + 1))
        }

        override fun clip(index: Int): BakedClip? = ready[index]
    }

    private class FakeClipPlayer : ClipPlayer {
        override var onCompleted: ((Int) -> Unit)? = null
        override var onError: ((Int) -> Unit)? = null
        override var positionMs = 0
        val started = mutableListOf<Pair<String, Int>>() // file name, from ms

        val lastId get() = ids.last()
        private val ids = mutableListOf<Int>()

        override fun start(
            file: File,
            fromMs: Int,
            id: Int,
        ) {
            started += file.name to fromMs
            ids += id
        }

        override fun pause(): Int = positionMs

        fun finishClip() = onCompleted?.invoke(lastId)
    }

    private val utterances = splitIntoUtterances(listOf("one two", "three four", "five six", "seven eight"))
    private val clips = GrowingClips(4)
    private val clipPlayer = FakeClipPlayer()
    private val player = BakedPlayer(utterances, clips, clipPlayer)

    @Test
    fun `playing the clips that are ready, then waiting for the next one`() {
        clips.land(0)
        clips.land(1)

        player.play()
        assertEquals(listOf("clip0.wav" to 0), clipPlayer.started)
        clipPlayer.finishClip()
        clipPlayer.finishClip() // the second clip ends, and the third has not been baked yet

        assertEquals(PlayerState(PlaybackStatus.PLAYING, 2, waiting = true), player.state)
        assertEquals(listOf("clip0.wav" to 0, "clip1.wav" to 0), clipPlayer.started)
    }

    @Test
    fun `a clip that lands while waiting is played at once, and playback carries on`() {
        clips.land(0)
        player.play()
        clipPlayer.finishClip()
        assertTrue(player.state.waiting)

        clips.land(1)
        player.onClipsReady()

        assertEquals(PlayerState(PlaybackStatus.PLAYING, 1, waiting = false), player.state)
        assertEquals("clip1.wav", clipPlayer.started.last().first)
        player.onClipsReady() // nothing more to do while a clip is playing
        assertEquals(2, clipPlayer.started.size)
    }

    @Test
    fun `a clip landing when playback is not waiting does nothing`() {
        clips.land(0)
        player.play()
        clips.land(1)
        player.onClipsReady()

        assertEquals(1, clipPlayer.started.size)
        assertFalse(player.state.waiting)
    }

    @Test
    fun `tapping a line whose clip is not baked yet waits for it`() {
        clips.land(0)

        player.jumpTo(Position(2, 0))

        assertEquals(PlayerState(PlaybackStatus.PLAYING, 2, waiting = true), player.state)
        assertEquals(emptyList(), clipPlayer.started)
        clips.land(2)
        player.onClipsReady()
        assertEquals(listOf("clip2.wav" to 0), clipPlayer.started)
    }

    @Test
    fun `pausing while waiting stops the wait, and playing again waits again if the clip is still missing`() {
        player.jumpTo(Position(1, 0))
        player.pause()
        assertEquals(PlayerState(PlaybackStatus.PAUSED, 1), player.state)

        player.play()
        assertEquals(PlayerState(PlaybackStatus.PLAYING, 1, waiting = true), player.state)
        clips.land(1)
        player.onClipsReady()
        assertEquals("clip1.wav", clipPlayer.started.last().first)
    }

    @Test
    fun `when the bake ends without the clip, the wait is given up and playback pauses there`() {
        player.jumpTo(Position(3, 0))
        assertTrue(player.state.waiting)

        player.stopWaiting()

        assertEquals(PlayerState(PlaybackStatus.PAUSED, 3), player.state)
        player.stopWaiting() // not waiting any more: nothing happens
        assertEquals(PlaybackStatus.PAUSED, player.state.status)
    }

    @Test
    fun `the length of the book is what is known so far, and all of it once every clip is there`() {
        clips.land(0)
        clips.land(1)
        assertEquals(1000L + 2000L, player.totalMs)

        clips.land(2)
        clips.land(3)
        assertEquals(1000L + 2000L + 3000L + 4000L, player.totalMs)
    }

    @Test
    fun `the time left comes from the rate so far, and is unknown before any progress`() {
        assertNull(estimateRemainingMs(startMs = 0, nowMs = 5_000, wordsAtStart = 0, wordsDone = 0, wordsTotal = 100))
        assertNull(estimateRemainingMs(startMs = 1_000, nowMs = 1_000, wordsAtStart = 0, wordsDone = 10, wordsTotal = 100))

        // 25 words in 10 s is 2.5 words a second, so the other 75 take 30 s.
        assertEquals(30_000L, estimateRemainingMs(startMs = 0, nowMs = 10_000, wordsAtStart = 0, wordsDone = 25, wordsTotal = 100))
        // A bake that resumed with 40 words already done only counts the work done since it started.
        assertEquals(20_000L, estimateRemainingMs(startMs = 0, nowMs = 10_000, wordsAtStart = 40, wordsDone = 70, wordsTotal = 130))
        assertEquals(0L, estimateRemainingMs(startMs = 0, nowMs = 10_000, wordsAtStart = 0, wordsDone = 100, wordsTotal = 100))
    }

    @Test
    fun `the time left is said in minutes and hours`() {
        assertEquals("less than a minute left", formatRemaining(0))
        assertEquals("about 1 min left", formatRemaining(1))
        assertEquals("about 1 min left", formatRemaining(60_000))
        assertEquals("about 2 min left", formatRemaining(60_001))
        assertEquals("about 59 min left", formatRemaining(59 * 60_000L))
        assertEquals("about 1 h 0 min left", formatRemaining(60 * 60_000L))
        assertEquals("about 2 h 5 min left", formatRemaining((2 * 60 + 5) * 60_000L))
    }
}
