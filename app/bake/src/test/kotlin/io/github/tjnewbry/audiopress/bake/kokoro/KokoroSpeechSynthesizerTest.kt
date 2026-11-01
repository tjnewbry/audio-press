package io.github.tjnewbry.audiopress.bake.kokoro

import io.github.tjnewbry.audiopress.bake.BakeItem
import io.github.tjnewbry.audiopress.bake.BakeOutOfMemoryException
import io.github.tjnewbry.audiopress.bake.BookBaker
import io.github.tjnewbry.audiopress.bake.SynthesisResult
import io.github.tjnewbry.audiopress.bake.Wav
import io.github.tjnewbry.audiopress.core.CancellationToken
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KokoroSpeechSynthesizerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val bella = KokoroVoices.byId("af_bella")!!

    /** A fake model: one second of audio per piece of text, and a record of what it was asked. */
    private class FakeEngine(
        val onSynthesize: (String) -> Unit = {},
    ) : KokoroEngine {
        val calls = mutableListOf<String>()
        var closed = false

        override fun synthesize(
            text: String,
            voice: KokoroVoice,
            speed: Float,
        ): FloatArray {
            calls += text
            onSynthesize(text)
            return FloatArray(24000) { 0.2f }
        }

        override fun close() {
            closed = true
        }
    }

    private class ThrowingEngine(
        val error: () -> Throwable,
    ) : KokoroEngine {
        override fun synthesize(
            text: String,
            voice: KokoroVoice,
            speed: Float,
        ): FloatArray = throw error()

        override fun close() = Unit
    }

    private fun sentences(count: Int) = List(count) { "This is sentence number $it of a long test paragraph." }.joinToString(" ")

    @Test
    fun `short text is one piece and one second of audio`() {
        val engine = FakeEngine()
        val file = temp.newFile("a.wav")

        val result = KokoroSpeechSynthesizer(engine, bella).synthesize("Hello there. How are you?", file, null)

        assertEquals(SynthesisResult.SUCCESS, result)
        assertEquals(listOf("Hello there. How are you?"), engine.calls)
        assertEquals(1000L, Wav.durationMs(file))
    }

    @Test
    fun `long text is cut at sentence ends into pieces under the model limit, with a pause between them`() {
        val engine = FakeEngine()
        val file = temp.newFile("b.wav")
        val text = sentences(20)

        KokoroSpeechSynthesizer(engine, bella, pauseMs = 120).synthesize(text, file, null)

        assertTrue(engine.calls.size > 1)
        assertTrue(engine.calls.all { it.length <= KokoroTextSplitter.MAX_CHARS })
        assertTrue(engine.calls.all { it.endsWith(".") }, "cut only at sentence ends")
        assertEquals(text, engine.calls.joinToString(" "))
        assertEquals(engine.calls.size * 1000L + (engine.calls.size - 1) * 120L, Wav.durationMs(file))
    }

    @Test
    fun `blank text gives a valid file with no audio`() {
        val engine = FakeEngine()
        val file = temp.newFile("c.wav")

        assertEquals(SynthesisResult.SUCCESS, KokoroSpeechSynthesizer(engine, bella).synthesize("   \n ", file, null))

        assertEquals(emptyList(), engine.calls)
        assertEquals(0L, Wav.durationMs(file))
    }

    @Test
    fun `cancelling stops before the next piece`() {
        val token = CancellationToken()
        val engine = FakeEngine { if (it.contains("number 1 ")) token.cancel() }
        val file = temp.newFile("d.wav")

        val result = KokoroSpeechSynthesizer(engine, bella).synthesize(sentences(20), file, token)

        assertEquals(SynthesisResult.CANCELLED, result)
        assertTrue(engine.calls.size < 20)
    }

    @Test
    fun `a token cancelled before the start runs nothing`() {
        val engine = FakeEngine()

        val result =
            KokoroSpeechSynthesizer(
                engine,
                bella,
            ).synthesize("Hello.", temp.newFile("e.wav"), CancellationToken().apply { cancel() })

        assertEquals(SynthesisResult.CANCELLED, result)
        assertEquals(emptyList(), engine.calls)
    }

    @Test
    fun `running out of memory and other failures are told apart`() {
        val oom = ThrowingEngine { OutOfMemoryError("model") }
        val broken = ThrowingEngine { IllegalStateException("onnx failed") }

        assertEquals(SynthesisResult.OUT_OF_MEMORY, KokoroSpeechSynthesizer(oom, bella).synthesize("Hello.", temp.newFile("f.wav"), null))
        assertEquals(SynthesisResult.FAILED, KokoroSpeechSynthesizer(broken, bella).synthesize("Hello.", temp.newFile("g.wav"), null))
    }

    @Test
    fun `closing the synthesizer closes the engine`() {
        val engine = FakeEngine()

        KokoroSpeechSynthesizer(engine, bella).close()

        assertTrue(engine.closed)
    }

    @Test
    fun `it bakes a book through BookBaker, and an out-of-memory model stops the bake`() {
        val items = List(3) { BakeItem(sentences(2), it, 0, 100, it + 1) }
        val folder = File(temp.root, "bake")

        val book =
            BookBaker(
                KokoroSpeechSynthesizer(FakeEngine(), bella),
            ).bake(items, folder, settingsKey = KokoroSettings.key(bella, KokoroQuality.LITE))

        assertTrue(book.isComplete)
        assertTrue(book.utterances.all { it.durationMs >= 1000 })
        val oom = ThrowingEngine { OutOfMemoryError() }
        assertFailsWith<BakeOutOfMemoryException> { BookBaker(KokoroSpeechSynthesizer(oom, bella)).bake(items, File(temp.root, "oom")) }
    }

    @Test
    fun `the splitter keeps abbreviations together and cuts a long sentence at a comma or a space`() {
        val text = "We asked Dr. Jones about it. " + "Then we waited, and waited some more, ".repeat(10) + "and left."

        val pieces = KokoroTextSplitter.split(text, maxChars = 80)

        assertTrue(pieces.first().startsWith("We asked Dr. Jones about it."))
        assertTrue(pieces.all { it.length <= 80 })
        assertEquals(text.trim().split(' ').filter { it.isNotEmpty() }, pieces.flatMap { it.split(' ') }.filter { it.isNotEmpty() })
        assertEquals(emptyList(), KokoroTextSplitter.split(""))
    }
}
