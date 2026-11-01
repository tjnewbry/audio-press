package io.github.tjnewbry.audiopress.core

import java.util.Locale

/**
 * Writes the layout as plain text, for tuning rules and debugging (the hidden `--dump-layout` option).
 * Removed pages, lines, and words are marked with the rule that removed them.
 */
object LayoutDump {
    /** Words whose baseline is raised by more than this share of the line's font size are shown as `^{...}`. */
    private const val RAISED = 0.15f

    fun write(
        pages: List<LayoutPage>,
        removals: Removals,
        out: Appendable,
    ) {
        for (page in pages) {
            val skipped = removals.pageRemoval(page.index)?.let { "  -- skipped by ${it.rule.id}" } ?: ""
            out.appendLine("== Page ${page.index} (${format(page.width)} x ${format(page.height)} pt, ${page.lines.size} lines)$skipped")
            for (line in page.lines) {
                val box = line.box
                val rotated = if (line.horizontal) "" else "  rotated"
                val removed = if (skipped.isEmpty()) removals.lineRemoval(line)?.let { "  -- removed by ${it.rule.id}" } ?: "" else ""
                out.appendLine(
                    "  L${line.index}  y=${format(line.baseline)}  x=${format(box.left)}..${format(box.right)}" +
                        "  size=${format(line.fontSize)}$rotated  \"${describe(line, removals)}\"$removed",
                )
            }
        }
    }

    private fun describe(
        line: Line,
        removals: Removals,
    ): String =
        buildString {
            line.words.forEachIndexed { i, word ->
                if (i > 0 && !word.joinsPrevious) append(' ')
                val raised = line.horizontal && line.baseline - word.baseline > RAISED * line.fontSize
                val text = if (raised) "^{${word.text}}" else word.text
                val removedWord = !removals.isRemoved(line) && removals.isRemoved(line, i)
                append(if (removedWord) "[-$text]" else text)
            }
        }

    private fun format(value: Float): String = String.format(Locale.ROOT, "%.1f", value)
}
