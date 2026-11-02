package io.github.tjnewbry.audiopress.pdfbox

import io.github.tjnewbry.audiopress.core.Box
import io.github.tjnewbry.audiopress.core.CorruptPdfException
import io.github.tjnewbry.audiopress.core.DocumentTitle
import io.github.tjnewbry.audiopress.core.ExtractionNotPermittedException
import io.github.tjnewbry.audiopress.core.InputNotFoundException
import io.github.tjnewbry.audiopress.core.InputUnreadableException
import io.github.tjnewbry.audiopress.core.InvalidPageRangeException
import io.github.tjnewbry.audiopress.core.NotAPdfException
import io.github.tjnewbry.audiopress.core.PdfEncryptedException
import io.github.tjnewbry.audiopress.core.SourcePage
import io.github.tjnewbry.audiopress.core.SourceWord
import io.github.tjnewbry.audiopress.core.TextSource
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/**
 * Reads text-based PDFs with Apache PDFBox. Open one with [open].
 *
 * With a page range, only those pages are offered: [pageCount] is the size of the range and [readPage] counts from
 * its first page, but every [SourcePage.index] is the PDF's own page number. [totalPageCount] is the whole PDF.
 */
class PdfBoxTextSource private constructor(
    private val document: PDDocument,
    private val name: String,
    range: IntRange?,
) : TextSource {
    private val collector = WordCollector()

    override val title: String? = readTitle(document)

    /** The number of pages in the whole PDF, whatever range was chosen. */
    val totalPageCount: Int = document.numberOfPages

    /** The PDF's own pages this source offers (1-based, inclusive): the chosen range, or every page. */
    val pages: IntRange = range ?: 1..totalPageCount

    /** The number of pages in the chosen range (all pages when none was chosen). [readPage] takes 1 to this. */
    override val pageCount: Int get() = pages.last - pages.first + 1

    override val firstPage: Int get() = pages.first

    override fun readPage(pageIndex: Int): SourcePage {
        require(pageIndex in 1..pageCount) { "page $pageIndex is outside 1..$pageCount" }
        val pdfPage = pages.first + pageIndex - 1 // the PDF's own number, which the page and its words keep
        val page = document.getPage(pdfPage - 1)
        val crop = page.cropBox
        val sideways = page.rotation % 180 != 0
        val words =
            try {
                collector.collect(document, pdfPage)
            } catch (e: IOException) {
                throw CorruptPdfException(name, e)
            }
        return SourcePage(
            index = pdfPage,
            width = if (sideways) crop.height else crop.width,
            height = if (sideways) crop.width else crop.height,
            words = words,
        )
    }

    override fun close() = document.close()

    private fun readTitle(document: PDDocument): String? =
        try {
            DocumentTitle.clean(document.documentInformation?.title)
                ?: document.documentCatalog.metadata
                    ?.createInputStream()
                    ?.use {
                        it.readNBytes(XMP_LIMIT_BYTES)
                    }?.let { DocumentTitle.fromXmp(String(it, Charsets.UTF_8)) }
        } catch (e: IOException) {
            null // A title is optional: unreadable metadata is not an error.
        } catch (e: RuntimeException) {
            null
        }

    companion object {
        /**
         * Opens [path], throwing a typed [io.github.tjnewbry.audiopress.core.AudioPressException] when it cannot be read.
         * [pages] limits the source to those pages of the PDF (1-based, inclusive); null means every page. A range that
         * starts before page 1, ends after the last page, or is backwards throws [InvalidPageRangeException].
         */
        fun open(
            path: Path,
            pages: IntRange? = null,
        ): PdfBoxTextSource {
            val name = path.toString()
            if (!Files.exists(path)) throw InputNotFoundException(name)
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) throw InputUnreadableException(name)
            val header =
                try {
                    Files.newInputStream(path).use { it.readNBytes(HEADER_SEARCH_BYTES) }
                } catch (e: IOException) {
                    throw InputUnreadableException(name, e)
                }
            if (!String(header, Charsets.ISO_8859_1).contains("%PDF-")) throw NotAPdfException(name)

            val document =
                try {
                    Loader.loadPDF(path.toFile())
                } catch (e: InvalidPasswordException) {
                    throw PdfEncryptedException(name)
                } catch (e: IOException) {
                    throw CorruptPdfException(name, e)
                }
            // Audio Press is an accessibility tool, so the PDF's "extract for accessibility" permission is enough.
            val permission = document.currentAccessPermission
            if (!permission.canExtractContent() && !permission.canExtractForAccessibility()) {
                document.close()
                throw ExtractionNotPermittedException(name)
            }
            if (pages != null && (pages.first < 1 || pages.last > document.numberOfPages || pages.first > pages.last)) {
                val total = document.numberOfPages
                document.close()
                throw InvalidPageRangeException(pages.first, pages.last, total)
            }
            return PdfBoxTextSource(document, name, pages)
        }

        /** PDF files may have a few bytes of junk before the header; readers accept it within the first 1 KB. */
        private const val HEADER_SEARCH_BYTES = 1024

        /** Only the start of the XMP packet is searched for a title. */
        private const val XMP_LIMIT_BYTES = 64 * 1024
    }
}

