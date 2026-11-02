package io.github.tjnewbry.audiopress.bake.kokoro.text

/**
 * Rewrites book text so that everything in it is a word Kokoro's dictionary can pronounce: numbers, years, money,
 * percentages, times, ordinals, Roman numerals after headings and royal names, and common abbreviations become
 * English words. Kokoro does not do this itself (misaki leaves it to num2words and spaCy), and Android's own speech
 * engines used to. Text it does not recognise passes through unchanged, so it never drops anything.
 *
 * The rules are heuristics for fiction and non-fiction prose:
 * - A four-digit number from 1100 to 2099 is a year unless a word before it ("about", "over") or after it ("dollars",
 *   "people") shows it is a quantity.
 * - `St.` is "Saint" before a capitalized word and "Street" otherwise; `Mt.` is "Mount"; `No. 5` is "number five".
 * - A Roman numeral is read after Chapter, Book, Part, Act, and similar words ("Chapter IV" is "Chapter four"), after
 *   "World War", and after a royal name as an ordinal ("Henry VIII" is "Henry the eighth").
 */
class EnglishTextNormalizer(
    private val numbers: EnglishNumbers,
) {
    fun normalize(text: String): String {
        val tokens = TextTokenizer.tokenize(text)
        val out = StringBuilder(text.length + 32)
        var i = 0
        while (i < tokens.size) {
            i += rewrite(tokens, i, out)
        }
        return out.toString()
    }

    /** Writes the replacement for the tokens starting at [i] to [out] and returns how many tokens it used. */
    private fun rewrite(
        t: List<TextToken>,
        i: Int,
        out: StringBuilder,
    ): Int {
        val token = t[i]
        return when (token.type) {
            TokenType.NUMBER -> number(t, i, out)
            TokenType.WORD -> word(t, i, out)
            TokenType.PUNCT -> punctuation(t, i, out)
            TokenType.SPACE -> out.append(token.text).let { 1 }
        }
    }

    // ---- numbers ----

    private fun number(
        t: List<TextToken>,
        i: Int,
        out: StringBuilder,
    ): Int {
        val raw = t[i].text
        val digits = raw.replace(",", "")
        val whole = digits.substringBefore('.')
        val fraction = if (digits.contains('.')) digits.substringAfter('.') else null
        val next = t.getOrNull(i + 1)
        val after = t.getOrNull(i + 2)

        // 10:45 and 9:05
        if (fraction == null && whole.length <= 2 && next?.text == ":" && after?.type == TokenType.NUMBER && after.text.length == 2 &&
            after.text.all { it in '0'..'9' } && t.getOrNull(i + 3)?.text != ":"
        ) {
            val hour = whole.toInt()
            val minute = after.text.toInt()
            if (hour in 0..24 && minute in 0..59) {
                out.append(numbers.time(hour, minute))
                return 3
            }
        }
        if (next?.text == "%") {
            out.append(spell(whole, fraction)).append(" percent")
            return 2
        }
        val suffix = next?.takeIf { it.type == TokenType.WORD }?.text?.lowercase()
        if (fraction == null && suffix in ORDINAL_SUFFIXES) {
            out.append(numbers.ordinal(whole))
            return 2
        }
        if (fraction == null && whole.length == 4 && !raw.contains(',')) {
            val value = whole.toInt()
            if (value % 10 == 0 && value in 1000..2999) {
                if (suffix == "s") {
                    out.append(numbers.decade(value))
                    return 2
                }
                if (next?.text in APOSTROPHE_STRINGS && after?.text?.lowercase() == "s") {
                    out.append(numbers.decade(value))
                    return 3
                }
            }
            if (value in 1100..2099 && !isQuantity(t, i)) {
                out.append(numbers.year(value))
                return 1
            }
        }
        out.append(spell(whole, fraction))
        return 1
    }

    private fun spell(
        whole: String,
        fraction: String?,
    ): String = if (fraction == null) numbers.cardinal(whole) else numbers.decimal(whole, fraction)

    /** A four-digit number that is a count or an amount, not a year, judging by the words around it. */
    private fun isQuantity(
        t: List<TextToken>,
        i: Int,
    ): Boolean {
        val before = wordBefore(t, i)?.lowercase()
        val after = wordAfter(t, i)?.lowercase()
        return before in QUANTITY_BEFORE || after in UNITS_AFTER
    }

    // ---- punctuation ----

    private fun punctuation(
        t: List<TextToken>,
        i: Int,
        out: StringBuilder,
    ): Int {
        val c = t[i].text
        val next = t.getOrNull(i + 1)
        val before = t.getOrNull(i - 1)
        val money = MONEY[c]
        if (money != null && next?.type == TokenType.NUMBER) {
            val digits = next.text.replace(",", "")
            val whole = digits.substringBefore('.')
            val cents = if (digits.contains('.')) digits.substringAfter('.') else null
            val scale = wordAfter(t, i + 1)?.lowercase()?.takeIf { it in MAGNITUDES && cents == null }
            if (scale != null) {
                out
                    .append(numbers.cardinal(whole))
                    .append(' ')
                    .append(scale)
                    .append(' ')
                    .append(plainPlural(money.first))
                return 2 + (nextWordOffset(t, i + 1) ?: 0)
            }
            out.append(numbers.money(whole, cents, money.first, money.second))
            return 2
        }
        if ((c == "-" || c == MINUS_SIGN) && next?.type == TokenType.NUMBER &&
            (before == null || before.type == TokenType.SPACE || before.text == "(")
        ) {
            out.append("minus ")
            return 1
        }
        if ((c == "-" || c == EN_DASH) && before?.type == TokenType.NUMBER && next?.type == TokenType.NUMBER) {
            out.append(" to ")
            return 1
        }
        out.append(c)
        return 1
    }

    /** How many tokens after the number at [numberIndex] hold the magnitude word (the space and the word), or null. */
    private fun nextWordOffset(
        t: List<TextToken>,
        numberIndex: Int,
    ): Int? {
        var j = numberIndex + 1
        while (j < t.size && t[j].type == TokenType.SPACE) j++
        return if (j < t.size && t[j].type == TokenType.WORD) j - numberIndex else null
    }

    private fun plainPlural(unit: String) = if (unit == "pound") "pounds" else unit + "s"

    // ---- words ----

    private fun word(
        t: List<TextToken>,
        i: Int,
        out: StringBuilder,
    ): Int {
        val w = t[i].text
        val lower = w.lowercase()
        val dot = t.getOrNull(i + 1)?.text == "."

        // e.g. and i.e.
        if (t.getOrNull(i + 2)?.type == TokenType.WORD && t.getOrNull(i + 3)?.text == "." && dot) {
            val pair = lower + "." + t[i + 2].text.lowercase()
            if (pair == "e.g") return out.append("for example").let { 4 }
            if (pair == "i.e") return out.append("that is").let { 4 }
        }
        if (dot) {
            ABBREVIATIONS[lower]?.let { (replacement, keepsSentenceEnd) ->
                out.append(matchCase(w, replacement))
                if (keepsSentenceEnd && endsSentenceAfter(t, i + 2)) out.append('.')
                return 2
            }
            if (lower == "st") return saintOrStreet(t, i, out)
            if (lower == "no" && w[0].isUpperCase() && nextIsNumber(t, i + 2)) return out.append(matchCase(w, "number")).let { 2 }
        }
        if (w.isRoman() && roman(t, i, w, out)) return 1
        out.append(w)
        return 1
    }

    private fun saintOrStreet(
        t: List<TextToken>,
        i: Int,
        out: StringBuilder,
    ): Int {
        var j = i + 2
        while (j < t.size && t[j].type == TokenType.SPACE) j++
        val next = t.getOrNull(j)
        val saint = next?.type == TokenType.WORD && next.text[0].isUpperCase() && j > i + 2
        if (saint) {
            out.append("Saint")
            return 2
        }
        val streetBefore = wordBefore(t, i)?.firstOrNull()?.isUpperCase() == true
        out.append(if (streetBefore) "Street." else "St.")
        return 2
    }

    private fun roman(
        t: List<TextToken>,
        i: Int,
        w: String,
        out: StringBuilder,
    ): Boolean {
        val value = numbers.romanValue(w) ?: return false
        val before = wordBefore(t, i)
        val beforeLower = before?.lowercase()
        val twoBefore = wordBefore(t, i, skip = 1)?.lowercase()
        return when {
            beforeLower in ROMAN_HEADINGS -> out.append(numbers.cardinal(value.toString())).let { true }
            beforeLower == "war" && twoBefore == "world" -> out.append(numbers.cardinal(value.toString())).let { true }
            before != null && before in ROYAL_NAMES -> out.append("the ").append(numbers.ordinal(value.toString())).let { true }
            else -> false
        }
    }

    private fun String.isRoman() = isNotEmpty() && all { it in "IVXLCDM" }

    /** True at the end of the text or before a capitalized word: the period of an abbreviation there probably ends a sentence. */
    private fun endsSentenceAfter(
        t: List<TextToken>,
        from: Int,
    ): Boolean {
        var j = from
        while (j < t.size && t[j].type == TokenType.SPACE) j++
        val next = t.getOrNull(j) ?: return true
        return next.type == TokenType.WORD && next.text[0].isUpperCase()
    }

    private fun nextIsNumber(
        t: List<TextToken>,
        from: Int,
    ): Boolean {
        var j = from
        while (j < t.size && t[j].type == TokenType.SPACE) j++
        return t.getOrNull(j)?.type == TokenType.NUMBER
    }

    /** The nearest word before token [i] (skipping spaces), [skip] words further back if asked. */
    private fun wordBefore(
        t: List<TextToken>,
        i: Int,
        skip: Int = 0,
    ): String? {
        var j = i - 1
        var skipped = 0
        while (j >= 0) {
            if (t[j].type == TokenType.WORD) {
                if (skipped == skip) return t[j].text
                skipped++
            } else if (t[j].type != TokenType.SPACE) {
                return null
            }
            j--
        }
        return null
    }

    private fun wordAfter(
        t: List<TextToken>,
        i: Int,
    ): String? {
        var j = i + 1
        while (j < t.size && t[j].type == TokenType.SPACE) j++
        return t.getOrNull(j)?.takeIf { it.type == TokenType.WORD }?.text
    }

    private fun matchCase(
        original: String,
        replacement: String,
    ) = if (original[0].isUpperCase()) replacement.replaceFirstChar { it.uppercase() } else replacement

    private companion object {
        val ORDINAL_SUFFIXES = setOf("st", "nd", "rd", "th")
        val APOSTROPHE_STRINGS = setOf("'", Char(0x2019).toString())
        val MINUS_SIGN = Char(0x2212).toString()
        val EN_DASH = Char(0x2013).toString()

        /** The currency signs: (major unit, minor unit). */
        val MONEY =
            mapOf(
                "$" to ("dollar" to "cent"),
                Char(0xA3).toString() to ("pound" to "penny"),
                Char(0x20AC).toString() to ("euro" to "cent"),
            )
        val MAGNITUDES = setOf("hundred", "thousand", "million", "billion", "trillion")

        /** Abbreviations that are spoken as a different word, lower case without the period. */
        val ABBREVIATIONS =
            mapOf(
                "mt" to ("mount" to false),
                "ft" to ("fort" to false),
                "vs" to ("versus" to false),
                "jr" to ("junior" to true),
                "sr" to ("senior" to true),
                "approx" to ("approximately" to true),
                "dept" to ("department" to true),
            )

        val QUANTITY_BEFORE =
            setOf(
                "about",
                "over",
                "under",
                "only",
                "approximately",
                "around",
                "some",
                "nearly",
                "roughly",
                "almost",
                "exactly",
                "than",
                "just",
                "all",
                "these",
                "those",
            )
        val UNITS_AFTER =
            setOf(
                "dollars",
                "dollar",
                "euros",
                "pounds",
                "cents",
                "people",
                "men",
                "women",
                "children",
                "miles",
                "feet",
                "meters",
                "kilometers",
                "words",
                "pages",
                "days",
                "hours",
                "minutes",
                "times",
                "copies",
                "soldiers",
                "years",
                "kilos",
                "kilograms",
                "acres",
                "votes",
                "troops",
                "workers",
                "students",
                "members",
                "yards",
                "inches",
                "tons",
            )
        val ROMAN_HEADINGS =
            setOf(
                "chapter",
                "book",
                "part",
                "act",
                "scene",
                "volume",
                "vol",
                "section",
                "canto",
                "appendix",
                "article",
                "table",
                "figure",
                "book",
                "stage",
                "phase",
                "type",
                "class",
            )
        val ROYAL_NAMES =
            setOf(
                "Henry",
                "Edward",
                "Richard",
                "George",
                "William",
                "Louis",
                "Charles",
                "James",
                "John",
                "Philip",
                "Elizabeth",
                "Victoria",
                "Mary",
                "Anne",
                "Pius",
                "Paul",
                "Gregory",
                "Leo",
                "Benedict",
                "Alexander",
                "Nicholas",
                "Frederick",
                "Napoleon",
                "Ramses",
                "Ramesses",
                "Catherine",
                "Peter",
                "Ivan",
                "Stephen",
                "Alfonso",
                "Ferdinand",
                "Charlemagne",
            )
    }
}
