package io.github.tjnewbry.audiopress.bake

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tjnewbry.audiopress.core.CancellationToken
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exports generated tones (no speech) with the device's own AAC encoder and checks the result with MediaExtractor. */
@RunWith(AndroidJUnit4::class)
class M4aExporterDeviceTest {
    private lateinit var folder: File
    private lateinit var out: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        folder = File(context.cacheDir, "export-test").apply { deleteRecursively() }
        folder.mkdirs()
        out = File(folder, "book.m4a")
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    private fun tone(
        file: File,
        seconds: Int,
        hz: Double,
    ) {
        PcmWavWriter(file).use { w ->
            w.write(FloatArray(24000 * seconds) { (0.4 * sin(2 * PI * hz * it / 24000)).toFloat() })
        }
    }

    private fun bake(
        clips: Int,
        seconds: Int,
        complete: Boolean = true,
    ): BakedBook {
        val dir = File(folder, "bake").apply { mkdirs() }
        val list =
            (0 until clips).map { i ->
                val name = "%05d.wav".format(i)
                tone(File(dir, name), seconds, 300.0 + 150 * i)
                BakedUtterance(i, name, i, 0, 10, i + 1, seconds * 1000L)
            }
        return BakedBook(dir, "fp", list, complete)
    }

    @Test
    fun exportsOneDecodableM4aWithTheRightDurationAndMuchSmallerThanTheWavs() {
        val book = bake(clips = 3, seconds = 20)
        val progress = mutableListOf<Float>()

        val result = M4aExporter.export(book, out, onProgress = { progress += it })

        assertEquals(60_000L, result.durationMs)
        assertEquals(out.length(), result.sizeBytes)
        val wavBytes = book.utterances.sumOf { book.file(it).length() }
        assertTrue(result.sizeBytes < wavBytes / 6, "m4a ${result.sizeBytes} vs wav $wavBytes")
        println("M4A_SIZE bytesFor60s=${result.sizeBytes} wav=$wavBytes")
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a }, "progress never goes back")

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(out.path)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
            assertEquals(24000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            val durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
            assertTrue(abs(durationMs - 60_000) <= 200, "duration $durationMs ms")
            extractor.selectTrack(0)
            var frames = 0
            while (extractor.sampleTime >= 0) {
                frames++
                if (!extractor.advance()) break
            }
            assertTrue(frames > 60 * 24000 / 1024 - 5, "decodable frames: $frames")
        } finally {
            extractor.release()
        }
    }

    @Test
    fun cancellingStopsAndDeletesThePartialFile() {
        val book = bake(clips = 3, seconds = 20)
        val token = CancellationToken()

        assertFailsWith<ExportCancelledException> {
            M4aExporter.export(book, out, token) { if (it > 0.2f) token.cancel() }
        }

        assertFalse(out.exists(), "the partial file is gone")
    }

    @Test
    fun anIncompleteBakeIsRefused() {
        val book = bake(clips = 2, seconds = 1, complete = false)

        val e = assertFailsWith<ExportIncompleteException> { M4aExporter.export(book, out) }

        assertTrue(e.message!!.contains("not fully baked"))
        assertFalse(out.exists())
    }

    @Test
    fun aMissingClipFailsWithAMessageAndLeavesNoFile() {
        val book = bake(clips = 2, seconds = 1)
        book.file(book.utterances[1]).delete()

        val e = assertFailsWith<ExportException> { M4aExporter.export(book, out) }

        assertTrue(e.message!!.isNotBlank())
        assertFalse(out.exists())
    }
}
