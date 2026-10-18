package io.github.tjnewbry.audiopress.core.rules

import io.github.tjnewbry.audiopress.core.CleanupRule
import io.github.tjnewbry.audiopress.core.LayoutDocument
import io.github.tjnewbry.audiopress.core.Line
import io.github.tjnewbry.audiopress.core.Removal
import io.github.tjnewbry.audiopress.core.Removals
import io.github.tjnewbry.audiopress.core.RuleId
import io.github.tjnewbry.audiopress.core.SourceWord
import io.github.tjnewbry.audiopress.core.TextNormalizer
import io.github.tjnewbry.audiopress.core.WordTarget
import io.github.tjnewbry.audiopress.core.keptLines
import io.github.tjnewbry.audiopress.core.keptPages

/**
 * `footnote-markers`: removes footnote reference marks inside sentences: raised, smaller words made of
 * 1 to 3 digits or footnote symbols, and words made of Unicode superscript digits.
 */
object FootnoteMarkersRule : CleanupRule {
    override val id = RuleId.FOOTNOTE_MARKERS

    private const val MAX_SIZE = 0.85f
    private const val MIN_RAISE = 0.2f

    /** Digits, or the symbols asterisk, dagger, double dagger, section sign, and pilcrow. */
    private val MARKER = Regex("""\d{1,3}|[*\x{2020}\x{2021}\x{00A7}\x{00B6}]{1,3}""")

    override fun apply(
        document: LayoutDocument,
        removed: Removals,
    ): List<Removal> =
        document.keptPages(removed).flatMap { page ->
            page.keptLines(removed).filter { it.horizontal }.flatMap { line ->
                line.words.indices
                    .filter { i -> !removed.isRemoved(line, i) && isMarker(line.words[i], line) }
                    .map { i ->
                        val before = line.words.getOrNull(i - 1)?.let { "after \"${it.text}\"" } ?: ""
                        Removal(id, WordTarget(line.page, line.index, i), line.words[i].text, before)
                    }
            }
        }

    private fun isMarker(
        word: SourceWord,
        line: Line,
    ): Boolean {
        if (word.text.all(TextNormalizer::isSuperscriptDigit)) return true
        val raised = line.baseline - word.baseline >= MIN_RAISE * line.fontSize
        val smaller = word.fontSize <= MAX_SIZE * line.fontSize
        return raised && smaller && MARKER.matches(word.text)
    }
}
