package io.github.tjnewbry.audiopress.core.rules

import io.github.tjnewbry.audiopress.core.CleanupRule
import io.github.tjnewbry.audiopress.core.LayoutDocument
import io.github.tjnewbry.audiopress.core.Removal
import io.github.tjnewbry.audiopress.core.Removals
import io.github.tjnewbry.audiopress.core.RuleId
import io.github.tjnewbry.audiopress.core.Zone
import io.github.tjnewbry.audiopress.core.Zones
import io.github.tjnewbry.audiopress.core.keptLines
import io.github.tjnewbry.audiopress.core.keptPages
import io.github.tjnewbry.audiopress.core.removedBy

/**
 * `running-headers`: removes zone lines that repeat, ignoring numbers and case, in the same zone of a
 * page within [WINDOW] pages. Checking nearby pages (not every page) handles books that alternate the
 * book title and the chapter title.
 */
object RunningHeadersRule : CleanupRule {
    override val id = RuleId.RUNNING_HEADERS

    private const val WINDOW = 2
    private const val MIN_LETTERS = 3

    private val DIGITS = Regex("""\d+""")
    private val PUNCTUATION = Regex("""[\p{P}\p{S}]""")
    private val WHITESPACE = Regex("""\s+""")
    private val ROMAN = Regex("""[ivxlcdm]+""")

    override fun apply(
        document: LayoutDocument,
        removed: Removals,
    ): List<Removal> {
        // Lines on removed pages still count as evidence that a header repeats.
        val signatures: Map<Pair<Int, Zone>, Set<String>> =
            document.pages
                .flatMap { page ->
                    page.lines
                        .filter { it.horizontal }
                        .mapNotNull { line -> Zones.of(line, page)?.let { zone -> (page.index to zone) to signature(line.text) } }
                }.groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }

        return document.keptPages(removed).flatMap { page ->
            page.keptLines(removed).filter { it.horizontal }.mapNotNull { line ->
                val zone = Zones.of(line, page)
                val signature = signature(line.text)
                if (zone == null || signature.count { it.isLetter() } < MIN_LETTERS) return@mapNotNull null
                val match =
                    (page.index - WINDOW..page.index + WINDOW).firstOrNull { other ->
                        other != page.index && signature in signatures[other to zone].orEmpty()
                    }
                match?.let { line.removedBy(id, "same as page $it") }
            }
        }
    }

    /**
     * The text with case, punctuation, digit runs, and leading or trailing roman numerals ignored.
     * Ignoring punctuation matters for scanned books, where OCR adds stray marks such as `114.`.
     */
    internal fun signature(text: String): String {
        val tokens =
            text
                .lowercase()
                .replace(PUNCTUATION, " ")
                .replace(DIGITS, "#")
                .split(WHITESPACE)
                .filter { it.isNotEmpty() }
                .toMutableList()
        if (tokens.size > 1 && ROMAN.matches(tokens.first())) tokens[0] = "#"
        if (tokens.size > 1 && ROMAN.matches(tokens.last())) tokens[tokens.lastIndex] = "#"
        return tokens.joinToString(" ")
    }
}
