package io.github.tjnewbry.audiopress.pdfbox

import io.github.tjnewbry.audiopress.core.ConversionOptions
import io.github.tjnewbry.audiopress.core.Converter
import io.github.tjnewbry.audiopress.core.CorruptPdfException
import io.github.tjnewbry.audiopress.core.InputNotFoundException
import io.github.tjnewbry.audiopress.core.InvalidPageRangeException
import io.github.tjnewbry.audiopress.core.NotAPdfException
import io.github.tjnewbry.audiopress.core.PdfEncryptedException
import io.github.tjnewbry.audiopress.testing.FixturePage
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
    fun `keeps letter-spaced words whole where PDFBox would break them after narrow letters`() {
        val pdf =
            fixturePdf(dir.resolve("tracked.pdf")) {
                page { trackedText("Questions about little details still follow", x = 72f, y = 100f, size = 10f, tracking = 1f) }
            }

        val words = PdfBoxTextSource.open(pdf).use { it.readPage(1).words }

        assertEquals(listOf("Questions", "about", "little", "details", "still", "follow"), words.map { it.text })
        assertTrue(words.none { it.joinsPrevious })
    }

    @Test
    fun `reads spaced capitals as whole words, not single letters`() {
        val pdf =
            fixturePdf(dir.resolve("spaced-caps.pdf")) {
                page { trackedText("THE LIGHTHOUSE KEEPER", x = 72f, y = 100f, size = 12f, tracking = 2f) }
            }

        val words = PdfBoxTextSource.open(pdf).use { it.readPage(1).words }

        assertEquals(listOf("THE", "LIGHTHOUSE", "KEEPER"), words.map { it.text })
        assertEquals(72f, words.first().box.left, TOLERANCE)
    }

    @Test
    fun `keeps letter-spaced words apart when the PDF has no space glyph between them`() {
        val size = 10f
        val tracking = 1f
        val space = FixturePage.FONT.getStringWidth(" ") / 1000f * size
        val pdf =
            fixturePdf(dir.resolve("tracked-words.pdf")) {
                page {
                    var x = 72f
                    for (word in listOf("quiet", "little", "lights")) {
                        trackedText(word, x = x, y = 100f, size = size, tracking = tracking)
                        x += FixturePage.FONT.getStringWidth(word) / 1000f * size + word.length * tracking + space
                    }
                }
            }

        val words = PdfBoxTextSource.open(pdf).use { it.readPage(1).words }

        assertEquals(listOf("quiet", "little", "lights"), words.map { it.text })
    }

    @Test
    fun `a formula set with even gaps around its symbols is not joined into one word`() {
        val pdf =
            fixturePdf(dir.resolve("formula.pdf")) {
                page {
                    var x = 72f
                    for (glyph in listOf("2", "y", "=", "x", "+", "1", "2")) {
                        val end = text(glyph, x = x, y = 100f, size = 10f)
                        x = if ((glyph == "2" && x == 72f) || glyph == "1") end else end + 2.5f
                    }
                }
            }

        val words = PdfBoxTextSource.open(pdf).use { it.readPage(1).words }

        assertEquals(listOf("2y", "=", "x", "+", "12"), words.map { it.text })
    }

    @Test
    fun `single letters at uneven gaps are not taken for letter-spaced text`() {
        val pdf =
            fixturePdf(dir.resolve("uneven.pdf")) {
                page {
                    var x = 72f
                    for ((letter, gap) in listOf("a" to 1.5f, "b" to 2.8f, "c" to 2f, "d" to 3.3f, "e" to 0f)) {
                        x = text(letter, x = x, y = 100f, size = 10f) + gap
                    }
                }
            }

        val words = PdfBoxTextSource.open(pdf).use { it.readPage(1).words }

        assertEquals(listOf("a", "b", "c", "d", "e"), words.map { it.text })
    }

    @Test
    fun `an invisible control-character glyph on a letter does not split the word`() {
        val pdf =
            fixturePdf(dir.resolve("control-mark.pdf")) {
                page {
                    trackedText("Harbor lights shone", x = 72f, y = 100f, size = 10f, tracking = 0.6f)
                    controlMark(x = 72f, y = 100f, size = 10f)
                    // A kerned pair: the second letter starts a little before the first one ends.
                    trackedText("P", x = 72f, y = 120f, size = 10f, tracking = 0.6f)
                    controlMark(x = 72f, y = 120f, size = 10f)
                    val kerned = 72f + FixturePage.FONT.getStringWidth("P") / 1000f * 10f - 0.1f
                    trackedText("ottery was made", x = kerned, y = 120f, size = 10f, tracking = 0.6f)
                }
            }

        val words = PdfBoxTextSource.open(pdf).use { it.readPage(1).words }

        assertEquals(listOf("Harbor", "lights", "shone", "Pottery", "was", "made"), words.map { it.text })
    }

    @Test
    fun `leaves words of ordinary text apart even when the gap between them is small`() {
        val pdf =
            fixturePdf(dir.resolve("close.pdf")) {
                page {
                    val end = text("still in", x = 72f, y = 100f, size = 10f)
                    text("it", x = end + 1f, y = 100f, size = 10f)
                }
            }

        val words = PdfBoxTextSource.open(pdf).use { it.readPage(1).words }

        assertEquals(listOf("still", "in", "it"), words.map { it.text })
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

    /** A book of [pages] generated pages; page n has its own rows of text, plus [extra] lines (page to text) at a given row. */
    private fun book(
        name: String,
        pages: Int,
        headerPages: Set<Int> = emptySet(),
        extra: Map<Int, String> = emptyMap(),
    ): Path =
        fixturePdf(dir.resolve(name)) {
            for (n in 1..pages) {
                page {
                    if (n in headerPages) text("Chapter Heading Words", x = 150f, y = 40f, size = 9f)
                    for (row in 0 until 20) {
                        text("Page $n row $row holds a plain sentence of ordinary body text.", x = 72f, y = 80f + row * 13.5f, size = 10f)
                    }
                    extra[n]?.let { text(it, x = 72f, y = 400f, size = 10f) }
                }
            }
        }

    private fun spoken(result: io.github.tjnewbry.audiopress.core.ConversionResult) =
        result.transcript.segments.joinToString(" ") { s -> s.tokens.joinToString(" ") { it.text } }

    @Test
    fun `a page range offers only those pages and keeps the PDF's own page numbers`() {
        val pdf = book("range.pdf", pages = 8)

        PdfBoxTextSource.open(pdf, pages = 3..5).use { source ->
            assertEquals(8, source.totalPageCount)
            assertEquals(3, source.pageCount)
            assertEquals(3, source.firstPage)
            assertEquals(3..5, source.pages)
            assertEquals(3, source.readPage(1).index)
            assertEquals(5, source.readPage(3).index)
            assertEquals(
                "Page",
                source
                    .readPage(2)
                    .words
                    .first()
                    .text,
            )
            assertEquals("4", source.readPage(2).words[1].text)
            assertFailsWith<IllegalArgumentException> { source.readPage(4) }
        }
    }

    @Test
    fun `a conversion of a page range reports the PDF's own page numbers and none from outside`() {
        val pdf = book("range-convert.pdf", pages = 8)

        val result = PdfBoxTextSource.open(pdf, pages = 4..6).use { Converter().convert(it, "range-convert.pdf", ConversionOptions()) }

        val pages =
            result.transcript.segments
                .flatMap { s -> s.tokens.map { it.page } }
                .toSet()
        assertEquals(setOf(4, 5, 6), pages)
        assertEquals(3 * 20 * 12, result.transcript.wordCount)
        assertEquals(listOf(4, 5, 6), result.document.pages.map { it.index })
    }

    @Test
    fun `no range gives the same transcript as the whole page range`() {
        val pdf = book("whole.pdf", pages = 6, headerPages = setOf(1, 2, 3, 4, 5, 6))

        val none =
            PdfBoxTextSource.open(pdf).use { source ->
                Converter().convert(source, "w.pdf", ConversionOptions()).also {
                    assertEquals(1, source.firstPage)
                }
            }
        val all = PdfBoxTextSource.open(pdf, pages = 1..6).use { Converter().convert(it, "w.pdf", ConversionOptions()) }

        assertEquals(none.transcript, all.transcript)
        assertEquals(none.removals, all.removals)
    }

    @Test
    fun `a range outside the PDF or backwards is refused with a clear message`() {
        val pdf = book("invalid.pdf", pages = 5)

        assertEquals(
            "The first page is 0, but pages start at 1",
            assertFailsWith<InvalidPageRangeException> {
                PdfBoxTextSource.open(
                    pdf,
                    pages =
                        0..3,
                )
            }.message,
        )
        assertEquals(
            "The last page is 6, but the PDF has only 5 pages",
            assertFailsWith<InvalidPageRangeException> {
                PdfBoxTextSource.open(
                    pdf,
                    pages =
                        2..6,
                )
            }.message,
        )
        assertEquals(
            "The first page (4) is after the last page (2)",
            assertFailsWith<InvalidPageRangeException> {
                PdfBoxTextSource.open(
                    pdf,
                    pages =
                        4..2,
                )
            }.message,
        )
        PdfBoxTextSource.open(pdf, pages = 5..5).use { assertEquals(1, it.pageCount) }
    }

    @Test
    fun `pages outside the range do not make a header inside it look repeated`() {
        // The heading is on pages 3 and 4 only. Read whole, both are removed as repeats; read from page 4, it is not repeated.
        val pdf = book("headers.pdf", pages = 6, headerPages = setOf(3, 4))

        val whole = PdfBoxTextSource.open(pdf).use { spoken(Converter().convert(it, "h.pdf", ConversionOptions())) }
        val range = PdfBoxTextSource.open(pdf, pages = 4..6).use { spoken(Converter().convert(it, "h.pdf", ConversionOptions())) }

        assertFalse("Chapter Heading Words" in whole)
        assertTrue("Chapter Heading Words" in range)
    }

    @Test
    fun `the front and back of a page range, not of the PDF, decide where copyright pages are skipped`() {
        // Page 30 mentions an ISBN. In the whole 40-page PDF it is mid-book, and in the range 11..40 it is mid-range too.
        // Counting the range as pages 1..30 but comparing the PDF's own numbers would wrongly treat it as the back.
        val pdf = book("copyright.pdf", pages = 40, extra = mapOf(30 to "ISBN 000 mid book", 38 to "ISBN 111 back", 12 to "ISBN 222 front"))

        val text = PdfBoxTextSource.open(pdf, pages = 11..40).use { spoken(Converter().convert(it, "c.pdf", ConversionOptions())) }

        assertTrue("ISBN 000 mid book" in text)
        assertFalse("ISBN 111 back" in text)
        assertFalse("ISBN 222 front" in text)
    }

    private companion object {
        const val TOLERANCE = 0.5f
    }
}
