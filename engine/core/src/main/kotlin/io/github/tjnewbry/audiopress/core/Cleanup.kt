package io.github.tjnewbry.audiopress.core

import kotlin.math.roundToInt

/** The selected pages of a document after line building, plus the measurements the rules use. */
data class LayoutDocument(
    val pages: List<LayoutPage>,
    /** The total number of pages in the PDF, including pages not selected with `--pages`. */
    val pageCount: Int,
) {
    val stats: DocumentStats = DocumentStats.measure(pages)
}

/** Measurements of the book itself, so rules adapt to its format (pipeline stage 5). */
data class DocumentStats(
    /** The font size used by the most characters. */
    val bodyFontSize: Float,
    /** The most common distance between baselines of consecutive body-size lines, if there are any. */
    val linePitch: Float?,
) {
    companion object {
        private const val DEFAULT_BODY_SIZE = 11f

        /** Lines within this share of the body size count as body text when measuring line pitch. */
        private const val BODY_TOLERANCE = 0.15f

        fun measure(pages: List<LayoutPage>): DocumentStats {
            val lines = pages.flatMap { page -> page.lines.filter { it.horizontal } }
            val body =
                lines
                    .flatMap { it.words }
                    .groupingBy { roundToHalf(it.fontSize) }
                    .fold(0) { characters, word -> characters + word.text.length }
                    .maxByOrNull { it.value }
                    ?.key ?: DEFAULT_BODY_SIZE
            val pitch =
                pages
                    .flatMap { page ->
                        page.lines
                            .filter { it.horizontal && isBodySize(it.fontSize, body) }
                            .sortedBy { it.baseline }
                            .zipWithNext { a, b -> b.baseline - a.baseline }
                            .filter { it > 0.8f * body && it < 3f * body }
                    }.groupingBy { roundToHalf(it) }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
            return DocumentStats(body, pitch)
        }

        private fun isBodySize(
            size: Float,
            body: Float,
        ): Boolean = size >= (1 - BODY_TOLERANCE) * body && size <= (1 + BODY_TOLERANCE) * body

        private fun roundToHalf(value: Float): Float = (value * 2).roundToInt() / 2f
    }
}

/** The top and bottom margins of a page, where headers, footers, and page numbers live. */
enum class Zone { TOP, BOTTOM }

object Zones {
    /** The share of the page height that each zone covers. */
    const val SHARE = 0.09f

    /** The zone that [line] lies entirely inside, or null for the body of the page. */
    fun of(
        line: Line,
        page: LayoutPage,
    ): Zone? =
        when {
            line.box.bottom <= SHARE * page.height -> Zone.TOP
            line.box.top >= (1 - SHARE) * page.height -> Zone.BOTTOM
            else -> null
        }
}

/** What a removal applies to. Pages and lines are identified by page number and line index. */
sealed interface Target {
    val page: Int
}

data class PageTarget(
    override val page: Int,
) : Target

data class LineTarget(
    override val page: Int,
    val line: Int,
) : Target

data class WordTarget(
    override val page: Int,
    val line: Int,
    val word: Int,
) : Target

/** One cleanup decision: [rule] removed [target], whose text was [text], because of [reason]. */
data class Removal(
    val rule: RuleId,
    val target: Target,
    val text: String,
    val reason: String = "",
)

/** The removals made so far, with fast lookups. A removed page removes its lines; a removed line removes its words. */
class Removals private constructor(
    val all: List<Removal>,
) {
    private val byTarget: Map<Target, Removal> = all.reversed().associateBy { it.target }

    fun pageRemoval(page: Int): Removal? = byTarget[PageTarget(page)]

    fun lineRemoval(line: Line): Removal? = pageRemoval(line.page) ?: byTarget[LineTarget(line.page, line.index)]

    fun wordRemoval(
        line: Line,
        word: Int,
    ): Removal? = lineRemoval(line) ?: byTarget[WordTarget(line.page, line.index, word)]

    fun isRemoved(page: LayoutPage): Boolean = pageRemoval(page.index) != null

    fun isRemoved(line: Line): Boolean = lineRemoval(line) != null

    fun isRemoved(
        line: Line,
        word: Int,
    ): Boolean = wordRemoval(line, word) != null

    operator fun plus(more: List<Removal>): Removals = if (more.isEmpty()) this else Removals(all + more)

    companion object {
        val NONE = Removals(emptyList())

        fun of(removals: List<Removal>): Removals = Removals(removals)
    }
}

/**
 * One cleanup rule (see docs/architecture.md#design-decisions). A rule never changes text: it returns
 * what should be removed, and it ignores anything an earlier rule already removed.
 */
interface CleanupRule {
    val id: RuleId

    fun apply(
        document: LayoutDocument,
        removed: Removals,
    ): List<Removal>
}

internal fun LayoutDocument.keptPages(removed: Removals): List<LayoutPage> = pages.filter { !removed.isRemoved(it) }

internal fun LayoutPage.keptLines(removed: Removals): List<Line> = lines.filter { !removed.isRemoved(it) }

internal fun Line.removedBy(
    rule: RuleId,
    reason: String = "",
): Removal = Removal(rule, LineTarget(page, index), text, reason)
