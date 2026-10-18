package io.github.tjnewbry.audiopress.core

/**
 * Part of the `footnote-markers` rule, for scanned books: OCR often turns a superscript footnote number
 * into degree signs and stray marks right after the end of a sentence, such as "flesh." followed by a closing quote and a degree sign.
 * A real degree sign never follows sentence-ending punctuation, so those marks are removed. A word made
 * only of such marks, right after a sentence ends, is dropped.
 */
object ScanMarkerRewriter {
    private const val SENTENCE_END = """[.!?;:][)\]"'\x{2019}\x{201D}]?"""
    private const val JUNK = """[*'\x{2019}"\x{201D}>?]*\x{00B0}+"""

    private val ATTACHED = Regex("""(.*$SENTENCE_END)$JUNK""")
    private val ALONE = Regex(JUNK)
    private val ENDS_SENTENCE = Regex(""".*$SENTENCE_END""")

    fun rewrite(transcript: Transcript): Transcript =
        Transcript(
            transcript.segments.map { segment ->
                val tokens = mutableListOf<Token>()
                for (token in segment.tokens) {
                    val previous = tokens.lastOrNull()
                    val alone = ALONE.matches(token.text) && previous != null && ENDS_SENTENCE.matches(previous.text)
                    if (!alone) tokens += ATTACHED.matchEntire(token.text)?.let { token.copy(text = it.groupValues[1]) } ?: token
                }
                segment.copy(tokens = tokens)
            },
        )
}
