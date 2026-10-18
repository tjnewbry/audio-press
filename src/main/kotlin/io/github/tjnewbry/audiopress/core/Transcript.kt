package io.github.tjnewbry.audiopress.core

/** The finished text, as paragraphs and headings that may cross page boundaries. */
data class Transcript(
    val segments: List<Segment>,
) {
    val wordCount: Int get() = segments.sumOf { it.tokens.size }
}

enum class SegmentKind { PARAGRAPH, HEADING }

/** One paragraph or heading. */
data class Segment(
    val kind: SegmentKind,
    val tokens: List<Token>,
)

/**
 * One spoken word. [spans] are the places on the page it came from: usually one, two for a word
 * hyphenated across a line or page break. A token's page is the page of its first span.
 */
data class Token(
    val text: String,
    val spaceBefore: Boolean,
    val spans: List<Span>,
) {
    val page: Int get() = spans.first().page
}

data class Span(
    val page: Int,
    val box: Box,
)

/** An output format for a transcript. */
interface TranscriptWriter {
    fun write(
        transcript: Transcript,
        out: Appendable,
    )
}

/** Writes the lo format. See `docs/usage.md#lo-format`. */
class LoTextWriter(
    private val pageMarkers: PageMarkers,
) : TranscriptWriter {
    override fun write(
        transcript: Transcript,
        out: Appendable,
    ) {
        val lines = mutableListOf<String>()
        var currentPage: Int? = null
        for (segment in transcript.segments) {
            if (lines.isNotEmpty()) lines += ""
            val text = StringBuilder()
            for (token in segment.tokens) {
                if (pageMarkers == PageMarkers.LINE && token.page != currentPage) {
                    if (text.isNotEmpty()) lines += text.toString()
                    text.clear()
                    lines += "[[page ${token.page}]]"
                    currentPage = token.page
                }
                if (text.isNotEmpty() && token.spaceBefore) text.append(' ')
                text.append(token.text)
            }
            if (text.isNotEmpty()) lines += text.toString()
        }
        lines.forEach { out.append(it).append('\n') }
    }
}
