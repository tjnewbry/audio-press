package io.github.tjnewbry.audiopress.app

/**
 * The rules of the import screen, kept free of Android so they can be tested: which pages of a PDF are chosen, how a typed
 * number is corrected, how the book's name follows the range, what the "Mark as" popup offers, and when Import asks first.
 *
 * Pages are 1-based. A boundary is a place between pages: boundary `k` lies below page `k` and above page `k + 1`, so
 * boundary 0 is the top of the first page and boundary `total` is the bottom of the last one. A range `start..end` is
 * marked by the boundary `start - 1` (its top) and the boundary `end` (its bottom).
 */
data class PageRange(
    val start: Int,
    val end: Int,
) {
    /** The boundary above the first imported page. */
    val startBoundary: Int get() = start - 1

    /** The boundary below the last imported page. */
    val endBoundary: Int get() = end

    fun contains(page: Int): Boolean = page in start..end

    fun isWhole(total: Int): Boolean = start == 1 && end == total

    val size: Int get() = end - start + 1

    fun toIntRange(): IntRange = start..end

    companion object {
        fun whole(total: Int) = PageRange(1, total)
    }
}

/** What the "Mark as" popup offers at one boundary. */
enum class MarkOption { START, END }

/**
 * The options offered at [boundary] for [range] in a PDF of [total] pages. The zone between the markers offers both, the zone
 * before the start marker only "start", the zone after the end marker only "end". The two marker boundaries themselves offer
 * nothing (they are already marked), and neither does a boundary that does not exist.
 */
fun markOptions(
    boundary: Int,
    range: PageRange,
    total: Int,
): List<MarkOption> {
    if (boundary !in 0..total || boundary == range.startBoundary || boundary == range.endBoundary) return emptyList()
    return buildList {
        if (boundary + 1 <= range.end) add(MarkOption.START) // the page below the boundary must not come after the end
        if (boundary >= range.start) add(MarkOption.END) // the page above the boundary must not come before the start
    }
}

/** The range after the user chose [option] at [boundary]. Only valid choices ([markOptions]) change it. */
fun markAt(
    boundary: Int,
    option: MarkOption,
    range: PageRange,
    total: Int,
): PageRange =
    if (option !in markOptions(boundary, range, total)) {
        range
    } else if (option == MarkOption.START) {
        range.copy(start = boundary + 1)
    } else {
        range.copy(end = boundary)
    }

/**
 * A number box's text after the user typed [text]: digits only, no leading zeros, a zero or a number longer than the PDF
 * corrected to the nearest page, and empty stays empty (the user is still typing).
 */
fun correctTyped(
    text: String,
    total: Int,
): String {
    val digits = text.filter { it in '0'..'9' }
    if (digits.isEmpty()) return ""
    val value =
        digits
            .trimStart('0')
            .take(total.toString().length + 1)
            .ifEmpty { "0" }
            .toLong()
    return value.coerceIn(1L, total.toLong()).toString()
}

/**
 * The range after the start box was typed to [typed]. While typing, a start that would come after the end is not taken (the
 * range stays as it was; [settle] corrects the box when the user is done), so the range is never nonsensical.
 */
fun typeStart(
    range: PageRange,
    typed: Int?,
): PageRange = if (typed != null && typed in 1..range.end) range.copy(start = typed) else range

/** Like [typeStart], for the end box: it must lie from the start to the last page. */
fun typeEnd(
    range: PageRange,
    typed: Int?,
    total: Int,
): PageRange = if (typed != null && typed in range.start..total) range.copy(end = typed) else range

/** Corrects the two boxes' values when the user is done typing: an empty box keeps the range's number; the rest is clamped. */
fun settle(
    range: PageRange,
    typedStart: Int?,
    typedEnd: Int?,
    total: Int,
): PageRange {
    val start = (typedStart ?: range.start).coerceIn(1, range.end)
    val end = (typedEnd ?: range.end).coerceIn(start, total)
    return PageRange(start, end)
}

/** Whether [range] is possible for a PDF of [total] pages. */
fun isValid(
    range: PageRange,
    total: Int,
): Boolean = range.start >= 1 && range.end <= total && range.start <= range.end

/** The name a book gets before the user types one: the PDF's own title, else the file name without ".pdf". */
fun defaultImportName(
    title: String?,
    fileName: String,
): String = title?.trim()?.takeIf { it.isNotEmpty() } ?: titleFromFileName(fileName)

/** The suffix that tells which pages were imported, such as " (pp. 12-340)", or null for the whole PDF. */
fun rangeSuffix(
    range: PageRange,
    total: Int,
): String? =
    when {
        range.isWhole(total) -> null
        range.start == range.end -> " (p. ${range.start})"
        else -> " (pp. ${range.start}-${range.end})"
    }

/**
 * The name shown and used for the book. A name the user typed ([typedName] not null) is never touched; otherwise it is the
 * [baseName] with the range suffix, which disappears when the range is the whole PDF again.
 */
fun importName(
    baseName: String,
    typedName: String?,
    range: PageRange,
    total: Int,
): String = typedName ?: (baseName + rangeSuffix(range, total).orEmpty())

/**
 * Whether tapping Import must first confirm the name: yes when the user did anything on the screen ([interacted]) and did not
 * type a name of their own. Import as the very first touch goes straight ahead.
 */
fun needsNameConfirmation(
    interacted: Boolean,
    typedName: String?,
): Boolean = interacted && typedName == null

/**
 * The page at a given place in the list, for the bubble beside the scroll thumb: the page that lies at the same height of the
 * screen as the thumb lies in its track. [thumb] is 0 to 1; [scrollPx] is how far the list is scrolled, [viewportPx] the list's
 * height and [pagePx] the height of one page with its gap. Reaches page 1 at the top and [total] at the bottom.
 */
fun pageUnderThumb(
    thumb: Float,
    scrollPx: Float,
    viewportPx: Float,
    pagePx: Float,
    total: Int,
): Int {
    if (pagePx <= 0f) return 1
    val y = scrollPx + thumb.coerceIn(0f, 1f) * viewportPx
    return (y / pagePx).toInt().plus(1).coerceIn(1, total)
}
