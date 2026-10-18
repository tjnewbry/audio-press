package io.github.tjnewbry.audiopress.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Special characters are built from code points with [c], because most are invisible in source code. */
class TextNormalizerTest {
    @Test
    fun `expands ligatures`() {
        assertEquals("first official", TextNormalizer.normalize("${c(0xFB01)}rst o${c(0xFB03)}cial"))
    }

    @Test
    fun `turns special spaces into ordinary spaces`() {
        assertEquals("10 km", TextNormalizer.normalize("10${c(0x00A0)}km"))
        assertEquals("a b", TextNormalizer.normalize("a${c(0x2009)}${c(0x2009)}b"))
    }

    @Test
    fun `removes invisible and unreadable characters`() {
        assertEquals("word", TextNormalizer.normalize("${c(0xFEFF)}wo${c(0x200B)}rd${c(0xFFFD)}${c(0xE001)}"))
    }

    @Test
    fun `keeps a soft hyphen only at the end of a word`() {
        assertEquals("international", TextNormalizer.normalize("inter${c(0x00AD)}national"))
        assertEquals("inter${c(0x00AD)}", TextNormalizer.normalize("inter${c(0x00AD)}"))
    }

    @Test
    fun `leaves ordinary text alone`() {
        val text = "${c(0x201C)}Quoted${c(0x201D)} ${c(0x2014)} caf${c(0x00E9)}"

        assertEquals(text, TextNormalizer.normalize(text))
    }

    @Test
    fun `drops words that become empty`() {
        val page = SourcePage(1, 100f, 100f, listOf(word("${c(0x200B)}"), word("kept")))

        assertEquals(listOf("kept"), TextNormalizer.normalize(page).words.map { it.text })
    }

    @Test
    fun `splits superscript digits off the end of a word`() {
        val page = SourcePage(1, 100f, 100f, listOf(word("summer.${c(0x00B3)}"), word("${c(0x00B2)}")))

        val words = TextNormalizer.normalize(page).words

        assertEquals(listOf("summer.", "${c(0x00B3)}", "${c(0x00B2)}"), words.map { it.text })
        assertEquals(listOf(false, true, false), words.map { it.joinsPrevious })
    }

    private fun c(code: Int): Char = Char(code)

    private fun word(text: String) = SourceWord(text, Box(0f, 0f, 10f, 10f), baseline = 8f, fontSize = 10f)
}
