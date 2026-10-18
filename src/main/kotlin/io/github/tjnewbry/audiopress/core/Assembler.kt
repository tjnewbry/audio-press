package io.github.tjnewbry.audiopress.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** Puts a page's lines in reading order (pipeline stage 7). Alpha supports single-column pages: top to bottom. */
object ReadingOrder {
    fun order(lines: List<Line>): List<Line> = lines.sortedWith(compareBy({ it.baseline }, { it.box.left }))
}

/**
 * Joins the kept lines into paragraphs and headings, across line and page breaks (pipeline stage 8).
 * The rules are documented in `docs/cleanup-rules.md#paragraph-assembly`.
 */
object Assembler {
    private const val HEADING_SIZE = 1.15f
    private const val PARAGRAPH_GAP = 1.5f
    private const val SHORT_LINE = 0.85f
    private const val INDENT = 0.8f
    private const val SAME_SIZE = 0.5f
    private const val DEFAULT_PITCH = 1.2f
    private const val MIN_LINES_FOR_EDGES = 3

    /** Bullet, white bullet, small square, triangular bullet, black circle, black square. */
    private val BULLETS = setOf(0x2022, 0x25E6, 0x25AA, 0x2023, 0x25CF, 0x25A0)

    fun assemble(
        document: LayoutDocument,
        removed: Removals,
    ): Transcript {
        val body = document.stats.bodyFontSize
        val pitch = document.stats.linePitch ?: (DEFAULT_PITCH * body)
        val lines =
            document.keptPages(removed).flatMap { page ->
                ReadingOrder.order(page.keptLines(removed).filter { it.horizontal }).mapNotNull { keep(it, removed, body) }
            }
        val edges = TextEdges.measure(lines, document.pages.associate { it.index to it.width })

        val segments = mutableListOf<Segment>()
        var builder: SegmentBuilder? = null
        var previous: KeptLine? = null
        for (line in lines) {
            val current = builder
            if (current == null || previous == null || startsParagraph(previous, line, edges, body, pitch)) {
                current?.let { segments += it.build() }
                builder =
                    SegmentBuilder(
                        if (line.heading) SegmentKind.HEADING else SegmentKind.PARAGRAPH,
                    ).also { it.add(line, continuing = false) }
            } else {
                current.add(line, continuing = true)
            }
            previous = line
        }
        builder?.let { segments += it.build() }
        return Transcript(segments.filter { it.tokens.isNotEmpty() })
    }

    private fun keep(
        line: Line,
        removed: Removals,
        body: Float,
    ): KeptLine? {
        val words = line.words.filterIndexed { i, _ -> !removed.isRemoved(line, i) }.toMutableList()
        if (words.isEmpty()) return null
        val bullet =
            words
                .first()
                .text
                .first()
                .code in BULLETS
        if (bullet) {
            val rest =
                words
                    .first()
                    .text
                    .drop(1)
                    .trim()
            if (rest.isEmpty()) words.removeAt(0) else words[0] = words.first().copy(text = rest)
            if (words.isEmpty()) return null
            words[0] = words.first().copy(joinsPrevious = false)
        }
        return KeptLine(line, words, heading = line.fontSize >= HEADING_SIZE * body, bullet = bullet)
    }

    private fun startsParagraph(
        previous: KeptLine,
        line: KeptLine,
        edges: TextEdges,
        body: Float,
        pitch: Float,
    ): Boolean {
        val samePage = previous.line.page == line.line.page
        val gap = line.line.baseline - previous.line.baseline
        if (previous.heading || line.heading) {
            val sameHeading =
                previous.heading && line.heading && abs(previous.line.fontSize - line.line.fontSize) <= SAME_SIZE &&
                    samePage && gap <= PARAGRAPH_GAP * DEFAULT_PITCH * line.line.fontSize
            return !sameHeading
        }
        if (line.bullet) return true
        if (samePage && gap > PARAGRAPH_GAP * pitch) return true
        val before = edges[previous.line.page]
        if (previous.line.box.right < before.left + SHORT_LINE * (before.right - before.left)) return true
        val after = edges[line.line.page]
        val indent = INDENT * body
        return line.line.box.left >= after.left + indent && previous.line.box.left < before.left + indent
    }

