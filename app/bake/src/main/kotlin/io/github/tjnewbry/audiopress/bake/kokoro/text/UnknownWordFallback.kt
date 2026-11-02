package io.github.tjnewbry.audiopress.bake.kokoro.text

import io.github.tjnewbry.audiopress.bake.kokoro.KokoroAccent

/**
 * What to do with a word the dictionaries do not have: a name from another language, an invented word, a typo.
 * Returning phonemes (in misaki's alphabet) makes the word be spoken; returning null means "no idea", and the
 * phonemizer then uses the next fallback. A word must never be dropped silently, which is what Kokoro's own pipeline
 * does with unknown words.
 *
 * The intended order is: a small trained model for English-like words (later), then [LetterSpellingFallback].
 */
interface UnknownWordFallback {
    /** The phonemes for [word], or null if this fallback cannot say it. */
    fun phonemize(
        word: String,
        accent: KokoroAccent,
    ): String?
}

/**
 * Spells a word out letter by letter ("Okonkwo" as "O K O N K W O"), using the pronunciation of each capital letter
 * that the dictionary itself holds ([letterPhonemes] gives the phonemes of one letter, or null). It is the safe
 * default: odd, but every word is heard.
 */
class LetterSpellingFallback(
    private val letterPhonemes: (Char) -> String?,
) : UnknownWordFallback {
    override fun phonemize(
        word: String,
        accent: KokoroAccent,
    ): String? {
        val spelled = word.filter { it.isLetter() }.mapNotNull { letterPhonemes(it.uppercaseChar()) }
        return spelled.joinToString(" ").ifEmpty { null }
    }
}

/** Tries each fallback in order and gives the first answer. */
class FallbackChain(
    private val fallbacks: List<UnknownWordFallback>,
) : UnknownWordFallback {
    override fun phonemize(
        word: String,
        accent: KokoroAccent,
    ): String? = fallbacks.firstNotNullOfOrNull { it.phonemize(word, accent) }
}
