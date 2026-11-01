package io.github.tjnewbry.audiopress.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpeechChunkerTest {
    private val box = Box(0f, 0f, 1f, 1f)

    /** A segment of the words in [text], all on [page] (or on the pages in [pages], one per word). */
    private fun segment(
        text: String,
        kind: SegmentKind = SegmentKind.PARAGRAPH,
        page: Int = 1,
        pages: List<Int> = emptyList(),
    ): Segment =
        Segment(
            kind,
            text.split(' ').mapIndexed {
                i,
                word,
                ->
                Token(word, spaceBefore = i > 0, spans = listOf(Span(pages.getOrElse(i) { page }, box)))
            },
        )

    private fun chunk(
        vararg segments: Segment,
        max: Int = 3900,
    ) = SpeechChunker(max).chunk(Transcript(segments.toList()))

    @Test
    fun `short paragraphs and headings are one utterance each`() {
        val utterances =
            chunk(segment("A Heading", SegmentKind.HEADING, page = 3), segment("One short paragraph. With two sentences.", page = 3))

        assertEquals(listOf("A Heading", "One short paragraph. With two sentences."), utterances.map { it.text })
        assertEquals(listOf(SegmentKind.HEADING, SegmentKind.PARAGRAPH), utterances.map { it.kind })
        assertEquals(listOf(0, 1), utterances.map { it.segmentIndex })
        assertEquals(listOf(0, 0), utterances.map { it.start })
        assertEquals(listOf(9, 40), utterances.map { it.end })
    }

    @Test
    fun `an utterance carries the page of its first word`() {
        val utterances = chunk(segment("over the page break", pages = listOf(7, 7, 8, 8)))

        assertEquals(7, utterances.single().page)
        assertEquals(listOf(7, 7, 8, 8), utterances.single().words.map { it.page })
    }

    @Test
    fun `a long paragraph is cut at sentence ends and packed up to the limit`() {
        val sentences = List(10) { "This is sentence number $it of the test." }
        val text = sentences.joinToString(" ")

        val utterances = chunk(segment(text), max = 100)

        assertTrue(utterances.size > 1)
        assertTrue(utterances.all { it.text.length <= 100 })
        assertTrue(utterances.all { it.text.endsWith(".") && it.text.startsWith("This") }, "cuts only at sentence ends")
        assertEquals(text, utterances.joinToString(" ") { it.text }, "nothing is lost")
        assertEquals(
            2,
            utterances
                .first()
                .text
                .split(". ")
                .size,
            "as many whole sentences as fit",
        )
    }

    @Test
    fun `each utterance text is exactly its range in the segment text`() {
        val seg = segment(List(40) { "Sentence $it ends here." }.joinToString(" "))

        val utterances = chunk(seg, max = 120)

        for (u in utterances) assertEquals(seg.text.substring(u.start, u.end), u.text)
        assertEquals(0, utterances.first().start)
        assertEquals(seg.text.length, utterances.last().end)
    }

    @Test
    fun `closing quotes stay with their sentence`() {
        val close = Char(0x201D)
        val text = "He said ${Char(0x201C)}Come here.$close She did not. ${Char(0x201C)}Why?$close he asked. Then it was over."

        val utterances = chunk(segment(text), max = 35)

        assertEquals("He said ${Char(0x201C)}Come here.$close She did not.", utterances.first().text)
        assertEquals(text, utterances.joinToString(" ") { it.text })
    }

    @Test
    fun `abbreviations and initials are not sentence ends`() {
        assertEquals("Dr. Jones went to see Mr. Smith", chunk(segment("Dr. Jones went to see Mr. Smith at noon"), max = 33).first().text)
        assertEquals("J. R. R. Tolkien", chunk(segment("J. R. R. Tolkien wrote many books"), max = 20).first().text)
        assertEquals("We left at 5 p.m. Then", chunk(segment("We left at 5 p.m. Then we walked home slowly"), max = 22).first().text)
    }

    @Test
    fun `a period followed by a lowercase word does not end a sentence`() {
        val utterances = chunk(segment("We saw apples, pears, etc. and plums. Then we left. Nobody stayed behind at all."), max = 50)

        assertEquals("We saw apples, pears, etc. and plums.", utterances.first().text)
    }

    @Test
    fun `question and exclamation marks end sentences even before a lowercase word`() {
        val utterances = chunk(segment("Why not? he asked. Because I say so! she said."), max = 17)

        assertEquals(listOf("Why not?", "he asked.", "Because I say so!", "she said."), utterances.map { it.text })
    }

    @Test
    fun `a sentence that is too long is cut after the last comma or semicolon`() {
        val text = "first clause here, second clause here; third clause here, fourth clause here and then it goes on without any stop"

        val utterances = chunk(segment(text), max = 60)

        assertEquals("first clause here, second clause here; third clause here,", utterances[0].text)
        assertEquals("fourth clause here and then it goes on without any stop", utterances[1].text)
    }

    @Test
    fun `without commas a long sentence is cut at the last space, never inside a word`() {
        val text = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda"

        val utterances = chunk(segment(text), max = 25)

        assertEquals(listOf("alpha beta gamma delta", "epsilon zeta eta theta", "iota kappa lambda"), utterances.map { it.text })
        assertTrue(utterances.all { it.text.length <= 25 })
    }

    @Test
    fun `a comma inside a number is not a cut point`() {
        val utterances = chunk(segment("the total came to 1,000,000 dollars in all"), max = 28)

        assertEquals(listOf("the total came to 1,000,000", "dollars in all"), utterances.map { it.text })
    }

    @Test
    fun `a single word longer than the limit is cut inside the word as a last resort`() {
        val x = "x".repeat(12)

        val utterances = chunk(segment("a ${x}${x}xxxxxx b"), max = 12)

        assertEquals(listOf("a", x, x, "xxxxxx b"), utterances.map { it.text })
    }

    @Test
    fun `the default limit keeps every utterance under Androids TTS limit of 4000 characters`() {
        val paragraph = List(2000) { "word$it" }.joinToString(" ") + "."

        val utterances = chunk(segment(paragraph))

        assertTrue(utterances.size >= 3)
        assertTrue(utterances.all { it.text.length <= 3900 })
        assertEquals(2000, utterances.sumOf { it.wordCount })
    }

    @Test
    fun `an offset reported by TTS maps back to the segment and the word`() {
        val text = List(30) { "Sentence number $it is here." }.joinToString(" ")
        val seg = segment(text)
        val utterances = chunk(seg, max = 100)
        val second = utterances[1]

        val word = second.wordAt(second.text.indexOf("number") + 2)!!

        assertEquals("number", seg.tokens[word.tokenIndex].text)
        assertEquals("number", seg.text.substring(word.range.start, word.range.end))
        val range = second.segmentRange(second.text.indexOf("number"), second.text.indexOf("number") + 6)
        assertEquals("number", seg.text.substring(range.start, range.end))
        assertEquals(word.range, range)
    }

    @Test
    fun `an offset on a space gives the next word and an offset past the end gives the last`() {
        val utterance = chunk(segment("one two three")).single()

        assertEquals("two", utterance.text.substring(utterance.wordAt(3)!!.range.start, utterance.wordAt(3)!!.range.end))
        assertEquals("three", utterance.text.substring(utterance.wordAt(99)!!.range.start, utterance.wordAt(99)!!.range.end))
        assertEquals("one", utterance.text.substring(utterance.wordAt(0)!!.range.start, utterance.wordAt(0)!!.range.end))
    }

    @Test
    fun `segmentRange clamps to the utterance`() {
        val utterance = chunk(segment("one two three")).single()

        assertEquals(TextRange(0, 13), utterance.segmentRange(-5, 500))
    }

    @Test
    fun `an empty transcript or empty segment gives no utterances`() {
        assertEquals(emptyList(), chunk())
        assertEquals(emptyList(), chunk(Segment(SegmentKind.PARAGRAPH, emptyList())))
    }

    @Test
    fun `the duration estimate is words over words per minute`() {
        assertEquals(60.0, SpeechChunker.estimatedSeconds(170))
        assertEquals(30.0, SpeechChunker.estimatedSeconds(170, wordsPerMinute = 340))
        assertEquals(60.0, Transcript(listOf(segment(List(170) { "w" }.joinToString(" ")))).estimatedSeconds())
        assertEquals(30.0, chunk(segment(List(85) { "w" }.joinToString(" "))).single().estimatedSeconds())
        assertFailsWith<IllegalArgumentException> { SpeechChunker.estimatedSeconds(10, 0) }
    }

    @Test
    fun `a limit that is too small is rejected`() {
        assertFailsWith<IllegalArgumentException> { SpeechChunker(3) }
    }
}
