package io.github.tjnewbry.audiopress.core

/** An expected failure, with a message meant for the user. The CLI maps each type to an exit code. */
sealed class AudioPressException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class InputNotFoundException(
    path: String,
) : AudioPressException("$path: file not found")

class InputUnreadableException(
    path: String,
    cause: Throwable? = null,
) : AudioPressException("$path: the file cannot be read", cause)

class NotAPdfException(
    path: String,
) : AudioPressException("$path: not a PDF file")

class CorruptPdfException(
    path: String,
    cause: Throwable? = null,
) : AudioPressException("$path: the PDF is damaged and cannot be read", cause)

class PdfEncryptedException(
    path: String,
) : AudioPressException("$path: the PDF needs a password to open. Save an unprotected copy and try again.")

class ExtractionNotPermittedException(
    path: String,
) : AudioPressException("$path: the PDF's permissions do not allow its text to be extracted")

class NoPagesSelectedException(
    selection: String,
    pageCount: Int,
) : AudioPressException("--pages $selection selects no pages; the PDF has $pageCount pages")

/** A source was asked to read pages [first] to [last], but the PDF has only [pageCount] pages (or the range is backwards). */
class InvalidPageRangeException(
    first: Int,
    last: Int,
    pageCount: Int,
) : AudioPressException(
        when {
            first < 1 -> "The first page is $first, but pages start at 1"
            last > pageCount -> "The last page is $last, but the PDF has only $pageCount pages"
            else -> "The first page ($first) is after the last page ($last)"
        },
    )

class NoTextLayerException(
    path: String,
) : AudioPressException(
        "$path: none of the selected pages contain text. This looks like a scanned PDF; reading scans (OCR) is planned for a later version.",
    )

/** The caller cancelled the conversion with a [CancellationToken]. No result was produced. */
class ConversionCancelledException : AudioPressException("The conversion was cancelled")
