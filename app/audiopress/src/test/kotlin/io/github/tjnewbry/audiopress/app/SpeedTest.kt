package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.app.ui.player.fractionAt
import io.github.tjnewbry.audiopress.core.SegmentKind
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the closed label trims trailing zeros`() {
        assertEquals("1x", PlaybackSpeed.closedLabel(1.0f))
        assertEquals("1.1x", PlaybackSpeed.closedLabel(1.1f))
        assertEquals("1.15x", PlaybackSpeed.closedLabel(1.15f))
        assertEquals("0.5x", PlaybackSpeed.closedLabel(0.5f))
        assertEquals("2x", PlaybackSpeed.closedLabel(2.0f))
        assertEquals("1x", PlaybackSpeed.closedLabel(0.95f)) // 0.95x and 1.05x are not offered
        assertEquals("1x", PlaybackSpeed.closedLabel(1.05f))
        assertEquals("0.9x", PlaybackSpeed.closedLabel(0.9f))
        assertEquals("1.1x", PlaybackSpeed.closedLabel(1.1f))
    }

    @Test
    fun `the open label always has two decimals`() {
        assertEquals("1.00x", PlaybackSpeed.openLabel(1.0f))
        assertEquals("1.10x", PlaybackSpeed.openLabel(1.1f))
        assertEquals("1.15x", PlaybackSpeed.openLabel(1.15f))
        assertEquals("0.50x", PlaybackSpeed.openLabel(0.5f))
        assertEquals("2.00x", PlaybackSpeed.openLabel(2.0f))
        assertEquals(5, PlaybackSpeed.openLabel(0.5f).length) // as wide as 0.00x, so the box never changes size
    }

    @Test
    fun `the slider runs from 0_50x at the bottom to 2_00x at the top in steps of 0_05`() {
        assertEquals(0.5f, PlaybackSpeed.fromFraction(0f))
        assertEquals(2.0f, PlaybackSpeed.fromFraction(1f))
        assertEquals(1.25f, PlaybackSpeed.fromFraction(0.5f))
        assertEquals(29, PlaybackSpeed.POSITIONS)
        assertEquals(29, (0..1000).map { PlaybackSpeed.fromFraction(it / 1000f) }.distinct().size)
        val all = (0..1000).map { PlaybackSpeed.fromFraction(it / 1000f) }.toSet()
        assertTrue(0.95f !in all && 1.05f !in all)
        assertEquals(1.0f, PlaybackSpeed.snap(0.95f))
        assertEquals(1.0f, PlaybackSpeed.snap(1.05f))
        assertEquals(0.9f, PlaybackSpeed.snap(0.9f))
        assertEquals(1.1f, PlaybackSpeed.snap(1.1f))
        assertEquals(1f / 3, PlaybackSpeed.toFraction(1.0f), 1e-6f) // the 1x dash is a third of the way up
        // Every position lands on a step, whatever the finger does.
        for (i in 0..1000) {
            val speed = PlaybackSpeed.fromFraction(i / 1000f)
            assertTrue(speed in 0.5f..2.0f)
            assertEquals(speed, PlaybackSpeed.snap(speed))
            assertEquals(0, (Math.round(speed * 100) - 50) % 5)
        }
        assertEquals(1.15f, PlaybackSpeed.fromFraction(PlaybackSpeed.toFraction(1.15f)))
        assertEquals(0.5f, PlaybackSpeed.snap(0.1f))
        assertEquals(2.0f, PlaybackSpeed.snap(9f))
    }

    @Test
    fun `a touch on the slider maps top to the fastest and bottom to the slowest`() {
        assertEquals(1f, fractionAt(0f, 200, 10f))
        assertEquals(0f, fractionAt(200f, 200, 10f))
        assertEquals(0.5f, fractionAt(100f, 200, 10f))
    }

    @Test
    fun `speed is saved per book, defaults to 1, and survives deleting only the bake`() {
        val store = BookStore(File(temp.root, "books"), newId = sequenceOf("a", "b").iterator()::next)
        val a = store.save("A", 1, 1, listOf(StoredParagraph(SegmentKind.PARAGRAPH, 1, "text")))
        val b = store.save("B", 1, 1, listOf(StoredParagraph(SegmentKind.PARAGRAPH, 1, "text")))
        assertEquals(1.0f, store.loadSpeed(a.id))

        store.saveSpeed(a.id, 1.15f)
        store.saveSpeed(b.id, 0.5f)

        assertEquals(1.15f, store.loadSpeed(a.id))
        assertEquals(0.5f, store.loadSpeed(b.id))
        store.deleteBake(a.id)
        assertEquals(1.15f, store.loadSpeed(a.id)) // the speed is not part of the bake
        store.deleteBook(a.id)
        assertEquals(1.0f, store.loadSpeed(a.id)) // gone with the folder
        store.saveSpeed(a.id, 2f) // a deleted book gets no file back
        assertTrue(!File(temp.root, "books/a").exists())
    }

    @Test
    fun `a damaged or out of range speed file reads as a valid speed`() {
        val store = BookStore(File(temp.root, "books"), newId = { "a" })
        val book = store.save("A", 1, 1, listOf(StoredParagraph(SegmentKind.PARAGRAPH, 1, "text")))
        val file = File(temp.root, "books/${book.id}/speed.txt")

        file.writeText("fast")
        assertEquals(1.0f, store.loadSpeed(book.id))
        file.writeText("7.3")
        assertEquals(2.0f, store.loadSpeed(book.id))
        file.writeText("0.95")
        assertEquals(1.0f, store.loadSpeed(book.id))
        file.writeText("1.05")
        assertEquals(1.0f, store.loadSpeed(book.id))
    }

    // The speed does not change the book's timeline.

    private class Output : SpeechOutput {
        var lastRate = 1f
        val spoken = mutableListOf<String>()

        override fun speak(
            text: String,
            utteranceId: String,
        ) {
            spoken += text
        }

        override fun stop() {}

        override fun setRate(rate: Float) {
            lastRate = rate
        }
    }

    @Test
    fun `live speed reaches the speech engine, and a measurement at double speed counts as normal speed`() {
        val utterances = splitIntoUtterances(listOf("one two three four five six", "seven eight"))
        val output = Output()
        val measured = mutableListOf<Pair<Int, Double>>()
        var now = 0L
        val player = ReaderPlayer(utterances, output, clock = { now }, onMeasured = { w, s -> measured += w to s })

        player.setSpeed(2f)
        assertEquals(2f, output.lastRate)
        player.play()
        player.onUtteranceStart("utterance-1")
        now = 1_500
        player.onUtteranceDone("utterance-1")

        // 6 words in 1.5 s at double speed would take 3 s at normal speed: the estimate stays on the 1x timeline.
        assertEquals(6 to 3.0, measured.single())
    }

    @Test
    fun `changing the speed while speaking carries on from the current word at the new rate`() {
        val utterances = splitIntoUtterances(listOf("one two three four"))
        val output = Output()
        val player = ReaderPlayer(utterances, output)
        player.play()
        player.onRange("utterance-1", 4, 7) // "two"

        player.setSpeed(1.5f)

        assertEquals(listOf("one two three four", "two three four"), output.spoken)
        assertEquals(PlaybackStatus.PLAYING, player.state.status)
    }

    @Test
    fun `the timeline is the same at any speed, because it never takes one`() {
        val words = listOf(10, 20, 30)
        val a = BookTimeline(words, listOf(6_000L), 0.35)
        val b = BookTimeline(words, listOf(6_000L), 0.35)

        assertEquals(a.totalMs, b.totalMs)
        assertEquals(a.boundaryShares, b.boundaryShares)
        assertEquals(a.bufferFraction, b.bufferFraction)
    }
}
