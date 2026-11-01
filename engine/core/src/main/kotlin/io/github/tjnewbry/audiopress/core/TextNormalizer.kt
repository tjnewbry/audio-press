package io.github.tjnewbry.audiopress.core

/**
 * Cleans up the characters of each word as soon as it is extracted (pipeline stage 3). See
 * `docs/cleanup-rules.md#text-normalization`.
 *
 * Characters are written as numeric code points because most of them are invisible in source code.
 */
object TextNormalizer {
    private val LIGATURES =
        mapOf(
            0xFB00 to "ff",
            0xFB01 to "fi",
            0xFB02 to "fl",
            0xFB03 to "ffi",
            0xFB04 to "ffl",
            0xFB05 to "st",
            0xFB06 to "st",
        )

    /** Kept only at the end of a word, where paragraph assembly treats it as a line-end hyphen. */
    val SOFT_HYPHEN: Char = Char(0x00AD)

    private val MULTIPLE_SPACES = Regex(" {2,}")

    /**
     * Normalizes every word on [page], dropping words that end up empty. Superscript digits at the end of
     * a word (a footnote marker such as "summer." followed by a superscript 3) are split off into their own word that joins the
     * previous one, so the `footnote-markers` rule can remove them.
     */
    fun normalize(page: SourcePage): SourcePage =
        page.copy(
            words =
                page.words.flatMap { word ->
                    val text = normalize(word.text)
                    if (text.isEmpty()) emptyList() else splitSuperscripts(word.copy(text = text))
                },
        )

    /** Unicode superscript digits 0 to 9. */
    fun isSuperscriptDigit(c: Char): Boolean =
        c.code == 0x00B9 || c.code == 0x00B2 || c.code == 0x00B3 || c.code == 0x2070 || c.code in 0x2074..0x2079

    private fun splitSuperscripts(word: SourceWord): List<SourceWord> {
        val start = word.text.indexOfLast { !isSuperscriptDigit(it) } + 1
        if (start == 0 || start == word.text.length) return listOf(word)
        return listOf(
            word.copy(text = word.text.substring(0, start)),
            word.copy(text = word.text.substring(start), joinsPrevious = true),
        )
    }

    fun normalize(text: String): String =
        buildString {
            text.forEachIndexed { i, c ->
                val ligature = LIGATURES[c.code]
                when {
                    ligature != null -> append(ligature)
                    c == SOFT_HYPHEN -> if (i == text.lastIndex) append(c)
                    isSpecialSpace(c.code) -> append(' ')
                    isInvisible(c) || isUnreadable(c.code) -> Unit
                    else -> append(c)
                }
            }
        }.trim().replace(MULTIPLE_SPACES, " ")

    /** Tab, no-break space, the typographic spaces, narrow no-break space, medium math space, ideographic space. */
    private fun isSpecialSpace(code: Int): Boolean =
        code == 0x0009 || code == 0x00A0 || code in 0x2000..0x200A || code == 0x202F || code == 0x205F || code == 0x3000

    /** Zero-width space and joiners, word joiner, byte order mark, and control characters. */
    private fun isInvisible(c: Char): Boolean =
        c.code in 0x200B..0x200D || c.code == 0x2060 || c.code == 0xFEFF || Character.isISOControl(c)

    /** The replacement character and private-use characters, which come from broken font encodings. */
    private fun isUnreadable(code: Int): Boolean = code == 0xFFFD || code in 0xE000..0xF8FF
}
