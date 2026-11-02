package io.github.tjnewbry.audiopress.pdfsource

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/**
 * Builds small PDFs with known content (original text only) for the instrumented tests. Positions are in
 * display space: points from the top-left corner, as the engine reports them. PDFBox-Android needs
 * `PDFBoxResourceLoader.init` before this is used.
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
        file: File,
        configure: (PDDocument) -> Unit = {},
    ): File {
        PDDocument().use { document ->
            pages.forEach { it.render(document) }
            configure(document)
            document.save(file)
        }
        return file
    }
}

class FixturePage(
    val width: Float,
    val height: Float,
) {
    private val operations = mutableListOf<(PDPageContentStream) -> Unit>()

    /** Draws [text] with its baseline [y] points from the top, raised by [rise]. Returns the x just after it. */
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
     * Draws [text] the way Chrome and Skia print-to-PDF do: a large font size (`Tf` [declaredSize]) under a `cm` that scales
     * the page down, so the text shows at [size] points.
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
            stream.transform(Matrix(scale, 0f, 0f, scale, 0f, 0f))
            stream.beginText()
            stream.setFont(FONT, declaredSize)
            stream.newLineAtOffset(x / scale, (height - y) / scale)
            stream.showText(text)
            stream.endText()
            stream.restoreGraphicsState()
        }
    }

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

    private companion object {
        val FONT: PDType1Font get() = PDType1Font.TIMES_ROMAN
    }
}
