package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.Box
import io.github.tjnewbry.audiopress.core.SourcePage
import io.github.tjnewbry.audiopress.core.SourceWord
import io.github.tjnewbry.audiopress.core.TextSource

/** What one page of the sample book says: an optional heading, then paragraphs. */
data class SamplePage(
    val heading: String?,
    val paragraphs: List<String>,
)

/**
 * A stand-in for the Android PDF reader (which does not exist yet): a two-page book of original text,
 * laid out like a printed page, so the engine's real pipeline runs on it. Boxes are estimated from the
 * font size, as the engine's own are. Each page ends with a printed page number, which the engine removes.
 */
class SampleTextSource(
    private val pages: List<SamplePage> = SAMPLE_PAGES,
) : TextSource {
    override val pageCount: Int get() = pages.size

    override fun readPage(pageIndex: Int): SourcePage {
        require(pageIndex in 1..pageCount) { "No page $pageIndex; the book has $pageCount." }
        val page = pages[pageIndex - 1]
        val words = mutableListOf<SourceWord>()
        var baseline = MARGIN + BODY_SIZE
        page.heading?.let {
            words += lineOfWords(it, MARGIN, baseline, HEADING_SIZE)
            baseline += HEADING_SIZE * LINE_SPACING + BODY_SIZE * LINE_SPACING
        }
        for (paragraph in page.paragraphs) {
            for (line in wrap(paragraph)) {
                words += lineOfWords(line, MARGIN, baseline, BODY_SIZE)
                baseline += BODY_SIZE * LINE_SPACING
            }
            baseline += BODY_SIZE * LINE_SPACING // a blank line between paragraphs
        }
        val number = pageIndex.toString()
        val numberLeft = (PAGE_WIDTH - number.length * FOOTER_SIZE * CHAR_WIDTH) / 2
        words += lineOfWords(number, numberLeft, PAGE_HEIGHT - MARGIN / 2, FOOTER_SIZE)
        return SourcePage(pageIndex, PAGE_WIDTH, PAGE_HEIGHT, words)
    }

    override fun close() = Unit

    /** Breaks [text] into lines no wider than the text column. */
    private fun wrap(text: String): List<String> {
        val maxChars = ((PAGE_WIDTH - 2 * MARGIN) / (BODY_SIZE * CHAR_WIDTH)).toInt()
        val lines = mutableListOf<String>()
        var current = ""
        for (word in text.split(' ')) {
            if (current.isNotEmpty() && current.length + 1 + word.length > maxChars) {
                lines += current
                current = word
            } else {
                current = if (current.isEmpty()) word else "$current $word"
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun lineOfWords(
        line: String,
        left: Float,
        baseline: Float,
        size: Float,
    ): List<SourceWord> {
        var x = left
        return line.split(' ').map { text ->
            val width = text.length * size * CHAR_WIDTH
            val box = Box(x, baseline - ASCENT * size, x + width, baseline + (1 - ASCENT) * size)
            x += width + size * CHAR_WIDTH // one space
            SourceWord(text, box, baseline, size)
        }
    }

    private companion object {
        const val PAGE_WIDTH = 612f
        const val PAGE_HEIGHT = 792f
        const val MARGIN = 72f
        const val BODY_SIZE = 12f
        const val HEADING_SIZE = 18f
        const val FOOTER_SIZE = 10f
        const val LINE_SPACING = 1.2f
        const val CHAR_WIDTH = 0.5f
        const val ASCENT = 0.8f
    }
}

/** Original text written for this app; it is not from any book. */
val SAMPLE_PAGES =
    listOf(
        SamplePage(
            heading = "The Lighthouse Keeper",
            paragraphs =
                listOf(
                    "On the far edge of the island stood a lighthouse that nobody remembered building. " +
                        "Its white tower leaned a little toward the sea, as if it were listening for something, " +
                        "and every evening the old keeper climbed its two hundred steps to light the lamp.",
                    "The keeper kept a small notebook beside the lamp. In it he wrote the weather, the names " +
                        "of passing ships, and sometimes a line or two about the color of the water. " +
                        "He had filled forty notebooks, and he never read any of them again. The sea never minded.",
                ),
        ),
        SamplePage(
            heading = null,
            paragraphs =
                listOf(
                    "One night a storm rolled in from the north, and the wind pushed against the tower until " +
                        "the glass hummed. The keeper held the lamp steady and counted the waves. Near midnight, " +
                        "a single small boat appeared in the beam, and he rang the old bell until it turned " +
                        "toward the harbor.",
                    "By morning the sea was calm. The keeper opened his notebook and wrote one word: home.",
                ),
        ),
    )
