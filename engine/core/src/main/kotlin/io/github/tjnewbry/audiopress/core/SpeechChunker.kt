package io.github.tjnewbry.audiopress.core

/** A half-open range of characters, `[start, end)`, in a string. */
data class TextRange(
    val start: Int,
    val end: Int,
)

/**
 * One word of an [Utterance]: the [tokenIndex]th token of its segment, on [page], at [range] in the segment's
 * text. For a word that had to be cut because it is longer than the limit, [range] covers only the part in
 * this utterance.
 */
data class SpokenWord(
    val tokenIndex: Int,
    val page: Int,
    val range: TextRange,
)

/**
 * A piece of text to hand to a text-to-speech engine in one call. [text] is exactly the characters
 * `[start, end)` of segment number [segmentIndex]'s text ([Segment.text]), so a character offset in [text] is
 * [start] plus that offset in the segment. [page] is the page of the first word.
 */
data class Utterance(
    val text: String,
    val segmentIndex: Int,
    val kind: SegmentKind,
    val start: Int,
    val end: Int,
    val page: Int,
    val words: List<SpokenWord>,
) {
    /** The number of words spoken. */
    val wordCount: Int get() = words.size

    /** Maps a range of this utterance's [text] (as TTS reports it) to the same range in the segment's text. */
    fun segmentRange(
        startInUtterance: Int,
        endInUtterance: Int = startInUtterance,
    ): TextRange {
        val from = startInUtterance.coerceIn(0, text.length)
        val to = endInUtterance.coerceIn(from, text.length)
        return TextRange(start + from, start + to)
    }

    /**
     * The word being spoken at [offset] in [text], for highlighting. An offset on a space gives the next word,
     * and one past the last word gives the last word. Returns null only for an utterance without words.
     */
    fun wordAt(offset: Int): SpokenWord? {
        if (words.isEmpty()) return null
        val position = start + offset
        val index = words.indexOfFirst { position < it.range.end }
        return if (index >= 0) words[index] else words.last()
    }

    /** The estimated time to speak this utterance at [wordsPerMinute], in seconds. */
    fun estimatedSeconds(wordsPerMinute: Int = SpeechChunker.WORDS_PER_MINUTE): Double =
        SpeechChunker.estimatedSeconds(wordCount, wordsPerMinute)
}

/**
 * Splits a [Transcript] into [Utterance]s for text to speech. Android's `TextToSpeech` rejects text longer than
 * `getMaxSpeechInputLength()` (4000 characters), so paragraphs longer than [maxChars] are cut. See
 * `docs/api.md#speaking-a-transcript`.
 *
 * Normally there is one utterance per paragraph or heading. A longer one is cut at sentence ends (`.`, `!`, `?`,
 * or an ellipsis, plus any closing quotes or brackets), packing as many sentences as fit. Abbreviations such as
 * "Mr." and "e.g.", initials such as "J. R. R.", and a period followed by a lowercase word do not end a
 * sentence. A sentence that is still too long is cut after its last comma or semicolon, then at its last space,
 * and only a single word longer than [maxChars] is cut inside the word.
 */
