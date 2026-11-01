package io.github.tjnewbry.audiopress.core

/** Which physical pages to convert, parsed from a `--pages` value such as `1,5-7,20-`. */
class PageSelection private constructor(
    private val ranges: List<IntRange>?,
    private val spec: String,
) {
    /** The selected page numbers that exist in a document of [pageCount] pages, in order and without duplicates. */
    fun resolve(pageCount: Int): List<Int> =
        if (ranges == null) {
            (1..pageCount).toList()
        } else {
            (1..pageCount).filter { page -> ranges.any { page in it } }
        }

    override fun toString(): String = spec

    companion object {
        val ALL = PageSelection(null, "all")

        private val RANGE = Regex("""(\d+)(?:-(\d*))?""")

        /** Parses a page list, throwing [IllegalArgumentException] with a readable message when it is invalid. */
        fun parse(spec: String): PageSelection {
            val parts = spec.split(',').map { it.trim() }
            require(parts.none { it.isEmpty() }) { "\"$spec\" contains an empty page range" }
            return PageSelection(parts.map(::parseRange), spec)
        }

        private fun parseRange(part: String): IntRange {
            val match =
                requireNotNull(RANGE.matchEntire(part)) {
                    "\"$part\" is not a page number or range (examples: 7, 5-9, 20-)"
                }
            val start = requireNotNull(match.groupValues[1].toIntOrNull()) { "\"$part\" is too large" }
            require(start >= 1) { "page numbers start at 1" }
            val endText = match.groups[2]?.value
            val end =
                when {
                    endText == null -> start
                    endText.isEmpty() -> Int.MAX_VALUE
                    else -> requireNotNull(endText.toIntOrNull()) { "\"$part\" is too large" }
                }
            require(end >= start) { "the range \"$part\" ends before it starts" }
            return start..end
        }
    }
}

/** How page boundaries are shown in lo output. */
enum class PageMarkers { LINE, NONE }

/** How web addresses are read. */
enum class UrlMode { DOMAIN, DROP, KEEP }

/** Every cleanup rule that can be switched off with `--disable`. See `docs/cleanup-rules.md`. */
enum class RuleId(
    val id: String,
    val summary: String,
) {
    ROTATED_TEXT("rotated-text", "text that is not horizontal, such as margin notices"),
    PAGE_NUMBERS("page-numbers", "page numbers at the top and bottom of pages"),
    RUNNING_HEADERS("running-headers", "headers and footers that repeat on nearby pages"),
    LEGAL_NOTICES("legal-notices", "copyright and licensing lines at the top and bottom of pages"),
    COPYRIGHT_PAGE("copyright-page", "the copyright page near the front or back of the book"),
    CONTENTS_AND_INDEX("contents-and-index", "table-of-contents and index pages"),
    FOOTNOTES("footnotes", "footnote text at the bottom of pages"),
    FOOTNOTE_MARKERS("footnote-markers", "footnote reference marks inside sentences"),
    URLS("urls", "shortening or removing web addresses (see --urls)"),
    ;

    companion object {
        fun fromId(id: String): RuleId? = entries.firstOrNull { it.id == id }
    }
}

/** Everything that controls a conversion. */
data class ConversionOptions(
    val pages: PageSelection = PageSelection.ALL,
    val pageMarkers: PageMarkers = PageMarkers.LINE,
    val urls: UrlMode = UrlMode.DOMAIN,
    val disabledRules: Set<RuleId> = emptySet(),
)
