package io.github.tjnewbry.audiopress.bake.kokoro.text

/**
 * Spells whole numbers in English words: "twenty-one", "one thousand two hundred fifty" (hyphens, no "and", no commas).
 * The SDK's android.icu stubs have no `RuleBasedNumberFormat` to use (compileSdk 36), so this is plain Kotlin that the JVM tests cover;
 * anything better can replace it behind [NumberSpeller].
 */
class EnglishNumberSpeller : NumberSpeller {
    private val small =
        listOf(
            "zero",
            "one",
            "two",
            "three",
            "four",
            "five",
            "six",
            "seven",
            "eight",
            "nine",
            "ten",
            "eleven",
            "twelve",
            "thirteen",
            "fourteen",
            "fifteen",
            "sixteen",
            "seventeen",
            "eighteen",
            "nineteen",
        )
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val scales =
        listOf(1_000_000_000_000L to "trillion", 1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")

    override fun cardinal(value: Long): String {
        require(value >= 0)
        if (value < 20) return small[value.toInt()]
        if (value < 100) return tens[(value / 10).toInt()] + if (value % 10 == 0L) "" else "-" + small[(value % 10).toInt()]
        if (value < 1000) return small[(value / 100).toInt()] + " hundred" + if (value % 100 == 0L) "" else " " + cardinal(value % 100)
        for ((scale, name) in scales) {
            if (value >= scale) return cardinal(value / scale) + " " + name + if (value % scale == 0L) "" else " " + cardinal(value % scale)
        }
        error("unreachable")
    }

    override fun ordinal(value: Long): String {
        val words = cardinal(value)
        val lastStart = maxOf(words.lastIndexOf(' '), words.lastIndexOf('-')) + 1
        val last = words.substring(lastStart)
        val ordinal =
            when {
                last == "one" -> "first"
                last == "two" -> "second"
                last == "three" -> "third"
                last == "five" -> "fifth"
                last == "eight" -> "eighth"
                last == "nine" -> "ninth"
                last == "twelve" -> "twelfth"
                last.endsWith("y") -> last.dropLast(1) + "ieth"
                else -> last + "th"
            }
        return words.substring(0, lastStart) + ordinal
    }
}
