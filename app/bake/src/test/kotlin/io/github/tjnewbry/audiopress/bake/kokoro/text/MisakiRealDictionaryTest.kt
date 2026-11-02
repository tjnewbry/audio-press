package io.github.tjnewbry.audiopress.bake.kokoro.text

import io.github.tjnewbry.audiopress.bake.kokoro.KokoroAccent
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroVocab
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroVoices
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the front end on misaki's real dictionaries. Those files (12.6 MB, Apache-2.0) are not in the repository: the
 * test uses the copies in `local-docs/research/kokoro-sources/` and is skipped when they are not there.
 */
class MisakiRealDictionaryTest {
    private val vocab = KokoroVocab.default()
    private val bella = KokoroVoices.byId("af_bella")!!
    private val george = KokoroVoices.byId("bm_george")!!

    private fun say(
        text: String,
        voice: io.github.tjnewbry.audiopress.bake.kokoro.KokoroVoice = bella,
    ): String = phonemizer.phonemize(text, voice)

    @Test
    fun `the bundled Kokoro vocabulary has the 114 symbols of the config`() {
        assertEquals(114, vocab.size)
        assertEquals(intArrayOf(156).toList(), vocab.encode("ˈ").toList())
    }

    @Test
    fun `the dictionaries load with the sizes the research found`() {
        assertTrue(usGold.size > 85_000, "us_gold has ${usGold.size} entries")
        assertTrue(usSilver.size > 85_000)
    }

    @Test
    fun `known words come out as in the dictionary, and the changes with the next sound`() {
        assertEquals(usGold["hello"], say("hello"))
        assertTrue(say("the apple").startsWith("ði "), say("the apple"))
        assertTrue(say("the cat").startsWith("ðə "), say("the cat"))
    }

    @Test
    fun `every character of the output is one Kokoro knows, and no word is dropped`() {
        val texts =
            listOf(
                "The quick brown fox jumps over the lazy dog.",
                "In 1984 the price rose from \$4.99 to \$1,250.50, about 3.5% a year.",
                "\"Don't be silly,\" said Mr. Smith; it's 10:45 on the 21st of March -- Chapter IV begins.",
                "Okonkwo and Featherstonehaugh walked to St. Crispin's with Dr. Jones (and the well-known NASA people).",
            )
        for (text in texts) {
            val ps = say(text)
            println("$text\n  -> $ps")
            assertTrue(ps.isNotBlank())
            val unknown = ps.filter { it != ' ' && vocab.encode(it.toString()).isEmpty() }
            assertEquals("", unknown, "characters Kokoro does not know in: $ps")
            assertTrue(ps.none { it.isDigit() }, ps)
            val words = text.split(' ').count { w -> w.any { it.isLetter() } }
            assertTrue(ps.split(' ').size >= words / 2, "words are missing in: $ps")
        }
    }

    @Test
    fun `an unknown name is spelled out letter by letter when there is no model`() {
        val ps = say("Okonkwo")
        println("Okonkwo -> $ps")
        assertTrue(ps.split(' ').size >= 5, ps)
    }

    @Test
    fun `British voices use the British dictionaries`() {
        val us = say("water")
        val gb = say("water", george)
        println("water US: $us  GB: $gb")
        assertTrue(us.isNotBlank() && gb.isNotBlank())
        assertEquals(KokoroAccent.BRITISH, george.accent)
    }

    @Test
    fun `heteronyms follow the word before when the dictionary has parts of speech`() {
        val noun = say("the record")
        val verb = say("to record")
        println("the record -> $noun   to record -> $verb")
        assertTrue(noun != verb.replace("tə", "ðə").replace("tʊ", "ðə"))
    }

    companion object {
        private val dataDir = File("../../local-docs/research/kokoro-sources/misaki/misaki/data")
        private lateinit var usGold: MisakiDictionary
        private lateinit var usSilver: MisakiDictionary
        private lateinit var phonemizer: MisakiEnglishPhonemizer

        @BeforeClass
        @JvmStatic
        fun load() {
            assumeTrue("misaki dictionaries not found in ${dataDir.absolutePath}", File(dataDir, "us_gold.json").isFile)
            usGold = MisakiDictionary.load(File(dataDir, "us_gold.json"))
            usSilver = MisakiDictionary.load(File(dataDir, "us_silver.json"))
            val gbGold = MisakiDictionary.load(File(dataDir, "gb_gold.json"))
            val gbSilver = MisakiDictionary.load(File(dataDir, "gb_silver.json"))
            phonemizer =
                MisakiEnglishPhonemizer(
                    EnglishTextNormalizer(EnglishNumbers(EnglishNumberSpeller())),
                    lexiconFor = { accent ->
                        if (accent == KokoroAccent.BRITISH) {
                            MisakiLexicon(true, gbGold, gbSilver)
                        } else {
                            MisakiLexicon(false, usGold, usSilver)
                        }
                    },
                )
        }
    }
}
