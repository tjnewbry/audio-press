package io.github.tjnewbry.audiopress.bake.kokoro

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GithubKokoroCatalogTest {
    private val catalog = GithubKokoroCatalog

    @Test
    fun `each quality points at its file in the pinned release, with a size and a checksum`() {
        for (quality in KokoroQuality.entries) {
            val spec = catalog.model(quality)
            assertEquals(quality.modelFileName, spec.fileName)
            assertEquals("https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/${spec.fileName}", spec.url)
            assertTrue(spec.sizeBytes > 50_000_000)
            assertTrue(Regex("[0-9a-f]{64}").matches(spec.sha256), "${spec.fileName} has a SHA-256")
        }
        assertEquals("voices-v1.0.bin", catalog.voices.fileName)
        assertEquals(28_214_398L, catalog.voices.sizeBytes)
        assertEquals(
            4,
            (KokoroQuality.entries.map { catalog.model(it).sha256 } + catalog.voices.sha256).toSet().size,
            "four different files",
        )
    }

    @Test
    fun `the sizes match the release files byte for byte`() {
        assertEquals(92_361_271L, catalog.model(KokoroQuality.LITE).sizeBytes)
        assertEquals(177_464_787L, catalog.model(KokoroQuality.MEDIUM).sizeBytes)
        assertEquals(325_532_387L, catalog.model(KokoroQuality.HEAVY).sizeBytes)
    }

    @Test
    fun `the download button shows decimal megabytes, 121 206 and 354`() {
        assertEquals(listOf(120_575_669L, 205_679_185L, 353_746_785L), KokoroQuality.entries.map { catalog.totalBytes(it) })
        assertEquals(listOf(121, 206, 354), KokoroQuality.entries.map { catalog.totalBytes(it).toDecimalMegabytes() })
        assertEquals(0, 0L.toDecimalMegabytes())
        assertEquals(1, 500_000L.toDecimalMegabytes())
        assertEquals(0, 499_999L.toDecimalMegabytes())
    }

    @Test
    fun `the fake service reports the same sizes as the real catalog`() {
        val fake = FakeKokoroService(downloadMillis = 0, steps = 1)

        assertEquals(listOf(121, 206, 354), KokoroQuality.entries.map { fake.downloadSizeMegabytes(it) })
    }
}
