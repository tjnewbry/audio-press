package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.CancellationToken
import io.github.tjnewbry.audiopress.core.SegmentKind
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookDeletionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val books get() = File(temp.root, "books")
    private val models get() = File(temp.root, "kokoro").apply { mkdirs() }
    private val store by lazy { BookStore(books) }

    /** A stopper that knows nothing is running, and records what it was told. */
    private class IdleStopper(
        val stops: Boolean = true,
    ) : BakeStopper {
        val calls = mutableListOf<String>()

        override fun stopAndWait(bookId: String): Boolean {
            calls += "stop:$bookId"
            return stops
        }

        override fun bakeDeleted(bookId: String) {
            calls += "bakeDeleted:$bookId"
        }

        override fun forget(bookId: String) {
            calls += "forget:$bookId"
        }
    }

    private fun newBook(title: String = "Sample"): Book {
        val book = store.save(title, 1, 3, listOf(StoredParagraph(SegmentKind.PARAGRAPH, 1, "Original test text.")))
        store.saveProgress(book.id, Position(0, 5))
        File(store.audioFolder(book.id), "clip0.wav").apply {
            parentFile?.mkdirs()
            writeText("audio")
        }
        store.voiceFile(book.id).writeText("af_bella\tLITE\n")
        File(models, "model.onnx").writeText("model")
        return book
    }

    @Test
    fun `delete all removes the book, its bake and its voice, and leaves other books and the models`() {
        val book = newBook("First")
        val other = newBook("Second")
        val stopper = IdleStopper()

        assertEquals(DeleteResult.DONE, BookDeletion(store, stopper).deleteAll(book))

        assertFalse(File(books, book.id).exists())
        assertEquals(listOf(other.id), store.list().map { it.id })
        assertTrue(store.audioFolder(other.id).isDirectory)
        assertTrue(File(models, "model.onnx").isFile)
        assertEquals(listOf("stop:${book.id}", "forget:${book.id}"), stopper.calls)
    }

    @Test
    fun `delete bake removes only the audio and the saved voice`() {
        val book = newBook()
        val stopper = IdleStopper()

        assertEquals(DeleteResult.DONE, BookDeletion(store, stopper).deleteBake(book))

        assertFalse(store.audioFolder(book.id).exists())
        assertFalse(store.voiceFile(book.id).exists())
        assertEquals(listOf(book.id), store.list().map { it.id })
        assertEquals("Original test text.", store.load(book.id).single().text)
        assertEquals(Position(0, 5), store.loadProgress(book.id))
        assertTrue(File(models, "model.onnx").isFile)
        assertEquals(listOf("stop:${book.id}", "bakeDeleted:${book.id}"), stopper.calls)
    }

    @Test
    fun `missing files are not an error`() {
        val book = store.save("Plain", 1, 3, listOf(StoredParagraph(SegmentKind.PARAGRAPH, 1, "Text.")))
        val deletion = BookDeletion(store, IdleStopper())

        assertEquals(DeleteResult.DONE, deletion.deleteBake(book)) // never baked: no bake folder, no voice file
        assertEquals(DeleteResult.DONE, deletion.deleteAll(book))
        assertEquals(DeleteResult.DONE, deletion.deleteAll(book)) // already gone
        assertEquals(DeleteResult.DONE, deletion.deleteBake(book))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `a book id that could escape the books folder is refused`() {
        val outside = File(temp.root, "keep.txt").apply { writeText("x") }
        val bad = Book("..", "Bad", 1, 1, 0L)

        runCatching { BookDeletion(store, IdleStopper()).deleteAll(bad) }

        assertTrue(outside.isFile)
        assertTrue(temp.root.isDirectory)
    }

    @Test
    fun `nothing is deleted when a running bake does not stop`() {
        val book = newBook()

        assertEquals(DeleteResult.STILL_BAKING, BookDeletion(store, IdleStopper(stops = false)).deleteAll(book))

        assertTrue(store.audioFolder(book.id).isDirectory)
        assertEquals(listOf(book.id), store.list().map { it.id })
    }

    /** A baker that runs until it is cancelled, then writes one last clip before it ends, as a bake that is slow to notice would. */
    private class SlowToStopBaker : BakeService {
        val started = CountDownLatch(1)
        val ended = AtomicBoolean(false)
        override val available = true

        override fun isModelDownloaded(quality: VoiceQuality) = true

        override fun downloadSizeBytes(quality: VoiceQuality) = 0L

        override fun downloadSizeMegabytes(quality: VoiceQuality) = 0

        override fun downloadModel(
            quality: VoiceQuality,
            cancellation: CancellationToken,
            onProgress: (Long, Long) -> Unit,
        ) = Unit

        override fun bake(
            utterances: List<Utterance>,
            folder: File,
            voice: VoiceChoice,
            cancellation: CancellationToken,
            onProgress: (Int, Int) -> Unit,
        ) {
            folder.mkdirs()
            started.countDown()
            while (!cancellation.isCancelled) Thread.sleep(5)
            Thread.sleep(150)
            File(folder, "late.wav").writeText("late")
            ended.set(true)
            throw BakeCancelledException()
        }

        override fun readManifest(folder: File): BakeManifest? = null
    }

    @Test
    fun `a bake in progress is cancelled and finished before the files are deleted`() {
        val book = newBook()
        val baker = SlowToStopBaker()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val utterances = listOf(Utterance(0, 0, "One short sentence."))
            val controller =
                BookBakeController(
                    utterances,
                    store.audioFolder(book.id),
                    baker,
                    executor,
                    freeSpace = { Long.MAX_VALUE },
                    choiceFile = store.voiceFile(book.id),
                )
            val center =
                object : BakeStopper {
                    override fun stopAndWait(bookId: String) = controller.cancelAndWait(10_000)

                    override fun bakeDeleted(bookId: String) = controller.resetAfterDelete()

                    override fun forget(bookId: String) = Unit
                }
            controller.start(VoiceChoice("af_bella", VoiceQuality.LITE))
            assertTrue(baker.started.await(5, TimeUnit.SECONDS))

            val result = BookDeletion(store, center).deleteBake(book)

            assertEquals(DeleteResult.DONE, result)
            assertTrue(baker.ended.get(), "the bake must have ended before the delete returned")
            assertFalse(store.audioFolder(book.id).exists(), "the late clip must not survive")
            assertFalse(store.voiceFile(book.id).exists())
            assertNull(controller.savedChoice)
            assertEquals(BakeState.NotBaked(0, 1, null), controller.state)

            // Deleting everything while another bake runs works the same way.
            controller.start(VoiceChoice("af_bella", VoiceQuality.LITE))
            assertTrue(baker.started.await(5, TimeUnit.SECONDS))
            assertEquals(DeleteResult.DONE, BookDeletion(store, center).deleteAll(book))
            assertFalse(File(books, book.id).exists())
        } finally {
            executor.shutdownNow()
        }
    }
}
