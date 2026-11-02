package io.github.tjnewbry.audiopress.bake

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tjnewbry.audiopress.bake.kokoro.GithubKokoroCatalog
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroAccent
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroModelStore
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroQuality
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroSettings
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroVoices
import io.github.tjnewbry.audiopress.bake.kokoro.kokoroService
import io.github.tjnewbry.audiopress.bake.kokoro.text.BartG2pFallback
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real Kokoro Lite model, misaki's dictionaries, and the unknown-word model on the device. It needs the
 * downloaded files (the Lite model, `voices-v1.0.bin`, the four dictionaries, and `misaki-en_us-g2p.onnx`, exactly as
 * [GithubKokoroCatalog] names them) in the test app's `files/kokoro-e2e/` folder, and is skipped without them. Copy them
 * in with `adb push` and `run-as io.github.tjnewbry.audiopress.bake.test cp`. Run it on an emulator or a test device.
 * The speech is left in `files/kokoro-e2e-out/` to be listened to.
 */
@RunWith(AndroidJUnit4::class)
class KokoroEndToEndTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "kokoro-e2e")
    private val out = File(context.filesDir, "kokoro-e2e-out").apply { mkdirs() }

    @Test
    fun theUnknownWordModelSaysWordsTheDictionariesLack() {
        val model = File(root, GithubKokoroCatalog.BART_US)
        assumeTrue("copy ${GithubKokoroCatalog.BART_US} to ${root.path}", model.isFile)
        BartG2pFallback(mapOf(KokoroAccent.AMERICAN to model)).use { bart ->
            // The results of the original PyTorch model, from the research.
            val expected =
                mapOf(
                    "Okonkwo" to "ˌOkˈɑŋkwO",
                    "glimmerfang" to "ɡlˈɪməɹfˌæŋ",
                    "Worcestershire" to "wˈʊstəɹʃˌɪɹ",
                    "Featherstonehaugh" to "fˈɛðəɹstˌOnhɔf",
                    "zibberish" to "zˈɪbəɹɪʃ",
                )
            for ((word, phonemes) in expected) assertEquals(phonemes, bart.phonemize(word, KokoroAccent.AMERICAN), word)
            val long = bart.phonemize("antidisestablishmentarianism", KokoroAccent.AMERICAN)!!
            assertTrue(long.length > 24, "a long word is not cut off at 20 steps: $long")
        }
    }

    @Test
    fun aSentenceIsSpokenAndBakedWithRealText() {
        val store = KokoroModelStore(root, GithubKokoroCatalog)
        assumeTrue("copy the Lite files to ${root.path}", store.isDownloaded(KokoroQuality.LITE))
        val service = kokoroService(root)
        val bella = KokoroVoices.byId("af_bella")!!
        val texts =
            listOf(
                "Hello! My name is Bella, and you're using Audio Press.",
                "In 1984 the price rose from \$4.99 to \$1,250.50, about 3.5% a year, said Dr. Okonkwo of St. Crispin's.",
            )
        service.synthesizer(bella, KokoroQuality.LITE).use { synthesizer ->
            val items = texts.mapIndexed { i, text -> BakeItem(text, i, 0, text.length, 1) }
            val book =
                BookBaker(synthesizer).bake(
                    items,
                    File(out, "bake").apply {
                        deleteRecursively()
                    },
                    KokoroSettings.key(bella, KokoroQuality.LITE),
                )

            assertTrue(book.isComplete)
            for ((i, baked) in book.utterances.withIndex()) {
                val seconds = baked.durationMs / 1000.0
                assertTrue(seconds in 2.0..30.0, "utterance $i lasts $seconds s")
                File(out, "utterance-$i.wav").writeBytes(book.file(baked).readBytes())
            }
            assertTrue(book.utterances[1].durationMs > book.utterances[0].durationMs, "the longer text is longer")
        }
    }
}
