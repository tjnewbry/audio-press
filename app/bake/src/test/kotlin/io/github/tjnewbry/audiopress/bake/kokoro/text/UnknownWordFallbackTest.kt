package io.github.tjnewbry.audiopress.bake.kokoro.text

import io.github.tjnewbry.audiopress.bake.kokoro.KokoroAccent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnknownWordFallbackTest {
    private val letters = mapOf('O' to "ˈO", 'K' to "kˈA", 'N' to "ˈɛn")

    @Test
    fun `a word is spelled out letter by letter with the dictionary's own letter sounds`() {
        val fallback = LetterSpellingFallback { letters[it] }

        assertEquals("ˈO kˈA ˈO ˈɛn kˈA", fallback.phonemize("Okonk", KokoroAccent.AMERICAN)?.let { it.replace("ˈɛn kˈA", "ˈɛn kˈA") })
        assertEquals("ˈO ˈO", fallback.phonemize("o'o", KokoroAccent.BRITISH), "punctuation inside is skipped")
    }

    @Test
    fun `a word with no letters the dictionary knows gives null`() {
        val fallback = LetterSpellingFallback { letters[it] }

        assertNull(fallback.phonemize("xyz", KokoroAccent.AMERICAN))
        assertNull(fallback.phonemize("123", KokoroAccent.AMERICAN))
    }

    @Test
    fun `a chain gives the first answer, and nothing is dropped while the last one can spell`() {
        val model =
            object : UnknownWordFallback {
                override fun phonemize(
                    word: String,
                    accent: KokoroAccent,
                ) = if (word == "Siobhan") "ʃəvˈɔn" else null
            }
        val chain = FallbackChain(listOf(model, LetterSpellingFallback { letters[it] }))

        assertEquals("ʃəvˈɔn", chain.phonemize("Siobhan", KokoroAccent.AMERICAN))
        assertEquals("ˈO ˈO", chain.phonemize("OO", KokoroAccent.AMERICAN))
        assertNull(chain.phonemize("zzz", KokoroAccent.AMERICAN))
    }
}
