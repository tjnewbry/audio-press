package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.ConversionOptions
import io.github.tjnewbry.audiopress.core.Converter
import io.github.tjnewbry.audiopress.core.SegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleTranscriptTest {
    private val expected = SAMPLE_PAGES.flatMap { listOfNotNull(it.heading) + it.paragraphs }

    private fun convert() = SampleTextSource().use { Converter().convert(it, "sample", ConversionOptions()) }

    @Test
    fun `the engine turns the sample source into its heading and paragraphs, without page numbers`() {
        val paragraphs = convert().transcript.toStoredParagraphs()

        assertEquals(expected, paragraphs.map { it.text })
    }

    @Test
    fun `stored paragraphs keep their kind and the page they started on`() {
        val paragraphs = convert().transcript.toStoredParagraphs()

        assertEquals(SegmentKind.HEADING, paragraphs.first().kind)
        assertEquals(listOf(SegmentKind.PARAGRAPH), paragraphs.drop(1).map { it.kind }.distinct())
        assertEquals(listOf(1, 1, 1, 2, 2), paragraphs.map { it.page })
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
