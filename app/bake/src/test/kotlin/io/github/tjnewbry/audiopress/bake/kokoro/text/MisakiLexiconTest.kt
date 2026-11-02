package io.github.tjnewbry.audiopress.bake.kokoro.text

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The ported lexicon rules on a tiny made-up dictionary, so they are checked without the real (large) misaki files. */
class MisakiLexiconTest {
    private val gold: Map<String, Any?> =
        mapOf(
            "cat" to "kˈæt",
            "dog" to "dˈɔɡ",
            "Paris" to "pˈæɹəs",
            "walk" to "wˈɔk",
            "play" to "plˈA",
            "stop" to "stˈɑp",
            "make" to "mˈAk",
            "judge" to "ʤˈʌʤ",
            "to" to "tˈu",
            "am" to "ˈæm",
            "used" to mapOf("DEFAULT" to "jˈuzd", "VBD" to "jˈust"),
            "record" to mapOf("DEFAULT" to "ɹᵻkˈɔɹd", "NOUN" to "ɹˈɛkɚd", "VERB" to "ɹᵻkˈɔɹd"),
            "quiet" to "kwIət",
            "A" to "ˈA",
            "B" to "bˈi",
            "C" to "sˈi",
            "N" to "ˈɛn",
            "S" to "ˈɛs",
            "versus" to "vˈɜɹsəs",
        )
    private val silver: Map<String, Any?> = mapOf("rare" to "ɹˈɛɹ")
    private val us = MisakiLexicon(false, MisakiDictionary(gold), MisakiDictionary(silver))
    private val gb = MisakiLexicon(true, MisakiDictionary(gold), MisakiDictionary(silver))

    private fun say(
        word: String,
        tag: String? = null,
        lexicon: MisakiLexicon = us,
        context: WordContext = WordContext(),
    ) = lexicon.phonemize(word, tag, context)

    @Test
    fun `a word in the gold dictionary is found, and silver is the second choice`() {
        assertEquals("kˈæt", say("cat"))
        assertEquals("ɹˈɛɹ", say("rare"))
        assertNull(say("zzyzx"))
    }

    @Test
    fun `a capitalized word finds the lower-case entry and the reverse, as in misaki's grown dictionary`() {
        assertEquals("pˈæɹəs", say("paris"), "lower case finds Paris")
        assertEquals("pˈæɹəs", say("Paris"))
        assertEquals("dˈɔɡ", say("Dog"), "capitalized finds dog")
        assertEquals("dˈɔɡ", say("dog"))
    }

    @Test
    fun `shouting and capitals keep a stressed word as it is`() {
        assertEquals("kˈæt", say("Cat"))
        assertEquals("kˈæt", say("CAT"))
    }

    @Test
    fun `capitals add a stress mark to a word that has none`() {
        assertEquals("kwIət", say("quiet"))
        assertEquals("kwˌIət", say("Quiet"), "capitalized: a secondary stress, moved to the first vowel")
        assertEquals("kwˈIət", say("QUIET"), "all capitals: a main stress")
    }

    @Test
    fun `plurals, past tenses and -ing forms are made from the stem`() {
        assertEquals("kˈæts", say("cats"))
        assertEquals("wˈɔkt", say("walked"))
        assertEquals("plˈAd", say("played"))
        assertEquals("plˈAz", say("plays"))
        assertEquals("wˈɔkɪŋ", say("walking"))
        assertEquals("stˈɑpɪŋ", say("stopping"), "a doubled consonant before -ing")
        assertEquals("mˈAkɪŋ", say("making"), "the e of the stem is restored")
    }

    @Test
    fun `the plural ending depends on the last sound of the stem and on the accent`() {
        assertEquals("ʤˈʌʤᵻz", say("judges"))
        assertEquals("ʤˈʌʤɪz", say("judges", lexicon = gb))
    }

    @Test
    fun `a heteronym uses the part of speech when it is given, and the default otherwise`() {
        assertEquals("ɹᵻkˈɔɹd", say("record"))
        assertEquals("ɹˈɛkɚd", say("record", "NOUN"))
        assertEquals("ɹᵻkˈɔɹd", say("record", "VERB"))
        assertEquals("ɹˈɛkɚd", say("record", "NN"), "a Penn tag is mapped to its parent")
    }

    @Test
    fun `the, to and in sound different before a vowel`() {
        assertEquals("ðə", say("the"))
        assertEquals("ði", say("the", context = WordContext(futureVowel = true)))
        assertEquals("tˈu", say("to"))
        assertEquals("tə", say("to", context = WordContext(futureVowel = false)))
        assertEquals("tʊ", say("to", context = WordContext(futureVowel = true)))
        assertEquals("ˈɪn", say("in"), "before a pause it is stressed")
        assertEquals("ɪn", say("in", "IN", context = WordContext(futureVowel = false)))
    }

    @Test
    fun `a is the weak vowel as an article and the letter name otherwise`() {
        assertEquals("ɐ", say("a", "DT"))
        assertEquals("ˈA", say("a"))
        assertEquals("ɐn", say("an"))
        assertEquals("ˌI", say("I", "PRP"))
    }

    @Test
    fun `used before to takes its past form`() {
        assertEquals("jˈuzd", say("used"))
        assertEquals("jˈust", say("used", "VBD", context = WordContext(futureTo = true)))
        assertEquals("jˈuzd", say("used", "VBD"))
    }

    @Test
    fun `acronyms with periods are spelled out, and versus is its own word`() {
        assertEquals("bˌisˈi", say("B.C."))
        assertEquals("vˈɜɹsəs", say("vs.", "IN"))
    }

    @Test
    fun `spelling out puts the main stress on the last letter, and needs every letter in the dictionary`() {
        assertEquals("ˌɛnˌAˈɛs", us.spellOut("NAS"))
        assertEquals("ˌAbˌisˈi", us.spellOut("abc"))
        assertNull(us.spellOut("xyz"), "there is no X in this dictionary")
        assertEquals("bˈi", us.letter('b'))
        assertNull(us.letter('x'))
    }

    @Test
    fun `an unknown word is left to the caller`() {
        assertNull(say("Okonkwo"))
    }
}
