package io.github.tjnewbry.audiopress.bake.kokoro.text

import org.junit.Test
import kotlin.test.assertEquals

class TextTokenizerTest {
    private fun types(text: String) = TextTokenizer.tokenize(text).map { it.type to it.text }

    @Test
    fun `joining the tokens gives the text back`() {
        val text = "In 1984, \"Don't!\" she said -- it's 3.5% (about \$1,250.50) well-known café\n\tdone."
        assertEquals(text, TextTokenizer.tokenize(text).joinToString("") { it.text })
    }

    @Test
    fun `words keep their apostrophes and hyphens`() {
        assertEquals(listOf(TokenType.WORD to "don't", TokenType.SPACE to " ", TokenType.WORD to "well-known"), types("don't well-known"))
        assertEquals(listOf(TokenType.WORD to "can${Char(0x2019)}t"), types("can${Char(0x2019)}t"))
    }

    @Test
    fun `a leading or trailing apostrophe or hyphen is punctuation`() {
        assertEquals(listOf(TokenType.PUNCT to "'", TokenType.WORD to "tis"), types("'tis"))
        assertEquals(listOf(TokenType.WORD to "dogs", TokenType.PUNCT to "'"), types("dogs'"))
        assertEquals(listOf(TokenType.WORD to "well", TokenType.PUNCT to "-"), types("well-"))
    }

    @Test
    fun `numbers keep thousands commas and one decimal point`() {
        assertEquals(listOf(TokenType.NUMBER to "1,250.50"), types("1,250.50"))
        assertEquals(listOf(TokenType.NUMBER to "1", TokenType.PUNCT to ",", TokenType.NUMBER to "2500"), types("1,2500"))
        assertEquals(listOf(TokenType.NUMBER to "3.14", TokenType.PUNCT to ".", TokenType.NUMBER to "15"), types("3.14.15"))
        assertEquals(listOf(TokenType.NUMBER to "5", TokenType.PUNCT to "."), types("5."))
        assertEquals(listOf(TokenType.NUMBER to "10", TokenType.PUNCT to ":", TokenType.NUMBER to "45"), types("10:45"))
    }

    @Test
    fun `a number glued to a word splits into the two`() {
        assertEquals(listOf(TokenType.NUMBER to "21", TokenType.WORD to "st"), types("21st"))
        assertEquals(listOf(TokenType.NUMBER to "1990", TokenType.WORD to "s"), types("1990s"))
    }

    @Test
    fun `whitespace runs are one token and punctuation is one character each`() {
        assertEquals(
            listOf(TokenType.WORD to "a", TokenType.SPACE to " \n ", TokenType.PUNCT to "!", TokenType.PUNCT to "?"),
            types("a \n !?"),
        )
        assertEquals(emptyList(), types(""))
    }
}
