package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.CancellationToken
import io.github.tjnewbry.audiopress.core.ConversionCancelledException
import io.github.tjnewbry.audiopress.core.CorruptPdfException
import io.github.tjnewbry.audiopress.core.ExtractionNotPermittedException
import io.github.tjnewbry.audiopress.core.InputNotFoundException
import io.github.tjnewbry.audiopress.core.InputUnreadableException
import io.github.tjnewbry.audiopress.core.NoTextLayerException
import io.github.tjnewbry.audiopress.core.NotAPdfException
import io.github.tjnewbry.audiopress.core.PdfEncryptedException
import io.github.tjnewbry.audiopress.core.SourcePage
import io.github.tjnewbry.audiopress.core.TextSource
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BookImporterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = BookStore(File(folder.root, "books"))

    /** A book whose pages have no text, like a scanned PDF. */
    private class ScannedSource : TextSource {
        override val pageCount = 3

        override fun readPage(pageIndex: Int) = SourcePage(pageIndex, 612f, 792f, emptyList())

        override fun close() = Unit
    }

    /** Wraps a source and records when it is closed; a closed source refuses further reads, like a closed PDF. */
    private class ClosingSource(
        private val inner: TextSource,
    ) : TextSource {
        var closes = 0
        var readAfterClose = false

        override val pageCount get() = inner.pageCount

        override fun readPage(pageIndex: Int): SourcePage {
            if (closes > 0) readAfterClose = true
            return inner.readPage(pageIndex)
        }

        override fun close() {
            closes++
        }
    }

    @Test
    fun `the source is closed as soon as analysis starts, not read after that, and closing again is harmless`() {
        val source = ClosingSource(SampleTextSource())
        var closedBeforeLastProgress = false

        val book =
            BookImporter(store()).import(source, "Lighthouse") { fraction ->
                if (fraction >=
                    1f
                ) {
                    closedBeforeLastProgress = source.closes > 0
                }
            }
        source.close() // the caller's use {} closes it again

        assertEquals(2, source.closes)
        assertTrue(closedBeforeLastProgress, "closed before the conversion finished")
        assertEquals(false, source.readAfterClose)
        assertEquals(2, book.pageCount) // the page count was taken before the source was closed
    }

    @Test
    fun `importing the sample book stores its paragraphs with title, pages, and word count`() {
        val store = store()

        val book = SampleTextSource().use { BookImporter(store).import(it, "Lighthouse") }

        assertEquals("Lighthouse", book.title)
        assertEquals(2, book.pageCount)
        assertEquals(listOf(book), store.list())
        val stored = store.load(book.id)
        assertEquals(SAMPLE_PAGES.flatMap { listOfNotNull(it.heading) + it.paragraphs }, stored.map { it.text })
        assertEquals(stored.sumOf { p -> p.text.split(' ').size }, book.wordCount)
    }

    @Test
    fun `a scanned book is not stored and reports no text layer`() {
        val store = store()

        assertFailsWith<NoTextLayerException> { ScannedSource().use { BookImporter(store).import(it, "scan") } }
        assertEquals(emptyList(), store.list())
    }

    @Test
    fun `progress only goes up and ends at one`() {
        val reports = mutableListOf<Float>()

        SampleTextSource().use { BookImporter(store()).import(it, "Lighthouse") { fraction -> reports += fraction } }

        assertTrue(reports.size > 2)
        assertEquals(reports.sorted(), reports)
        assertEquals(1f, reports.last())
    }

    @Test
    fun `cancelling stops the import quietly and stores nothing`() {
        val store = store()
        val token = CancellationToken()

        assertFailsWith<ConversionCancelledException> {
            SampleTextSource().use { BookImporter(store).import(it, "Lighthouse", token) { token.cancel() } }
        }
        assertEquals(emptyList(), store.list())
        assertEquals("Import cancelled.", importErrorMessage("x.pdf", ConversionCancelledException()))
    }

    @Test
    fun `running out of storage is reported as such, wherever in the chain it is found`() {
        val plain = importErrorMessage("big.pdf", java.io.IOException("write failed: ENOSPC (No space left on device)"))
        val wrapped = importErrorMessage("big.pdf", InputUnreadableException("big.pdf", java.io.IOException("No space left on device")))

        assertTrue("not enough free storage" in plain && "big.pdf" in plain)
        assertEquals(plain, wrapped)
        assertTrue(isOutOfSpace(RuntimeException("x", java.io.IOException("ENOSPC"))))
        assertTrue(!isOutOfSpace(java.io.IOException("Permission denied")))
    }

    @Test
    fun `a book that cannot be written leaves nothing in the library`() {
        val blocker = File(folder.root, "books").apply { writeText("a file, so no folder can be made here") }
        val store = BookStore(blocker)

        assertFailsWith<java.io.IOException> { SampleTextSource().use { BookImporter(store).import(it, "Lighthouse") } }
        assertEquals(emptyList(), store.list())
        assertEquals(listOf("books"), folder.root.list()!!.toList())
    }

    @Test
    fun `titles come from the file name without the pdf extension`() {
        assertEquals("My Book", titleFromFileName("My Book.pdf"))
        assertEquals("My Book", titleFromFileName("My Book.PDF"))
        assertEquals("notes.txt", titleFromFileName("notes.txt"))
        assertEquals("Untitled", titleFromFileName(".pdf"))
        assertEquals("Untitled", titleFromFileName(null))
    }

    @Test
    fun `each kind of failure has its own clear message that names the file`() {
        val name = "book.pdf"
        val messages =
            listOf(
                importErrorMessage(name, NoTextLayerException(name)) to "scanned",
                importErrorMessage(name, PdfEncryptedException(name)) to "password",
                importErrorMessage(name, CorruptPdfException(name)) to "damaged",
                importErrorMessage(name, NotAPdfException(name)) to "not a PDF",
                importErrorMessage(name, EmptyBookException()) to "no readable text",
                importErrorMessage(name, ExtractionNotPermittedException(name)) to "copied",
                importErrorMessage(name, InputNotFoundException(name)) to "could not be opened",
                importErrorMessage(name, InputUnreadableException(name)) to "could not be opened",
                importErrorMessage(name, OutOfMemoryError()) to "too big",
                importErrorMessage(name, IllegalStateException("boom")) to "Something went wrong",
            )

        for ((message, expected) in messages) {
            assertTrue(expected in message, "\"$message\" should mention \"$expected\"")
            assertTrue(name in message || "Something went wrong" in message)
            assertTrue("Exception" !in message && "boom" !in message)
        }
        assertEquals(messages.size - 1, messages.map { it.first }.distinct().size) // two share the "could not be opened" text
        assertNotEquals(messages[0].first, messages[2].first)
    }
}
