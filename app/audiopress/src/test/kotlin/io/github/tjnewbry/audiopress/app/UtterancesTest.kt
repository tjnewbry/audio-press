package io.github.tjnewbry.audiopress.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UtterancesTest {
    @Test
    fun `a short paragraph is one utterance and blank paragraphs are skipped`() {
        val utterances = splitIntoUtterances(listOf("First paragraph.", "   ", "Second one."))

        assertEquals(listOf(Utterance(0, 0, "First paragraph."), Utterance(2, 0, "Second one.")), utterances)
    }

    @Test
    fun `a long paragraph is cut at sentence ends, and every piece maps back to the paragraph`() {
        val sentence = "This is a sentence of moderate length. "
        val paragraph = sentence.repeat(40).trim() // about 1500 characters
        val utterances = splitIntoUtterances(listOf(paragraph), maxChars = 400)

        assertTrue(utterances.size > 3)
        for (utterance in utterances) {
            assertTrue(utterance.text.length <= 400)
            assertTrue(utterance.text.endsWith("."), "cut at a sentence end: ${utterance.text.takeLast(10)}")
            assertEquals(utterance.text, paragraph.substring(utterance.start, utterance.start + utterance.text.length))
            assertEquals(0, utterance.paragraph)
        }
        assertEquals(paragraph.replace(" ", ""), utterances.joinToString("") { it.text }.replace(" ", ""))
    }

    @Test
    fun `text with no sentence end is cut at a space, and a giant word inside the word`() {
        val spaced = splitIntoUtterances(listOf("word ".repeat(50).trim()), maxChars = 40)
        assertTrue(spaced.all { it.text.length <= 40 && !it.text.startsWith(" ") && it.text.startsWith("word") })

        val giant = splitIntoUtterances(listOf("x".repeat(95)), maxChars = 40)
        assertEquals(listOf(40, 40, 15), giant.map { it.text.length })
        assertEquals(listOf(0, 40, 80), giant.map { it.start })
    }

    @Test
    fun `words are found with their character ranges`() {
        val utterance = Utterance(0, 0, "Hello,  brave world.")

        assertEquals(listOf(0..5, 8..12, 14..19), utterance.words)
    }

    @Test
    fun `a position is located in the right piece of a long paragraph`() {
        val paragraph = "Alpha beta gamma. ".repeat(30).trim()
        val utterances = splitIntoUtterances(listOf("Short one.", paragraph), maxChars = 100)
        val second = utterances.last()

        val (index, offset) = locate(utterances, Position(1, second.start + 6))

        assertEquals(utterances.lastIndex, index)
        assertEquals(6, offset)
        assertEquals(1 to 0, locate(utterances, Position(1, 0)).let { utterances[it.first].paragraph to it.second })
    }

    @Test
    fun `a position past the end or in an empty book falls back to the start`() {
        val utterances = splitIntoUtterances(listOf("one", "two"))

        assertEquals(0 to 0, locate(utterances, Position(9, 3)))
        assertEquals(0 to 0, locate(emptyList(), Position(1, 1)))
    }
}
