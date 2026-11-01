package io.github.tjnewbry.audiopress.testing

import io.github.tjnewbry.audiopress.core.Box
import io.github.tjnewbry.audiopress.core.LayoutDocument
import io.github.tjnewbry.audiopress.core.LineBuilder
import io.github.tjnewbry.audiopress.core.SourcePage
import io.github.tjnewbry.audiopress.core.SourceWord

/**
 * Builds in-memory layouts for rule and assembly tests, without any PDF. Each character is half the
 * font size wide. In line text, `word^3` adds a raised, smaller `3` right after `word`.
 */
class LayoutBuilder {
    private val pages = mutableListOf<SourcePage>()

    fun page(
        width: Float = 432f,
        height: Float = 648f,
        block: PageBuilder.() -> Unit = {},
    ) {
        pages += PageBuilder(pages.size + 1, width, height).apply(block).build()
    }

    fun build(pageCount: Int?): LayoutDocument = LayoutDocument(pages.map(LineBuilder::build), pageCount ?: pages.size)
}

class PageBuilder(
    private val index: Int,
    private val width: Float,
    private val height: Float,
) {
    private val words = mutableListOf<SourceWord>()

    /** Adds a line starting at [x] with its baseline at [y]. With [right], the words are spread out to end there. */
    fun line(
        text: String,
        y: Float,
        x: Float = LEFT,
        size: Float = 11f,
        right: Float? = null,
        horizontal: Boolean = true,
    ) {
        val parts = text.split(' ').filter { it.isNotEmpty() }
        val widths = parts.map { width(it.substringBefore('^'), size) }
        val space = if (right != null && parts.size > 1) (right - x - widths.sum()) / (parts.size - 1) else size * 0.25f
        var cursor = x
        parts.forEachIndexed { i, part ->
            words += word(part.substringBefore('^'), cursor, y, size, horizontal, joinsPrevious = false)
            cursor += widths[i]
            val marker = part.substringAfter('^', "")
            if (marker.isNotEmpty()) {
                val markerSize = size * 0.6f
                words += word(marker, cursor, y - size * 0.35f, markerSize, horizontal, joinsPrevious = true)
                cursor += width(marker, markerSize)
            }
            cursor += space
        }
    }

    internal fun build() = SourcePage(index, width, height, words.toList())

    private fun width(
        text: String,
        size: Float,
    ) = text.length * size * 0.5f

    private fun word(
        text: String,
        x: Float,
        baseline: Float,
        size: Float,
        horizontal: Boolean,
        joinsPrevious: Boolean,
    ) = SourceWord(
        text = text,
        box = Box(x, baseline - 0.8f * size, x + width(text, size), baseline + 0.2f * size),
        baseline = baseline,
        fontSize = size,
        horizontal = horizontal,
        joinsPrevious = joinsPrevious,
    )

    companion object {
        const val LEFT = 54f
        const val RIGHT = 378f
    }
}

/** Builds a layout document. [pageCount] is the PDF's total page count, if it differs from the pages built. */
fun layout(
    pageCount: Int? = null,
    block: LayoutBuilder.() -> Unit,
): LayoutDocument = LayoutBuilder().apply(block).build(pageCount)