/**
 * Collects positioned words from one page at a time. PDFBox decides where words start and end; this
 * class additionally splits a word where its baseline shifts, so superscripts (footnote markers) and
 * subscripts become separate words that join the previous one.
 */
private class WordCollector : PDFTextStripper() {
    private val words = mutableListOf<SourceWord>()

    init {
        sortByPosition = true
    }

    fun collect(
        document: PDDocument,
        pageIndex: Int,
    ): List<SourceWord> {
        words.clear()
        startPage = pageIndex
        endPage = pageIndex
        getText(document)
        return words.toList()
    }

    override fun writeString(
        text: String,
        textPositions: List<TextPosition>,
    ) {
        var run = mutableListOf<TextPosition>()
        var joinsPrevious = false
        for (position in textPositions) {
            if (position.unicode.isBlank()) {
                if (run.isNotEmpty()) emit(run, joinsPrevious)
                run = mutableListOf()
                joinsPrevious = false
                continue
            }
            if (run.isNotEmpty() && baselineShifts(run.first(), position)) {
                emit(run, joinsPrevious)
                run = mutableListOf()
                joinsPrevious = true
            }
            run += position
        }
        if (run.isNotEmpty()) emit(run, joinsPrevious)
    }

    private fun baselineShifts(
        a: TextPosition,
        b: TextPosition,
    ): Boolean = abs(a.yDirAdj - b.yDirAdj) > BASELINE_SHIFT * maxOf(size(a), size(b))

    private fun emit(
        run: List<TextPosition>,
        joinsPrevious: Boolean,
    ) {
        val first = run.first()
        val horizontal = first.dir == 0f
        val fontSize = run.maxOf(::size)
        val text = run.joinToString("") { it.unicode }
        val baseline: Float
        val box: Box
        if (horizontal) {
            baseline = first.yDirAdj
            box =
                Box(
                    left = run.minOf { it.xDirAdj },
                    top = baseline - ASCENT * fontSize,
                    right = run.maxOf { it.xDirAdj + it.widthDirAdj },
                    bottom = baseline + DESCENT * fontSize,
                )
        } else {
            // Rotated text is only ever removed, so an approximate box is enough.
            baseline = run.maxOf { it.y }
            box =
                Box(
                    left = run.minOf { it.x } - fontSize,
                    top = run.minOf { it.y } - fontSize,
                    right = run.maxOf { it.x } + fontSize,
                    bottom = baseline,
                )
        }
        words += SourceWord(text, box, baseline, fontSize, horizontal, joinsPrevious)
    }

    /**
     * The glyph's font size in points. PDFBox's `fontSizeInPt` leaves out the page's coordinate scaling (the `cm`
     * operator), so a PDF written as "Tf 327 under cm 0.099" (Chrome and Skia print-to-PDF do this) reports 327 for text
     * drawn at 24 points, and every word's box then overlaps every other line. The displayed size, `yScale`, includes the
     * scaling. It is used when the two differ by more than a quarter; otherwise PDFBox's own value is kept, so ordinary
     * PDFs read exactly as before. Falls back to the glyph height for fonts that report no size.
     */
    private fun size(position: TextPosition): Float {
        val declared = position.fontSizeInPt
        val displayed = abs(position.yScale)
        return when {
            declared > 0f && displayed > 0f && (declared < 0.8f * displayed || declared > 1.25f * displayed) -> displayed.toInt().toFloat()
            declared > 0f -> declared
            else -> position.heightDir
        }
    }

    private companion object {
        /** A baseline shift larger than this share of the font size starts a new word (a superscript or subscript). */
        const val BASELINE_SHIFT = 0.15f

        /** Approximate ascent and descent as shares of the font size, used for word boxes until Bravo needs exact ones. */
        const val ASCENT = 0.8f
        const val DESCENT = 0.2f
    }
}
