package io.github.tjnewbry.audiopress.core

/** Writes the `--report` file: every removal and warning, by page. See `docs/usage.md#report`. */
object ReportWriter {
    fun write(
        result: ConversionResult,
        inputName: String,
        options: ConversionOptions,
        out: Appendable,
    ) {
        val document = result.document
        val disabled = RuleId.entries.filter { it in options.disabledRules }
        out.appendLine("Audio Press report")
        out.appendLine("Input: $inputName (${document.pageCount} pages, ${document.pages.size} selected)")
        out.appendLine("Rules: ${RuleId.entries.filterNot { it in disabled }.joinToString { it.id }}")
        out.appendLine("Disabled: ${disabled.joinToString { it.id }.ifEmpty { "none" }}")

        val removalsByPage = result.removals.groupBy { it.target.page }
        val warningsByPage = result.warnings.groupBy { it.page }
        for (page in document.pages.map { it.index }) {
            val removals = removalsByPage[page].orEmpty()
            val warnings = warningsByPage[page].orEmpty()
            if (removals.isEmpty() && warnings.isEmpty()) continue
            out.appendLine()
            val skipped = removals.firstOrNull { it.target is PageTarget }
            if (skipped != null) {
                out.appendLine("Page $page: skipped by ${skipped.rule.id}${reason(skipped)}")
                continue
            }
            out.appendLine("Page $page:")
            for (removal in removals.sortedWith(compareBy({ lineOf(it.target) }, { wordOf(it.target) }))) {
                val kind = if (removal.target is WordTarget) "word" else "line"
                out.appendLine("  $kind removed by ${removal.rule.id}: \"${removal.text}\"${reason(removal)}")
            }
            warnings.forEach { out.appendLine("  warning: ${it.message}") }
        }

        val pages = result.removals.count { it.target is PageTarget }
        val lines = result.removals.count { it.target is LineTarget }
        val words = result.removals.count { it.target is WordTarget }
        out.appendLine()
        val warnings = result.warnings.size
        out.appendLine(
            "Summary: $lines lines, $words words, and $pages pages removed. $warnings ${if (warnings == 1) "warning" else "warnings"}.",
        )
    }

    private fun reason(removal: Removal): String = if (removal.reason.isEmpty()) "" else " (${removal.reason})"

    private fun lineOf(target: Target): Int =
        when (target) {
            is LineTarget -> target.line
            is WordTarget -> target.line
            is PageTarget -> -1
        }

    private fun wordOf(target: Target): Int = if (target is WordTarget) target.word else -1
}
