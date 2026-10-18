package io.github.tjnewbry.audiopress.core.rules

import io.github.tjnewbry.audiopress.core.CleanupRule
import io.github.tjnewbry.audiopress.core.LayoutDocument
import io.github.tjnewbry.audiopress.core.Removal
import io.github.tjnewbry.audiopress.core.Removals
import io.github.tjnewbry.audiopress.core.RuleId
import io.github.tjnewbry.audiopress.core.Zones
import io.github.tjnewbry.audiopress.core.keptLines
import io.github.tjnewbry.audiopress.core.keptPages
import io.github.tjnewbry.audiopress.core.removedBy

/**
 * `page-numbers`: removes zone lines that are only a page number, such as `12`, `xii`, `- 12 -`, or
 * `Page 12 of 300`, and zone lines of at most [MAX_MARK] characters (stray marks, common in scans).
 */
object PageNumbersRule : CleanupRule {
    override val id = RuleId.PAGE_NUMBERS

    private const val MAX_MARK = 2

    private val PAGE_NUMBER =
        Regex(
            """[-\x{2013}\x{2014}(\[]?\s*(?:page\s+)?(?:\d{1,4}|[ivxlcdm]{1,7})(?:\s+of\s+\d{1,4})?\s*[-\x{2013}\x{2014})\]]?""",
            RegexOption.IGNORE_CASE,
        )

    override fun apply(
        document: LayoutDocument,
        removed: Removals,
    ): List<Removal> =
        document.keptPages(removed).flatMap { page ->
            page.keptLines(removed).filter { it.horizontal && Zones.of(it, page) != null }.mapNotNull { line ->
                when {
                    PAGE_NUMBER.matches(line.text) -> line.removedBy(id)
                    line.text.count { !it.isWhitespace() } <= MAX_MARK -> line.removedBy(id, "stray mark")
                    else -> null
                }
            }
        }
}
