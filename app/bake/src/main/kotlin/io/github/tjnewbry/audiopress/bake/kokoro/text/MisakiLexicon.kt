package io.github.tjnewbry.audiopress.bake.kokoro.text

import java.text.Normalizer

/** What follows a word: whether the next sound is a vowel (`null` if a pause comes), and whether the next word is "to". */
data class WordContext(
    val futureVowel: Boolean? = null,
    val futureTo: Boolean = false,
)

/**
 * The dictionary half of misaki's English G2P, ported from `misaki/en.py` (hexgrad/misaki, Apache-2.0): gold and
 * silver lookup, stress by capitalization, `-s`, `-ed`, and `-ing` stemming, acronyms, and the special cases for
 * "a", "the", "to", "in", and a few others. The results are phonemes in misaki's alphabet, which is what Kokoro
 * was trained on.
 *
 * What is not ported: numbers (the text normalizer already turned them into words), and the fallback for words the
 * dictionaries do not hold (an [UnknownWordFallback] does that). Part-of-speech tags, which misaki gets from spaCy,
 * are guessed by the caller; with no tag a word gets its `DEFAULT` pronunciation.
 */
class MisakiLexicon(
    private val british: Boolean,
    private val golds: MisakiDictionary,
    private val silvers: MisakiDictionary,
) {
    /** Phonemes of the letter [c] in capitals, such as `ˈA`, or null (used to spell words out). */
    fun letter(c: Char): String? = golds[c.uppercaseChar().toString()] as? String

    /**
     * The phonemes for [rawWord], or null if the dictionaries cannot say it. [tag] is a part-of-speech hint ("NOUN",
     * "VERB", "ADJ", "ADV", or a Penn tag such as "NNP", "DT", "IN", "VBD"), or null when unknown.
     */
    fun phonemize(
        rawWord: String,
        tag: String?,
        ctx: WordContext,
    ): String? {
        var word = rawWord.replace(Char(0x2018), '\'').replace(Char(0x2019), '\'')
        word = Normalizer.normalize(word, Normalizer.Form.NFKC)
        val stress: Double? = if (word == word.lowercase()) null else CAP_STRESSES[if (word == word.uppercase()) 1 else 0]
        return getWord(word, tag, stress, ctx)?.first
    }

    // ---- stress ----

    private fun applyStress(
        ps: String?,
        stress: Double?,
    ): String? {
        if (ps == null || stress == null) return ps

        fun has(c: Char) = ps.indexOf(c) >= 0
        val noStress = STRESSES.none { has(it) }
        val noVowel = ps.none { it in VOWELS }
        return when {
            stress < -1 -> {
                ps.replace(PRIMARY.toString(), "").replace(SECONDARY.toString(), "")
            }

            stress == -1.0 || ((stress == 0.0 || stress == -0.5) && has(PRIMARY)) -> {
                ps.replace(SECONDARY.toString(), "").replace(PRIMARY, SECONDARY)
            }

            (stress == 0.0 || stress == 0.5 || stress == 1.0) && noStress -> {
                if (noVowel) ps else restress(SECONDARY + ps)
            }

            stress >= 1 && !has(PRIMARY) && has(SECONDARY) -> {
                ps.replace(SECONDARY, PRIMARY)
            }

            stress > 1 && noStress -> {
                if (noVowel) ps else restress(PRIMARY + ps)
            }

            else -> {
                ps
            }
        }
    }

    /** Moves each stress mark to just before the first vowel after it. */
    private fun restress(ps: String): String {
        val items = ArrayList<Pair<Double, Char>>(ps.length)
        for ((i, p) in ps.withIndex()) {
            if (p in STRESSES) {
                val vowel = (i until ps.length).first { ps[it] in VOWELS }
                items += (vowel - 0.5) to p
            } else {
                items += i.toDouble() to p
            }
        }
        return items.sortedWith(compareBy({ it.first }, { it.second })).joinToString("") { it.second.toString() }
    }

    // ---- lookup ----

    /** A word spelled out letter by letter, such as an acronym ("NASA" as the letters N-A-S-A), with the stress on the last letter. */
    fun spellOut(word: String): String? {
        val parts = word.filter { it.isLetter() }.map { golds[it.uppercaseChar().toString()] as? String }
        if (parts.any { it == null }) return null
        val ps = applyStress(parts.joinToString(""), 0.0) ?: return null
        val at = ps.lastIndexOf(SECONDARY)
        return if (at < 0) ps else ps.substring(0, at) + PRIMARY + ps.substring(at + 1)
    }

    private fun specialCase(
        word: String,
        tag: String?,
        stress: Double?,
        ctx: WordContext,
    ): String? =
        when {
            word in SYMBOLS -> {
                lookup(SYMBOLS.getValue(word), null, null, null)?.first
            }

            '.' in word.trim('.') && word.replace(".", "").all { it.isLetter() } && word.split('.').maxOf { it.length } < 3 -> {
                spellOut(word)
            }

            word == "a" || word == "A" -> {
                if (tag == "DT") "ɐ" else "ˈA"
            }

            word == "am" || word == "Am" || word == "AM" -> {
                when {
                    tag?.startsWith("NN") == true -> spellOut(word)
                    ctx.futureVowel == null || word != "am" || (stress != null && stress > 0) -> golds["am"] as? String
                    else -> "ɐm"
                }
            }

            word == "an" || word == "An" || word == "AN" -> {
                if (word == "AN" && tag?.startsWith("NN") == true) spellOut(word) else "ɐn"
            }

            word == "I" && tag == "PRP" -> {
                "ˌI"
            }

            (word == "by" || word == "By" || word == "BY") && parentTag(tag) == "ADV" -> {
                "bˈI"
            }

            word == "to" || word == "To" || (word == "TO" && (tag == "TO" || tag == "IN")) -> {
                when (ctx.futureVowel) {
                    null -> golds["to"] as? String
                    false -> "tə"
                    true -> "tʊ"
                }
            }

            word == "in" || word == "In" || (word == "IN" && tag != "NNP") -> {
                (if (ctx.futureVowel == null || tag != "IN") PRIMARY.toString() else "") + "ɪn"
            }

            word == "the" || word == "The" || (word == "THE" && tag == "DT") -> {
                if (ctx.futureVowel == true) "ði" else "ðə"
            }

            tag == "IN" && VS.matches(word) -> {
                lookup("versus", null, null, null)?.first
            }

            word == "used" || word == "Used" || word == "USED" -> {
                val used = golds["used"] as? Map<*, *>
                if ((tag == "VBD" || tag == "JJ") && ctx.futureTo) used?.get("VBD") as? String else used?.get("DEFAULT") as? String
            }

            else -> {
                null
            }
        }

    private fun parentTag(tag: String?): String? =
        when {
            tag == null -> null
            tag.startsWith("VB") -> "VERB"
            tag.startsWith("NN") -> "NOUN"
            tag.startsWith("ADV") || tag.startsWith("RB") -> "ADV"
            tag.startsWith("ADJ") || tag.startsWith("JJ") -> "ADJ"
            else -> tag
        }

    private fun isKnown(
        word: String,
        tag: String?,
    ): Boolean =
        when {
            word in golds || word in SYMBOLS || word in silvers -> true
            !word.all { it.isLetter() } || !word.all { it in LEXICON_CHARS } -> false
            word.length == 1 -> true
            word == word.uppercase() && word.lowercase() in golds -> true
            else -> word.substring(1) == word.substring(1).uppercase()
        }

    private fun lookup(
        rawWord: String,
        rawTag: String?,
        stress: Double?,
        ctx: WordContext?,
    ): Pair<String, Int>? {
        var word = rawWord
        var tag = rawTag
        var isNnp: Boolean? = null
        if (word == word.uppercase() && word !in golds) {
            word = word.lowercase()
            isNnp = tag == "NNP"
        }
        var entry: Any? = golds[word]
        var rating = 4
        if (entry == null && isNnp != true) {
            entry = silvers[word]
            rating = 3
        }
        var ps: String? =
            when (entry) {
                is String -> {
                    entry
                }

                is Map<*, *> -> {
                    if (ctx != null && ctx.futureVowel == null && entry.containsKey("None")) {
                        tag = "None"
                    } else if (tag == null || !entry.containsKey(tag)) {
                        tag = parentTag(tag)
                    }
                    (if (tag != null && entry.containsKey(tag)) entry[tag] else entry["DEFAULT"]) as? String
                }

                else -> {
                    null
                }
            }
        if (ps == null || (isNnp == true && PRIMARY !in ps)) {
            val spelled = spellOut(word)
            if (spelled != null) return spelled to 3
        }
        ps = applyStress(ps, stress)
        return ps?.let { it to rating }
    }

    // ---- stemming ----

    private fun suffixS(stem: String?): String? =
        when {
            stem.isNullOrEmpty() -> null
            stem.last() in "ptkfθ" -> stem + "s"
            stem.last() in "szʃʒʧʤ" -> stem + (if (british) "ɪ" else "ᵻ") + "z"
            else -> stem + "z"
        }

    private fun stemS(
        word: String,
        tag: String?,
        stress: Double?,
        ctx: WordContext,
    ): String? {
        if (word.length < 3 || !word.endsWith("s")) return null
        val stem =
            when {
                !word.endsWith("ss") && isKnown(word.dropLast(1), tag) -> word.dropLast(1)

                (
                    word.endsWith(
                        "'s",
                    ) || (
                        word.length > 4 &&
                            word.endsWith(
                                "es",
                            ) && !word.endsWith("ies")
                    )
                ) && isKnown(word.dropLast(2), tag) -> word.dropLast(2)

                word.length > 4 && word.endsWith("ies") && isKnown(word.dropLast(3) + "y", tag) -> word.dropLast(3) + "y"

                else -> return null
            }
        return suffixS(lookup(stem, tag, stress, ctx)?.first)
    }

    private fun suffixEd(stem: String?): String? =
        when {
            stem.isNullOrEmpty() -> null
            stem.last() in "pkfθʃsʧ" -> stem + "t"
            stem.last() == 'd' -> stem + (if (british) "ɪ" else "ᵻ") + "d"
            stem.last() != 't' -> stem + "d"
            british || stem.length < 2 -> stem + "ɪd"
            stem[stem.length - 2] in US_TAUS -> stem.dropLast(1) + "ɾᵻd"
            else -> stem + "ᵻd"
        }

    private fun stemEd(
        word: String,
        tag: String?,
        stress: Double?,
        ctx: WordContext,
    ): String? {
        if (word.length < 4 || !word.endsWith("d")) return null
        val stem =
            when {
                !word.endsWith("dd") && isKnown(word.dropLast(1), tag) -> word.dropLast(1)
                word.length > 4 && word.endsWith("ed") && !word.endsWith("eed") && isKnown(word.dropLast(2), tag) -> word.dropLast(2)
                else -> return null
            }
        return suffixEd(lookup(stem, tag, stress, ctx)?.first)
    }

    private fun suffixIng(stem: String?): String? =
        when {
            stem.isNullOrEmpty() -> null
            british && stem.last() in "əː" -> null
            !british && stem.length > 1 && stem.last() == 't' && stem[stem.length - 2] in US_TAUS -> stem.dropLast(1) + "ɾɪŋ"
            else -> stem + "ɪŋ"
        }

    private fun stemIng(
        word: String,
        tag: String?,
        stress: Double?,
        ctx: WordContext,
    ): String? {
        if (word.length < 5 || !word.endsWith("ing")) return null
        val stem =
            when {
                word.length > 5 && isKnown(word.dropLast(3), tag) -> word.dropLast(3)
                isKnown(word.dropLast(3) + "e", tag) -> word.dropLast(3) + "e"
                word.length > 5 && DOUBLED_ING.containsMatchIn(word) && isKnown(word.dropLast(4), tag) -> word.dropLast(4)
                else -> return null
            }
        return suffixIng(lookup(stem, tag, stress, ctx)?.first)
    }

    private fun getWord(
        wordIn: String,
        tag: String?,
        stress: Double?,
        ctx: WordContext,
    ): Pair<String, Int>? {
        var word = wordIn
        specialCase(word, tag, stress, ctx)?.let { return it to 4 }
        val wl = word.lowercase()
        if (word.length > 1 && word.replace("'", "").let { it.isNotEmpty() && it.all { c -> c.isLetter() } } && word != wl &&
            (tag != "NNP" || word.length > 7) && word !in golds && word !in silvers &&
            (word == word.uppercase() || word.substring(1) == word.substring(1).lowercase()) &&
            (
                wl in golds || wl in silvers || stemS(wl, tag, stress, ctx) != null || stemEd(wl, tag, stress, ctx) != null ||
                    stemIng(wl, tag, stress, ctx) != null
            )
        ) {
            word = wl
        }
        if (isKnown(word, tag)) return lookup(word, tag, stress, ctx)
        if (word.endsWith("s'") && isKnown(word.dropLast(2) + "'s", tag)) return lookup(word.dropLast(2) + "'s", tag, stress, ctx)
        if (word.endsWith("'") && isKnown(word.dropLast(1), tag)) return lookup(word.dropLast(1), tag, stress, ctx)
        stemS(word, tag, stress, ctx)?.let { return it to 3 }
        stemEd(word, tag, stress, ctx)?.let { return it to 3 }
        stemIng(word, tag, if (stress == null) 0.5 else stress, ctx)?.let { return it to 3 }
        return null
    }

    companion object {
        const val PRIMARY = 'ˈ'
        const val SECONDARY = 'ˌ'
        private const val STRESSES = "ˌˈ"

        /** Stress given to Capitalized and to ALL CAPS words. */
        private val CAP_STRESSES = doubleArrayOf(0.5, 2.0)

        val VOWELS: Set<Char> = "AIOQWYaiuæɑɒɔəɛɜɪʊʌᵻ".toSet()
        val CONSONANTS: Set<Char> = "bdfhjklmnpstvwzðŋɡɹɾʃʒʤʧθ".toSet()
        private val US_TAUS: Set<Char> = "AIOWYiuæɑəɛɪɹʊʌ".toSet()
        private val LEXICON_CHARS: Set<Char> = setOf('\'', '-') + ('A'..'Z') + ('a'..'z')
        private val SYMBOLS = mapOf("%" to "percent", "&" to "and", "+" to "plus", "@" to "at")
        private val VS = Regex("(?i)vs\\.?")
        private val DOUBLED_ING = Regex("([bcdgklmnprstvxz])\\1ing$|cking$")
    }
}
