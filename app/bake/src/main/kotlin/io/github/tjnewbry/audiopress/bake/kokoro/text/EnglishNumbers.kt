package io.github.tjnewbry.audiopress.bake.kokoro.text

/**
 * Turns the numbers found in book text into the English words to speak, following misaki's conventions (the front
 * end Kokoro was trained with): years are read in pairs ("nineteen eighty-four"), big numbers have no "and" ("one
 * thousand two hundred fifty"), and "and" appears only between dollars and cents. Whole numbers are spelled by
 * [speller]; everything built on top of them is plain Kotlin, so it is covered by JVM tests.
 *
 * Digits arrive as strings, so numbers too big for a `Long` are read digit by digit instead of failing.
 */
class EnglishNumbers(
    private val speller: NumberSpeller,
) {
    private val digitWords = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")

    /** A whole number: "1250" is "one thousand two hundred fifty". Leading zeros or 16 and more digits are read digit by digit. */
    fun cardinal(digits: String): String {
        require(digits.isNotEmpty() && digits.all { it in '0'..'9' }) { "not a number: $digits" }
        if (digits.length > MAX_SPELLED_DIGITS || (digits.length > 1 && digits[0] == '0')) return digitByDigit(digits)
        return speller.cardinal(digits.toLong())
    }

    /** A decimal such as "3.5" ("three point five") or "0.05" ("zero point zero five"). */
    fun decimal(
        whole: String,
        fraction: String,
    ): String = cardinal(whole.ifEmpty { "0" }) + " point " + digitByDigit(fraction)

    /** "21" as "twenty-first". */
    fun ordinal(digits: String): String =
        if (digits.length > MAX_SPELLED_DIGITS || (digits.length > 1 && digits[0] == '0')) {
            cardinal(digits)
        } else {
            speller.ordinal(digits.toLong())
        }

    /**
     * A year: 1984 is "nineteen eighty-four", 2019 "twenty nineteen", 1905 "nineteen oh five", 1900 "nineteen
     * hundred", 2000 "two thousand", 2005 "two thousand five". Values outside 1000 to 2999 are read as numbers.
     */
    fun year(value: Int): String {
        if (value !in 1000..2999) return speller.cardinal(value.toLong())
        val high = value / 100
        val low = value % 100
        return when {
            value % 1000 == 0 -> speller.cardinal(value.toLong())
            value in 2000..2009 -> "two thousand " + speller.cardinal(low.toLong())
            low == 0 -> speller.cardinal(high.toLong()) + " hundred"
            low < 10 -> speller.cardinal(high.toLong()) + " oh " + speller.cardinal(low.toLong())
            else -> speller.cardinal(high.toLong()) + " " + speller.cardinal(low.toLong())
        }
    }

    /** A decade: 1990 is "nineteen nineties", 1900 "nineteen hundreds", 2000 "two thousands". */
    fun decade(value: Int): String {
        val words = year(value).split(' ')
        return (words.dropLast(1) + plural(words.last())).joinToString(" ")
    }

    /** Money: ("4", "99", "dollar") is "four dollars and ninety-nine cents"; one dollar is "one dollar". */
    fun money(
        whole: String,
        cents: String?,
        major: String,
        minor: String,
    ): String {
        val dollars = whole.ifEmpty { "0" }
        val hundredths = cents?.padEnd(2, '0')?.take(2)?.toInt() ?: 0
        val parts = mutableListOf<String>()
        if (dollars != "0" || hundredths == 0) parts += cardinal(dollars) + " " + unit(major, dollars == "1")
        if (hundredths > 0) parts += cardinal(hundredths.toString()) + " " + unit(minor, hundredths == 1)
        return parts.joinToString(" and ")
    }

    /** A clock time: 10:45 is "ten forty-five", 9:05 "nine oh five", 10:00 "ten o'clock". */
    fun time(
        hour: Int,
        minute: Int,
    ): String =
        when {
            minute == 0 -> speller.cardinal(hour.toLong()) + " o'clock"
            minute < 10 -> speller.cardinal(hour.toLong()) + " oh " + speller.cardinal(minute.toLong())
            else -> speller.cardinal(hour.toLong()) + " " + speller.cardinal(minute.toLong())
        }

    /** Roman numeral text (like "XIV") as a number, or null if it is not a valid numeral from I to MMMCMXCIX. */
    fun romanValue(text: String): Int? {
        if (text.isEmpty()) return null
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        for (i in text.indices) {
            val v = values[text[i]] ?: return null
            val next = values[text.getOrNull(i + 1)]
            total += if (next != null && next > v) -v else v
        }
        return total.takeIf { it in 1..3999 && roman(it) == text }
    }

    private fun roman(value: Int): String {
        val symbols =
            listOf(
                1000 to "M",
                900 to "CM",
                500 to "D",
                400 to "CD",
                100 to "C",
                90 to "XC",
                50 to "L",
                40 to "XL",
                10 to "X",
                9 to "IX",
                5 to "V",
                4 to "IV",
                1 to "I",
            )
        var rest = value
        return buildString {
            for ((n, s) in symbols) {
                while (rest >= n) {
                    append(s)
                    rest -= n
                }
            }
        }
    }

    private fun unit(
        name: String,
        single: Boolean,
    ) = if (single) name else plural(name)

    private fun digitByDigit(digits: String): String = digits.map { digitWords[it - '0'] }.joinToString(" ")

    private fun plural(word: String): String =
        when {
            word == "penny" -> "pence"
            word.endsWith("y") && word.length > 1 && word[word.length - 2] !in "aeiou" -> word.dropLast(1) + "ies"
            word.endsWith("s") || word.endsWith("x") || word.endsWith("ch") || word.endsWith("sh") -> word + "es"
            else -> word + "s"
        }

    private companion object {
        const val MAX_SPELLED_DIGITS = 15
    }
}
