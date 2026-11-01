package io.github.tjnewbry.audiopress.bake

import io.github.tjnewbry.audiopress.core.CancellationToken
import io.github.tjnewbry.audiopress.core.SegmentKind
import io.github.tjnewbry.audiopress.core.Utterance
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BookBakerTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A fake voice: writes a WAV as long as 10 ms per character, and can be told to fail or cancel. */
    private class FakeSynthesizer : SpeechSynthesizer {
        val spoken = mutableListOf<String>()
        var failOn: String? = null
        var failuresLeft = Int.MAX_VALUE
        var silentOn: String? = null
        var onSynthesize: (String) -> Unit = {}

        override fun synthesize(
            text: String,
            target: File,
            cancellation: CancellationToken?,
        ): SynthesisResult {
            spoken += text
            onSynthesize(text)
            if (cancellation?.isCancelled == true) return SynthesisResult.CANCELLED
            if (text == failOn && failuresLeft > 0) {
                failuresLeft--
                return SynthesisResult.FAILED
            }
            Wav.writeSilence(target, if (text == silentOn) 0 else text.length * 10L, sampleRate = 1000)
            return SynthesisResult.SUCCESS
        }
    }

    private fun utterances(count: Int = 5): List<Utterance> =
        List(count) { i ->
            val text = "Utterance number $i " + "x".repeat(i * 10)
            Utterance(
                text,
                segmentIndex = i / 2,
                kind = SegmentKind.PARAGRAPH,
                start = (i % 2) * 100,
                end = (i % 2) * 100 + text.length,
                page =
                    i + 1,
                words = emptyList(),
            )
        }

    private fun folder() = File(temp.root, "bake")

    @Test
    fun `bakes every utterance into numbered files and a complete manifest`() {
        val synth = FakeSynthesizer()
        val items = utterances()

        val book = BookBaker(synth).bake(items, folder())

        assertTrue(book.isComplete)
        assertEquals(items.map { it.text }, synth.spoken)
        assertEquals((0..4).map { "0000$it.wav" }, book.utterances.map { it.fileName })
        for ((u, baked) in items.zip(book.utterances)) {
            assertTrue(File(folder(), baked.fileName).isFile)
            assertEquals(u.text.length * 10L, baked.durationMs, "the duration is read from the file")
            assertEquals(u.segmentIndex, baked.segmentIndex)
            assertEquals(u.start, baked.start)
            assertEquals(u.end, baked.end)
            assertEquals(u.page, baked.page)
        }
        assertEquals(book.totalDurationMs, items.sumOf { it.text.length * 10L })
        assertEquals(emptyList(), folder().listFiles { f -> f.name.endsWith(".tmp") }?.toList())
    }

    @Test
    fun `the manifest on disk reads back the same`() {
        val book = BookBaker(FakeSynthesizer()).bake(utterances(), folder())

        val read = assertNotNull(BakedBook.read(folder()))

        assertTrue(read.isComplete)
        assertEquals(book.utterances, read.utterances)
        assertEquals(book.fingerprint, read.fingerprint)
    }

    @Test
    fun `progress goes from 0 to the total, one step at a time`() {
        val reports = mutableListOf<BakeProgress>()

        BookBaker(FakeSynthesizer()).bake(utterances(4), folder()) { reports += it }

        assertEquals((0..4).map { BakeProgress(it, 4) }, reports)
    }

    @Test
    fun `cancelling stops the bake, keeps the finished files, and leaves an incomplete manifest`() {
        val token = CancellationToken()
        val synth = FakeSynthesizer().apply { onSynthesize = { if (spoken.size == 3) token.cancel() } }

        assertFailsWith<BakeCancelledException> { BookBaker(synth).bake(utterances(), folder(), cancellation = token) }

        assertEquals(3, synth.spoken.size, "nothing is synthesized after the cancel")
        assertEquals(listOf("00000.wav", "00001.wav"), folder().listFiles { f -> f.name.endsWith(".wav") }!!.map { it.name }.sorted())
        assertEquals(emptyList(), folder().listFiles { f -> f.name.endsWith(".tmp") && f.name != "manifest.txt.tmp" }?.toList())
        assertFalse(assertNotNull(BakedBook.read(folder())).isComplete)
    }

    @Test
    fun `a token cancelled before the start synthesizes nothing`() {
        val synth = FakeSynthesizer()

        assertFailsWith<BakeCancelledException> {
            BookBaker(synth).bake(utterances(), folder(), cancellation = CancellationToken().apply { cancel() })
        }

        assertEquals(emptyList(), synth.spoken)
    }

    @Test
    fun `a restarted bake skips the utterances that are already baked`() {
        val items = utterances()
        val first = FakeSynthesizer()
        val token = CancellationToken()
        first.onSynthesize = { if (first.spoken.size == 3) token.cancel() }
        assertFailsWith<BakeCancelledException> { BookBaker(first).bake(items, folder(), cancellation = token) }

        val second = FakeSynthesizer()
        val progress = mutableListOf<BakeProgress>()
        val book = BookBaker(second).bake(items, folder()) { progress += it }

        assertEquals(items.drop(2).map { it.text }, second.spoken, "only the unfinished utterances are made")
        assertTrue(book.isComplete)
        assertEquals(5, book.utterances.size)
        assertEquals(BakeProgress(0, 5), progress.first())
        assertEquals(BakeProgress(5, 5), progress.last())
    }

    @Test
    fun `baking a finished bake again synthesizes nothing`() {
        val items = utterances()
        BookBaker(FakeSynthesizer()).bake(items, folder())
        val again = FakeSynthesizer()

        val book = BookBaker(again).bake(items, folder())

        assertEquals(emptyList(), again.spoken)
        assertTrue(book.isComplete)
    }

    @Test
    fun `a partly written file from a killed run is replaced`() {
        val items = utterances(3)
        BookBaker(FakeSynthesizer()).bake(items, folder())
        File(folder(), "00001.wav").writeBytes(ByteArray(10)) // damaged
        File(folder(), "00002.wav.tmp").writeText("half")
        val again = FakeSynthesizer()

        val book = BookBaker(again).bake(items, folder())

        assertEquals(listOf(items[1].text), again.spoken)
        assertTrue(book.isComplete)
        assertFalse(File(folder(), "00002.wav.tmp").exists())
    }

    @Test
    fun `different voice settings or text start over instead of reusing old audio`() {
        val items = utterances(3)
        BookBaker(FakeSynthesizer()).bake(items, folder(), settingsKey = "voice-a|1.0")
        val other = FakeSynthesizer()

        BookBaker(other).bake(items, folder(), settingsKey = "voice-b|1.0")
        assertEquals(items.map { it.text }, other.spoken)

        val edited = FakeSynthesizer()
        BookBaker(edited).bake(
            items.mapIndexed { i, u ->
                if (i ==
                    0
                ) {
                    u.copy(text = "Changed")
                } else {
                    u
                }
            },
            folder(),
            settingsKey = "voice-b|1.0",
        )
        assertEquals(3, edited.spoken.size)
    }

    @Test
    fun `a utterance that fails is tried once more, then the bake stops with its index and can resume`() {
        val items = utterances()
        val synth = FakeSynthesizer().apply { failOn = items[2].text }

        val error = assertFailsWith<BakeFailedException> { BookBaker(synth).bake(items, folder()) }

        assertEquals(2, error.index)
        assertEquals(listOf(items[0].text, items[1].text, items[2].text, items[2].text), synth.spoken)

        val fixed = FakeSynthesizer()
        val book = BookBaker(fixed).bake(items, folder())
        assertEquals(items.drop(2).map { it.text }, fixed.spoken)
        assertTrue(book.isComplete)
    }

    @Test
    fun `one failure is forgiven by the retry`() {
        val items = utterances(3)
        val synth =
            FakeSynthesizer().apply {
                failOn = items[1].text
                failuresLeft = 1
            }

        val book = BookBaker(synth).bake(items, folder())

        assertTrue(book.isComplete)
        assertEquals(4, synth.spoken.size)
    }

    @Test
    fun `an utterance the voice cannot speak becomes a moment of silence so the timeline stays whole`() {
        val items = utterances(3)
        val synth = FakeSynthesizer().apply { silentOn = items[1].text }

        val book = BookBaker(synth).bake(items, folder())

        assertTrue(book.isComplete)
        assertEquals(250L, book.utterances[1].durationMs)
    }

    @Test
    fun `an empty list is rejected`() {
        assertFailsWith<IllegalArgumentException> { BookBaker(FakeSynthesizer()).bake(emptyList(), folder()) }
    }

    @Test
    fun `other files in the folder are left alone when a bake starts over`() {
        folder().mkdirs()
        File(folder(), "notes.txt").writeText("mine")
        BookBaker(FakeSynthesizer()).bake(utterances(2), folder(), settingsKey = "a")

        BookBaker(FakeSynthesizer()).bake(utterances(2), folder(), settingsKey = "b")

        assertEquals("mine", File(folder(), "notes.txt").readText())
    }
}
