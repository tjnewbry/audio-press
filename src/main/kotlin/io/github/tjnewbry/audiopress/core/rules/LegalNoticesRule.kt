package io.github.tjnewbry.audiopress.core.rules

import io.github.tjnewbry.audiopress.core.CleanupRule
import io.github.tjnewbry.audiopress.core.LayoutDocument
import io.github.tjnewbry.audiopress.core.Line
import io.github.tjnewbry.audiopress.core.ReadingOrder
import io.github.tjnewbry.audiopress.core.Removal
import io.github.tjnewbry.audiopress.core.Removals
import io.github.tjnewbry.audiopress.core.RuleId
import io.github.tjnewbry.audiopress.core.Zones
import io.github.tjnewbry.audiopress.core.keptLines
import io.github.tjnewbry.audiopress.core.keptPages
import io.github.tjnewbry.audiopress.core.removedBy

/**
 * `legal-notices`: removes copyright and licensing lines in the top and bottom zones, and small-print
 * license notices anywhere on the page (such as "This page is shared under a CC BY license...").
 */
object LegalNoticesRule : CleanupRule {
    override val id = RuleId.LEGAL_NOTICES

    /** Matched case-insensitively in zone lines. The first entry is the copyright sign. */
    private val ZONE_MARKERS =
        listOf(Char(0x00A9).toString(), "copyright", "all rights reserved", "downloaded from", "licensed to", "personal use only")

    /** Matched case-insensitively in small-print blocks anywhere on the page. */
    private val LICENSE_MARKERS = listOf("is shared under a", "licensed under a", "creative commons")

    /** Small print is below this share of the body font size. */
    private const val SMALL_PRINT = 0.95f

    override fun apply(
        document: LayoutDocument,
        removed: Removals,
    ): List<Removal> {
        val smallPrint = SMALL_PRINT * document.stats.bodyFontSize
        return document.keptPages(removed).flatMap { page ->
            val lines = ReadingOrder.order(page.keptLines(removed).filter { it.horizontal })
            val zoneNotices =
                lines.filter { Zones.of(it, page) != null }.mapNotNull { line ->
                    val text = line.text.lowercase()
                    ZONE_MARKERS.firstOrNull { it in text }?.let { line.removedBy(id, "contains \"$it\"") }
                }
            val noticeLines = zoneNotices.map { it.target }.toSet()
            val licenseNotices =
                smallPrintBlocks(lines, smallPrint).flatMap { block ->
                    val text = block.joinToString(" ") { it.text }.lowercase()
                    val marker = LICENSE_MARKERS.firstOrNull { it in text } ?: return@flatMap emptyList()
                    block.map { it.removedBy(id, "small-print notice containing \"$marker\"") }.filter { it.target !in noticeLines }
                }
            zoneNotices + licenseNotices
        }
    }

    /** Runs of consecutive lines (in reading order) set smaller than [limit]. */
    private fun smallPrintBlocks(
        lines: List<Line>,
        limit: Float,
    ): List<List<Line>> {
        val blocks = mutableListOf<MutableList<Line>>()
        var previousSmall = false
        for (line in lines) {
            val small = line.fontSize < limit
            if (small && previousSmall) {
                blocks.last() += line
            } else if (small) {
                blocks += mutableListOf(line)
            }
            previousSmall = small
        }
        return blocks
    }
}