class SpeechChunker(
    val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    init {
        require(maxChars >= MIN_MAX_CHARS) { "maxChars must be at least $MIN_MAX_CHARS" }
    }

    fun chunk(transcript: Transcript): List<Utterance> = transcript.segments.flatMapIndexed { index, segment -> chunk(index, segment) }

    private fun chunk(
        segmentIndex: Int,
        segment: Segment,
    ): List<Utterance> {
        val text = segment.text
        if (text.isBlank()) return emptyList()
        val wordRanges = wordRanges(segment)
        return pieces(text).mapNotNull { (from, to) ->
            val words = wordRanges.filter { it.range.start < to && it.range.end > from }.map { clip(it, from, to) }
            if (words.isEmpty()) {
                null
            } else {
                Utterance(
                    text.substring(from, to),
                    segmentIndex,
                    segment.kind,
                    from,
                    to,
                    words.first().page,
                    words,
                )
            }
        }
    }

    private fun clip(
        word: SpokenWord,
        from: Int,
        to: Int,
    ): SpokenWord = word.copy(range = TextRange(maxOf(word.range.start, from), minOf(word.range.end, to)))

    /** Where each token sits in the segment's text. */
    private fun wordRanges(segment: Segment): List<SpokenWord> {
        val ranges = ArrayList<SpokenWord>(segment.tokens.size)
        var position = 0
        for ((i, token) in segment.tokens.withIndex()) {
            if (i > 0 && token.spaceBefore) position++
            ranges += SpokenWord(i, token.page, TextRange(position, position + token.text.length))
            position += token.text.length
        }
        return ranges
    }

    /** The `[from, to)` ranges of [text] to speak, without leading or trailing spaces. */
    private fun pieces(text: String): List<Pair<Int, Int>> {
        var start = skipSpaces(text, 0)
        if (text.length <= maxChars) return listOf(start to trimEnd(text, text.length))
        val pieces = mutableListOf<Pair<Int, Int>>()
        val ends = sentenceEnds(text)
        while (start < text.length) {
            if (text.length - start <= maxChars) {
                pieces += start to trimEnd(text, text.length)
                break
            }
            val limit = start + maxChars
            val cut = ends.lastOrNull { it in (start + 1)..limit } ?: clauseCut(text, start, limit)
            pieces += start to trimEnd(text, cut)
            start = skipSpaces(text, cut)
        }
        return pieces
    }

    /** Where to cut a sentence that does not fit: after the last comma or semicolon, else at the last space, else mid-word. */
    private fun clauseCut(
        text: String,
        start: Int,
        limit: Int,
    ): Int {
        for (i in limit - 1 downTo start + 1) {
            if ((text[i] == ',' || text[i] == ';') && i + 1 < text.length && text[i + 1].isWhitespace()) return i + 1
        }
        for (i in limit downTo start + 1) {
            if (text[i].isWhitespace()) return i
        }
        return if (text[limit].isLowSurrogate()) limit - 1 else limit
    }

    private fun sentenceEnds(text: String): List<Int> {
        val ends = mutableListOf<Int>()
        var i = 0
        while (i < text.length) {
            if (text[i] !in TERMINATORS) {
                i++
                continue
            }
            var j = i
            while (j < text.length && text[j] in TERMINATORS) j++
            val terminatorsEnd = j
            while (j < text.length && text[j] in CLOSERS) j++
            if ((j == text.length || text[j].isWhitespace()) && endsSentence(text, i, terminatorsEnd, j)) ends += j
            i = j
        }
        return ends
    }

    /** Whether the terminators at `[from, to)`, with closers up to [after], really end a sentence. */
    private fun endsSentence(
        text: String,
        from: Int,
        to: Int,
        after: Int,
    ): Boolean {
        val run = text.substring(from, to)
        if (run.any { it == '!' || it == '?' }) return true
        if (run == "." && !endsWord(text, from)) return false
        val next = skipSpaces(text, after)
        return next >= text.length || !text[next].isLowerCase()
    }

    /** False when the period at [dot] belongs to an abbreviation or an initial. */
    private fun endsWord(
        text: String,
        dot: Int,
    ): Boolean {
        var begin = dot
        while (begin > 0 && !text[begin - 1].isWhitespace()) begin--
        val word = text.substring(begin, dot).trimStart { it in OPENERS }
        if (word.isEmpty()) return false
        if (word.lowercase() in ABBREVIATIONS) return false
        return !isInitials(word)
    }

    /** `J` or `U.S`: letters separated by periods. */
    private fun isInitials(word: String): Boolean =
        word.length % 2 == 1 && word.withIndex().all { (i, c) -> if (i % 2 == 0) c.isLetter() else c == '.' } &&
            (word.length > 1 || word[0].isUpperCase())

    private fun skipSpaces(
        text: String,
        from: Int,
    ): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun trimEnd(
        text: String,
        to: Int,
    ): Int {
        var i = to
        while (i > 0 && text[i - 1].isWhitespace()) i--
        return i
    }

    companion object {
        /** Android's TextToSpeech limit is 4000; this leaves room. */
        const val DEFAULT_MAX_CHARS = 3900

        /** An average adult audiobook pace. The app scales it by the speech rate it sets. */
        const val WORDS_PER_MINUTE = 170

        private const val MIN_MAX_CHARS = 8

        /** The time to speak [words] words at [wordsPerMinute], in seconds. */
        fun estimatedSeconds(
            words: Int,
            wordsPerMinute: Int = WORDS_PER_MINUTE,
        ): Double {
            require(wordsPerMinute > 0) { "wordsPerMinute must be positive" }
            return words * 60.0 / wordsPerMinute
        }

        private val ELLIPSIS = Char(0x2026)
        private val TERMINATORS = setOf('.', '!', '?', ELLIPSIS)

        /** Closing quotes and brackets that stay with the sentence they close: ) ] } " ' and the curly and angle quotes. */
        private val CLOSERS = setOf(')', ']', '}', '"', '\'', Char(0x201D), Char(0x2019), Char(0x00BB))

        private val OPENERS = setOf('(', '[', '{', '"', '\'', Char(0x201C), Char(0x2018), Char(0x00AB))

        /** Lower case, without the final period; "e.g" and "u.s" keep their inner one. */
        private val ABBREVIATIONS =
            setOf(
                "mr",
                "mrs",
                "ms",
                "dr",
                "prof",
                "sr",
                "jr",
                "st",
                "mt",
                "vs",
                "etc",
                "e.g",
                "i.e",
                "cf",
                "viz",
                "fig",
                "vol",
                "pp",
                "approx",
                "dept",
                "inc",
                "ltd",
                "corp",
                "gen",
                "col",
                "capt",
                "lt",
                "sgt",
                "rev",
                "hon",
                "gov",
                "sen",
                "rep",
                "u.s",
                "u.k",
                "a.m",
                "p.m",
            )
    }
}

/** The time to speak the whole transcript at [wordsPerMinute], in seconds. */
fun Transcript.estimatedSeconds(wordsPerMinute: Int = SpeechChunker.WORDS_PER_MINUTE): Double =
    SpeechChunker.estimatedSeconds(wordCount, wordsPerMinute)
