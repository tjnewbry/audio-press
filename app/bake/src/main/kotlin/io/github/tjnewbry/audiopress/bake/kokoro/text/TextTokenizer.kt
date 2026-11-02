package io.github.tjnewbry.audiopress.bake.kokoro.text

enum class TokenType {
    /** Letters, with apostrophes and hyphens between letters ("don't", "well-known"). */
    WORD,

    /** Digits, with thousands commas and one decimal point inside ("1,250.50"). */
    NUMBER,

    /** Any one other visible character: punctuation and symbols. */
    PUNCT,

    /** A run of whitespace. */
    SPACE,
}

data class TextToken(
    val type: TokenType,
    val text: String,
)

/**
 * Splits text into words, numbers, single punctuation marks, and runs of spaces, without losing any character: joining
 * the tokens' text gives the input back. This is the first step of the front end, and the normalizer and the phonemizer
 * both work on these tokens.
 */
object TextTokenizer {
    private val APOSTROPHES = "'" + Char(0x2019)
    private val HYPHENS = "-" + Char(0x2010)

    fun tokenize(text: String): List<TextToken> {
        val tokens = ArrayList<TextToken>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val end =
                when {
                    c.isWhitespace() -> scan(text, i) { it.isWhitespace() }
                    c.isLetter() -> scanWord(text, i)
                    c in '0'..'9' -> scanNumber(text, i)
                    else -> i + 1
                }
            val type =
                when {
                    c.isWhitespace() -> TokenType.SPACE
                    c.isLetter() -> TokenType.WORD
                    c in '0'..'9' -> TokenType.NUMBER
                    else -> TokenType.PUNCT
                }
            tokens += TextToken(type, text.substring(i, end))
            i = end
        }
        return tokens
    }

    private fun scan(
        text: String,
        start: Int,
        accept: (Char) -> Boolean,
    ): Int {
        var i = start
        while (i < text.length && accept(text[i])) i++
        return i
    }

    /** A word runs over letters, and over an apostrophe or hyphen that has a letter on both sides. */
    private fun scanWord(
        text: String,
        start: Int,
    ): Int {
        var i = start
        while (i < text.length) {
            val c = text[i]
            val joiner = (c in APOSTROPHES || c in HYPHENS) && i > start && text.getOrNull(i + 1)?.isLetter() == true
            if (!c.isLetter() && !joiner) break
            i++
        }
        return i
    }

    /** A number runs over digits, over a comma followed by exactly three digits, and over one decimal point before a digit. */
    private fun scanNumber(
        text: String,
        start: Int,
    ): Int {
        var i = start
        var decimal = false
        while (i < text.length) {
            val c = text[i]
            when {
                c in '0'..'9' -> {
                    i++
                }

                c == ',' && !decimal && isThousandsGroup(text, i + 1) -> {
                    i++
                }

                c == '.' && !decimal && text.getOrNull(i + 1)?.let { it in '0'..'9' } == true -> {
                    decimal = true
                    i++
                }

                else -> {
                    break
                }
            }
        }
        return i
    }

    private fun isThousandsGroup(
        text: String,
        start: Int,
    ): Boolean {
        if (start + 3 > text.length || (start + 3 < text.length && text[start + 3] in '0'..'9')) return false
        return (start until start + 3).all { text[it] in '0'..'9' }
    }
}
