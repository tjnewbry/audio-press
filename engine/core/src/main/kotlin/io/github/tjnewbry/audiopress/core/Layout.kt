package io.github.tjnewbry.audiopress.core

/** A row of words on a page, ordered left to right. */
data class Line(
    /** The 1-based physical page number. */
    val page: Int,
    /** The 0-based position of the line on its page: horizontal lines top to bottom, then rotated text. */
    val index: Int,
    val words: List<SourceWord>,
) {
    init {
        require(words.isNotEmpty()) { "A line needs at least one word" }
    }

    val box: Box = words.map { it.box }.reduce(Box::union)

    /** The font size that carries the most characters in the line. */
    val fontSize: Float =
        words
            .groupBy { it.fontSize }
            .maxBy { (_, sized) -> sized.sumOf { it.text.length } }
            .key

    /** The median baseline of the words set in the line's main font size. */
    val baseline: Float =
        words
            .filter { it.fontSize == fontSize }
            .map { it.baseline }
            .sorted()
            .let { it[it.size / 2] }

    val horizontal: Boolean get() = words.first().horizontal

    /** The line's text: words separated by single spaces, except where a word joins the previous one. */
    val text: String
        get() =
            buildString {
                words.forEachIndexed { i, word ->
                    if (i > 0 && !word.joinsPrevious) append(' ')
                    append(word.text)
                }
            }
}

/** A page after its words have been grouped into lines. */
data class LayoutPage(
    val index: Int,
    val width: Float,
    val height: Float,
    val lines: List<Line>,
)

/** Groups a page's words into lines (pipeline stage 4). */
object LineBuilder {
    /** The minimum overlap, as a share of the shorter of the two, for a word to join a line. */
    private const val MIN_OVERLAP = 0.5f

    fun build(page: SourcePage): LayoutPage {
        val rows =
            group(page.words.filter { it.horizontal }, Box::verticalOverlap, { it.height }) { it.baseline }
                .map { row -> row.sortedBy { it.box.left } }
                .sortedBy { row -> row.first().box.top }
        // Rotated text runs up or down the page, so its "lines" are columns of words that overlap horizontally.
        val columns =
            group(page.words.filterNot { it.horizontal }, Box::horizontalOverlap, { it.width }) { it.box.left }
                .map { column -> column.sortedBy { it.box.top } }
                .sortedBy { column -> column.first().box.left }
        val lines = (rows + columns).mapIndexed { i, words -> Line(page.index, i, words) }
        return LayoutPage(page.index, page.width, page.height, lines)
    }

    private fun group(
        words: List<SourceWord>,
        overlap: (Box, Box) -> Float,
        extent: (Box) -> Float,
        order: (SourceWord) -> Float,
    ): List<List<SourceWord>> {
        val groups = mutableListOf<MutableList<SourceWord>>()
        val bounds = mutableListOf<Box>()
        for (word in words.sortedBy(order)) {
            val match =
                bounds.indices.lastOrNull { i ->
                    overlap(bounds[i], word.box) >= MIN_OVERLAP * minOf(extent(bounds[i]), extent(word.box))
                }
            if (match == null) {
                groups += mutableListOf(word)
                bounds += word.box
            } else {
                groups[match] += word
                bounds[match] = bounds[match].union(word.box)
            }
        }
        return groups
    }
}

/** Reads pages from a source and turns them into layout pages (pipeline stages 2 to 4). */
object LayoutReader {
    /** Reads [pages] (1-based page numbers) from [source], normalizes their text, and builds lines. */
    fun read(
        source: TextSource,
        pages: List<Int>,
    ): List<LayoutPage> = pages.map { LineBuilder.build(TextNormalizer.normalize(source.readPage(it))) }
}
