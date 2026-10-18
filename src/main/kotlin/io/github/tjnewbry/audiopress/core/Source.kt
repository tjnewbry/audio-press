package io.github.tjnewbry.audiopress.core

import kotlin.math.max
import kotlin.math.min

/**
 * A rectangle in display space: PDF points (1/72 inch), origin at the top-left corner of the page
 * as a viewer shows it, with y increasing downward.
 */
data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** The smallest box that contains both this box and [other]. */
    fun union(other: Box): Box = Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    /** How far this box and [other] overlap vertically, in points; 0 when they do not overlap. */
    fun verticalOverlap(other: Box): Float = max(0f, min(bottom, other.bottom) - max(top, other.top))

    /** How far this box and [other] overlap horizontally, in points; 0 when they do not overlap. */
    fun horizontalOverlap(other: Box): Float = max(0f, min(right, other.right) - max(left, other.left))
}

/** One word as read from a page, before any cleanup. */
data class SourceWord(
    val text: String,
    val box: Box,
    /** The y position of the word's baseline, in display space. */
    val baseline: Float,
    val fontSize: Float,
    /** False for rotated or vertical text, such as notices printed up the page margin. */
    val horizontal: Boolean = true,
    /**
     * True when no space separates this word from the previous one, because both were split from the
     * same word in the PDF (for example a superscript footnote marker after a word).
     */
    val joinsPrevious: Boolean = false,
)

/** One page as read from a [TextSource]. [index] is the 1-based physical page number. */
data class SourcePage(
    val index: Int,
    val width: Float,
    val height: Float,
    val words: List<SourceWord>,
) {
    /** False when the page has no extractable text, which usually means it is a scanned image. */
    val hasText: Boolean get() = words.isNotEmpty()
}

/**
 * Where page text comes from (the pipeline's input port). Implemented with PDFBox now, and by an OCR
 * source in Milestone Charlie.
 */
interface TextSource : AutoCloseable {
    val pageCount: Int

    /** Reads one page. [pageIndex] is 1-based. */
    fun readPage(pageIndex: Int): SourcePage
}
