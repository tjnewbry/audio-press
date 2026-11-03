package io.github.tjnewbry.audiopress.pdfsource

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
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
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Reads text-based PDFs on Android with PdfBox-Android, the community port of Apache PDFBox 2.0. This is
 * the Android twin of the engine's `PdfBoxTextSource` (`engine/pdfbox`); the word logic is the same.
 *
 * Open one with [open] (a content `Uri` from the file picker, or a [File]) and always [close] it: closing
 * deletes the temporary copy and scratch files. Opening and reading block and are slow for big books, so
 * call them off the main thread. One instance must be used from one thread at a time.
 *
 * Pass `pages` to [open] to read only those pages of the PDF (1-based, inclusive; null is every page). [pageCount] is
 * then the size of the range and [readPage] counts from its first page, but every [SourcePage.index] is the PDF's own
 * page number. [totalPageCount] is the length of the whole PDF. A range outside the PDF throws
 * [InvalidPageRangeException].
 *
 * Failures are the engine's typed exceptions, with the same meaning as on the desktop: [InputNotFoundException],
 * [InputUnreadableException], [NotAPdfException], [CorruptPdfException], [PdfEncryptedException], and
 * [ExtractionNotPermittedException].
 */
class AndroidPdfTextSource private constructor(
    private val document: PDDocument,
    private val name: String,
    private val cleanup: List<File>,
    range: IntRange?,
) : TextSource {
    private val collector = WordCollector()
    private var closed = false

    /** The title from the PDF's metadata (see [TextSource.title]), or null. */
    override val title: String? = readTitle(document)

    /** The number of pages in the whole PDF, whatever range was chosen. Use it for "PDF length: N pages". */
    val totalPageCount: Int = document.numberOfPages

    /** The PDF's own pages this source offers (1-based, inclusive): the chosen range, or every page. */
    val pages: IntRange = range ?: 1..totalPageCount

    /** The number of pages in the chosen range (all pages when none was chosen). [readPage] takes 1 to this. */
    override val pageCount: Int get() = pages.last - pages.first + 1

    override val firstPage: Int get() = pages.first

    override fun readPage(pageIndex: Int): SourcePage {
        require(pageIndex in 1..pageCount) { "page $pageIndex is outside 1..$pageCount" }
        val pdfPage = pages.first + pageIndex - 1 // the PDF's own number, which the page and its words keep
        try {
            val page = document.getPage(pdfPage - 1)
            val crop = page.cropBox
            val sideways = page.rotation % 180 != 0
            return SourcePage(
                index = pdfPage,
                width = if (sideways) crop.height else crop.width,
                height = if (sideways) crop.width else crop.height,
                words = collector.collect(document, pdfPage),
            )
        } catch (e: IOException) {
            Log.w(TAG, "page $pdfPage of $name could not be read", e)
            throw CorruptPdfException(name, e)
        } catch (e: RuntimeException) {
            // PDFBox 2.0 throws unchecked exceptions (for example NullPointerException) from some damaged pages.
            Log.w(TAG, "page $pdfPage of $name could not be read", e)
            throw CorruptPdfException(name, e)
        }
    }

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

    /** Frees the PDF and deletes the temporary copy. Safe to call more than once. */
    override fun close() {
        if (closed) return
        closed = true
        openSources.decrementAndGet()
        try {
            document.close()
        } finally {
            cleanup.forEach { it.delete() }
        }
    }

    companion object {
        /**
         * Opens the PDF at [uri], such as one chosen with the system file picker. The content is copied into the
         * app's cache first (PDFBox needs a seekable file), so the copy needs free space about the size of the PDF.
         * [displayName] is used in error messages; by default it is the name the provider reports.
         */
        fun open(
            context: Context,
            uri: Uri,
            displayName: String? = null,
            pages: IntRange? = null,
        ): AndroidPdfTextSource {
            beginOpening()
            try {
                return openUri(context, uri, displayName, pages)
            } catch (e: Throwable) {
                openSources.decrementAndGet()
                throw e
            }
        }

        private fun openUri(
            context: Context,
            uri: Uri,
            displayName: String?,
            pages: IntRange?,
        ): AndroidPdfTextSource {
            val app = context.applicationContext
            val name = displayName ?: queryDisplayName(app, uri) ?: uri.lastPathSegment ?: "document.pdf"
            val directory = workDirectory(app)
            val copy =
                try {
                    File.createTempFile("book-", ".pdf", directory)
                } catch (e: IOException) {
                    throw InputUnreadableException(name, e)
                }
            try {
                copyTo(app, uri, copy, name)
                return openFile(app, copy, name, deleteOnClose = true, pages = pages)
            } catch (e: Throwable) {
                copy.delete()
                throw e
            }
        }

        /** Opens the PDF in [file]. The file is left in place when the source is closed. */
        fun open(
            context: Context,
            file: File,
            displayName: String = file.name,
            pages: IntRange? = null,
        ): AndroidPdfTextSource {
            beginOpening()
            try {
                return openFile(context.applicationContext, file, displayName, deleteOnClose = false, pages = pages)
            } catch (e: Throwable) {
                openSources.decrementAndGet()
                throw e
            }
        }

        private fun openFile(
            app: Context,
            file: File,
            name: String,
            deleteOnClose: Boolean,
            pages: IntRange?,
        ): AndroidPdfTextSource {
            if (!file.exists()) throw InputNotFoundException(name)
            if (!file.isFile || !file.canRead()) throw InputUnreadableException(name)
            checkHeader(file, name)

            // PDFBox needs its bundled fonts and glyph lists from the app's assets. Calling this again is harmless.
            PDFBoxResourceLoader.init(app)
            val document = load(app, file, name)
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
            return AndroidPdfTextSource(document, name, if (deleteOnClose) listOf(file) else emptyList(), pages)
        }

        /**
         * Loads [file] with PDFBox. If the work folder for PDFBox's scratch files vanished while it ran (the system
         * cleared the cache, or something deleted it), that is not a damaged PDF: recreate it and try once more.
         */
        private fun load(
            app: Context,
            file: File,
            name: String,
        ): PDDocument {
            var retried = false
            while (true) {
                try {
                    return PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(workDirectory(app)))
                } catch (e: InvalidPasswordException) {
                    throw PdfEncryptedException(name)
                } catch (e: IOException) {
                    if (!retried && !File(app.cacheDir, WORK_DIRECTORY).isDirectory) {
                        retried = true
                        Log.w(TAG, "the work folder vanished while opening $name; trying once more", e)
                        continue
                    }
                    Log.w(TAG, "$name (${file.length()} bytes) could not be loaded", e)
                    throw CorruptPdfException(name, e)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "$name (${file.length()} bytes) could not be loaded", e)
                    throw CorruptPdfException(name, e)
                }
            }
        }

        /** Throws [NotAPdfException] unless "%PDF-" appears in the first 1 KB (readers accept junk before the header). */
        internal fun checkHeader(
            file: File,
            name: String,
        ) {
            val header =
                try {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(HEADER_SEARCH_BYTES)
                        buffer.copyOf(maxOf(input.read(buffer), 0))
                    }
                } catch (e: IOException) {
                    throw InputUnreadableException(name, e)
                }
            if (!String(header, Charsets.ISO_8859_1).contains("%PDF-")) throw NotAPdfException(name)
        }

        private fun copyTo(
            context: Context,
            uri: Uri,
            target: File,
            name: String,
        ) {
            try {
                val input = context.contentResolver.openInputStream(uri) ?: throw InputNotFoundException(name)
                input.use { source -> target.outputStream().use { source.copyTo(it) } }
            } catch (e: FileNotFoundException) {
                throw InputNotFoundException(name)
            } catch (e: SecurityException) {
                throw InputUnreadableException(name, e)
            } catch (e: IOException) {
                throw InputUnreadableException(name, e)
            }
        }

        private fun queryDisplayName(
            context: Context,
            uri: Uri,
        ): String? =
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (e: RuntimeException) {
                null // Not every provider supports a query; the name is only for messages.
            }

        /** Where the temporary PDF copies and PDFBox scratch files go. Delete leftovers with [clearWorkFiles]. */
        private fun workDirectory(context: Context): File = File(context.cacheDir, WORK_DIRECTORY).apply { mkdirs() }

        /**
         * Deletes temporary copies and scratch files left behind by a crash or a source that was never closed.
         * Call it once at app start, before opening any PDF.
         */
        fun clearWorkFiles(context: Context) {
            synchronized(lock) {
                if (openSources.get() > 0) return // a conversion is using them
                File(context.applicationContext.cacheDir, WORK_DIRECTORY).deleteRecursively()
            }
        }

        private const val WORK_DIRECTORY = "pdf-source"
        private const val TAG = "AudioPressPdf"

        private val lock = Any()

        /** Counts a source as open from now, so [clearWorkFiles] cannot delete its files. */
        private fun beginOpening() {
            synchronized(lock) { openSources.incrementAndGet() }
        }

        /** How many sources are open or being opened in this process; [clearWorkFiles] waits for zero. */
        private val openSources = AtomicInteger()

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
 *
 * Same logic as the desktop adapter.
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
