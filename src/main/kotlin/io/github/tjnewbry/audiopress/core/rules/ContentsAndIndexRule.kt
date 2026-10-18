package io.github.tjnewbry.audiopress.core.rules

import io.github.tjnewbry.audiopress.core.CleanupRule
import io.github.tjnewbry.audiopress.core.LayoutDocument
import io.github.tjnewbry.audiopress.core.PageTarget
import io.github.tjnewbry.audiopress.core.Removal
import io.github.tjnewbry.audiopress.core.Removals
import io.github.tjnewbry.audiopress.core.RuleId
import io.github.tjnewbry.audiopress.core.keptLines
import io.github.tjnewbry.audiopress.core.keptPages

/** `contents-and-index`: skips pages where most lines are entries that end in a page reference. */
object ContentsAndIndexRule : CleanupRule {
    override val id = RuleId.CONTENTS_AND_INDEX

    private const val MIN_LINES = 8
    private const val MIN_SHARE = 0.6

    /** Entries need some words, so lines of equations (such as `y = 2`) do not count. */
    private const val MIN_LETTERS = 4

    /** A page number or roman numeral at the end of the line, after a space or dot leaders, optionally followed by more numbers. */
    private val PAGE_REFERENCE =
        Regex("""(?:\.{2,}|\s)\s*(?:\d{1,4}|[ivxlcdm]{1,7})(?:\s*[,\x{2013}-]\s*\d{1,4})*\s*$""", RegexOption.IGNORE_CASE)

    override fun apply(
        document: LayoutDocument,
        removed: Removals,
    ): List<Removal> =
        document.keptPages(removed).mapNotNull { page ->
            val lines = page.keptLines(removed).filter { it.horizontal }
            val entries = lines.count { isEntry(it.text) }
            if (lines.size >= MIN_LINES && entries >= MIN_SHARE * lines.size) {
                Removal(id, PageTarget(page.index), "", "$entries of ${lines.size} lines end in a page number")
            } else {
                null
            }
        }

    private fun isEntry(text: String): Boolean = text.count { it.isLetter() } >= MIN_LETTERS && PAGE_REFERENCE.containsMatchIn(text)
}
