package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.AudioPressException
import io.github.tjnewbry.audiopress.core.CancellationToken
import io.github.tjnewbry.audiopress.core.ConversionCancelledException
import io.github.tjnewbry.audiopress.core.ConversionOptions
import io.github.tjnewbry.audiopress.core.Converter
import io.github.tjnewbry.audiopress.core.CorruptPdfException
import io.github.tjnewbry.audiopress.core.ExtractionNotPermittedException
import io.github.tjnewbry.audiopress.core.InputNotFoundException
import io.github.tjnewbry.audiopress.core.InputUnreadableException
import io.github.tjnewbry.audiopress.core.NoTextLayerException
import io.github.tjnewbry.audiopress.core.NotAPdfException
import io.github.tjnewbry.audiopress.core.PdfEncryptedException
import io.github.tjnewbry.audiopress.core.TextSource

/** The PDF read fine but the engine found no text to speak (for example, every page was a cover or an index). */
class EmptyBookException : Exception("The transcript has no words")

/** Turns a [TextSource] into a stored book: engine conversion, then [store]. Blocks; call it off the main thread. */
class BookImporter(
    private val store: BookStore,
) {
    fun import(
        source: TextSource,
        title: String,
        cancellation: CancellationToken? = null,
        onProgress: (fraction: Float) -> Unit = {},
    ): Book {
        val transcript = Converter().convert(source, title, ConversionOptions(), cancellation) { onProgress(it.fraction) }.transcript
        if (transcript.wordCount == 0) throw EmptyBookException()
        return store.save(title, source.pageCount, transcript.wordCount, transcript.toStoredParagraphs())
    }
}

/** A book title from a file name: the name without its `.pdf` extension. */
fun titleFromFileName(fileName: String?): String {
    val name =
        fileName
            ?.trim()
            .orEmpty()
            .removeSuffix(".pdf")
            .removeSuffix(".PDF")
            .trim()
    return name.ifEmpty { "Untitled" }
}

/** What to tell the user when importing [fileName] failed with [error]. Never mentions exceptions or stack traces. */
fun importErrorMessage(
    fileName: String,
    error: Throwable,
): String =
    when (error) {
        is NoTextLayerException -> {
            "\"$fileName\" has no text to read. It looks like a scanned PDF, and scanned PDFs are not supported yet."
        }

        is EmptyBookException -> {
            "\"$fileName\" has no readable text after the covers, page numbers, and other clutter were removed."
        }

        is PdfEncryptedException -> {
            "\"$fileName\" is password protected. Save an unprotected copy of the PDF and import that."
        }

        is ExtractionNotPermittedException -> {
            "\"$fileName\" does not allow its text to be copied, so it cannot be read aloud."
        }

        is NotAPdfException -> {
            "\"$fileName\" is not a PDF file."
        }

        is CorruptPdfException -> {
            "\"$fileName\" is damaged and cannot be read."
        }

        is InputNotFoundException, is InputUnreadableException -> {
            "\"$fileName\" could not be opened. Check that the file still exists and try again."
        }

        is ConversionCancelledException -> {
            "Import cancelled."
        }

        // a quiet cancel; the screen does not show it
        is AudioPressException -> {
            "\"$fileName\" could not be imported."
        }

        is OutOfMemoryError -> {
            "\"$fileName\" is too big for this device to import."
        }

        else -> {
            "Something went wrong while importing \"$fileName\". Please try again."
        }
    }
