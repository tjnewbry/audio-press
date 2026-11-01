package io.github.tjnewbry.audiopress.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeEstimatorTest {
    @Test
    fun `starts at 170 words per minute`() {
        val estimator = TimeEstimator(listOf(170, 340))

        assertEquals(3.0 * 60, estimator.totalSeconds(), 1e-9)
        assertEquals(170.0, estimator.wordsPerMinute, 1e-9)
    }

    @Test
    fun `elapsed time counts the words before the current one`() {
        val estimator = TimeEstimator(listOf(170, 340))

        assertEquals(0.0, estimator.elapsedSeconds(0, 0), 1e-9)
        assertEquals(60.0, estimator.elapsedSeconds(1, 0), 1e-9)
        assertEquals(90.0, estimator.elapsedSeconds(1, 85), 1e-9)
        assertEquals(estimator.totalSeconds(), estimator.elapsedSeconds(2, 0), 1e-9)
    }

    @Test
    fun `measured speech moves the rate, a little at first and then more`() {
        val estimator = TimeEstimator(listOf(1000))

        estimator.record(words = 50, seconds = 15.0) // 200 wpm
        val afterOne = estimator.wordsPerMinute
        assertTrue(afterOne in 170.0..200.0, "moved toward 200: $afterOne")

        repeat(40) { estimator.record(words = 50, seconds = 15.0) }
        assertTrue(estimator.wordsPerMinute in 195.0..200.0, "close to 200: ${estimator.wordsPerMinute}")
        assertTrue(estimator.totalSeconds() < 1000 * 60.0 / 170)
    }

    @Test
    fun `tiny or impossible measurements are ignored`() {
        val estimator = TimeEstimator(listOf(1000))

        estimator.record(words = 2, seconds = 0.1)
        estimator.record(words = 50, seconds = 0.0)
        estimator.record(words = 50, seconds = 1.0) // 3000 wpm
        estimator.record(words = 50, seconds = 600.0) // 5 wpm

        assertEquals(170.0, estimator.wordsPerMinute, 1e-9)
    }

    @Test
    fun `durations are formatted as minutes and seconds, with hours when needed`() {
        assertEquals("0:00", formatDuration(0.0))
        assertEquals("1:05", formatDuration(65.4))
        assertEquals("59:59", formatDuration(3599.9))
        assertEquals("1:02:03", formatDuration(3723.0))
        assertEquals("0:00", formatDuration(-5.0))
    }
}
