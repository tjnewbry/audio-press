package io.github.tjnewbry.audiopress.bake.kokoro

import org.junit.Test
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KokoroVocabTest {
    private val config =
        """
        {
          "istftnet": {"upsample_rates": [10, 6], "gen_istft_n_fft": 20},
          "dim_in": 64, "flag": true, "nothing": null, "ratio": 0.25,
          "vocab": {";": 1, "a": 38, " ": 16, "ɑ": 63, "\"": 11, "\\": 99, "ˈ": 156}
        }
        """.trimIndent()

    @Test
    fun `reads the vocab from a Kokoro config and encodes phonemes to ids`() {
        val vocab = KokoroVocab.fromConfigJson(config)

        assertEquals(7, vocab.size)
        assertContentEquals(intArrayOf(156, 38, 16, 63, 1), vocab.encode("ˈa ɑ;"))
        assertContentEquals(intArrayOf(11, 99), vocab.encode("\"\\"))
    }

    @Test
    fun `characters without an id are dropped, as in Kokoros own code`() {
        val vocab = KokoroVocab.fromConfigJson(config)

        assertContentEquals(intArrayOf(38, 38), vocab.encode("aéz\u0000a😀"))
        assertContentEquals(intArrayOf(), vocab.encode(""))
    }

    @Test
    fun `a config without a usable vocab is an error`() {
        assertFailsWith<IOException> { KokoroVocab.fromConfigJson("[1, 2]") }
        assertFailsWith<IOException> { KokoroVocab.fromConfigJson("""{"other": {}}""") }
        assertFailsWith<IOException> { KokoroVocab.fromConfigJson("""{"vocab": {"ab": 1}}""") }
        assertFailsWith<IOException> { KokoroVocab.fromConfigJson("""{"vocab": {"a": 1""") }
    }

    @Test
    fun `the engine checks audio for NaN, infinity, and silence where speech is expected`() {
        assertEquals(true, OnnxKokoroEngine.isValid(floatArrayOf(0.1f, -0.2f, 0f), tokens = 30))
        assertEquals(false, OnnxKokoroEngine.isValid(floatArrayOf(0.1f, Float.NaN), tokens = 30))
        assertEquals(false, OnnxKokoroEngine.isValid(floatArrayOf(Float.POSITIVE_INFINITY), tokens = 30))
        assertEquals(false, OnnxKokoroEngine.isValid(FloatArray(100), tokens = 30), "a phrase that came out silent")
        assertEquals(true, OnnxKokoroEngine.isValid(FloatArray(100), tokens = 2), "a pause is allowed to be silent")
    }
}
