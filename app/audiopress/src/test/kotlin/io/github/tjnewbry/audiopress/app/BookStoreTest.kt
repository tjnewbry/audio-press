package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.SegmentKind
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BookStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000L
    private var counter = 0
    private lateinit var root: File
    private lateinit var store: BookStore

    @Before
    fun setUp() {
        root = File(folder.root, "books")
        store = BookStore(root, clock = { now }, newId = { "id${counter++}" })
    }

    private val paragraphs =
        listOf(
            StoredParagraph(SegmentKind.HEADING, 1, "Chapter One"),
            StoredParagraph(SegmentKind.PARAGRAPH, 1, "It was a quiet night."),
            StoredParagraph(SegmentKind.PARAGRAPH, 2, "Tabs\tand\nnewlines \\ backslashes, café, 日本語."),
        )

    @Test
    fun `a saved book is listed with its metadata and loads back unchanged`() {
        val book = store.save("My Book", pageCount = 12, wordCount = 99, paragraphs = paragraphs)

        assertEquals(Book("id0", "My Book", 12, 99, 1_000L), book)
        assertEquals(listOf(book), store.list())
        assertEquals(paragraphs, store.load(book.id))
    }

    @Test
    fun `a title with special characters survives`() {
        val book = store.save("Ünïcode: a=b #1\\2", 1, 1, paragraphs)

        assertEquals("Ünïcode: a=b #1\\2", store.list().single().title)
        assertEquals(book, store.list().single())
    }

    @Test
    fun `books are listed newest first`() {
        store.save("old", 1, 1, paragraphs)
        now = 5_000L
        store.save("new", 1, 1, paragraphs)

        assertEquals(listOf("new", "old"), store.list().map { it.title })
    }

    @Test
    fun `an empty store lists nothing`() {
        assertEquals(emptyList(), store.list())
    }

    @Test
    fun `a damaged book is skipped and leftovers of an interrupted save are removed`() {
        store.save("good", 1, 1, paragraphs)
        File(root, "broken").mkdirs()
        File(root, "broken/book.properties").writeText("title=No numbers here")
        File(root, "half.tmp").mkdirs()

        assertEquals(listOf("good"), store.list().map { it.title })
        assertTrue(!File(root, "half.tmp").exists())
    }

    @Test
    fun `loading a missing book fails with an IOException, and a path-like id is refused`() {
        assertFailsWith<IOException> { store.load("nothing") }
        assertFailsWith<IllegalArgumentException> { store.load("../outside") }
    }

    @Test
    fun `a damaged transcript is reported rather than returning partial text`() {
        val book = store.save("book", 1, 1, paragraphs)
        File(root, "${book.id}/transcript.txt").writeText("NOT_A_KIND\t1\tx\n")

        assertFailsWith<IOException> { store.load(book.id) }
    }
}
