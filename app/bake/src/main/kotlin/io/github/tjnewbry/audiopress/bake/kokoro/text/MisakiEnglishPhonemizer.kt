package io.github.tjnewbry.audiopress.bake.kokoro.text

import io.github.tjnewbry.audiopress.bake.kokoro.KokoroAccent
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroPhonemizer
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroVoice

/**
 * Turns English text into misaki phonemes for Kokoro, without espeak-ng or any GPL code. The steps:
 * 1. [EnglishTextNormalizer] writes numbers, years, money, times, Roman numerals, and abbreviations as words.
 * 2. [TextTokenizer] splits the text into words and punctuation.
 * 3. Each word is looked up by [MisakiLexicon] (misaki's gold and silver dictionaries, stress, and stemming). Words
 *    it cannot say go to [modelFallback] (a small trained model) if there is one, and then are spelled out letter by
 *    letter, so no word is ever dropped.
 * 4. Punctuation that Kokoro knows (`; : , . ! ? — … " ( )`) stays, as it makes the pauses.
 *
 * Words are processed from the end of the text to the start, because "the" and "to" sound different before a vowel
 * (misaki does the same). Misaki gets part-of-speech tags from spaCy; here [guessTag] makes a cheap guess from the
 * word before, which is enough for the common cases (capitalized names, "to read", "the record"). The accent of the
 * voice picks the US or GB dictionaries.
 */
