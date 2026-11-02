package io.github.tjnewbry.audiopress.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordClockTest {
    private fun at(
        status: PlaybackStatus,
        line: Int,
        word: Int,
    ) = PlayerState(status, line, word, word + 3)

    @Test
    fun `the time of a word runs from when it was reported, at the reading speed`() {
        val clock = WordClock()
        clock.note(at(PlaybackStatus.PLAYING, 0, 0), nowMs = 1_000)

        assertEquals(500, clock.sinceMs(nowMs = 1_500, speed = 1f))
        assertEquals(1_000, clock.sinceMs(nowMs = 1_500, speed = 2f))
        assertEquals(0, clock.sinceMs(nowMs = 900, speed = 1f)) // never negative
    }

    @Test
    fun `words reported inside one line show that the engine reports words`() {
        val clock = WordClock()
        clock.note(at(PlaybackStatus.PLAYING, 0, 0), 0)
        assertFalse(clock.rangesReported) // the first word of a line is set by the player, not reported

        clock.note(at(PlaybackStatus.PLAYING, 0, 4), 300)
        assertTrue(clock.rangesReported)

        clock.note(at(PlaybackStatus.PLAYING, 1, 0), 900) // a new line starts unreported again
        assertFalse(clock.rangesReported)
    }

    @Test
    fun `resuming on the same word starts its time again, so the position does not jump ahead`() {
        val clock = WordClock()
        clock.note(at(PlaybackStatus.PLAYING, 2, 10), nowMs = 1_000)
        clock.note(at(PlaybackStatus.PAUSED, 2, 10), nowMs = 1_200) // paused on that word

        clock.note(at(PlaybackStatus.PLAYING, 2, 10), nowMs = 60_000) // resumed a minute later, same word

        assertEquals(0, clock.sinceMs(nowMs = 60_000, speed = 1f))
        assertEquals(100, clock.sinceMs(nowMs = 60_100, speed = 1f))
    }

    @Test
    fun `a state change on the same word while playing does not restart its time`() {
        val clock = WordClock()
        clock.note(at(PlaybackStatus.PLAYING, 0, 4), nowMs = 1_000)
        clock.note(at(PlaybackStatus.PLAYING, 0, 4).copy(waiting = true), nowMs = 1_400)

        assertEquals(500, clock.sinceMs(nowMs = 1_500, speed = 1f))
    }
}
