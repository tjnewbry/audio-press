package io.github.tjnewbry.audiopress.pdfsource

import io.github.tjnewbry.audiopress.core.NotAPdfException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The file checks that run before PDFBox is touched, so they need no Android runtime. */
class HeaderCheckTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `accepts a file that starts with the PDF header`() {
        val file = folder.newFile("a.pdf").apply { writeText("%PDF-1.7\nrest of the file") }

        AndroidPdfTextSource.checkHeader(file, "a.pdf")
    }

    @Test
    fun `accepts a header preceded by a few bytes of junk`() {
        val file = folder.newFile("junk.pdf").apply { writeText("\r\n  junk before\n%PDF-1.4\n") }

        AndroidPdfTextSource.checkHeader(file, "junk.pdf")
    }

    @Test
    fun `rejects a file without the header, with the file name in the message`() {
        val file = folder.newFile("notes.pdf").apply { writeText("just some text") }

        val error = assertFailsWith<NotAPdfException> { AndroidPdfTextSource.checkHeader(file, "notes.pdf") }

        assertEquals("notes.pdf: not a PDF file", error.message)
    }

    @Test
    fun `rejects an empty file`() {
        val file = folder.newFile("empty.pdf")

        assertFailsWith<NotAPdfException> { AndroidPdfTextSource.checkHeader(file, "empty.pdf") }
    }

    @Test
    fun `rejects a header that appears only after the first kilobyte`() {
        val file = folder.newFile("late.pdf").apply { writeText("x".repeat(2000) + "%PDF-1.7") }

        assertFailsWith<NotAPdfException> { AndroidPdfTextSource.checkHeader(file, "late.pdf") }
    }
}
