package io.github.tjnewbry.audiopress.pdfbox

import io.github.tjnewbry.audiopress.core.ConversionOptions
import io.github.tjnewbry.audiopress.core.Converter
import io.github.tjnewbry.audiopress.core.CorruptPdfException
import io.github.tjnewbry.audiopress.core.InputNotFoundException
import io.github.tjnewbry.audiopress.core.NotAPdfException
import io.github.tjnewbry.audiopress.core.PdfEncryptedException
import io.github.tjnewbry.audiopress.testing.FixturePdf
import io.github.tjnewbry.audiopress.testing.fixturePdf
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfBoxTextSourceTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `reads words with their positions and font size`() {
        val pdf = fixturePdf(dir.resolve("words.pdf")) { page { text("Hello world", x = 72f, y = 100f, size = 12f) } }

        PdfBoxTextSource.open(pdf).use { source ->
            assertEquals(1, source.pageCount)
            val page = source.readPage(1)
            assertEquals(432f, page.width)
            assertEquals(648f, page.height)
            assertEquals(listOf("Hello", "world"), page.words.map { it.text })
            val hello = page.words.first()
            assertEquals(72f, hello.box.left, TOLERANCE)
            assertEquals(100f, hello.baseline, TOLERANCE)
            assertEquals(12f, hello.fontSize, TOLERANCE)
            assertTrue(hello.horizontal)
            assertFalse(page.words[1].joinsPrevious)
        }
    }

    @Test
    fun `splits a raised footnote marker into its own word that joins the previous one`() {
        val pdf =
            fixturePdf(dir.resolve("marker.pdf")) {
                page {
                    val end = text("summer.", x = 72f, y = 100f, size = 12f)
                    text("3", x = end, y = 100f, size = 7f, rise = 4f)
                }
            }

        val words = PdfBoxTextSource.open(pdf).use { it.readPage(1).words }

        assertEquals(listOf("summer.", "3"), words.map { it.text })
        val marker = words[1]
        assertTrue(marker.joinsPrevious)
        assertEquals(7f, marker.fontSize, TOLERANCE)
        assertEquals(96f, marker.baseline, TOLERANCE)
    }

    @Test
    fun `does not split small caps that share a baseline`() {
        val pdf =
            fixturePdf(dir.resolve("smallcaps.pdf")) {
                page {
                    val end = text("C", x = 72f, y = 100f, size = 12f)
                    text("HAPTER", x = end, y = 100f, size = 9f)
                }
            }

        val words = PdfBoxTextSource.open(pdf).use { it.readPage(1).words }

        assertEquals(listOf("CHAPTER"), words.map { it.text })
    }

    @Test
    fun `marks rotated text as not horizontal`() {
        val pdf =
            fixturePdf(dir.resolve("rotated.pdf")) {
                page {
                    text("Body", x = 72f, y = 100f)
                    rotatedText("Downloaded", x = 20f, y = 500f)
                }
            }

        val words = PdfBoxTextSource.open(pdf).use { it.readPage(1).words }

        assertEquals(true, words.single { it.text == "Body" }.horizontal)
        assertEquals(false, words.single { it.text == "Downloaded" }.horizontal)
    }

    @Test
    fun `a page without text has no words`() {
        val pdf = fixturePdf(dir.resolve("blank.pdf")) { page() }

        val page = PdfBoxTextSource.open(pdf).use { it.readPage(1) }

        assertFalse(page.hasText)
    }

    @Test
    fun `a missing file is reported as not found`() {
        assertFailsWith<InputNotFoundException> { PdfBoxTextSource.open(dir.resolve("missing.pdf")) }
    }

    @Test
    fun `a file that is not a PDF is rejected`() {
        val file = Files.writeString(dir.resolve("notes.pdf"), "just some text")

        assertFailsWith<NotAPdfException> { PdfBoxTextSource.open(file) }
    }

    @Test
    fun `a damaged PDF is rejected`() {
        val file = Files.writeString(dir.resolve("damaged.pdf"), "%PDF-1.7\nthis is not really a PDF\n")

        assertFailsWith<CorruptPdfException> { PdfBoxTextSource.open(file) }
    }

    @Test
    fun `a PDF that needs a password is rejected`() {
        val pdf =
            FixturePdf().apply { page { text("Secret", x = 72f, y = 100f) } }.save(dir.resolve("locked.pdf")) { document ->
                document.protect(StandardProtectionPolicy("owner", "user", AccessPermission()).apply { encryptionKeyLength = 128 })
            }

        assertFailsWith<PdfEncryptedException> { PdfBoxTextSource.open(pdf) }
    }

    @Test
    fun `the title comes from the document information, trimmed`() {
        val pdf =
            FixturePdf().apply { page { text("Body", x = 72f, y = 100f) } }.save(dir.resolve("titled.pdf")) {
                it.documentInformation.title =
                    "  A Titled Book "
            }

        assertEquals("A Titled Book", PdfBoxTextSource.open(pdf).use { it.title })
    }

    @Test
    fun `a PDF without a title, or with a junk one, has a null title`() {
        val none = fixturePdf(dir.resolve("none.pdf")) { page { text("Body", x = 72f, y = 100f) } }
        val junk =
            FixturePdf().apply { page { text("Body", x = 72f, y = 100f) } }.save(dir.resolve("junk.pdf")) {
                it.documentInformation.title =
                    "Microsoft Word - draft.docx"
            }

        assertEquals(null, PdfBoxTextSource.open(none).use { it.title })
        assertEquals(null, PdfBoxTextSource.open(junk).use { it.title })
    }

    @Test
    fun `the title falls back to XMP metadata`() {
        val xmp = "<x:xmpmeta><dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">From The XMP</rdf:li></rdf:Alt></dc:title></x:xmpmeta>"
        val pdf =
            FixturePdf().apply { page { text("Body", x = 72f, y = 100f) } }.save(dir.resolve("xmp.pdf")) { document ->
                document.documentCatalog.metadata = PDMetadata(document, xmp.byteInputStream())
            }

        assertEquals("From The XMP", PdfBoxTextSource.open(pdf).use { it.title })
    }

    @Test
    fun `reads the displayed font size when the page scales its coordinates`() {
        val pdf = fixturePdf(dir.resolve("scaled.pdf")) { page { scaledText("Hello world", x = 72f, y = 100f, size = 12f) } }

        val page = PdfBoxTextSource.open(pdf).use { it.readPage(1) }

        assertEquals(listOf("Hello", "world"), page.words.map { it.text })
        assertEquals(12f, page.words.first().fontSize, TOLERANCE)
        assertEquals(100f, page.words.first().baseline, TOLERANCE)
        assertEquals(
            72f,
            page.words
                .first()
                .box.left,
            TOLERANCE,
        )
    }

    @Test
    fun `lines of text drawn through a scaled coordinate system stay separate lines in reading order`() {
        val pdf =
            fixturePdf(dir.resolve("scaled-lines.pdf")) {
                page {
                    scaledText("The first line is here.", x = 72f, y = 100f, size = 12f)
                    scaledText("Then comes the second.", x = 72f, y = 114f, size = 12f)
                    scaledText("And the third at last.", x = 72f, y = 128f, size = 12f)
                }
            }

        val result = PdfBoxTextSource.open(pdf).use { Converter().convert(it, "scaled-lines.pdf", ConversionOptions()) }

        val spoken = result.transcript.segments.joinToString(" ") { s -> s.tokens.joinToString(" ") { it.text } }
        assertEquals("The first line is here. Then comes the second. And the third at last.", spoken)
    }

    @Test
    fun `a size set by the text matrix is still read as before`() {
        val pdf = fixturePdf(dir.resolve("plain.pdf")) { page { text("Plain text", x = 72f, y = 100f, size = 10.5f) } }

        val page = PdfBoxTextSource.open(pdf).use { it.readPage(1) }

        assertEquals(10f, page.words.first().fontSize, 0.01f, "PDFBox reports whole points, and that is kept")
    }

    private companion object {
        const val TOLERANCE = 0.5f
    }
}
