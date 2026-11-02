package io.github.tjnewbry.audiopress.app

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
        "Source length: ${formatPages(length)}",
        "Imported range: $first-$last",
        "Bake data: ${sizes.bake?.let(::formatInfoSize) ?: "none"}",
        "Total: ${formatInfoSize(sizes.total)}",
    )
}
