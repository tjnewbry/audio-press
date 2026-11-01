package io.github.tjnewbry.audiopress.bake.kokoro

import io.github.tjnewbry.audiopress.bake.BakeCancelledException
import io.github.tjnewbry.audiopress.bake.BakeItem
import io.github.tjnewbry.audiopress.bake.BakedBook
import io.github.tjnewbry.audiopress.bake.BookBaker
import io.github.tjnewbry.audiopress.core.CancellationToken
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KokoroServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val santa = KokoroVoices.byId("am_santa")!!
    private val bella = KokoroVoices.byId("af_bella")!!

    @Test
    fun `there are 28 English voices with the names and accents of the brief`() {
        val all = KokoroVoices.all

        assertEquals(28, all.size)
        assertEquals(28, all.map { it.id }.toSet().size)
        assertEquals(11, all.count { it.id.startsWith("af_") })
        assertEquals(9, all.count { it.id.startsWith("am_") })
        assertEquals(4, all.count { it.id.startsWith("bf_") })
        assertEquals(4, all.count { it.id.startsWith("bm_") })
        assertEquals("Santa", santa.name)
        assertEquals("Santa (American Male)", santa.label)
        assertEquals("Isabella (British Female)", KokoroVoices.byId("bf_isabella")!!.label)
        assertEquals("en-us", bella.languageTag)
        assertEquals("en-gb", KokoroVoices.byId("bm_george")!!.languageTag)
        assertTrue(all.all { it.languageTag == if (it.id.startsWith("a")) "en-us" else "en-gb" })
    }

    @Test
    fun `the three qualities map to the model files of the release`() {
        assertEquals(
            listOf("kokoro-v1.0.int8.onnx", "kokoro-v1.0.fp16.onnx", "kokoro-v1.0.onnx"),
            KokoroQuality.entries.map { it.modelFileName },
        )
        assertEquals(listOf("Lite", "Medium", "Heavy"), KokoroQuality.entries.map { it.displayName })
    }

    @Test
    fun `the settings key names the model, quality, voice, and speed, so changing any of them rebakes`() {
        val base = KokoroSettings.key(santa, KokoroQuality.LITE)

        assertEquals("kokoro-v1.0|lite|am_santa|speed=1.00", base)
        assertNotEquals(base, KokoroSettings.key(bella, KokoroQuality.LITE))
        assertNotEquals(base, KokoroSettings.key(santa, KokoroQuality.HEAVY))
        assertNotEquals(base, KokoroSettings.key(santa, KokoroQuality.LITE, speed = 1.25f))
    }

    @Test
    fun `a bake made with the fake voice reuses its files, and a changed voice or quality bakes again`() {
        val service = FakeKokoroService(downloadMillis = 0, steps = 1, downloaded = setOf(KokoroQuality.LITE, KokoroQuality.HEAVY))
        val items = List(3) { BakeItem("Paragraph number $it of the test book.", it, 0, 36, it + 1) }
        val folder = File(temp.root, "bake")

        fun bake(
            voice: KokoroVoice,
            quality: KokoroQuality,
        ): List<String> {
            val spoken = mutableListOf<String>()
            service.synthesizer(voice, quality).use { inner ->
                val counting =
                    object : io.github.tjnewbry.audiopress.bake.SpeechSynthesizer {
                        override fun synthesize(
                            text: String,
                            target: File,
                            cancellation: CancellationToken?,
                        ): io.github.tjnewbry.audiopress.bake.SynthesisResult {
                            spoken += text
                            return inner.synthesize(text, target, cancellation)
                        }
                    }
                BookBaker(counting).bake(items, folder, settingsKey = service.settingsKey(voice, quality))
            }
            return spoken
        }

        assertEquals(3, bake(santa, KokoroQuality.LITE).size)
        assertEquals(emptyList(), bake(santa, KokoroQuality.LITE), "same voice and quality: nothing to do")
        assertEquals("kokoro-v1.0|lite|am_santa|speed=1.00", BakedBook.read(folder)!!.settingsKey, "the manifest says what made it")
        assertEquals(3, bake(bella, KokoroQuality.LITE).size, "another voice bakes again")
        assertEquals(3, bake(bella, KokoroQuality.HEAVY).size, "another quality bakes again")
        assertEquals("kokoro-v1.0|heavy|af_bella|speed=1.00", BakedBook.read(folder)!!.settingsKey)
    }

    @Test
    fun `the fake service downloads, reports progress, and remembers`() {
        val service = FakeKokoroService(downloadMillis = 0, steps = 4)
        val fractions = mutableListOf<Float>()
        assertFalse(service.isDownloaded(KokoroQuality.MEDIUM))
        assertEquals(205_679_185L, service.downloadSizeBytes(KokoroQuality.MEDIUM))

        service.download(KokoroQuality.MEDIUM) { fractions += it.fraction }

        assertTrue(service.isDownloaded(KokoroQuality.MEDIUM))
        assertEquals(0L, service.downloadSizeBytes(KokoroQuality.MEDIUM))
        assertEquals(listOf(0f, 0.25f, 0.5f, 0.75f, 1f), fractions)
    }

    @Test
    fun `the fake service can be cancelled or made to fail, and a failed download is not remembered`() {
        val service = FakeKokoroService(downloadMillis = 0, steps = 4)
        assertFailsWith<BakeCancelledException> { service.download(KokoroQuality.LITE, CancellationToken().apply { cancel() }) }
        service.failNextDownloadWith = DownloadFailure.DISK_FULL

        val error = assertFailsWith<KokoroDownloadException> { service.download(KokoroQuality.LITE) }

        assertEquals(DownloadFailure.DISK_FULL, error.reason)
        assertFalse(service.isDownloaded(KokoroQuality.LITE))
        service.download(KokoroQuality.LITE)
        assertTrue(service.isDownloaded(KokoroQuality.LITE))
    }

    @Test
    fun `a synthesizer needs a downloaded model and a runtime`() {
        val fake = FakeKokoroService(downloadMillis = 0, steps = 1)
        assertFailsWith<KokoroUnavailableException> { fake.synthesizer(santa, KokoroQuality.LITE) }
        assertFailsWith<KokoroUnavailableException> {
            FakeKokoroService(downloaded = KokoroQuality.entries.toSet(), runtimeAvailable = false).synthesizer(santa, KokoroQuality.LITE)
        }
    }

    @Test
    fun `the real service has no runtime until one is plugged in`() {
        val catalog =
            object : KokoroCatalog {
                override fun model(quality: KokoroQuality) = KokoroFileSpec("m.onnx", "https://example.test/m", 1, "00")

                override val voices = KokoroFileSpec("v.bin", "https://example.test/v", 1, "00")
            }
        val service = DefaultKokoroService(KokoroModelStore(File(temp.root, "k"), catalog))

        assertFalse(service.runtimeAvailable)
        assertFalse(service.isDownloaded(KokoroQuality.LITE))
        assertFailsWith<KokoroUnavailableException> { service.synthesizer(santa, KokoroQuality.LITE) }
    }
}
