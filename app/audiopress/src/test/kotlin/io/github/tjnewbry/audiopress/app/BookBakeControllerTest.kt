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
    ) : BakeService {
        val downloaded = VoiceQuality.entries.toMutableSet()
        val downloads = mutableListOf<VoiceQuality>()
        val baked = mutableListOf<VoiceChoice>()
        var cancelDownload = false
        var size = 100L * 1024 * 1024
        var stopAfter: Int? = null // cancel (as a user would) once this many clips exist
        var failAt: Int? = null
        var onFail: () -> Unit = {}
        val synthesized = mutableListOf<String>()

        override fun isModelDownloaded(quality: VoiceQuality) = quality in downloaded

        override fun downloadSizeBytes(quality: VoiceQuality) = if (quality in downloaded) 0L else size

        override fun downloadSizeMegabytes(quality: VoiceQuality) = (downloadSizeBytes(quality) / 1_000_000L).toInt()

        override fun downloadModel(
            quality: VoiceQuality,
            cancellation: CancellationToken,
            onProgress: (Long, Long) -> Unit,
        ) {
            onProgress(0L, 100L)
            if (cancelDownload) {
                cancellation.cancel()
                throw BakeCancelledException()
            }
            onProgress(50L, 100L)
            onProgress(100L, 100L)
            downloads += quality
            downloaded += quality
        }

        override fun bake(
            utterances: List<Utterance>,
            folder: File,
            voice: VoiceChoice,
            cancellation: CancellationToken,
            onProgress: (Int, Int) -> Unit,
        ) {
            baked += voice
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

    private val choice = VoiceChoice("af_bella", VoiceQuality.LITE)
    private val choiceFile get() = File(folder.root, "voice.txt")

    private fun controller(baker: BakeService) =
        BookBakeController(texts, audio, baker, now, freeSpace = { free }, choiceFile = choiceFile).also {
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

        controller.start(choice)

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

        controller.start(choice)
        assertEquals(BakeState.NotBaked(2, 4, choice), controller.state)
        assertNull(controller.clips)

        baker.stopAfter = null
        controller.start(choice)
        assertEquals(listOf("one", "two", "three", "four"), baker.synthesized) // nothing was made twice
        assertTrue(controller.state is BakeState.Baked)
    }

    @Test
    fun `a failure is reported with what was done, and can be retried`() {
        val baker = FakeBaker().apply { failAt = 2 }
        val controller = controller(baker)

        controller.start(choice)
        val failed = assertNotNull(controller.state as? BakeState.Failed)
        assertEquals(2, failed.done)
        assertTrue("continues where it left off" in failed.message)

        baker.failAt = null
        controller.start(choice)
        assertTrue(controller.state is BakeState.Baked)
    }

    @Test
    fun `a book that is already baked is found when the controller is made`() {
        val baker = FakeBaker()
        controller(baker).start(choice)

        val reopened = BookBakeController(texts, audio, baker, now)

        assertTrue(reopened.state is BakeState.Baked)
        assertEquals(4, reopened.clips?.size)
        reopened.start(choice) // nothing to do
        assertEquals(4, baker.synthesized.size)
    }

    @Test
    fun `clips that do not match the book's text are not used`() {
        val baker = FakeBaker()
        controller(baker).start(choice)

        val changed = BookBakeController(texts.take(2), audio, baker, now)

        assertNull(changed.clips)
        assertTrue(changed.state is BakeState.NotBaked)
    }

    @Test
    fun `a build without a baker offers nothing`() {
        val controller = BookBakeController(texts, audio, UnavailableBaker, now)

        assertEquals(BakeState.Unavailable, controller.state)
        controller.start(choice)
        assertEquals(BakeState.Unavailable, controller.state)
        assertFailsWith<UnsupportedOperationException> { UnavailableBaker.bake(texts, audio, choice, CancellationToken()) { _, _ -> } }
    }

    @Test
    fun `baking is refused up front when there is not enough free storage, and nothing is written`() {
        free = 10L * 1024 * 1024
        val baker = FakeBaker()
        val controller = controller(baker)

        controller.start(choice)

        val failed = assertNotNull(controller.state as? BakeState.Failed)
        assertTrue("Not enough free storage" in failed.message)
        assertTrue("10.5 MB is free" in failed.message)
        assertEquals(emptyList(), baker.synthesized)
        assertTrue(!audio.exists())

        free = Long.MAX_VALUE
        controller.start(choice)
        assertTrue(controller.state is BakeState.Baked)
    }

    @Test
    fun `a bake that dies when storage runs out says so, and one that fails for another reason does not`() {
        val baker = FakeBaker().apply { failAt = 1 }
        val controller = controller(baker)
        free = 100L * 1024 * 1024 // enough to start
        baker.onFail = { free = 1024 } // and then the disk fills up

        controller.start(choice)
        assertTrue("Not enough free storage" in assertNotNull(controller.state as? BakeState.Failed).message)

        free = Long.MAX_VALUE
        baker.onFail = {}
        controller.start(choice)
        assertTrue("continues where it left off" in assertNotNull(controller.state as? BakeState.Failed).message)
    }

    @Test
    fun `a problem with its own message is shown as it is`() {
        val baker =
            object : BakeService by FakeBaker() {
                override fun bake(
                    utterances: List<Utterance>,
                    folder: File,
                    voice: VoiceChoice,
                    cancellation: CancellationToken,
                    onProgress: (Int, Int) -> Unit,
                ): Unit = throw BakeProblem("No voice here")
            }
        val controller = controller(baker)

        controller.start(choice)

        assertEquals("No voice here", assertNotNull(controller.state as? BakeState.Failed).message)
    }

    @Test
    fun `folder size adds up the files below it, and a missing folder is empty`() {
        File(audio, "sub").mkdirs()
        File(audio, "a.wav").writeBytes(ByteArray(100))
        File(audio, "sub/b.wav").writeBytes(ByteArray(23))

        assertEquals(123L, folderSize(audio))
        assertEquals(0L, folderSize(File(folder.root, "nothing")))
    }

    @Test
    fun `a model that is not on the phone is downloaded first, with progress, then the book is baked`() {
        val baker = FakeBaker().apply { downloaded.clear() }
        val controller = controller(baker)
        assertEquals(false, controller.isModelDownloaded(VoiceQuality.LITE))

        controller.start(choice)

        val downloading = states.filterIsInstance<BakeState.Downloading>()
        assertEquals(listOf(0f, 0.5f, 1f), downloading.map { it.fraction }.distinct())
        assertEquals(VoiceQuality.LITE, downloading.first().quality)
        assertEquals(listOf(VoiceQuality.LITE), baker.downloads)
        assertEquals(listOf(choice), baker.baked)
        assertTrue(controller.state is BakeState.Baked)
        // The progress screen reads: downloading comes before baking.
        assertTrue(states.indexOfFirst { it is BakeState.Downloading } < states.indexOfFirst { it is BakeState.Baking })
    }

    @Test
    fun `a model that is already here is not downloaded again`() {
        val baker = FakeBaker()
        val controller = controller(baker)

        controller.start(choice)

        assertEquals(emptyList(), baker.downloads)
        assertTrue(states.none { it is BakeState.Downloading })
    }

    @Test
    fun `cancelling the download leaves the book not baked and the model not downloaded`() {
        val baker = FakeBaker().apply { downloaded.clear() }
        baker.cancelDownload = true
        val controller = controller(baker)

        controller.start(choice)

        assertEquals(BakeState.NotBaked(0, 4, choice), controller.state)
        assertEquals(false, controller.isModelDownloaded(VoiceQuality.LITE))
        assertEquals(emptyList(), baker.baked)
    }

    @Test
    fun `the space check counts the model that has to be downloaded`() {
        val baker = FakeBaker().apply { downloaded.clear() }
        free = 100L * 1024 * 1024 // enough for the tiny book, not for a 100 MB model plus the margin
        val controller = controller(baker)

        controller.start(choice)

        val failed = assertNotNull(controller.state as? BakeState.Failed)
        assertTrue("Not enough free storage" in failed.message)
        assertEquals(emptyList(), baker.downloads)
    }

    @Test
    fun `without a voice nothing starts, and the voice of an earlier bake is remembered across restarts`() {
        val baker = FakeBaker().apply { stopAfter = 2 }
        val controller = controller(baker)
        controller.start() // no voice chosen, none saved
        assertEquals(BakeState.NotBaked(0, 4, null), controller.state)

        controller.start(choice)
        assertEquals(BakeState.NotBaked(2, 4, choice), controller.state)

        val restarted = controller(baker) // a new app run, same files
        assertEquals(choice, restarted.savedChoice)
        assertEquals(BakeState.NotBaked(2, 4, choice), restarted.state)
        baker.stopAfter = null
        restarted.start() // resume with the saved voice
        assertTrue(restarted.state is BakeState.Baked)
        assertEquals(listOf(choice, choice), baker.baked)
    }

    @Test
    fun `a different voice starts the book over`() {
        val baker = FakeBaker().apply { stopAfter = 2 }
        val controller = controller(baker)
        controller.start(choice)
        baker.stopAfter = null
        val other = VoiceChoice("am_santa", VoiceQuality.LITE)

        controller.start(other)

        assertEquals(other, controller.savedChoice)
        assertEquals(listOf(choice, other), baker.baked)
    }

    @Test
    fun `while baking only the clips reported done can be played, and every clip once the book is done`() {
        val controller = controller(FakeBaker())
        val seen = mutableListOf<Pair<Int, Int>>() // clips reported done, clips that can be played
        controller.listener = { state ->
            if (state is BakeState.Baking) seen += state.done to (0 until 4).count { controller.clipAt(it) != null }
        }

        controller.start(choice)

        assertEquals(listOf(0 to 0, 1 to 1, 2 to 2, 3 to 3, 4 to 4), seen)
        assertEquals(listOf("clip0.wav", "clip1.wav", "clip2.wav", "clip3.wav"), (0 until 4).map { controller.clipAt(it)?.file?.name })
        assertNull(controller.clipAt(4))
    }

    @Test
    fun `nothing can be played from a book that is not being baked`() {
        val controller = controller(FakeBaker().apply { stopAfter = 2 })
        controller.start(choice) // cancelled after two clips: they exist, but nothing is baking now

        assertNull(controller.clipAt(0))
    }

    @Test
    fun `the time left is worked out from the words baked so far`() {
        var clock = 0L
        val baker =
            object : BakeService by FakeBaker() {
                override fun bake(
                    utterances: List<Utterance>,
                    folder: File,
                    voice: VoiceChoice,
                    cancellation: CancellationToken,
                    onProgress: (Int, Int) -> Unit,
                ) {
                    for (i in 1..utterances.size) {
                        clock += 10_000 // each clip takes 10 s
                        onProgress(i, utterances.size)
                    }
                }
            }
        val controller = BookBakeController(texts, audio, baker, now, freeSpace = { free }, choiceFile = choiceFile, now = { clock })
        val etas = mutableListOf<Long?>()
        controller.listener = { state -> if (state is BakeState.Baking) etas += state.etaMs }

        controller.start(choice)

        // The first Baking state, before any clip is done, has no estimate. After that, four equal clips at 10 s each:
        // 30 s left after the first, 20 s after the second, 10 s after the third, none after the last.
        assertEquals(listOf(null, 30_000L, 20_000L, 10_000L, 0L), etas)
    }
}
