package io.github.tjnewbry.audiopress.bake.kokoro

import io.github.tjnewbry.audiopress.bake.BakeCancelledException
import io.github.tjnewbry.audiopress.core.CancellationToken
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KokoroModelStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val root get() = File(temp.root, "kokoro")

    private fun bytes(
        seed: Int,
        size: Int,
    ) = ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private val lite = bytes(1, 300_000)
    private val heavy = bytes(2, 500_000)
    private val voices = bytes(3, 100_000)

    private val catalog =
        object : KokoroCatalog {
            override fun model(quality: KokoroQuality) =
                when (quality) {
                    KokoroQuality.HEAVY -> KokoroFileSpec("heavy.onnx", "https://example.test/heavy", heavy.size.toLong(), sha(heavy))
                    else -> KokoroFileSpec("lite.onnx", "https://example.test/lite", lite.size.toLong(), sha(lite))
                }

            override val voices =
                KokoroFileSpec(
                    "voices.bin",
                    "https://example.test/voices",
                    this@KokoroModelStoreTest.voices.size.toLong(),
                    sha(this@KokoroModelStoreTest.voices),
                )
        }

    /** A fake server holding the files; it records every request and can drop the connection after some bytes. */
    private inner class FakeHttp : HttpSource {
        val files =
            mapOf(
                "https://example.test/lite" to lite,
                "https://example.test/heavy" to heavy,
                "https://example.test/voices" to voices,
            )
        val requests = mutableListOf<Pair<String, Long>>()
        var supportsRange = true
        var failAfterBytes: Int? = null
        var offline = false
        var corrupt = false

        override fun open(
            url: String,
            rangeStart: Long,
        ): HttpResponse {
            requests += url to rangeStart
            if (offline) throw UnknownHostException("example.test")
            var data = files.getValue(url)
            if (corrupt) data = data.copyOf().also { it[10] = (it[10] + 1).toByte() }
            val start = if (supportsRange) rangeStart.toInt() else 0
            val body = data.copyOfRange(start, data.size)
            val limit = failAfterBytes
            val stream =
                if (limit != null && body.size > limit) {
                    failAfterBytes = null
                    object : java.io.InputStream() {
                        private val inner = ByteArrayInputStream(body, 0, limit)

                        override fun read(): Int = inner.read().takeIf { it >= 0 } ?: throw IOException("connection reset")

                        override fun read(
                            b: ByteArray,
                            off: Int,
                            len: Int,
                        ): Int = inner.read(b, off, len).takeIf { it >= 0 } ?: throw IOException("connection reset")
                    }
                } else {
                    ByteArrayInputStream(body)
                }
            return HttpResponse(if (supportsRange && rangeStart > 0) 206 else 200, stream)
        }
    }

    private fun store(
        http: HttpSource,
        free: Long = Long.MAX_VALUE,
    ) = KokoroModelStore(root, catalog, http, freeSpace = { free })

    @Test
    fun `nothing is downloaded at first, and the size is the model plus the voices`() {
        val store = store(FakeHttp())

        assertFalse(store.isDownloaded(KokoroQuality.LITE))
        assertEquals((lite.size + voices.size).toLong(), store.downloadSizeBytes(KokoroQuality.LITE))
        assertNull(store.modelFile(KokoroQuality.LITE))
        assertNull(store.voicesFile())
    }

    @Test
    fun `a download fetches both files, verifies them, and reports progress up to 100 percent`() {
        val progress = mutableListOf<DownloadProgress>()
        val store = store(FakeHttp())

        store.download(KokoroQuality.LITE) { progress += it }

        assertTrue(store.isDownloaded(KokoroQuality.LITE))
        assertEquals(lite.toList(), store.modelFile(KokoroQuality.LITE)!!.readBytes().toList())
        assertEquals(voices.toList(), store.voicesFile()!!.readBytes().toList())
        assertEquals(0L, store.downloadSizeBytes(KokoroQuality.LITE))
        assertEquals(0f, progress.first().fraction)
        assertEquals(1f, progress.last().fraction)
        assertEquals(progress.map { it.doneBytes }.sorted(), progress.map { it.doneBytes }, "progress only goes up")
        assertEquals(emptyList(), root.listFiles { f -> f.name.endsWith(".part") }?.toList())
    }

    @Test
    fun `the voices file is downloaded once and shared by every quality`() {
        val http = FakeHttp()
        val store = store(http)
        store.download(KokoroQuality.LITE)
        http.requests.clear()

        assertEquals(heavy.size.toLong(), store.downloadSizeBytes(KokoroQuality.HEAVY))
        store.download(KokoroQuality.HEAVY)

        assertEquals(listOf("https://example.test/heavy"), http.requests.map { it.first })
        assertTrue(store.isDownloaded(KokoroQuality.HEAVY))
        assertTrue(store.isDownloaded(KokoroQuality.LITE))
    }

    @Test
    fun `downloading what is already there does no network at all`() {
        val http = FakeHttp()
        val store = store(http)
        store.download(KokoroQuality.LITE)
        http.requests.clear()

        store.download(KokoroQuality.LITE)

        assertEquals(emptyList(), http.requests)
    }

    @Test
    fun `a dropped connection keeps the partial file, which does not count as downloaded, and the next try resumes it`() {
        val http = FakeHttp().apply { failAfterBytes = 120_000 }
        val store = store(http)

        val error = assertFailsWith<KokoroDownloadException> { store.download(KokoroQuality.LITE) }

        assertEquals(DownloadFailure.NETWORK, error.reason)
        assertFalse(store.isDownloaded(KokoroQuality.LITE))
        val partial = File(root, "lite.onnx.part")
        assertEquals(120_000L, partial.length())
        assertEquals((lite.size - 120_000 + voices.size).toLong(), store.downloadSizeBytes(KokoroQuality.LITE))

        http.requests.clear()
        store.download(KokoroQuality.LITE)

        assertEquals("https://example.test/lite" to 120_000L, http.requests.first(), "asks for the rest only")
        assertTrue(store.isDownloaded(KokoroQuality.LITE))
        assertEquals(lite.toList(), store.modelFile(KokoroQuality.LITE)!!.readBytes().toList())
    }

    @Test
    fun `a server that cannot resume makes the download start over, and the result is still right`() {
        val http = FakeHttp().apply { failAfterBytes = 50_000 }
        val store = store(http)
        assertFailsWith<KokoroDownloadException> { store.download(KokoroQuality.LITE) }
        http.supportsRange = false

        store.download(KokoroQuality.LITE)

        assertEquals(lite.toList(), store.modelFile(KokoroQuality.LITE)!!.readBytes().toList())
    }

    @Test
    fun `a checksum mismatch deletes the file and says it is damaged`() {
        val http = FakeHttp().apply { corrupt = true }
        val store = store(http)

        val error = assertFailsWith<KokoroDownloadException> { store.download(KokoroQuality.LITE) }

        assertEquals(DownloadFailure.CHECKSUM, error.reason)
        assertFalse(store.isDownloaded(KokoroQuality.LITE))
        assertEquals(emptyList(), root.listFiles()?.toList(), "neither a final nor a partial file is left")
    }

    @Test
    fun `cancelling keeps the partial file and throws the bake cancel exception`() {
        val token = CancellationToken()
        val store = store(FakeHttp())

        assertFailsWith<BakeCancelledException> {
            store.download(KokoroQuality.LITE, token) { if (it.doneBytes > 0) token.cancel() }
        }

        assertFalse(store.isDownloaded(KokoroQuality.LITE))
        store.download(KokoroQuality.LITE)
        assertTrue(store.isDownloaded(KokoroQuality.LITE))
    }

    @Test
    fun `no network is reported as such, with a message for the user`() {
        val store = store(FakeHttp().apply { offline = true })

        val error = assertFailsWith<KokoroDownloadException> { store.download(KokoroQuality.LITE) }

        assertEquals(DownloadFailure.NO_NETWORK, error.reason)
        assertTrue(error.message!!.contains("network"))
    }

    @Test
    fun `too little storage is reported before anything is fetched`() {
        val http = FakeHttp()
        val store = store(http, free = 1_000)

        val error = assertFailsWith<KokoroDownloadException> { store.download(KokoroQuality.LITE) }

        assertEquals(DownloadFailure.DISK_FULL, error.reason)
        assertEquals(emptyList(), http.requests)
    }

    @Test
    fun `a file of the wrong size is not downloaded`() {
        val store = store(FakeHttp())
        store.download(KokoroQuality.LITE)
        File(root, "lite.onnx").writeBytes(ByteArray(10))

        assertFalse(store.isDownloaded(KokoroQuality.LITE))
        assertNull(store.modelFile(KokoroQuality.LITE))
    }

    @Test
    fun `an oversized leftover partial file is discarded`() {
        root.mkdirs()
        File(root, "lite.onnx.part").writeBytes(ByteArray(lite.size + 5))
        val store = store(FakeHttp())

        store.download(KokoroQuality.LITE)

        assertEquals(lite.toList(), store.modelFile(KokoroQuality.LITE)!!.readBytes().toList())
    }

    @Test
    fun `deleting partial files keeps the finished ones`() {
        val http = FakeHttp()
        val store = store(http)
        store.download(KokoroQuality.LITE)
        http.failAfterBytes = 1000
        assertFailsWith<KokoroDownloadException> { store.download(KokoroQuality.HEAVY) }
        assertTrue(File(root, "heavy.onnx.part").exists())

        store.deletePartialFiles()

        assertFalse(File(root, "heavy.onnx.part").exists())
        assertTrue(store.isDownloaded(KokoroQuality.LITE))
    }
}
