package io.github.tjnewbry.audiopress.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeekerModelTest {
    // 1 s per word, so a line of 10 words is estimated at 10 s.
    private val slow = 1.0
    private val words = listOf(10, 10, 10, 10)

    @Test
    fun `nothing baked means an estimated scale and no buffer`() {
        val timeline = BookTimeline(words, emptyList(), slow)

        assertEquals(40_000, timeline.totalMs)
        assertEquals(0f, timeline.bufferFraction)
        assertEquals(0, timeline.bakedCount)
        assertEquals(0.25f, timeline.fraction(10_000))
    }

    @Test
    fun `baked time is exact and the rest is estimated, so the buffer is their proportion`() {
        val timeline = BookTimeline(words, listOf(6_000L, 14_000L), slow) // 20 s baked; 20 words left, under the calibration size

        assertEquals(40_000, timeline.totalMs) // 20 s baked + 2 lines x 10 words x 1 s
        assertEquals(20_000, timeline.bakedTotalMs)
        assertEquals(20_000f / 40_000f, timeline.bufferFraction, 1e-6f)
        assertEquals(6_000, timeline.durationMs(0))
        assertEquals(6_000, timeline.startMs(1))
        assertEquals(20_000, timeline.startMs(2))
    }

    @Test
    fun `the buffer grows in steps as clips land and is full when everything is baked`() {
        val steps = (0..4).map { BookTimeline(words, List(it) { 5_000L }, slow).bufferFraction }

        assertEquals(0f, steps[0])
        assertTrue(steps.zipWithNext().all { (a, b) -> b > a })
        assertEquals(1f, steps[4])
    }

    @Test
    fun `enough baked words calibrate the estimate for the rest`() {
        // 40 baked words took 10 s: 250 ms a word. The 10 unbaked words are then 2.5 s, not 10 s.
        val timeline = BookTimeline(listOf(40, 10), listOf(10_000L), slow)

        assertEquals(12_500, timeline.totalMs)
        assertEquals(0.8f, timeline.bufferFraction, 1e-6f)
    }

    @Test
    fun `a clip without a length is not counted as baked`() {
        val timeline = BookTimeline(words, listOf(5_000L, 0L, 5_000L), slow)

        assertEquals(1, timeline.bakedCount)
    }

    @Test
    fun `played fraction is the elapsed time over the whole book, from a word inside a line`() {
        val timeline = BookTimeline(words, listOf(8_000L), slow)

        assertEquals(13_000L, timeline.elapsedMs(1, 5)) // half way into line 1, after the real 8 s
        assertEquals(0L, timeline.elapsedMs(0, 0))
        assertEquals(4_000L, timeline.elapsedMs(0, 5)) // half of the real 8 s
        assertEquals(timeline.totalMs, timeline.elapsedMs(3, 10))
        assertEquals(0.5f, BookTimeline(words, emptyList(), slow).fraction(20_000))
    }

    @Test
    fun `seek maps a share of the book to a line and the offset in it`() {
        val timeline = BookTimeline(words, listOf(6_000L), slow) // total 36 s: 6 + 10 + 10 + 10

        assertEquals(TimePoint(0, 0), timeline.lineAt(0))
        assertEquals(TimePoint(0, 5_999), timeline.lineAt(5_999))
        assertEquals(TimePoint(1, 0), timeline.lineAt(6_000))
        assertEquals(TimePoint(2, 4_000), timeline.lineAt(20_000))
        assertEquals(TimePoint(3, 10_000), timeline.lineAt(1_000_000)) // clamped to the end
        assertEquals(TimePoint(0, 0), timeline.lineAt(-5))
    }

    @Test
    fun `scroll position maps to time with the same scale`() {
        val timeline = BookTimeline(words, listOf(6_000L), slow)

        assertEquals(3_000, timeline.msAt(0, 0.5))
        assertEquals(6_000 + 2_500, timeline.msAt(1, 0.25))
        assertEquals(timeline.startMs(2), timeline.msAt(2, 0.0))
    }

    @Test
    fun `the arrow reaches both ends of the bar and stays inside the text on screen`() {
        val timeline = BookTimeline(List(10) { 10 }, emptyList(), slow) // 100 s, a screen shows 20 s

        assertEquals(0, timeline.indicatorMs(0, 20_000)) // scrolled to the top
        assertEquals(100_000, timeline.indicatorMs(80_000, 100_000)) // scrolled to the bottom
        val middle = timeline.indicatorMs(40_000, 60_000)
        assertTrue(middle in 40_000..60_000)
        assertEquals(50_000, middle)
        assertEquals(50_000, timeline.indicatorMs(0, 100_000)) // the whole book fits: its middle
    }

    @Test
    fun `an empty book has an empty scale`() {
        val timeline = BookTimeline(emptyList(), emptyList(), slow)

        assertEquals(0, timeline.totalMs)
        assertEquals(0f, timeline.fraction(5))
        assertEquals(TimePoint(0, 0), timeline.lineAt(5))
    }

    @Test
    fun `locked follows the player, scrolling unlocks and shows the arrow and the button, returning locks again`() {
        val start = ScrollFollow()
        assertTrue(start.locked)
        assertTrue(start.autoScrolls)
        assertFalse(start.showArrow)
        assertFalse(start.showReturnButton)

        val scrolled = start.userScrolled()
        assertFalse(scrolled.locked)
        assertFalse(scrolled.autoScrolls)
        assertTrue(scrolled.showArrow)
        assertTrue(scrolled.showReturnButton)

        assertEquals(start, scrolled.returned())
    }

    @Test
    fun `the start and end of the book are one cap radius in from the ends of the track`() {
        val scale = barScale(width = 1000, inset = 20f, thickness = 8f) // track from 20 to 980, caps of radius 4

        assertEquals(24f, scale.x(0f))
        assertEquals(976f, scale.x(1f))
        assertEquals(500f, scale.x(0.5f))
        assertEquals(24f, scale.x(-1f)) // clamped: never over a cap
        assertEquals(976f, scale.x(2f))
    }

    @Test
    fun `a touch maps back through the same scale`() {
        val scale = barScale(width = 1000, inset = 20f, thickness = 8f)

        assertEquals(0f, scale.share(0f)) // on or beyond the cap: the start
        assertEquals(0.5f, scale.share(500f))
        assertEquals(1f, scale.share(999f))
        for (share in listOf(0f, 0.25f, 0.8f, 1f)) assertEquals(share, scale.share(scale.x(share)), 1e-5f)
    }

    @Test
    fun `block boundaries are at the starts of lines after the first, baked ones at their real lengths`() {
        val timeline = BookTimeline(words, listOf(6_000L, 14_000L), slow) // 6 + 14 baked, then 10 + 10 estimated: 40 s

        assertEquals(listOf(0.15f, 0.5f, 0.75f), timeline.boundaryShares)
    }

    @Test
    fun `a book of one line has no boundary dots`() {
        assertEquals(emptyList(), BookTimeline(listOf(10), emptyList(), slow).boundaryShares)
    }

    @Test
    fun `dots too close to the one before are left out, and none sit on the end caps`() {
        val scale = barScale(width = 1000, inset = 20f, thickness = 8f) // from 24 to 976
        val many = timelineShares(500)

        val xs = dotPositions(many, scale, minGap = 8f)

        assertTrue(xs.size < many.size)
        assertTrue(xs.zipWithNext().all { (a, b) -> b - a >= 8f })
        assertTrue(xs.all { it > 24f && it < 976f })
        assertEquals(listOf(500f), dotPositions(listOf(0.5f), scale, 8f))
        assertEquals(listOf(scale.x(0.3f), scale.x(0.5f)), dotPositions(listOf(0.3f, 0.3f, 0.5f), scale, 8f))
    }

    private fun timelineShares(lines: Int): List<Float> = BookTimeline(List(lines) { 10 }, emptyList(), slow).boundaryShares

    @Test
    fun `live position moves between word reports, by one word at most when words are reported`() {
        val timeline = BookTimeline(words, emptyList(), slow) // 1 s a word, line 1 starts at 10 s
        val atWord = timeline.elapsedMs(1, 3) // 13 s

        assertEquals(atWord, timeline.elapsedMs(1, 3, 0, true))
        assertEquals(atWord + 400, timeline.elapsedMs(1, 3, 400, true))
        assertEquals(atWord + 1_000, timeline.elapsedMs(1, 3, 5_000, true)) // waits for the next report
    }

    @Test
    fun `live position of an engine that reports no words runs on to the end of the line`() {
        val timeline = BookTimeline(words, emptyList(), slow)

        assertEquals(10_000 + 6_000, timeline.elapsedMs(1, 0, 6_000, false))
        assertEquals(20_000, timeline.elapsedMs(1, 0, 60_000, false)) // never past the line
    }

    // Seeking a baked player: the clip and the offset in it.

    private class RecordingClipPlayer : ClipPlayer {
        override var onCompleted: ((Int) -> Unit)? = null
        override var onError: ((Int) -> Unit)? = null
        override var positionMs = 0
        val started = mutableListOf<Pair<String, Int>>()

        override fun start(
            file: File,
            fromMs: Int,
            id: Int,
        ) {
            started += file.name to fromMs
        }

        override fun pause(): Int = positionMs
    }

    private val utterances = splitIntoUtterances(listOf("one two", "three four", "five six"))
    private val clips = List(3) { BakedClip(File("c$it.wav"), 4_000L) }

    @Test
    fun `seeking while playing starts the clip at the offset`() {
        val clipPlayer = RecordingClipPlayer()
        val player = BakedPlayer(utterances, clips, clipPlayer)
        player.play()

        player.seekTo(Position(1, 0), 2_500, play = true)

        assertEquals("c1.wav" to 2_500, clipPlayer.started.last())
        assertEquals(PlayerState(PlaybackStatus.PLAYING, 1), player.state)
        clipPlayer.positionMs = 2_700
        assertEquals(2_700, player.intoClipMs) // while playing it is the clip player's position
    }

    @Test
    fun `seeking while paused moves there without playing, and play carries on from the offset`() {
        val clipPlayer = RecordingClipPlayer()
        val player = BakedPlayer(utterances, clips, clipPlayer)

        player.seekTo(Position(2, 0), 1_000, play = false)

        assertEquals(PlayerState(PlaybackStatus.PAUSED, 2), player.state)
        assertTrue(clipPlayer.started.isEmpty())
        assertEquals(1_000, player.intoClipMs)
        player.play()
        assertEquals("c2.wav" to 1_000, clipPlayer.started.single())
    }

    @Test
    fun `an offset past the end of the clip is clamped to it`() {
        val player = BakedPlayer(utterances, clips, RecordingClipPlayer())

        player.seekTo(Position(0, 0), 99_000, play = false)

        assertEquals(4_000, player.intoClipMs)
    }
}
