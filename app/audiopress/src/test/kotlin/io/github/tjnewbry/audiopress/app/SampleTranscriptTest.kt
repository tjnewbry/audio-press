package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.ConversionOptions
import io.github.tjnewbry.audiopress.core.Converter
import io.github.tjnewbry.audiopress.core.SegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SampleTranscriptTest {
    private val expected = SAMPLE_PAGES.flatMap { listOfNotNull(it.heading) + it.paragraphs }

    @Test
    fun `the engine turns the sample source into its heading and paragraphs`() {
        val paragraphs = SampleTextSource().use { convertToParagraphs(it, "sample") }

        assertEquals(expected, paragraphs)
    }

    @Test
    fun `the heading is a heading and the page numbers are removed`() {
        val result = SampleTextSource().use { Converter().convert(it, "sample", ConversionOptions()) }

        assertEquals(
            SegmentKind.HEADING,
            result.transcript.segments
                .first()
                .kind,
        )
        assertEquals(expected.size, result.transcript.segments.size)
        assertFalse(result.transcript.paragraphTexts().any { it.trim() in setOf("1", "2") })
    }

    @Test
    fun `every word has a box on its page`() {
        SampleTextSource().use { source ->
            for (index in 1..source.pageCount) {
                val page = source.readPage(index)
                assertTrue(page.words.isNotEmpty())
                assertTrue(page.words.all { it.box.left >= 0 && it.box.right <= page.width && it.box.bottom <= page.height })
            }
        }
    }
}
