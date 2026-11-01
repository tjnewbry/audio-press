package io.github.tjnewbry.audiopress.bake

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BakedBookTest {
    @get:Rule
    val temp = TemporaryFolder()

    // Three utterances: 1000 ms, 2000 ms, 500 ms. The first two are in segment 0, the third in segment 1.
    private val items =
        listOf(
            BakedUtterance(0, "00000.wav", segmentIndex = 0, start = 0, end = 100, page = 1, durationMs = 1000),
            BakedUtterance(1, "00001.wav", segmentIndex = 0, start = 100, end = 300, page = 1, durationMs = 2000),
            BakedUtterance(2, "00002.wav", segmentIndex = 1, start = 0, end = 50, page = 2, durationMs = 500),
        )
    private val book = BakedBook(File("x"), "fp", items, isComplete = true)

    @Test
    fun `the total is the sum of the durations and each start is the sum before it`() {
        assertEquals(3500, book.totalDurationMs)
        assertEquals(listOf(0L, 1000L, 3000L), items.indices.map { book.startMs(it) })
    }

    @Test
    fun `a playback position maps to the utterance and the offset into it`() {
        assertEquals(BakedPosition(0, 0, 0), book.locate(0))
        assertEquals(0, book.locate(999)!!.utteranceIndex)
        assertEquals(BakedPosition(1, 0, 100), book.locate(1000))
        assertEquals(BakedPosition(1, 1000, 200), book.locate(2000))
        assertEquals(BakedPosition(2, 100, 10), book.locate(3100))
    }

    @Test
    fun `positions outside the book are clamped to its ends`() {
        assertEquals(0, book.locate(-50)!!.utteranceIndex)
        assertEquals(2, book.locate(99_999)!!.utteranceIndex)
        assertEquals(499, book.locate(99_999)!!.offsetMs)
    }

    @Test
    fun `an empty bake has no position`() {
        assertNull(BakedBook(File("x"), "fp", emptyList(), isComplete = false).locate(0))
    }

    @Test
    fun `positionMs is the reverse of locate`() {
        for (position in listOf(0L, 1L, 999L, 1000L, 2500L, 3000L, 3499L)) {
            val at = book.locate(position)!!
            assertEquals(position, book.positionMs(at.utteranceIndex, at.offsetMs))
        }
        assertEquals(3000, book.positionMs(2))
        assertEquals(3500, book.positionMs(2, 99_999), "an offset past the end stops at the end of the utterance")
    }

    @Test
    fun `a character in a segment maps to a position, by proportion within its utterance`() {
        assertEquals(0L, book.positionOfSegmentChar(0, 0))
        assertEquals(1000L, book.positionOfSegmentChar(0, 100))
        assertEquals(2000L, book.positionOfSegmentChar(0, 200))
        assertEquals(3250L, book.positionOfSegmentChar(1, 25))
        assertNull(book.positionOfSegmentChar(1, 50), "past the end of the segment")
        assertNull(book.positionOfSegmentChar(7, 0), "no such segment")
    }

    @Test
    fun `the manifest text parses back, with the complete line deciding isComplete`() {
        val complete = BakeManifest.format("abc123", 3, items, complete = true)
        val partial = BakeManifest.format("abc123", 3, items.take(2), complete = false)

        val a = assertNotNull(BakeManifest.parse(File("x"), complete))
        val b = assertNotNull(BakeManifest.parse(File("x"), partial))

        assertEquals(items, a.utterances)
        assertTrue(a.isComplete)
        assertEquals("abc123", a.fingerprint)
        assertEquals(items.take(2), b.utterances)
        assertEquals(false, b.isComplete)
        assertTrue(complete.endsWith("complete\t3500\n"))
    }

    @Test
    fun `a complete line without all the utterances is not complete`() {
        val text = BakeManifest.format("abc", 3, items.take(2), complete = true)

        assertEquals(false, BakeManifest.parse(File("x"), text)!!.isComplete)
    }

    @Test
    fun `text that is not a manifest, or is damaged, reads as null`() {
        assertNull(BakeManifest.parse(File("x"), ""))
        assertNull(BakeManifest.parse(File("x"), "hello\nworld\n"))
        assertNull(BakeManifest.parse(File("x"), "audiopress-bake\t2\nfingerprint\tabc\nutterances\t0\n"))
        assertNull(BakeManifest.parse(File("x"), "audiopress-bake\t1\nutterances\t0\n"), "no fingerprint")
        assertNull(BakeManifest.parse(File("x"), "audiopress-bake\t1\nfingerprint\tabc\nutterances\t1\nu\t0\ta.wav\tx\t0\t1\t1\t5\n"))
        assertNull(
            BakeManifest.parse(File("x"), "audiopress-bake\t1\nfingerprint\tabc\nutterances\t1\nu\t3\ta.wav\t0\t0\t1\t1\t5\n"),
            "index out of order",
        )
    }

    @Test
    fun `read returns null for a folder without a manifest`() {
        assertNull(BakedBook.read(temp.root))
    }
}
