package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.SegmentKind
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BookInfoTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val paragraphs = listOf(StoredParagraph(SegmentKind.PARAGRAPH, 1, "text"))

    private fun store() = BookStore(File(temp.root, "books"), newId = sequenceOf("a", "b", "c").iterator()::next)

    @Test
    fun `sizes use decimal units, whole megabytes and one decimal for gigabytes`() {
        assertEquals("0 KB", formatInfoSize(0))
        assertEquals("640 KB", formatInfoSize(640_000))
        assertEquals("999 KB", formatInfoSize(999_700)) // never reads "1000 KB"
        assertEquals("1 MB", formatInfoSize(1_000_000))
        assertEquals("83 MB", formatInfoSize(83_400_000))
        assertEquals("100 MB", formatInfoSize(100_000_000))
        assertEquals("999 MB", formatInfoSize(999_000_000))
        assertEquals("1.0 GB", formatInfoSize(999_600_000))
        assertEquals("1.2 GB", formatInfoSize(1_234_000_000))
    }

    @Test
    fun `a ranged import shows its source, range and sizes`() {
        val book = Book("a", "Title (pp. 11-11)", 1, 10, 0, BookSource("poo-poo.pdf", 431, 11, 11))

        assertEquals(
            listOf(
                "Source file: poo-poo.pdf",
                "Source length: 431 pages",
                "Imported range: 11-11",
                "Bake data: 83 MB",
                "Total: 100 MB",
            ),
            bookInfoLines(book, BookSizes(83_000_000, 100_000_000)),
        )
    }

    @Test
    fun `an old book says Unknown and uses its own page count, and no bake reads none`() {
        val old = Book("a", "Old", 431, 10, 0)

        assertEquals(
            listOf("Source file: Unknown", "Source length: 431 pages", "Imported range: 1-431", "Bake data: none", "Total: 640 KB"),
            bookInfoLines(old, BookSizes(null, 640_000)),
        )
        assertEquals("Source length: 1 page", bookInfoLines(Book("a", "One", 1, 1, 0), BookSizes(null, 0))[1])
    }

    @Test
    fun `the source is saved, read back, and kept by a rename`() {
        val store = store()
        val source = BookSource("My book (final).pdf", 431, 11, 20)
        val book = store.save("T", 10, 5, paragraphs, source)

        assertEquals(source, store.list().single().source)
        store.rename(book.id, "New title")
        val renamed = store.list().single()
        assertEquals("New title", renamed.title)
        assertEquals(source, renamed.source)
    }

    @Test
    fun `missing or damaged source fields never hide a book`() {
        val store = store()
        val book = store.save("T", 10, 5, paragraphs, BookSource("a.pdf", 50, 1, 10))
        val file = File(temp.root, "books/${book.id}/book.properties")

        file.writeText(file.readLines().filterNot { it.startsWith("sourceFirst") }.joinToString("\n"))
        assertNull(store.list().single().source)
        file.writeText(file.readLines().joinToString("\n") + "\nsourceFirst=abc\n")
        val damaged = store.list().single()
        assertEquals("T", damaged.title)
        assertNull(damaged.source)
        file.writeText(
            file.readLines().filterNot { it.startsWith("source") }.joinToString("\n") +
                "\nsourceFile=a.pdf\nsourcePages=5\nsourceFirst=3\nsourceLast=9\n",
        )
        assertNull(store.list().single().source) // a range outside the PDF
    }

    @Test
    fun `bake data counts the bake folder and the saved voice, and is null with neither`() {
        val store = store()
        val book = store.save("T", 1, 1, paragraphs)
        assertNull(store.bakeBytes(book.id))

        store
            .audioFolder(book.id)
            .apply { mkdirs() }
            .resolve("clip.wav")
            .writeBytes(ByteArray(1000))
        store.voiceFile(book.id).writeText("voice")
        assertEquals(1005L, store.bakeBytes(book.id))
        assertNotNull(store.footprintBytes(book.id))
        assert(store.footprintBytes(book.id) >= 1005L)
    }

    @Test
    fun `the importer records the source it is given`() {
        val record = BookSource("lighthouse.pdf", 2, 1, 2)
        val book = SampleTextSource().use { BookImporter(store()).import(it, "Lighthouse", record = record) }

        assertEquals(record, book.source)
    }
}
