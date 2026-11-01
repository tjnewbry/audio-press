package io.github.tjnewbry.audiopress.pdfsource

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.tjnewbry.audiopress.core.Box
import io.github.tjnewbry.audiopress.core.CorruptPdfException
import io.github.tjnewbry.audiopress.core.ExtractionNotPermittedException
import io.github.tjnewbry.audiopress.core.InputNotFoundException
import io.github.tjnewbry.audiopress.core.InputUnreadableException
import io.github.tjnewbry.audiopress.core.NotAPdfException
import io.github.tjnewbry.audiopress.core.PdfEncryptedException
import io.github.tjnewbry.audiopress.core.SourcePage
import io.github.tjnewbry.audiopress.core.SourceWord
import io.github.tjnewbry.audiopress.core.TextSource
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.abs

/**
 * Reads text-based PDFs on Android with PdfBox-Android, the community port of Apache PDFBox 2.0. This is
 * the Android twin of the engine's `PdfBoxTextSource` (`engine/pdfbox`); the word logic is the same.
 *
 * Open one with [open] (a content `Uri` from the file picker, or a [File]) and always [close] it: closing
 * deletes the temporary copy and scratch files. Opening and reading block and are slow for big books, so
 * call them off the main thread. One instance must be used from one thread at a time.
 *
 * Failures are the engine's typed exceptions, with the same meaning as on the desktop: [InputNotFoundException],
 * [InputUnreadableException], [NotAPdfException], [CorruptPdfException], [PdfEncryptedException], and
 * [ExtractionNotPermittedException].
 */
class AndroidPdfTextSource private constructor(
    private val document: PDDocument,
    private val name: String,
    private val cleanup: List<File>,
) : TextSource {
    private val collector = WordCollector()

    override val pageCount: Int get() = document.numberOfPages

    override fun readPage(pageIndex: Int): SourcePage {
        require(pageIndex in 1..pageCount) { "page $pageIndex is outside 1..$pageCount" }
        try {
            val page = document.getPage(pageIndex - 1)
            val crop = page.cropBox
            val sideways = page.rotation % 180 != 0
            return SourcePage(
                index = pageIndex,
                width = if (sideways) crop.height else crop.width,
                height = if (sideways) crop.width else crop.height,
                words = collector.collect(document, pageIndex),
            )
        } catch (e: IOException) {
            throw CorruptPdfException(name, e)
        } catch (e: RuntimeException) {
            // PDFBox 2.0 throws unchecked exceptions (for example NullPointerException) from some damaged pages.
            throw CorruptPdfException(name, e)
        }
    }

    override fun close() {
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
                return openFile(app, copy, name, deleteOnClose = true)
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
        ): AndroidPdfTextSource = openFile(context.applicationContext, file, displayName, deleteOnClose = false)

        private fun openFile(
            app: Context,
            file: File,
            name: String,
            deleteOnClose: Boolean,
        ): AndroidPdfTextSource {
            if (!file.exists()) throw InputNotFoundException(name)
            if (!file.isFile || !file.canRead()) throw InputUnreadableException(name)
            checkHeader(file, name)

            // PDFBox needs its bundled fonts and glyph lists from the app's assets. Calling this again is harmless.
            PDFBoxResourceLoader.init(app)
            val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(workDirectory(app))
            val document =
                try {
                    PDDocument.load(file, memory)
                } catch (e: InvalidPasswordException) {
                    throw PdfEncryptedException(name)
                } catch (e: IOException) {
                    throw CorruptPdfException(name, e)
                } catch (e: RuntimeException) {
                    throw CorruptPdfException(name, e)
                }
            // Audio Press is an accessibility tool, so the PDF's "extract for accessibility" permission is enough.
            val permission = document.currentAccessPermission
            if (!permission.canExtractContent() && !permission.canExtractForAccessibility()) {
                document.close()
                throw ExtractionNotPermittedException(name)
            }
            return AndroidPdfTextSource(document, name, if (deleteOnClose) listOf(file) else emptyList())
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
            File(context.applicationContext.cacheDir, WORK_DIRECTORY).deleteRecursively()
        }

        private const val WORK_DIRECTORY = "pdf-source"

        /** PDF files may have a few bytes of junk before the header; readers accept it within the first 1 KB. */
        private const val HEADER_SEARCH_BYTES = 1024
    }
}

/**
 * Collects positioned words from one page at a time. PDFBox decides where words start and end; this
 * class additionally splits a word where its baseline shifts, so superscripts (footnote markers) and
 * subscripts become separate words that join the previous one. Same logic as the desktop adapter.
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

    /** The glyph's font size in points, falling back to its height for fonts that report no size. */
    private fun size(position: TextPosition): Float = position.fontSizeInPt.takeIf { it > 0f } ?: position.heightDir

    private companion object {
        /** A baseline shift larger than this share of the font size starts a new word (a superscript or subscript). */
        const val BASELINE_SHIFT = 0.15f

        /** Approximate ascent and descent as shares of the font size, used for word boxes until Bravo needs exact ones. */
        const val ASCENT = 0.8f
        const val DESCENT = 0.2f
    }
}
