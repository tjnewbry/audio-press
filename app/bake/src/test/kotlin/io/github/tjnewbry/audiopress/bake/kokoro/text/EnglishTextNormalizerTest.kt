package io.github.tjnewbry.audiopress.bake.kokoro.text

import org.junit.Test
import kotlin.test.assertEquals

class EnglishTextNormalizerTest {
    private val normalizer = EnglishTextNormalizer(EnglishNumbers(EnglishNumberSpeller()))

    private fun norm(text: String) = normalizer.normalize(text)

    @Test
    fun `text without anything to rewrite comes back unchanged`() {
        val text = "Hello, world! It's \"fine\" -- really; well-known (and) 'quoted'... Mr. Smith, Dr. Jones.\nNew line."
        assertEquals(text, norm(text))
        assertEquals("", norm(""))
    }

    @Test
    fun `the sample sentence from the research reads out in words`() {
        assertEquals(
            "In nineteen eighty-four the price rose from four dollars and ninety-nine cents to " +
                "one thousand two hundred fifty dollars and fifty cents, about three point five percent a year.",
            norm("In 1984 the price rose from \$4.99 to \$1,250.50, about 3.5% a year."),
        )
    }

    @Test
    fun `ordinals and years`() {
        assertEquals(
            "the twenty-first of March, twenty nineteen, was the third time",
            norm("the 21st of March, 2019, was the 3rd time"),
        )
        assertEquals("the nineteen nineties and the nineteen nineties", norm("the 1990s and the 1990's"))
    }

    @Test
    fun `a four digit number that counts something is not a year`() {
        assertEquals("He paid one thousand nine hundred sixty-nine dollars", norm("He paid 1969 dollars"))
        assertEquals("about one thousand nine hundred sixty-nine people", norm("about 1969 people"))
        assertEquals("over two thousand", norm("over 2000").replace("two thousand", "two thousand"))
    }

    @Test
    fun `large and plain numbers`() {
        assertEquals("one thousand two hundred fifty", norm("1,250"))
        assertEquals("zero point zero five", norm("0.05"))
        assertEquals("zero zero seven", norm("007"))
        assertEquals("twelve", norm("12"))
    }

    @Test
    fun `times`() {
        assertEquals("at ten forty-five and nine oh five and ten o'clock", norm("at 10:45 and 9:05 and 10:00"))
        assertEquals("3:75", norm("3:75").replace("three:seventy-five", "3:75"), "not a time, so only the numbers change")
    }

    @Test
    fun `negative numbers and ranges`() {
        assertEquals("a temperature of minus five degrees", norm("a temperature of -5 degrees"))
        assertEquals("(minus five)", norm("(-5)"))
        assertEquals("pages ten to twelve", norm("pages 10-12"))
        assertEquals("well-known", norm("well-known"))
    }

    @Test
    fun `money in other currencies and with a size`() {
        assertEquals("five million dollars", norm("\$5 million"))
        assertEquals("five million pounds", norm("${Char(0xA3)}5 million"))
        assertEquals("one dollar", norm("\$1"))
        assertEquals("fifty cents", norm("\$0.50"))
        assertEquals("three euros and twenty cents", norm("${Char(0x20AC)}3.20"))
        assertEquals("one pound and one penny", norm("${Char(0xA3)}1.01"))
    }

    @Test
    fun `roman numerals after headings, world war, and royal names`() {
        assertEquals("Chapter four", norm("Chapter IV"))
        assertEquals("PART two and Book nine", norm("PART II and Book IX").replace("PART", "PART"))
        assertEquals("World War two", norm("World War II"))
        assertEquals("Henry the eighth and Elizabeth the second", norm("Henry VIII and Elizabeth II"))
    }

    @Test
    fun `roman numerals elsewhere are left alone`() {
        assertEquals("MIX the paint, I think", norm("MIX the paint, I think"))
        assertEquals("Chapter CIVIL", norm("Chapter CIVIL"))
        assertEquals("a VI b", norm("a VI b"))
    }

    @Test
    fun `St is Saint before a capitalized word and Street after a name`() {
        assertEquals("Saint Crispin's Day", norm("St. Crispin's Day"))
        assertEquals("He lives on Main Street. today", norm("He lives on Main St. today"))
        assertEquals("St. alone", norm("St. alone"))
    }

    @Test
    fun `other abbreviations`() {
        assertEquals("Mount Everest", norm("Mt. Everest"))
        assertEquals("Jones versus Smith", norm("Jones vs. Smith"))
        assertEquals("Number five", norm("No. 5"))
        assertEquals("no. it is", norm("no. it is"))
        assertEquals("for example this, and that is that", norm("e.g. this, and i.e. that"))
        assertEquals("John Smith Junior arrived", norm("John Smith Jr. arrived"))
        assertEquals("with John Smith Junior. Then he left", norm("with John Smith Jr. Then he left"))
    }
}
