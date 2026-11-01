package io.github.tjnewbry.audiopress.app

/**
 * One piece of text handed to the speech engine in a single call. [text] is exactly the characters from
 * [start] of paragraph number [paragraph], so an offset in [text] plus [start] is an offset in the paragraph.
 * Normally a paragraph is one utterance; a very long one is cut at sentence ends.
 */
data class Utterance(
    val paragraph: Int,
    val start: Int,
    val text: String,
    /** The book page the paragraph starts on; the bake module records it. */
    val page: Int = 0,
) {
    /** The character range of each word in [text]. */
    val words: List<IntRange> by lazy { WORD.findAll(text).map { it.range }.toList() }

    private companion object {
        val WORD = Regex("\\S+")
    }
}

/**
 * The longest utterance, in characters. Android's `TextToSpeech` refuses more than 4000 at once, and the reader shows one
 * utterance per line, which stays smooth only when a line is not enormous. Longer paragraphs are cut at sentence ends.
 */
const val MAX_UTTERANCE_CHARS = 1500

private val SENTENCE_END = Regex("[.!?…][\"'”’)\\]]*(?=\\s)")

/**
 * Splits [paragraphs] into utterances, in order. A paragraph that fits is one utterance. A longer one is cut
 * after the last sentence end that fits (and is past the halfway point), else at the last space, else inside
 * the word. Blank paragraphs produce nothing. Every utterance's text is a substring of its paragraph.
 */
fun splitIntoUtterances(
    paragraphs: List<String>,
    maxChars: Int = MAX_UTTERANCE_CHARS,
): List<Utterance> {
    require(maxChars >= 10) { "maxChars is too small" }
    val result = mutableListOf<Utterance>()
    paragraphs.forEachIndexed { index, paragraph ->
        var from = 0
        while (from < paragraph.length) {
            while (from < paragraph.length && paragraph[from].isWhitespace()) from++
            if (from >= paragraph.length) break
            val cut = if (paragraph.length - from <= maxChars) paragraph.length else cutPoint(paragraph, from, maxChars)
            val text = paragraph.substring(from, cut).trimEnd()
            if (text.isNotEmpty()) result += Utterance(index, from, text)
            from = cut
        }
    }
    return result
}

/** Where to cut the text that starts at [from]: the end of the last sentence within [maxChars], or a space. */
private fun cutPoint(
    text: String,
    from: Int,
    maxChars: Int,
): Int {
    val window = text.substring(from, from + maxChars)
    val sentence = SENTENCE_END.findAll(window).lastOrNull { it.range.last + 1 >= maxChars / 2 }
    if (sentence != null) return from + sentence.range.last + 1
    val space = window.lastIndexOf(' ')
    return if (space > 0) from + space else from + maxChars
}

/** Where playback is: [paragraph], and the character [offset] of the current word in that paragraph. */
data class Position(
    val paragraph: Int,
    val offset: Int,
)

/** The index of the utterance that holds [position], and the offset in its text. Clamps a position that is out of range. */
fun locate(
    utterances: List<Utterance>,
    position: Position,
): Pair<Int, Int> {
    if (utterances.isEmpty()) return 0 to 0
    val index =
        utterances
            .indexOfLast { it.paragraph < position.paragraph || (it.paragraph == position.paragraph && it.start <= position.offset) }
            .coerceAtLeast(0)
    val utterance = utterances[index]
    if (utterance.paragraph != position.paragraph) {
        // A paragraph with no utterance (blank) or past the end: start at the nearest utterance after it.
        val next = utterances.indexOfFirst { it.paragraph >= position.paragraph }
        return if (next >= 0) next to 0 else 0 to 0
    }
    return index to (position.offset - utterance.start).coerceIn(0, utterance.text.length)
}

/** The index of the utterance to start from when jumping to [position], or null when the position is past the end of the book. */
fun utteranceIndexAt(
    utterances: List<Utterance>,
    position: Position,
): Int? {
    if (utterances.isEmpty() || position.paragraph > utterances.last().paragraph) return null
    return locate(utterances, position).first
}
