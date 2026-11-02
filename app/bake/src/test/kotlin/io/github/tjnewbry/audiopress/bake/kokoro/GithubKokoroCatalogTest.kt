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
    fun `the front-end files are pinned too, four misaki dictionaries and the unknown-word model, 16 MB in all`() {
        assertEquals(
            listOf("us_gold.json", "us_silver.json", "gb_gold.json", "gb_silver.json", "misaki-en_us-g2p.onnx"),
            catalog.frontEnd.map { it.fileName },
        )
        assertEquals(16_040_680L, catalog.frontEnd.sumOf { it.sizeBytes })
        assertTrue(
            catalog.frontEnd.take(4).all {
                it.url.startsWith("https://raw.githubusercontent.com/hexgrad/misaki/fba1236595f2d2bf21d414ba6e57d25256afada3/")
            },
        )
        assertTrue(
            catalog.frontEnd
                .last()
                .url
                .contains("4b3a605d2f605318ab4f762ab9855c44106528b7"),
        )
        assertTrue(catalog.frontEnd.all { Regex("[0-9a-f]{64}").matches(it.sha256) })
    }

    @Test
    fun `the download button shows decimal megabytes, 137 222 and 370`() {
        assertEquals(listOf(136_616_349L, 221_719_865L, 369_787_465L), KokoroQuality.entries.map { catalog.totalBytes(it) })
        assertEquals(listOf(137, 222, 370), KokoroQuality.entries.map { catalog.totalBytes(it).toDecimalMegabytes() })
        assertEquals(0, 0L.toDecimalMegabytes())
        assertEquals(1, 500_000L.toDecimalMegabytes())
        assertEquals(0, 499_999L.toDecimalMegabytes())
    }

    @Test
    fun `the fake service reports the same sizes as the real catalog`() {
        val fake = FakeKokoroService(downloadMillis = 0, steps = 1)

        assertEquals(listOf(137, 222, 370), KokoroQuality.entries.map { fake.downloadSizeMegabytes(it) })
    }
}
