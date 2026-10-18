package io.github.tjnewbry.audiopress.testing

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.util.Matrix
import java.nio.file.Path

/**
 * Builds small PDFs with known content for tests. Positions are given in display space (points from
 * the top-left corner), the same coordinates the program reports.
 */
class FixturePdf {
    private val pages = mutableListOf<FixturePage>()

    fun page(
        width: Float = 432f,
        height: Float = 648f,
        block: FixturePage.() -> Unit = {},
    ) {
        pages += FixturePage(width, height).apply(block)
    }

    fun save(
        path: Path,
        configure: (PDDocument) -> Unit = {},
    ): Path {
        PDDocument().use { document ->
            pages.forEach { it.render(document) }
            configure(document)
            document.save(path.toFile())
        }
        return path
    }
}

class FixturePage(
    val width: Float,
    val height: Float,
) {
    private val operations = mutableListOf<(PDPageContentStream) -> Unit>()

    /**
     * Draws [text] starting [x] points from the left, with its baseline [y] points from the top, raised by
     * [rise] points. Returns the x position just after the text, so text can continue right after it.
     */
    fun text(
        text: String,
        x: Float,
        y: Float,
        size: Float = 11f,
        rise: Float = 0f,
    ): Float {
        operations += { stream ->
            stream.beginText()
            stream.setFont(FONT, size)
            stream.setTextRise(rise)
            stream.newLineAtOffset(x, height - y)
            stream.showText(text)
            stream.endText()
        }
        return x + FONT.getStringWidth(text) / 1000f * size
    }

    /**
     * Draws a line word by word, like typeset text. With [right], the words are spread out so the line ends
     * exactly there (justified). A word written as `word^1` gets a raised, smaller `1` right after it.
     */
    fun line(
        text: String,
        x: Float,
        y: Float,
        size: Float = 11f,
        right: Float? = null,
    ) {
        val words = text.split(' ').filter { it.isNotEmpty() }
        val widths = words.map { width(it.substringBefore('^'), size) + width(it.substringAfter('^', ""), size * MARKER_SCALE) }
        val space = if (right != null && words.size > 1) (right - x - widths.sum()) / (words.size - 1) else width(" ", size)
        var cursor = x
        words.forEachIndexed { i, word ->
            val end = text(word.substringBefore('^'), cursor, y, size)
            val marker = word.substringAfter('^', "")
            if (marker.isNotEmpty()) text(marker, end, y, size * MARKER_SCALE, rise = size * MARKER_RISE)
            cursor += widths[i] + space
        }
    }

    private fun width(
        text: String,
        size: Float,
    ): Float = FONT.getStringWidth(text) / 1000f * size

    /** Draws [text] turned 90 degrees, reading upward from ([x], [y]). */
    fun rotatedText(
        text: String,
        x: Float,
        y: Float,
        size: Float = 8f,
    ) {
        operations += { stream ->
            stream.beginText()
            stream.setFont(FONT, size)
            stream.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(90.0), x, height - y))
            stream.showText(text)
            stream.endText()
        }
    }

    internal fun render(document: PDDocument) {
        val page = PDPage(PDRectangle(width, height))
        document.addPage(page)
        PDPageContentStream(document, page).use { stream -> operations.forEach { it(stream) } }
    }

    companion object {
        val FONT = PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN)
        private const val MARKER_SCALE = 0.6f
        private const val MARKER_RISE = 0.35f
    }
}

/** Builds a PDF with [block] and saves it to [path]. */
fun fixturePdf(
    path: Path,
    block: FixturePdf.() -> Unit,
): Path = FixturePdf().apply(block).save(path)
