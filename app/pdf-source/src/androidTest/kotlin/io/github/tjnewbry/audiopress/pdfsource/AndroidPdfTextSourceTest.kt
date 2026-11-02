package io.github.tjnewbry.audiopress.pdfsource

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import io.github.tjnewbry.audiopress.core.ConversionOptions
import io.github.tjnewbry.audiopress.core.Converter
import io.github.tjnewbry.audiopress.core.CorruptPdfException
import io.github.tjnewbry.audiopress.core.InputNotFoundException
import io.github.tjnewbry.audiopress.core.InvalidPageRangeException
import io.github.tjnewbry.audiopress.core.NotAPdfException
import io.github.tjnewbry.audiopress.core.PdfEncryptedException
import io.github.tjnewbry.audiopress.core.RuleId
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs generated PDFs through the Android source on a device. The first five tests repeat the engine's
 * desktop `PdfBoxTextSourceTest` cases with the same expected values, so the PDFBox 2.0 port is checked
 * against the desktop PDFBox 3.0 adapter.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPdfTextSourceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        AndroidPdfTextSource.clearWorkFiles(context)
    }

    private fun pdf(
        name: String,
        block: FixturePdf.() -> Unit,
    ): File = FixturePdf().apply(block).save(folder.newFile(name))

    @Test
    fun readsWordsWithTheirPositionsAndFontSize() {
        val file = pdf("words.pdf") { page { text("Hello world", x = 72f, y = 100f, size = 12f) } }

        AndroidPdfTextSource.open(context, file).use { source ->
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
    fun splitsARaisedFootnoteMarkerIntoItsOwnWordThatJoinsThePreviousOne() {
        val file =
            pdf("marker.pdf") {
                page {
                    val end = text("summer.", x = 72f, y = 100f, size = 12f)
                    text("3", x = end, y = 100f, size = 7f, rise = 4f)
                }
            }

        val words = AndroidPdfTextSource.open(context, file).use { it.readPage(1).words }

        assertEquals(listOf("summer.", "3"), words.map { it.text })
        val marker = words[1]
        assertTrue(marker.joinsPrevious)
        assertEquals(7f, marker.fontSize, TOLERANCE)
        assertEquals(96f, marker.baseline, TOLERANCE)
    }

    @Test
    fun doesNotSplitSmallCapsThatShareABaseline() {
        val file =
            pdf("smallcaps.pdf") {
                page {
                    val end = text("C", x = 72f, y = 100f, size = 12f)
                    text("HAPTER", x = end, y = 100f, size = 9f)
                }
            }

        val words = AndroidPdfTextSource.open(context, file).use { it.readPage(1).words }

        assertEquals(listOf("CHAPTER"), words.map { it.text })
    }

    @Test
    fun marksRotatedTextAsNotHorizontal() {
        val file =
            pdf("rotated.pdf") {
                page {
                    text("Body", x = 72f, y = 100f)
                    rotatedText("Downloaded", x = 20f, y = 500f)
                }
            }

        val words = AndroidPdfTextSource.open(context, file).use { it.readPage(1).words }

        assertEquals(true, words.single { it.text == "Body" }.horizontal)
        assertEquals(false, words.single { it.text == "Downloaded" }.horizontal)
    }

    @Test
    fun readsTheDisplayedFontSizeWhenThePageScalesItsCoordinates() {
        val file =
            pdf("scaled.pdf") {
                page {
                    scaledText("The first line is here.", x = 72f, y = 100f, size = 12f)
                    scaledText("Then comes the second.", x = 72f, y = 114f, size = 12f)
                    scaledText("And the third at last.", x = 72f, y = 128f, size = 12f)
                }
            }

        AndroidPdfTextSource.open(context, file).use { source ->
            val words = source.readPage(1).words
            assertEquals(12f, words.first().fontSize, TOLERANCE)
            assertEquals(100f, words.first().baseline, TOLERANCE)
            val spoken =
                Converter()
                    .convert(source, "scaled.pdf", ConversionOptions())
                    .transcript.segments
                    .joinToString(" ") { s -> s.tokens.joinToString(" ") { it.text } }
            assertEquals("The first line is here. Then comes the second. And the third at last.", spoken)
        }
    }

    @Test
    fun aPageWithoutTextHasNoWords() {
        val file = pdf("blank.pdf") { page() }

        val page = AndroidPdfTextSource.open(context, file).use { it.readPage(1) }

        assertFalse(page.hasText)
    }

    @Test
    fun aMissingFileIsReportedAsNotFound() {
        assertFailsWith<InputNotFoundException> { AndroidPdfTextSource.open(context, File(folder.root, "missing.pdf")) }
    }

    @Test
    fun aFileThatIsNotAPdfIsRejected() {
        val file = folder.newFile("notes.pdf").apply { writeText("just some text") }

        assertFailsWith<NotAPdfException> { AndroidPdfTextSource.open(context, file) }
    }

    @Test
    fun aDamagedPdfIsRejected() {
        val file = folder.newFile("damaged.pdf").apply { writeText("%PDF-1.7\nthis is not really a PDF\n") }

        assertFailsWith<CorruptPdfException> { AndroidPdfTextSource.open(context, file) }
    }

    @Test
    fun aPdfThatNeedsAPasswordIsRejected() {
        val file =
            FixturePdf().apply { page { text("Secret", x = 72f, y = 100f) } }.save(folder.newFile("locked.pdf")) { document ->
                document.protect(StandardProtectionPolicy("owner", "user", AccessPermission()).apply { encryptionKeyLength = 128 })
            }

        assertFailsWith<PdfEncryptedException> { AndroidPdfTextSource.open(context, file) }
    }

    @Test
    fun opensAContentUriThroughACopyThatIsDeletedOnClose() {
        val file = pdf("picked.pdf") { page { text("Picked file", x = 72f, y = 100f) } }
        val work = File(context.cacheDir, "pdf-source")

        AndroidPdfTextSource.open(context, Uri.fromFile(file), displayName = "picked.pdf").use { source ->
            assertEquals(listOf("Picked", "file"), source.readPage(1).words.map { it.text })
            assertEquals(1, work.listFiles { f -> f.name.endsWith(".pdf") }?.size)
        }

        assertEquals(0, work.listFiles { f -> f.name.endsWith(".pdf") }?.size ?: 0)
        assertTrue(file.exists(), "the original file is untouched")
    }

    @Test
    fun closingTwiceIsHarmless() {
        val file = pdf("twice.pdf") { page { text("Body", x = 72f, y = 100f) } }
        val source = AndroidPdfTextSource.open(context, Uri.fromFile(file), displayName = "twice.pdf")

        source.close()
        source.close()

        assertEquals(0, File(context.cacheDir, "pdf-source").listFiles { f -> f.name.endsWith(".pdf") }?.size ?: 0)
    }

    @Test
    fun aMissingContentUriIsReportedAsNotFound() {
        val missing = Uri.fromFile(File(folder.root, "gone.pdf"))

        val error = assertFailsWith<InputNotFoundException> { AndroidPdfTextSource.open(context, missing, "gone.pdf") }

        assertEquals("gone.pdf: file not found", error.message)
        assertEquals(0, File(context.cacheDir, "pdf-source").listFiles { f -> f.name.endsWith(".pdf") }?.size ?: 0)
    }

    @Test
    fun theEngineConvertsAMultiPageBookOnTheDevice() {
        val file =
            pdf("book.pdf") {
                repeat(3) { n ->
                    page {
                        text("The Test Book", x = 150f, y = 40f, size = 9f)
                        text("${n + 1}", x = 210f, y = 610f, size = 9f)
                        for (row in 0 until 20) {
                            text(
                                "Line $row of page ${n + 1} is a plain sentence of ordinary body text.",
                                x = 72f,
                                y = 100f + row * 14f,
                                size = 11f,
                            )
                        }
                    }
                }
            }

        val result =
            AndroidPdfTextSource.open(context, file).use { source ->
                Converter().convert(source, "book.pdf", ConversionOptions())
            }

        val spoken = result.transcript.segments.joinToString(" ") { s -> s.tokens.joinToString(" ") { it.text } }
        assertTrue(spoken.contains("Line 0 of page 1 is a plain sentence"), spoken.take(300))
        assertFalse(spoken.contains("Test Book"), "the running header is removed")
        assertTrue(result.transcript.wordCount > 3 * 20 * 10)
        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun theTitleComesFromTheDocumentInformationTrimmed() {
        val file =
            FixturePdf().apply { page { text("Body", x = 72f, y = 100f) } }.save(folder.newFile("titled.pdf")) {
                it.documentInformation.title =
                    "  A Titled Book "
            }

        assertEquals("A Titled Book", AndroidPdfTextSource.open(context, file).use { it.title })
    }

    @Test
    fun aPdfWithoutATitleOrWithAJunkOneHasANullTitle() {
        val none = pdf("none.pdf") { page { text("Body", x = 72f, y = 100f) } }
        val junk =
            FixturePdf().apply { page { text("Body", x = 72f, y = 100f) } }.save(folder.newFile("junk.pdf")) {
                it.documentInformation.title =
                    "Microsoft Word - draft.docx"
            }

        assertEquals(null, AndroidPdfTextSource.open(context, none).use { it.title })
        assertEquals(null, AndroidPdfTextSource.open(context, junk).use { it.title })
    }

    @Test
    fun theTitleFallsBackToXmpMetadata() {
        val xmp = "<x:xmpmeta><dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">From The XMP</rdf:li></rdf:Alt></dc:title></x:xmpmeta>"
        val file =
            FixturePdf().apply { page { text("Body", x = 72f, y = 100f) } }.save(folder.newFile("xmp.pdf")) { document ->
                document.documentCatalog.metadata = PDMetadata(document, xmp.byteInputStream())
            }

        assertEquals("From The XMP", AndroidPdfTextSource.open(context, file).use { it.title })
    }

    @Test
    fun convertsALargeGeneratedBookAndLogsTheTimings() {
        val pages = 300
        val file =
            pdf("large.pdf") {
                repeat(pages) { n ->
                    page {
                        text("The Large Book", x = 150f, y = 40f, size = 9f)
                        text("${n + 1}", x = 210f, y = 610f, size = 9f)
                        for (row in 0 until 38) {
                            text(
                                "Row $row of page ${n + 1} holds a plain sentence of ordinary body text.",
                                x = 72f,
                                y = 80f + row * 13.5f,
                                size = 10f,
                            )
                        }
                    }
                }
            }
        val start = System.nanoTime()

        val result = AndroidPdfTextSource.open(context, file).use { Converter().convert(it, "large.pdf", ConversionOptions()) }

        val seconds = (System.nanoTime() - start) / 1e9
        android.util.Log.i("AudioPressTiming", "converted $pages generated pages in %.1f s".format(seconds))
        assertEquals(pages * 38 * 13, result.transcript.wordCount)
        assertTrue(result.removals.any { it.rule == RuleId.RUNNING_HEADERS }, "the running header is removed")
    }

    private fun book(
        name: String,
        pages: Int,
        headerPages: Set<Int> = emptySet(),
        extra: Map<Int, String> = emptyMap(),
    ): File =
        pdf(name) {
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
    fun aPageRangeOffersOnlyThosePagesAndKeepsThePdfsOwnPageNumbers() {
        val file = book("range.pdf", pages = 8)

        AndroidPdfTextSource.open(context, file, pages = 4..6).use { source ->
            assertEquals(8, source.totalPageCount)
            assertEquals(3, source.pageCount)
            assertEquals(4, source.firstPage)
            assertEquals(4, source.readPage(1).index)
            assertEquals("4", source.readPage(1).words[1].text)
            assertEquals(6, source.readPage(3).index)
            val result = Converter().convert(source, "range.pdf", ConversionOptions())
            assertEquals(
                setOf(4, 5, 6),
                result.transcript.segments
                    .flatMap { s -> s.tokens.map { it.page } }
                    .toSet(),
            )
        }
    }

    @Test
    fun aRangeOutsideThePdfOrBackwardsIsRefused() {
        val file = book("invalid.pdf", pages = 5)

        assertEquals(
            "The first page is 0, but pages start at 1",
            assertFailsWith<InvalidPageRangeException> {
                AndroidPdfTextSource.open(
                    context,
                    file,
                    pages =
                        0..3,
                )
            }.message,
        )
        assertEquals(
            "The last page is 6, but the PDF has only 5 pages",
            assertFailsWith<InvalidPageRangeException> {
                AndroidPdfTextSource.open(
                    context,
                    file,
                    pages =
                        2..6,
                )
            }.message,
        )
        assertEquals(
            "The first page (4) is after the last page (2)",
            assertFailsWith<InvalidPageRangeException> {
                AndroidPdfTextSource.open(
                    context,
                    file,
                    pages =
                        4..2,
                )
            }.message,
        )
        AndroidPdfTextSource.open(context, file).use { assertEquals(5, it.pageCount) }
    }

    @Test
    fun pagesOutsideTheRangeDoNotMakeAHeaderInsideItLookRepeated() {
        val file = book("headers.pdf", pages = 6, headerPages = setOf(3, 4))

        val whole = AndroidPdfTextSource.open(context, file).use { spoken(Converter().convert(it, "h.pdf", ConversionOptions())) }
        val range =
            AndroidPdfTextSource
                .open(
                    context,
                    file,
                    pages = 4..6,
                ).use { spoken(Converter().convert(it, "h.pdf", ConversionOptions())) }

        assertFalse("Chapter Heading Words" in whole)
        assertTrue("Chapter Heading Words" in range)
    }

    @Test
    fun theFrontAndBackOfAPageRangeDecideWhereCopyrightPagesAreSkipped() {
        val file =
            book("copyright.pdf", pages = 40, extra = mapOf(30 to "ISBN 000 mid book", 38 to "ISBN 111 back", 12 to "ISBN 222 front"))

        val text =
            AndroidPdfTextSource
                .open(
                    context,
                    file,
                    pages = 11..40,
                ).use { spoken(Converter().convert(it, "c.pdf", ConversionOptions())) }

        assertTrue("ISBN 000 mid book" in text)
        assertFalse("ISBN 111 back" in text)
        assertFalse("ISBN 222 front" in text)
    }

    private companion object {
        const val TOLERANCE = 0.5f
    }
}
