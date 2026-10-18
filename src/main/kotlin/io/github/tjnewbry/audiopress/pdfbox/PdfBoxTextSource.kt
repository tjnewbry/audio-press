package io.github.tjnewbry.audiopress.pdfbox

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
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/** Reads text-based PDFs with Apache PDFBox. Open one with [open]. */
class PdfBoxTextSource private constructor(
    private val document: PDDocument,
    private val name: String,
) : TextSource {
    private val collector = WordCollector()

    override val pageCount: Int get() = document.numberOfPages

    override fun readPage(pageIndex: Int): SourcePage {
        require(pageIndex in 1..pageCount) { "page $pageIndex is outside 1..$pageCount" }
        val page = document.getPage(pageIndex - 1)
        val crop = page.cropBox
        val sideways = page.rotation % 180 != 0
        val words =
            try {
                collector.collect(document, pageIndex)
            } catch (e: IOException) {
                throw CorruptPdfException(name, e)
            }
        return SourcePage(
            index = pageIndex,
            width = if (sideways) crop.height else crop.width,
            height = if (sideways) crop.width else crop.height,
            words = words,
        )
    }

    override fun close() = document.close()

    companion object {
        /** Opens [path], throwing a typed [io.github.tjnewbry.audiopress.core.AudioPressException] when it cannot be read. */
        fun open(path: Path): PdfBoxTextSource {
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
            return PdfBoxTextSource(document, name)
        }

        /** PDF files may have a few bytes of junk before the header; readers accept it within the first 1 KB. */
        private const val HEADER_SEARCH_BYTES = 1024
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
