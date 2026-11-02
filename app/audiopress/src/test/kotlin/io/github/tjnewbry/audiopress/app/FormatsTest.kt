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
    fun `every size is in decimal units with one decimal for megabytes and gigabytes`() {
        assertEquals("0 bytes", formatSize(0))
        assertEquals("512 bytes", formatSize(512))
        assertEquals("999 bytes", formatSize(999))
        assertEquals("1 KB", formatSize(1_000))
        assertEquals("2 KB", formatSize(2_048))
        assertEquals("640 KB", formatSize(640_000))
        assertEquals("999 KB", formatSize(999_400))
        assertEquals("1.0 MB", formatSize(1_000_000))
        assertEquals("21.4 MB", formatSize(21_400_000))
        assertEquals("83.4 MB", formatSize(83_400_000))
        assertEquals("100.0 MB", formatSize(100_000_000))
        assertEquals("137.4 MB", formatSize(137_400_000))
        assertEquals("999.6 MB", formatSize(999_600_000))
        assertEquals("1.2 GB", formatSize(1_234_000_000))
        assertEquals("1.8 GB", formatSize(1_823_000_000))
    }

    @Test
    fun `a size that rounds up to the next unit is shown in it`() {
        assertEquals("1.0 MB", formatSize(999_700)) // not 1000 KB
        assertEquals("1.0 GB", formatSize(999_960_000)) // not 1000.0 MB
        assertEquals("0 bytes", formatSize(-5)) // never negative
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
