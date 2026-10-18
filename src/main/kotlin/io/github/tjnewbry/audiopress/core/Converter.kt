package io.github.tjnewbry.audiopress.core

import io.github.tjnewbry.audiopress.core.rules.DefaultRules

/** Something worth telling the user that did not stop the conversion. */
data class Warning(
    val page: Int,
    val message: String,
)

/** Everything a conversion produced. */
data class ConversionResult(
    val document: LayoutDocument,
    val transcript: Transcript,
    val removals: List<Removal>,
    val warnings: List<Warning>,
)

/** Runs the whole pipeline (see `docs/architecture.md#pipeline`). */
class Converter(
    private val rules: List<CleanupRule> = DefaultRules.all,
) {
    /** Converts [source]. [sourceName] is used in error messages. */
    fun convert(
        source: TextSource,
        sourceName: String,
        options: ConversionOptions,
    ): ConversionResult {
        val pages = options.pages.resolve(source.pageCount)
        if (pages.isEmpty()) throw NoPagesSelectedException(options.pages.toString(), source.pageCount)
        val layout = LayoutReader.read(source, pages)
        if (layout.all { it.lines.isEmpty() }) throw NoTextLayerException(sourceName)

        val document = LayoutDocument(layout, source.pageCount)
        var removals = Removals.NONE
        for (rule in rules) {
            if (rule.id !in options.disabledRules) removals += rule.apply(document, removals)
        }
        var transcript = Assembler.assemble(document, removals)
        if (RuleId.FOOTNOTE_MARKERS !in options.disabledRules) transcript = ScanMarkerRewriter.rewrite(transcript)
        if (RuleId.URLS !in options.disabledRules) transcript = UrlRewriter.rewrite(transcript, options.urls)

        val warnings = layout.filter { it.lines.isEmpty() }.map { Warning(it.index, NO_TEXT_WARNING) }
        return ConversionResult(document, transcript, removals.all, warnings)
    }

    companion object {
        const val NO_TEXT_WARNING = "no text layer (probably a scanned image)"
    }
}
