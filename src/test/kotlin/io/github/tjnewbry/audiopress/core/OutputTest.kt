package io.github.tjnewbry.audiopress.core

import kotlin.test.Test
import kotlin.test.assertEquals

class OutputTest {
    private fun token(
        text: String,
        page: Int,
        spaceBefore: Boolean = true,
    ) = Token(text, spaceBefore, listOf(Span(page, Box(0f, 0f, 1f, 1f))))

    private fun paragraph(vararg tokens: Token) = Segment(SegmentKind.PARAGRAPH, tokens.toList())

    private val transcript =
        Transcript(
            listOf(
                Segment(SegmentKind.HEADING, listOf(token("CHAPTER", 7, false), token("ONE", 7))),
                paragraph(token("It", 7, false), token("was", 7), token("striking", 7), token("thirteen.", 8)),
                paragraph(token("The", 9, false), token("end.", 9)),
            ),
        )

    private fun write(markers: PageMarkers) = StringBuilder().also { LoTextWriter(markers).write(transcript, it) }.toString()

    @Test
    fun `lo writer places page markers before each page's first text, even inside a paragraph`() {
        assertEquals(
            "[[page 7]]\nCHAPTER ONE\n\nIt was striking\n[[page 8]]\nthirteen.\n\n[[page 9]]\nThe end.\n",
            write(PageMarkers.LINE),
        )
    }

    @Test
    fun `lo writer without markers writes one line per paragraph`() {
        assertEquals("CHAPTER ONE\n\nIt was striking thirteen.\n\nThe end.\n", write(PageMarkers.NONE))
    }

    private fun rewrite(
        mode: UrlMode,
        vararg words: String,
    ): String {
        val tokens = words.mapIndexed { i, text -> token(text, 1, spaceBefore = i > 0) }
        val result = UrlRewriter.rewrite(Transcript(listOf(paragraph(*tokens.toTypedArray()))), mode)
        return result.segments
            .single()
            .tokens
            .joinToString("") { (if (it.spaceBefore) " " else "") + it.text }
    }

    @Test
    fun `scanned footnote marks after a sentence are removed, but real degree signs stay`() {
        val degree = Char(0x00B0)
        val quote = Char(0x201D)
        val words = listOf("of", "flesh.$quote$degree", "It", "sold.", "$degree$degree", "At", "30$degree", "north.")
        val tokens = words.mapIndexed { i, text -> token(text, 1, spaceBefore = i > 0) }

        val result = ScanMarkerRewriter.rewrite(Transcript(listOf(paragraph(*tokens.toTypedArray()))))

        assertEquals(
            listOf("of", "flesh.$quote", "It", "sold.", "At", "30$degree", "north."),
            result.segments
                .single()
                .tokens
                .map { it.text },
        )
    }

    @Test
    fun `urls are shortened to the domain, keeping surrounding punctuation`() {
        assertEquals("Visit example.com.", rewrite(UrlMode.DOMAIN, "Visit", "https://www.example.com/a/b?c=1."))
        assertEquals("(see example.org)", rewrite(UrlMode.DOMAIN, "(see", "www.example.org/page)"))
    }

    @Test
    fun `urls can be dropped or kept, and email addresses are left alone`() {
        assertEquals(
            "Visit for more, or write to info@example.com.",
            rewrite(UrlMode.DROP, "Visit", "https://example.com/x", "for", "more,", "or", "write", "to", "info@example.com."),
        )
        assertEquals("See it.", rewrite(UrlMode.DROP, "See", "it", "https://example.com/x."))
        assertEquals("Visit https://example.com/x now", rewrite(UrlMode.KEEP, "Visit", "https://example.com/x", "now"))
    }
}
