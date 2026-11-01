package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.CancellationToken
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookBakeControllerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val texts = listOf("one", "two", "three", "four").mapIndexed { i, text -> Utterance(i, 0, text) }

    /** A baker that writes one tiny file per text, can be told to stop after some clips, and can fail. */
    private class FakeBaker(
        override val available: Boolean = true,
    ) : BookBaker {
        var stopAfter: Int? = null // cancel (as a user would) once this many clips exist
        var failAt: Int? = null
        var onFail: () -> Unit = {}
        val synthesized = mutableListOf<String>()

        override fun bake(
            utterances: List<Utterance>,
            folder: File,
            cancellation: CancellationToken,
            onProgress: (Int, Int) -> Unit,
        ) {
            folder.mkdirs()
            utterances.forEachIndexed { i, utterance ->
                val text = utterance.text
                val file = File(folder, "clip$i.wav")
                if (!file.exists()) {
                    if (failAt == i) {
                        onFail()
                        throw IllegalStateException("synthesis failed")
                    }
                    file.writeText(text)
                    synthesized += text
                }
                onProgress(i + 1, utterances.size)
                if (stopAfter == i + 1) cancellation.cancel()
                if (cancellation.isCancelled && i + 1 < utterances.size) throw BakeCancelledException()
            }
        }

        override fun readManifest(folder: File): BakeManifest? {
            val files = folder.listFiles()?.filter { it.name.endsWith(".wav") }?.sortedBy { it.name } ?: return null
            return BakeManifest(files.map { BakedClip(it, 1000) }, complete = files.size == 4)
        }
    }

    private val now = Executor { it.run() } // runs the bake on the calling thread
    private val audio get() = File(folder.root, "audio")
    private val states = mutableListOf<BakeState>()

    private var free = Long.MAX_VALUE

    private fun controller(baker: BookBaker) =
        BookBakeController(texts, audio, baker, now, freeSpace = { free }).also {
            it.listener =
                { s -> states += s }
        }

    @Test
    fun `a book that was never baked starts as not baked, and has no clips`() {
        val controller = controller(FakeBaker())

        assertEquals(BakeState.NotBaked(0, 4), controller.state)
        assertNull(controller.clips)
    }

    @Test
    fun `baking reports progress and ends baked with its size on disk`() {
        val controller = controller(FakeBaker())

        controller.start()

        val progress = states.filterIsInstance<BakeState.Baking>().map { it.done }
        assertEquals(listOf(0, 1, 2, 3, 4), progress)
        val baked = assertNotNull(controller.state as? BakeState.Baked)
        assertEquals(3L + 3 + 5 + 4, baked.bytes) // "one" "two" "three" "four"
        assertEquals(4, controller.clips?.size)
    }

    @Test
    fun `cancelling keeps the clips, and starting again resumes with only the missing ones`() {
        val baker = FakeBaker().apply { stopAfter = 2 }
        val controller = controller(baker)

        controller.start()
        assertEquals(BakeState.NotBaked(2, 4), controller.state)
        assertNull(controller.clips)

        baker.stopAfter = null
        controller.start()
        assertEquals(listOf("one", "two", "three", "four"), baker.synthesized) // nothing was made twice
        assertTrue(controller.state is BakeState.Baked)
    }

    @Test
    fun `a failure is reported with what was done, and can be retried`() {
        val baker = FakeBaker().apply { failAt = 2 }
        val controller = controller(baker)

        controller.start()
        val failed = assertNotNull(controller.state as? BakeState.Failed)
        assertEquals(2, failed.done)
        assertTrue("continues where it left off" in failed.message)

        baker.failAt = null
        controller.start()
        assertTrue(controller.state is BakeState.Baked)
    }

    @Test
    fun `a book that is already baked is found when the controller is made`() {
        val baker = FakeBaker()
        controller(baker).start()

        val reopened = BookBakeController(texts, audio, baker, now)

        assertTrue(reopened.state is BakeState.Baked)
        assertEquals(4, reopened.clips?.size)
        reopened.start() // nothing to do
        assertEquals(4, baker.synthesized.size)
    }

    @Test
    fun `clips that do not match the book's text are not used`() {
        val baker = FakeBaker()
        controller(baker).start()

        val changed = BookBakeController(texts.take(2), audio, baker, now)

        assertNull(changed.clips)
        assertTrue(changed.state is BakeState.NotBaked)
    }

    @Test
    fun `a build without a baker offers nothing`() {
        val controller = BookBakeController(texts, audio, UnavailableBaker, now)

        assertEquals(BakeState.Unavailable, controller.state)
        controller.start()
        assertEquals(BakeState.Unavailable, controller.state)
        assertFailsWith<UnsupportedOperationException> { UnavailableBaker.bake(texts, audio, CancellationToken()) { _, _ -> } }
    }

    @Test
    fun `baking is refused up front when there is not enough free storage, and nothing is written`() {
        free = 10L * 1024 * 1024
        val baker = FakeBaker()
        val controller = controller(baker)

        controller.start()

        val failed = assertNotNull(controller.state as? BakeState.Failed)
        assertTrue("Not enough free storage" in failed.message)
        assertTrue("10 MB is free" in failed.message)
        assertEquals(emptyList(), baker.synthesized)
        assertTrue(!audio.exists())

        free = Long.MAX_VALUE
        controller.start()
        assertTrue(controller.state is BakeState.Baked)
    }

    @Test
    fun `a bake that dies when storage runs out says so, and one that fails for another reason does not`() {
        val baker = FakeBaker().apply { failAt = 1 }
        val controller = controller(baker)
        free = 100L * 1024 * 1024 // enough to start
        baker.onFail = { free = 1024 } // and then the disk fills up

        controller.start()
        assertTrue("Not enough free storage" in assertNotNull(controller.state as? BakeState.Failed).message)

        free = Long.MAX_VALUE
        baker.onFail = {}
        controller.start()
        assertTrue("continues where it left off" in assertNotNull(controller.state as? BakeState.Failed).message)
    }

    @Test
    fun `a problem with its own message is shown as it is`() {
        val baker =
            object : BookBaker by FakeBaker() {
                override fun bake(
                    utterances: List<Utterance>,
                    folder: File,
                    cancellation: CancellationToken,
                    onProgress: (Int, Int) -> Unit,
                ): Unit = throw BakeProblem("No voice here")
            }
        val controller = controller(baker)

        controller.start()

        assertEquals("No voice here", assertNotNull(controller.state as? BakeState.Failed).message)
    }

    @Test
    fun `sizes are shown in units people read`() {
        assertEquals("0 bytes", formatSize(0))
        assertEquals("512 bytes", formatSize(512))
        assertEquals("2 KB", formatSize(2048))
        assertEquals("85 MB", formatSize(85L * 1024 * 1024))
        assertEquals("1.5 GB", formatSize(1536L * 1024 * 1024))
    }

    @Test
    fun `folder size adds up the files below it, and a missing folder is empty`() {
        File(audio, "sub").mkdirs()
        File(audio, "a.wav").writeBytes(ByteArray(100))
        File(audio, "sub/b.wav").writeBytes(ByteArray(23))

        assertEquals(123L, folderSize(audio))
        assertEquals(0L, folderSize(File(folder.root, "nothing")))
    }
}
