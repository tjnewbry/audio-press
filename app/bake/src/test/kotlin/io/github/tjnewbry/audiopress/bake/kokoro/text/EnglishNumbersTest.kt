package io.github.tjnewbry.audiopress.bake.kokoro.text

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EnglishNumbersTest {
    private val numbers = EnglishNumbers(EnglishNumberSpeller())

    @Test
    fun `years are read in pairs the way misaki reads them`() {
        assertEquals("nineteen eighty-four", numbers.year(1984))
        assertEquals("twenty nineteen", numbers.year(2019))
        assertEquals("nineteen oh five", numbers.year(1905))
        assertEquals("nineteen hundred", numbers.year(1900))
        assertEquals("two thousand", numbers.year(2000))
        assertEquals("two thousand five", numbers.year(2005))
        assertEquals("one thousand", numbers.year(1000))
        assertEquals("ten sixty-six", numbers.year(1066))
        assertEquals("twenty twenty-four", numbers.year(2024))
        assertEquals("three thousand five hundred", numbers.year(3500), "outside 1000 to 2999 it is just a number")
    }

    @Test
    fun `decades are plural`() {
        assertEquals("nineteen nineties", numbers.decade(1990))
        assertEquals("eighteen hundreds", numbers.decade(1800))
        assertEquals("two thousands", numbers.decade(2000))
        assertEquals("eighteen twenties", numbers.decade(1820))
    }

    @Test
    fun `whole numbers have no and`() {
        assertEquals("one thousand two hundred fifty", numbers.cardinal("1250"))
        assertEquals("zero", numbers.cardinal("0"))
        assertEquals("one hundred one", numbers.cardinal("101"))
    }

    @Test
    fun `leading zeros and huge numbers are read digit by digit`() {
        assertEquals("zero zero seven", numbers.cardinal("007"))
        assertEquals("one two three four five six seven eight nine zero one two three four five six", numbers.cardinal("1234567890123456"))
        assertEquals(
            "nine hundred ninety-nine trillion nine hundred ninety-nine billion nine hundred ninety-nine million nine hundred ninety-nine thousand nine hundred ninety-nine",
            numbers.cardinal("999999999999999"),
        )
        assertFailsWith<IllegalArgumentException> { numbers.cardinal("12a") }
    }

    @Test
    fun `decimals read the digits after the point one by one`() {
        assertEquals("three point five", numbers.decimal("3", "5"))
        assertEquals("zero point zero five", numbers.decimal("0", "05"))
        assertEquals("zero point two five", numbers.decimal("", "25"))
        assertEquals("one thousand point five", numbers.decimal("1000", "5"))
    }

    @Test
    fun `ordinals`() {
        assertEquals("twenty-first", numbers.ordinal("21"))
        assertEquals("third", numbers.ordinal("3"))
        assertEquals("one hundredth", numbers.ordinal("100"))
    }

    @Test
    fun `money joins dollars and cents with and, and says one dollar in the singular`() {
        assertEquals("four dollars and ninety-nine cents", numbers.money("4", "99", "dollar", "cent"))
        assertEquals("one thousand two hundred fifty dollars and fifty cents", numbers.money("1250", "50", "dollar", "cent"))
        assertEquals("five dollars", numbers.money("5", null, "dollar", "cent"))
        assertEquals("one dollar", numbers.money("1", null, "dollar", "cent"))
        assertEquals("fifty cents", numbers.money("0", "50", "dollar", "cent"))
        assertEquals("one dollar and one cent", numbers.money("1", "01", "dollar", "cent"))
        assertEquals("zero dollars", numbers.money("0", null, "dollar", "cent"))
        assertEquals("three euros and twenty cents", numbers.money("3", "2", "euro", "cent"))
        assertEquals("one pound and one penny", numbers.money("1", "01", "pound", "penny"))
        assertEquals("two pounds and five pence", numbers.money("2", "05", "pound", "penny"))
    }

    @Test
    fun `clock times`() {
        assertEquals("ten forty-five", numbers.time(10, 45))
        assertEquals("nine oh five", numbers.time(9, 5))
        assertEquals("ten o'clock", numbers.time(10, 0))
    }

    @Test
    fun `roman numerals are accepted only in their proper form`() {
        assertEquals(4, numbers.romanValue("IV"))
        assertEquals(8, numbers.romanValue("VIII"))
        assertEquals(1994, numbers.romanValue("MCMXCIV"))
        assertEquals(3999, numbers.romanValue("MMMCMXCIX"))
        assertNull(numbers.romanValue("IIII"))
        assertNull(numbers.romanValue("VX"))
        assertNull(numbers.romanValue("CIVIL"))
        assertNull(numbers.romanValue("MILD"))
        assertNull(numbers.romanValue(""))
        assertNull(numbers.romanValue("ABC"))
    }
}
