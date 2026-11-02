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
    /**
     * Converts [source]. [sourceName] is used in error messages.
     *
     * [cancellation] lets another thread stop the conversion, which then throws [ConversionCancelledException]
     * and returns nothing. [onProgress] is called on the calling thread, after each page is read and after each
     * later step; see [ConversionProgress]. If it throws, the conversion stops with that exception. Both are
     * optional, and without them this behaves exactly as before.
     */
    fun convert(
        source: TextSource,
        sourceName: String,
        options: ConversionOptions,
        cancellation: CancellationToken? = null,
        onProgress: (ConversionProgress) -> Unit = {},
    ): ConversionResult {
        val pageCount = source.pageCount
        val pages = options.pages.resolve(pageCount)
        if (pages.isEmpty()) throw NoPagesSelectedException(options.pages.toString(), pageCount)
        onProgress(ConversionProgress(ConversionStage.READING, 0, pages.size))
        val layout =
            LayoutReader.read(source, pages, cancellation) { done ->
                onProgress(ConversionProgress(ConversionStage.READING, done, pages.size))
            }
        if (layout.all { it.lines.isEmpty() }) throw NoTextLayerException(sourceName)

        val enabled = rules.filter { it.id !in options.disabledRules }
        val steps = enabled.size + 1 // each rule, then the assembly
        var step = 0
        onProgress(ConversionProgress(ConversionStage.ANALYZING, step, steps))
        val document = LayoutDocument(layout, pageCount, source.firstPage)
        var removals = Removals.NONE
        for (rule in enabled) {
            cancellation?.throwIfCancelled()
            removals += rule.apply(document, removals)
            onProgress(ConversionProgress(ConversionStage.ANALYZING, ++step, steps))
        }
        cancellation?.throwIfCancelled()
        var transcript = Assembler.assemble(document, removals)
        if (RuleId.FOOTNOTE_MARKERS !in options.disabledRules) transcript = ScanMarkerRewriter.rewrite(transcript)
        if (RuleId.URLS !in options.disabledRules) transcript = UrlRewriter.rewrite(transcript, options.urls)
        onProgress(ConversionProgress(ConversionStage.ANALYZING, steps, steps))

        val warnings = layout.filter { it.lines.isEmpty() }.map { Warning(it.index, NO_TEXT_WARNING) }
        return ConversionResult(document, transcript, removals.all, warnings)
    }

    companion object {
        const val NO_TEXT_WARNING = "no text layer (probably a scanned image)"
    }
}
