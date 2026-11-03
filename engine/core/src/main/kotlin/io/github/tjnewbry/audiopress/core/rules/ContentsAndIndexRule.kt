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
        Regex("""(?:\.{2,}|\s)\s*(?:[0-9]{1,4}|[ivxlcdm]{1,7})(?:\s*[,\x{2013}\-]\s*[0-9]{1,4})*\s*$""", RegexOption.IGNORE_CASE)

    override fun apply(
        document: LayoutDocument,
        removed: Removals,
    ): List<Removal> =
        document.keptPages(removed).mapNotNull { page ->
            val lines = entryTexts(page.keptLines(removed).filter { it.horizontal }.map { it.text })
            val entries = lines.count { isEntry(it) }
            if (lines.size >= MIN_LINES && entries >= MIN_SHARE * lines.size) {
                Removal(id, PageTarget(page.index), "", "$entries of ${lines.size} lines end in a page number")
            } else {
                null
            }
        }

    /** A line holding only a page number, possibly letter-spaced (`2 8`). */
    private val PAGE_NUMBER_ONLY = Regex("""^\s*[0-9](?:\s?[0-9]){0,3}\s*$""")

    /**
     * The page's entries: each line, except that a line holding only a page number is joined to the line before it,
     * for contents pages that set the number under its title. A number after another number stays a line of its own.
     */
    private fun entryTexts(lines: List<String>): List<String> {
        val entries = mutableListOf<String>()
        for (text in lines) {
            val previous = entries.lastOrNull()
            if (previous != null && PAGE_NUMBER_ONLY.matches(text) && previous.any { it.isLetter() } &&
                !PAGE_REFERENCE.containsMatchIn(previous)
            ) {
                entries[entries.lastIndex] = "$previous $text"
            } else {
                entries += text
            }
        }
        return entries
    }

    private fun isEntry(text: String): Boolean = text.count { it.isLetter() } >= MIN_LETTERS && PAGE_REFERENCE.containsMatchIn(text)
}