    private class KeptLine(
        val line: Line,
        val words: List<SourceWord>,
        val heading: Boolean,
        val bullet: Boolean,
    )

    private class Edges(
        val left: Float,
        val right: Float,
    )

    /**
     * Each page's left text edge (most common line start) and right text edge (rightmost line end), from
     * its body lines. Pages with too few lines use the book's typical edges.
     */
    private class TextEdges(
        private val pages: Map<Int, Edges>,
        private val fallback: (Int) -> Edges,
    ) {
        operator fun get(page: Int): Edges = pages[page] ?: fallback(page)

        companion object {
            fun measure(
                lines: List<KeptLine>,
                pageWidths: Map<Int, Float>,
            ): TextEdges {
                val measured =
                    lines
                        .filterNot { it.heading }
                        .groupBy { it.line.page }
                        .filterValues { it.size >= MIN_LINES_FOR_EDGES }
                        .mapValues { (_, pageLines) -> edgesOf(pageLines) }
                if (measured.isNotEmpty()) {
                    val typical = Edges(median(measured.values.map { it.left }), median(measured.values.map { it.right }))
                    return TextEdges(measured) { typical }
                }
                // Too little text anywhere to measure the text width: assume equal left and right margins.
                val lefts = lines.groupBy { it.line.page }.mapValues { (_, pageLines) -> pageLines.minOf { it.line.box.left } }
                return TextEdges(emptyMap()) { page ->
                    val left = lefts[page] ?: 0f
                    Edges(left, (pageWidths[page] ?: 0f) - left)
                }
            }

            private fun edgesOf(lines: List<KeptLine>): Edges {
                val left =
                    lines
                        .groupingBy {
                            it.line.box.left
                                .roundToInt()
                        }.eachCount()
                        .maxBy { it.value }
                        .key
                        .toFloat()
                return Edges(left, lines.maxOf { it.line.box.right })
            }

            private fun median(values: List<Float>): Float = values.sorted()[values.size / 2]
        }
    }

    /** Collects the tokens of one segment, joining words across line breaks. */
    private class SegmentBuilder(
        private val kind: SegmentKind,
    ) {
        private val tokens = mutableListOf<Token>()

        fun add(
            line: KeptLine,
            continuing: Boolean,
        ) {
            line.words.forEachIndexed { i, word ->
                val span = Span(line.line.page, word.box)
                when {
                    tokens.isEmpty() -> tokens += Token(word.text, spaceBefore = false, spans = listOf(span))
                    i == 0 && continuing -> joinLine(word.text, span)
                    word.joinsPrevious -> replaceLast(tokens.last().text + word.text, span)
                    else -> tokens += Token(word.text, spaceBefore = true, spans = listOf(span))
                }
            }
        }

        private fun joinLine(
            text: String,
            span: Span,
        ) {
            val last = tokens.last().text
            val end = last.last()
            val hyphenAfterLetter = end in HYPHENS && last.length > 1 && last[last.length - 2].isLetter()
            when {
                end == TextNormalizer.SOFT_HYPHEN -> replaceLast(last.dropLast(1) + text, span)
                hyphenAfterLetter && text.first().isLowerCase() -> replaceLast(last.dropLast(1) + text, span)
                hyphenAfterLetter -> replaceLast(last + text, span)
                end in DASHES -> tokens += Token(text, spaceBefore = false, spans = listOf(span))
                else -> tokens += Token(text, spaceBefore = true, spans = listOf(span))
            }
        }

        private fun replaceLast(
            text: String,
            span: Span,
        ) {
            val last = tokens.removeAt(tokens.lastIndex)
            tokens += last.copy(text = text, spans = last.spans + span)
        }

        /** Soft hyphens that were not at a line end are dropped here. */
        fun build(): Segment =
            Segment(
                kind,
                tokens
                    .map { it.copy(text = it.text.replace(TextNormalizer.SOFT_HYPHEN.toString(), "")) }
                    .filter { it.text.isNotEmpty() },
            )

        private companion object {
            /** Hyphen-minus and hyphen. */
            val HYPHENS = setOf('-', Char(0x2010))

            /** En dash and em dash. */
            val DASHES = setOf(Char(0x2013), Char(0x2014))
        }
    }
}
