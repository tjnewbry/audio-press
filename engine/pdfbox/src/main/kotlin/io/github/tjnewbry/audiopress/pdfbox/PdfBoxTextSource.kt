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
 * Collects positioned words from one page at a time. PDFBox decides where words start and end, with two
 * corrections:
 *
 * * **Letter-spaced text.** PDFBox starts a new word wherever a gap is wider than a share of the previous
 *   glyph's width, so in text set with extra space between letters (tracking) it breaks words after narrow
 *   letters ("Quest ions") and splits spaced capitals into single letters ("S E C T I O N"). Where a line's
 *   letters are evenly spaced out, a gap no wider than that spacing plus half a space is rejoined; real
 *   word gaps, which have a space glyph or a full space more, stay. See [joinTrackedWords].
 * * **Baseline shifts.** A word is split where its baseline shifts, so superscripts (footnote markers) and
 *   subscripts become separate words that join the previous one.
 */
private class WordCollector : PDFTextStripper() {
    private val words = mutableListOf<SourceWord>()

    /** PDFBox's words on the line being written, turned into words at each line end. */
    private val line = mutableListOf<List<TextPosition>>()

    init {
        sortByPosition = true
    }

    fun collect(
        document: PDDocument,
        pageIndex: Int,
    ): List<SourceWord> {
        words.clear()
        line.clear()
        startPage = pageIndex
        endPage = pageIndex
        getText(document)
        endLine()
        return words.toList()
    }

    override fun writeString(
        text: String,
        textPositions: List<TextPosition>,
    ) {
        // A glyph whose text is empty or only control characters (an invisible mark, such as the BEL some layout
        // programs put after a list bullet) carries no text, and must not split the word it sits in.
        val glyphs = textPositions.filterNot { position -> position.unicode.all { it.isISOControl() && !it.isWhitespace() } }
        if (glyphs.isNotEmpty()) line += glyphs
    }

    override fun writeLineSeparator() {
        endLine()
        super.writeLineSeparator()
    }

    override fun writeParagraphEnd() {
        endLine()
        super.writeParagraphEnd()
    }

    override fun endArticle() {
        endLine()
        super.endArticle()
    }

    override fun writePageEnd() {
        endLine()
        super.writePageEnd()
    }

    private fun endLine() {
        if (line.isEmpty()) return
        joinTrackedWords(line).forEach(::split)
        line.clear()
    }

    /**
     * Rejoins words that PDFBox split inside letter-spaced text. Letter spacing is the median gap between a line's
     * neighbouring glyphs of one font and size (gaps at a space glyph are not counted). It is tracking only where
     * it is at least [MIN_TRACKING] of the font size, most gaps ([MIN_EVEN_SHARE]) are within [MAX_SPREAD] of it,
     * and the line either marks its word gaps with space glyphs or is a single word in one font. That leaves out
     * formulas, whose evenly spaced symbols have no space glyphs between them. Then a break between two glyphs of
     * that font with no space glyph is rejoined if it is between letters or digits (or a letter and the punctuation
     * after it) and the gap exceeds the spacing by less than [SPACING_TOLERANCE] of a space. Other lines are left
     * exactly as PDFBox split them.
     */
    private fun joinTrackedWords(pieces: List<List<TextPosition>>): List<List<TextPosition>> {
        if (pieces.size < 2) return pieces
        val glyphs = pieces.flatten()
        val hasSpaces = glyphs.any { it.unicode.isBlank() }
        val oneWord = glyphs.all { it.unicode.all(Char::isLetter) && style(it) == style(glyphs.first()) }
        if (!hasSpaces && !oneWord) return pieces
        val tracking =
            glyphs
                .zipWithNext()
                .filter { (a, b) -> sameStyle(a, b) && !a.unicode.isBlank() && !b.unicode.isBlank() }
                .groupBy({ (a, _) -> style(a) }, { (a, b) -> gap(a, b) })
                .mapNotNull { (style, gaps) -> trackingOf(gaps, style.second)?.let { style to it } }
                .toMap()
        val joined = mutableListOf(pieces.first().toMutableList())
        for (piece in pieces.drop(1)) {
            val a = joined.last().last()
            val b = piece.first()
            val spacing = tracking[style(a)]
            val space = b.widthOfSpace
            val joins =
                spacing != null &&
                    sameStyle(a, b) &&
                    wordInside(a.unicode.last(), b.unicode.first()) &&
                    space > 0f &&
                    gap(a, b) > -MAX_KERN * size(a) &&
                    gap(a, b) < spacing + SPACING_TOLERANCE * space
            if (joins) joined.last() += piece else joined += piece.toMutableList()
        }
        return joined
    }

    /** The letter spacing of a line's [gaps] in one font of [size] points, or null if they do not show tracking. */
    private fun trackingOf(
        gaps: List<Float>,
        size: Float,
    ): Float? {
        if (gaps.size < MIN_PAIRS) return null
        val median = gaps.sorted()[gaps.size / 2]
        val even = gaps.count { abs(it - median) <= MAX_SPREAD * size }
        return median.takeIf { it >= MIN_TRACKING * size && even >= MIN_EVEN_SHARE * gaps.size }
    }

    /** Whether [a] then [b] can be inside one word: letters, digits, apostrophes, and the punctuation that ends a word. */
    private fun wordInside(
        a: Char,
        b: Char,
    ): Boolean = (a.isLetterOrDigit() || a in APOSTROPHES) && (b.isLetterOrDigit() || b in APOSTROPHES || b in WORD_END)

    private fun style(position: TextPosition): Pair<String?, Float> = position.font?.name to size(position)

    private fun sameStyle(
        a: TextPosition,
        b: TextPosition,
    ): Boolean = style(a) == style(b) && !baselineShifts(a, b)

    private fun gap(
        a: TextPosition,
        b: TextPosition,
    ): Float = b.xDirAdj - (a.xDirAdj + a.widthDirAdj)

    private fun split(textPositions: List<TextPosition>) {
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

        /** Letter spacing below this share of the font size is not tracking, so the line is left as PDFBox split it. */
        const val MIN_TRACKING = 0.03f

        /** A gap may exceed the letter spacing by less than this share of a space and still be inside a word, as in PDFBox. */
        const val SPACING_TOLERANCE = 0.5f

        /** The fewest glyph pairs in one font and size needed to measure a line's letter spacing. */
        const val MIN_PAIRS = 3

        /** Kerning can pull a letter back over the previous one by up to this share of the font size, and still be in the word. */
        const val MAX_KERN = 0.2f

        /** In tracked text, at least [MIN_EVEN_SHARE] of the gaps are within this share of the font size of the median. */
        const val MAX_SPREAD = 0.03f
        const val MIN_EVEN_SHARE = 0.6f

        /** Apostrophes, which can sit inside a word. */
        val APOSTROPHES = setOf('\'', Char(0x2019))

        /** Punctuation that can end a word with no space before it. */
        val WORD_END = setOf('.', ',', ';', ':', '!', '?', ')', Char(0x201D))

        /** Approximate ascent and descent as shares of the font size, used for word boxes until Bravo needs exact ones. */
        const val ASCENT = 0.8f
        const val DESCENT = 0.2f
    }
}
