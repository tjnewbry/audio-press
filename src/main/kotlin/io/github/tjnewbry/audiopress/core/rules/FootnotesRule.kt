package io.github.tjnewbry.audiopress.core.rules

import io.github.tjnewbry.audiopress.core.CleanupRule
import io.github.tjnewbry.audiopress.core.LayoutDocument
import io.github.tjnewbry.audiopress.core.Removal
import io.github.tjnewbry.audiopress.core.Removals
import io.github.tjnewbry.audiopress.core.RuleId
import io.github.tjnewbry.audiopress.core.keptLines
import io.github.tjnewbry.audiopress.core.keptPages
import io.github.tjnewbry.audiopress.core.removedBy

/**
 * `footnotes`: removes the block of small-print lines at the bottom of a page, as long as there is
 * body-size text above it. Runs after the zone rules, so footers are already gone.
 */
object FootnotesRule : CleanupRule {
    override val id = RuleId.FOOTNOTES

    /** Footnote text is at most this share of the body font size. */
    private const val MAX_SIZE = 0.85f

    override fun apply(
        document: LayoutDocument,
        removed: Removals,
    ): List<Removal> {
        val limit = MAX_SIZE * document.stats.bodyFontSize
        return document.keptPages(removed).flatMap { page ->
            val lines = page.keptLines(removed).filter { it.horizontal }.sortedBy { it.baseline }
            val block = lines.takeLastWhile { it.fontSize <= limit }
            if (block.size == lines.size) emptyList() else block.map { it.removedBy(id, "small print at the bottom of the page") }
        }
    }
}
