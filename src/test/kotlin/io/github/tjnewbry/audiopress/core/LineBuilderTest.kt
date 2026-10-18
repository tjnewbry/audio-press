package io.github.tjnewbry.audiopress.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LineBuilderTest {
    @Test
    fun `groups words on the same baseline into one line, left to right`() {
        val page = page(word("world", x = 110f, baseline = 100f), word("Hello", x = 72f, baseline = 100f))

        val lines = LineBuilder.build(page).lines

        assertEquals(listOf("Hello world"), lines.map { it.text })
    }

    @Test
    fun `orders lines from top to bottom`() {
        val page =
            page(
                word("second", x = 72f, baseline = 114f),
                word("first", x = 72f, baseline = 100f),
            )

        val lines = LineBuilder.build(page).lines

        assertEquals(listOf("first", "second"), lines.map { it.text })
        assertEquals(listOf(0, 1), lines.map { it.index })
    }

    @Test
    fun `keeps a raised footnote marker in its line and joined to the previous word`() {
        val page =
            page(
                word("summer.", x = 72f, baseline = 100f, size = 12f),
                word("3", x = 110f, baseline = 96f, size = 7f, joinsPrevious = true),
                word("Next", x = 72f, baseline = 114f, size = 12f),
            )

        val lines = LineBuilder.build(page).lines

        assertEquals(listOf("summer.3", "Next"), lines.map { it.text })
        assertEquals(12f, lines.first().fontSize)
        assertEquals(100f, lines.first().baseline)
    }

    @Test
    fun `puts rotated text in its own lines after the horizontal lines`() {
        val page =
            page(
                word("Body", x = 72f, baseline = 100f),
                SourceWord("Downloaded", Box(10f, 300f, 20f, 360f), baseline = 360f, fontSize = 8f, horizontal = false),
                SourceWord("from", Box(10f, 260f, 20f, 290f), baseline = 290f, fontSize = 8f, horizontal = false),
            )

        val lines = LineBuilder.build(page).lines

        assertEquals(listOf("Body", "from Downloaded"), lines.map { it.text })
        assertEquals(listOf(true, false), lines.map { it.horizontal })
    }

    private fun page(vararg words: SourceWord) = SourcePage(1, 432f, 648f, words.toList())

    private fun word(
        text: String,
        x: Float,
        baseline: Float,
        size: Float = 11f,
        joinsPrevious: Boolean = false,
    ) = SourceWord(
        text = text,
        box = Box(x, baseline - 0.8f * size, x + text.length * size * 0.5f, baseline + 0.2f * size),
        baseline = baseline,
        fontSize = size,
        joinsPrevious = joinsPrevious,
    )
}