class MisakiEnglishPhonemizer(
    private val normalizer: EnglishTextNormalizer,
    private val lexiconFor: (KokoroAccent) -> MisakiLexicon,
    private val modelFallback: UnknownWordFallback? = null,
) : KokoroPhonemizer {
    private class Item(
        val token: TextToken,
    ) {
        var phonemes: String = ""
        var space: String = ""
    }

    override fun phonemize(
        text: String,
        voice: KokoroVoice,
    ): String {
        val lexicon = lexiconFor(voice.accent)
        val fallback = FallbackChain(listOfNotNull(modelFallback, LetterSpellingFallback { lexicon.letter(it) }))
        val items = ArrayList<Item>()
        for (token in TextTokenizer.tokenize(normalizer.normalize(text))) {
            if (token.type == TokenType.SPACE) items.lastOrNull()?.space = " " else items += Item(token)
        }
        var ctx = WordContext()
        for (i in items.indices.reversed()) {
            val item = items[i]
            item.phonemes =
                when (item.token.type) {
                    TokenType.WORD -> word(items, i, ctx, lexicon, fallback, voice.accent)
                    TokenType.PUNCT -> punctuation(items, i, ctx, lexicon)
                    else -> ""
                }
            ctx = contextAfter(ctx, item.phonemes, item.token)
        }
        val out = StringBuilder()
        for (item in items) {
            append(out, item.phonemes)
            if (item.space.isNotEmpty() && out.isNotEmpty() && out.last() != ' ') out.append(' ')
        }
        return out
            .toString()
            .trim()
            .replace('ɾ', 'T')
            .replace('ʔ', 't')
    }

    private fun append(
        out: StringBuilder,
        phonemes: String,
    ) {
        if (phonemes.startsWith('—') && out.isNotEmpty() && out.last() == '—') return
        out.append(phonemes)
    }

    // ---- words ----

    private fun word(
        items: List<Item>,
        i: Int,
        ctx: WordContext,
        lexicon: MisakiLexicon,
        fallback: UnknownWordFallback,
        accent: KokoroAccent,
    ): String {
        val w = items[i].token.text
        val tag = guessTag(items, i)
        lexicon.phonemize(w, tag, ctx)?.let { return it }
        val parts = w.split('-', Char(0x2010), '_').filter { it.isNotEmpty() }
        if (parts.size > 1) return compound(parts, lexicon, fallback, accent, ctx)
        return fallback.phonemize(w, accent) ?: ""
    }

    /** A hyphenated word whose whole is not in the dictionary: its parts said one after another, without a pause. */
    private fun compound(
        parts: List<String>,
        lexicon: MisakiLexicon,
        fallback: UnknownWordFallback,
        accent: KokoroAccent,
        ctx: WordContext,
    ): String {
        val phonemes = parts.map { lexicon.phonemize(it, null, ctx) ?: fallback.phonemize(it, accent) ?: "" }.toMutableList()
        demoteStresses(parts, phonemes)
        return phonemes.joinToString("")
    }

    /** Misaki's rule for words made of several parts: only about half of the parts keep their main stress. */
    private fun demoteStresses(
        parts: List<String>,
        phonemes: MutableList<String>,
    ) {
        val candidates =
            phonemes.indices
                .filter {
                    phonemes[it].isNotEmpty()
                }.map { Triple(phonemes[it].contains(MisakiLexicon.PRIMARY), stressWeight(phonemes[it]), it) }
        if (candidates.size == 2 && parts[candidates[0].third].length == 1) {
            val j = candidates[1].third
            phonemes[j] = demote(phonemes[j])
            return
        }
        if (candidates.size < 2 || candidates.count { it.first } <= (candidates.size + 1) / 2) return
        candidates
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            .take(candidates.size / 2)
            .forEach { phonemes[it.third] = demote(phonemes[it.third]) }
    }

    private fun stressWeight(ps: String) = ps.sumOf { if (it in DIPHTHONGS) 2 else 1 }

    /** Misaki's `apply_stress(ps, -0.5)`: a main stress becomes a secondary one. */
    private fun demote(ps: String): String =
        if (ps.contains(
                MisakiLexicon.PRIMARY,
            )
        ) {
            ps.replace(MisakiLexicon.SECONDARY.toString(), "").replace(MisakiLexicon.PRIMARY, MisakiLexicon.SECONDARY)
        } else {
            ps
        }

    // ---- tags ----

    /**
     * A cheap stand-in for spaCy's part-of-speech tag: "NNP" for a capitalized word inside a sentence, "PRP" for
     * "I", "DT" for "a", "VBD" for "used" before "to", and NOUN or VERB from the word before for words whose
     * pronunciation depends on it (a dictionary entry without that key falls back to `DEFAULT`).
     */
    private fun guessTag(
        items: List<Item>,
        i: Int,
    ): String? {
        val w = items[i].token.text
        val previous = previousWord(items, i)
        val next =
            items
                .getOrNull(i + 1)
                ?.token
                ?.takeIf { it.type == TokenType.WORD }
                ?.text
                ?.lowercase()
        return when {
            w == "I" -> "PRP"
            w == "a" -> "DT"
            w.lowercase() == "used" && next == "to" -> "VBD"
            w[0].isUpperCase() && !w.all { it.isUpperCase() } && !startsSentence(items, i) -> "NNP"
            previous in VERB_BEFORE -> "VERB"
            previous in NOUN_BEFORE -> "NOUN"
            else -> null
        }
    }

    private fun previousWord(
        items: List<Item>,
        i: Int,
    ): String? =
        items
            .getOrNull(i - 1)
            ?.takeIf { it.token.type == TokenType.WORD }
            ?.token
            ?.text
            ?.lowercase()

    private fun startsSentence(
        items: List<Item>,
        i: Int,
    ): Boolean {
        val before = items.getOrNull(i - 1) ?: return true
        return before.token.type == TokenType.PUNCT && before.token.text in SENTENCE_ENDS
    }

    // ---- punctuation ----

    private fun punctuation(
        items: List<Item>,
        i: Int,
        ctx: WordContext,
        lexicon: MisakiLexicon,
    ): String {
        val c = items[i].token.text
        return when {
            c.single() in KEPT_PUNCT -> c
            c == "\"" -> if (i == 0 || items[i - 1].space.isNotEmpty()) OPEN_QUOTE else CLOSE_QUOTE
            c == OPEN_QUOTE || c == CLOSE_QUOTE -> c
            c == "(" || c == "[" || c == "{" -> "("
            c == ")" || c == "]" || c == "}" -> ")"
            c == "-" || c == "–" || c == "‒" || c == "―" -> "—"
            c in SYMBOL_WORDS -> lexicon.phonemize(c, null, ctx) ?: ""
            else -> ""
        }
    }

    /** What the sound after a word is like: misaki's `token_context`. */
    private fun contextAfter(
        ctx: WordContext,
        phonemes: String,
        token: TextToken,
    ): WordContext {
        var vowel = ctx.futureVowel
        for (c in phonemes) {
            if (c in MisakiLexicon.VOWELS || c in MisakiLexicon.CONSONANTS || c in NON_QUOTE_PUNCT) {
                vowel = if (c in NON_QUOTE_PUNCT) null else c in MisakiLexicon.VOWELS
                break
            }
        }
        return WordContext(vowel, token.text == "to" || token.text == "To")
    }

    private companion object {
        const val OPEN_QUOTE = "“"
        const val CLOSE_QUOTE = "”"
        const val KEPT_PUNCT = ";:,.!?—…"
        const val NON_QUOTE_PUNCT = ";:,.!?—…"
        val SENTENCE_ENDS = setOf(".", "!", "?", "…", "“", "\"", "—")
        val SYMBOL_WORDS = setOf("%", "&", "+", "@")
        val DIPHTHONGS = "AIOQWYʤʧ".toSet()
        val VERB_BEFORE =
            setOf(
                "to",
                "will",
                "would",
                "can",
                "could",
                "should",
                "may",
                "might",
                "must",
                "shall",
                "did",
                "does",
                "do",
                "not",
                "i",
                "you",
                "we",
                "they",
                "he",
                "she",
                "it",
                "who",
            )
        val NOUN_BEFORE =
            setOf(
                "the",
                "a",
                "an",
                "this",
                "that",
                "these",
                "those",
                "his",
                "her",
                "my",
                "your",
                "our",
                "their",
                "its",
                "some",
                "any",
                "no",
                "every",
                "each",
                "another",
                "of",
                "in",
                "on",
                "for",
                "with",
            )
    }
}
