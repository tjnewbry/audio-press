package io.github.tjnewbry.audiopress.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitialEstimateTest {
    @Test
    fun `170 words take about a minute of speech, scaled by the speed of the quality`() {
        assertEquals(60_000L, initialEstimateMs(170, VoiceQuality.MEDIUM))
        assertEquals(75_000L, initialEstimateMs(170, VoiceQuality.LITE))
        assertEquals(72_000L, initialEstimateMs(170, VoiceQuality.HEAVY))
    }

    @Test
    fun `no words, or a negative count, is no time`() {
        assertEquals(0L, initialEstimateMs(0, VoiceQuality.MEDIUM))
        assertEquals(0L, initialEstimateMs(-5, VoiceQuality.MEDIUM))
    }

    @Test
    fun `a long book gets an estimate of hours`() {
        // About 81,000 words (a long novel) at Medium: close to eight hours.
        assertTrue(initialEstimateMs(81_000, VoiceQuality.MEDIUM) in 7L * 3_600_000..9L * 3_600_000)
    }
}
