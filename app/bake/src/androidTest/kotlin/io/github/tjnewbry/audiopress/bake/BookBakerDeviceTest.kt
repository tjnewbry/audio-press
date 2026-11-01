package io.github.tjnewbry.audiopress.bake

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tjnewbry.audiopress.core.CancellationToken
import io.github.tjnewbry.audiopress.core.SegmentKind
import io.github.tjnewbry.audiopress.core.Utterance
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Bakes with the device's real speech engine. It is skipped if the device has no working speech engine. */
@RunWith(AndroidJUnit4::class)
class BookBakerDeviceTest {
    private var synthesizer: TtsFileSynthesizer? = null
    private lateinit var folder: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        folder = File(context.cacheDir, "bake-test").apply { deleteRecursively() }
        val ready = CountDownLatch(1)
        TtsFileSynthesizer.open(context) {
            synthesizer = it
            ready.countDown()
        }
        assertTrue(ready.await(60, TimeUnit.SECONDS), "the speech engine did not start in time")
        assumeTrue("no working speech engine on this device", synthesizer != null)
    }

    @After
    fun tearDown() {
        synthesizer?.shutdown()
        folder.deleteRecursively()
    }

    private fun utterance(
        text: String,
        index: Int,
    ) = Utterance(
        text,
        segmentIndex = index,
        kind = SegmentKind.PARAGRAPH,
        start = 0,
        end = text.length,
        page = index + 1,
        words = emptyList(),
    )

    private val items = listOf(utterance("Hello there, this is the first test sentence.", 0), utterance("And this is the second one.", 1))

    @Test
    fun bakesTwoShortUtterancesIntoPlayableFilesWithADuration() {
        val progress = mutableListOf<BakeProgress>()

        val book = BookBaker(synthesizer!!).bake(items, folder, settingsKey = "device-default") { progress += it }

        assertTrue(book.isComplete)
        assertEquals(listOf(BakeProgress(0, 2), BakeProgress(1, 2), BakeProgress(2, 2)), progress)
        assertEquals(2, book.utterances.size)
        for (baked in book.utterances) {
            val file = book.file(baked)
            assertTrue(file.isFile && file.length() > 44, "${file.name} has audio")
            assertTrue(baked.durationMs > 300, "${file.name} lasts ${baked.durationMs} ms")
            assertEquals(baked.durationMs, Wav.durationMs(file))
        }
        val read = assertNotNull(BakedBook.read(folder))
        assertTrue(read.isComplete)
        assertEquals(book.utterances, read.utterances)
        assertEquals(1, read.locate(read.startMs(1) + 10)!!.utteranceIndex)
    }

    @Test
    fun aSecondBakeReusesTheFilesAndACancelledOneStops() {
        BookBaker(synthesizer!!).bake(items, folder, settingsKey = "device-default")
        val firstModified = File(folder, "00000.wav").lastModified()

        BookBaker(synthesizer!!).bake(items, folder, settingsKey = "device-default")

        assertEquals(firstModified, File(folder, "00000.wav").lastModified(), "the finished file was not made again")
        val token = CancellationToken().apply { cancel() }
        assertFailsWith<BakeCancelledException> {
            BookBaker(
                synthesizer!!,
            ).bake(items, folder, settingsKey = "other", cancellation = token)
        }
    }
}
