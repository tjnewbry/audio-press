package io.github.tjnewbry.audiopress.bake

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BakeSettingsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val items = List(3) { BakeItem("Paragraph number $it of the test.", it, 0, 30, it + 1) }

    private class Voice(
        val onText: (String) -> SynthesisResult = { SynthesisResult.SUCCESS },
    ) : SpeechSynthesizer {
        val spoken = mutableListOf<String>()

        override fun synthesize(
            text: String,
            target: File,
            cancellation: io.github.tjnewbry.audiopress.core.CancellationToken?,
        ): SynthesisResult {
            spoken += text
            val result = onText(text)
            if (result == SynthesisResult.SUCCESS) Wav.writeSilence(target, 500)
            return result
        }
    }

    @Test
    fun `the manifest records the settings key, and a bake without one records none`() {
        val folder = File(temp.root, "a")
        BookBaker(Voice()).bake(items, folder, settingsKey = "kokoro-v1.0|lite|af_bella|speed=1.00")
        val without = File(temp.root, "b")
        BookBaker(Voice()).bake(items, without)

        assertEquals("kokoro-v1.0|lite|af_bella|speed=1.00", BakedBook.read(folder)!!.settingsKey)
        assertNull(BakedBook.read(without)!!.settingsKey)
    }

    @Test
    fun `an old manifest without a settings line still reads`() {
        val text = "audiopress-bake\t1\nformat\twav\nfingerprint\tabc\nutterances\t0\ncomplete\t0\n"

        val book = BakeManifest.parse(File("x"), text)!!

        assertNull(book.settingsKey)
    }

    @Test
    fun `a tab or newline in the settings key cannot break the manifest`() {
        val folder = File(temp.root, "c")

        BookBaker(Voice()).bake(items, folder, settingsKey = "a\tb\nc")

        val book = BakedBook.read(folder)!!
        assertEquals("a b c", book.settingsKey)
        assertEquals(3, book.utterances.size)
    }

    @Test
    fun `running out of memory stops the bake at once with the utterance, keeping what was baked`() {
        val folder = File(temp.root, "d")
        val voice = Voice { text -> if (text == items[1].text) SynthesisResult.OUT_OF_MEMORY else SynthesisResult.SUCCESS }

        val error = assertFailsWith<BakeOutOfMemoryException> { BookBaker(voice).bake(items, folder) }

        assertEquals(1, error.index)
        assertEquals(listOf(items[0].text, items[1].text), voice.spoken, "no retry after an out-of-memory")
        assertEquals(listOf("00000.wav"), folder.listFiles { f -> f.name.endsWith(".wav") }!!.map { it.name })
    }
}
