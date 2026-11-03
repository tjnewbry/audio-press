package io.github.tjnewbry.audiopress.testing

import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.PDStream
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
    private lateinit var document: PDDocument

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
     * Draws [text] letter-spaced: [tracking] extra points after every glyph (`Tc`), as designers set headings,
     * spaced capitals, and some body text. Position is as for [text].
     */
    fun trackedText(
        text: String,
        x: Float,
        y: Float,
        size: Float = 11f,
        tracking: Float = 1f,
    ) {
        operations += { stream ->
            stream.beginText()
            stream.setFont(FONT, size)
            stream.setCharacterSpacing(tracking)
            stream.newLineAtOffset(x, height - y)
            stream.showText(text)
            stream.setCharacterSpacing(0f)
            stream.endText()
        }
    }

    /**
     * Draws one glyph at ([x], [y]) whose text is the control character BEL (U+0007), in a second font. Some layout
     * programs leave such invisible marks after list bullets, on top of the next word's first letter.
     */
    fun controlMark(
        x: Float,
        y: Float,
        size: Float = 11f,
    ) {
        operations += { stream ->
            val font = PDType1Font(Standard14Fonts.FontName.TIMES_ITALIC)
            val cmap =
                "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /Mark def " +
                    "1 begincodespacerange <00> <FF> endcodespacerange 1 beginbfchar <7C> <0007> endbfchar " +
                    "endcmap CMapName currentdict /CMap defineresource pop end end"
            val toUnicode = PDStream(document, cmap.byteInputStream())
            font.cosObject.setItem(COSName.TO_UNICODE, toUnicode)
            stream.beginText()
            stream.setFont(font, size)
            stream.newLineAtOffset(x, height - y)
            stream.showText("|")
            stream.endText()
        }
    }

    /**
     * Draws [text] the way Chrome and Skia print-to-PDF do: the font size is set large (`Tf` [declaredSize]) and the page's
     * coordinate system is scaled down with `cm`, so the text shows at [size] points. Position is as for [text].
     */
    fun scaledText(
        text: String,
        x: Float,
        y: Float,
        size: Float = 11f,
        declaredSize: Float = 300f,
    ) {
        val scale = size / declaredSize
        operations += { stream ->
            stream.saveGraphicsState()
            stream.transform(Matrix.getScaleInstance(scale, scale))
            stream.beginText()
            stream.setFont(FONT, declaredSize)
            stream.newLineAtOffset(x / scale, (height - y) / scale)
            stream.showText(text)
            stream.endText()
            stream.restoreGraphicsState()
        }
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
        this.document = document
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
