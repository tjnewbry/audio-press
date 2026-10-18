package io.github.tjnewbry.audiopress.core.rules

import io.github.tjnewbry.audiopress.core.CleanupRule
import io.github.tjnewbry.audiopress.core.LayoutDocument
import io.github.tjnewbry.audiopress.core.PageTarget
import io.github.tjnewbry.audiopress.core.Removal
import io.github.tjnewbry.audiopress.core.Removals
import io.github.tjnewbry.audiopress.core.RuleId
import io.github.tjnewbry.audiopress.core.keptPages

/** `copyright-page`: skips a page near the front or back of the PDF that carries copyright details. */
object CopyrightPageRule : CleanupRule {
    override val id = RuleId.COPYRIGHT_PAGE

    private const val FRONT_PAGES = 10
    private const val BACK_PAGES = 5
    private val MARKERS = listOf("all rights reserved", "isbn", "library of congress")

    override fun apply(
        document: LayoutDocument,
        removed: Removals,
    ): List<Removal> =
        document
            .keptPages(removed)
            .filter { it.index <= FRONT_PAGES || it.index > document.pageCount - BACK_PAGES }
            .mapNotNull { page ->
                val text = page.lines.joinToString(" ") { it.text }.lowercase()
                MARKERS.firstOrNull { it in text }?.let { Removal(id, PageTarget(page.index), "", "contains \"$it\"") }
            }
}
