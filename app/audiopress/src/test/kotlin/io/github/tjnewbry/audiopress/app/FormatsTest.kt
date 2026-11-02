package io.github.tjnewbry.audiopress.app

import kotlin.test.Test
import kotlin.test.assertEquals

// The size formats have their own tests next to the screens that use them (BookInfoTest, ExportTest, BookBakeControllerTest).
class FormatsTest {
    @Test
    fun `page counts say page or pages`() {
        assertEquals("1 page", formatPages(1))
        assertEquals("30 pages", formatPages(30))
        assertEquals("0 pages", formatPages(0))
    }

    @Test
    fun `percentages are whole and rounded`() {
        assertEquals("0%", formatPercent(0f))
        assertEquals("42%", formatPercent(0.424f))
        assertEquals("43%", formatPercent(0.426f))
        assertEquals("100%", formatPercent(1f))
    }

    @Test
    fun `durations show minutes and seconds, and hours once there are any`() {
        assertEquals("0:00", formatDuration(0.0))
        assertEquals("1:05", formatDuration(65.9))
        assertEquals("1:02:03", formatDuration(3723.0))
        assertEquals("0:00", formatDuration(-5.0))
    }

    @Test
    fun `download sizes are whole decimal megabytes`() {
        assertEquals("137 MB", formatMegabytes(137_400_000))
        assertEquals("0 MB", formatMegabytes(0))
    }

    @Test
    fun `a bake that is stopping, or running, is recognised from its state`() {
        val baking = BakeState.Baking(1, 7)
        assertEquals(false, baking.isCancelling)
        assertEquals(true, baking.copy(cancelling = true).isCancelling)
        assertEquals(true, BakeState.Downloading(VoiceQuality.LITE, 0, 10, cancelling = true).isCancelling)
        assertEquals(false, BakeState.Baked(5).isCancelling)
        assertEquals(true, baking.isRunning)
        assertEquals(false, BakeState.NotBaked(0, 7).isRunning)
    }
}
