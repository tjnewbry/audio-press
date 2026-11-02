package io.github.tjnewbry.audiopress.app

import java.util.Locale

/** The sizes the Info popup shows, in bytes: [bake] is null when the book has no baked audio. */
data class BookSizes(
    val bake: Long?,
    val total: Long,
)

/**
 * The lines of the Info popup for [book]: its source PDF, the length of that PDF, the pages imported, and what it takes on
 * the phone. A book imported before the source was recorded shows "Unknown", its own page count, and 1 to that count.
 */
fun bookInfoLines(
    book: Book,
    sizes: BookSizes,
): List<String> {
    val source = book.source
    val length = source?.totalPages ?: book.pageCount
    val first = source?.firstPage ?: 1
    val last = source?.lastPage ?: book.pageCount
    return listOf(
        "Source file: ${source?.fileName ?: "Unknown"}",
        "Source length: ${pagesText(length)}",
        "Imported range: $first-$last",
        "Bake data: ${sizes.bake?.let(::formatInfoSize) ?: "none"}",
        "Total: ${formatInfoSize(sizes.total)}",
    )
}

private fun pagesText(count: Int) = if (count == 1) "1 page" else "$count pages"

private const val KB = 1_000.0
private const val MB = 1_000_000.0
private const val GB = 1_000_000_000.0
private const val KB_PER_MB = 1_000

/** A size for the Info popup, in decimal units: "640 KB", "83 MB" (whole), "1.2 GB" (one decimal). */
fun formatInfoSize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L).toDouble()
    val megabytes = Math.round(b / MB)
    return when {
        b >= GB || megabytes >= KB_PER_MB -> String.format(Locale.US, "%.1f GB", b / GB)
        b >= MB -> "$megabytes MB"
        else -> "${minOf(Math.round(b / KB), (KB_PER_MB - 1).toLong())} KB"
    }
}
