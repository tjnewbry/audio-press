package io.github.tjnewbry.audiopress.app

/** A point in the book's time: line number [index] and [intoMs] milliseconds into that line. */
data class TimePoint(
    val index: Int,
    val intoMs: Long,
)

/**
 * The one time scale the seeker bar, the buffer and the scroll arrow all share. Each utterance (a line of the reader)
 * has a duration: its real one for the leading lines that are baked ([bakedMs], clip by clip), and an estimate for the
 * rest. The estimate is the speed the baked part was spoken at once enough of it exists ([MIN_CALIBRATION_WORDS]
 * words), otherwise [fallbackSecondsPerWord] (the words-per-minute estimate, refined by live speech). So the total is
 * the baked time (exact) plus the estimated time of what is not baked yet.
 *
 * A baked clip with no length counts as not baked, and baked clips are only counted from the start of the book on, in
 * order, which is the order a bake makes them.
 */
class BookTimeline(
    private val wordCounts: List<Int>,
    bakedMs: List<Long> = emptyList(),
    fallbackSecondsPerWord: Double = 60.0 / TimeEstimator.DEFAULT_WPM,
) {
    /** How many leading lines are baked. */
    val bakedCount: Int = bakedMs.take(wordCounts.size).takeWhile { it > 0 }.size

    private val bakedDurations: List<Long> = bakedMs.take(bakedCount)
    private val msPerWord: Double
    private val starts = LongArray(wordCounts.size + 1)

    init {
        val bakedWords = wordCounts.take(bakedCount).sum()
        msPerWord =
            if (bakedWords >=
                MIN_CALIBRATION_WORDS
            ) {
                bakedDurations.sum().toDouble() / bakedWords
            } else {
                fallbackSecondsPerWord * MS_PER_SECOND
            }
        for (i in wordCounts.indices) starts[i + 1] = starts[i] + durationMs(i)
    }

    /** The length of the whole book: baked time plus the estimate for the rest. */
    val totalMs: Long get() = starts.last()

    /** The baked time, which is exact. */
    val bakedTotalMs: Long get() = starts[bakedCount]

    /** The share of the book's time that is baked, 0 to 1. 0 when nothing is baked, and 1 once everything is. */
    val bufferFraction: Float
        get() = if (totalMs <= 0 || bakedCount == 0) 0f else (bakedTotalMs.toDouble() / totalMs).toFloat().coerceIn(0f, 1f)

    /** The length of line [index]: real when it is baked, else estimated from its word count. */
    fun durationMs(index: Int): Long =
        when {
            index < 0 || index >= wordCounts.size -> 0L
            index < bakedCount -> bakedDurations[index]
            else -> (wordCounts[index] * msPerWord).toLong()
        }

    /** The time before line [index]. */
    fun startMs(index: Int): Long = starts[index.coerceIn(0, wordCounts.size)]

    /** The time before the word that is [wordsInto] words into line [index], taking the words as evenly spaced. */
    fun elapsedMs(
        index: Int,
        wordsInto: Int,
    ): Long {
        val words = wordCounts.getOrNull(index)?.coerceAtLeast(1) ?: return startMs(index)
        return startMs(index) + durationMs(index) * wordsInto.coerceIn(0, words) / words
    }

    /**
     * The same, moved on by [sinceMs], the time since the word was reported, so the position runs smoothly between words.
     * When the engine reports words ([rangesReported]) it moves on by at most one word, as the next report corrects it; when
     * it does not, it carries on at the estimated speed to the end of the line.
     */
    fun elapsedMs(
        index: Int,
        wordsInto: Int,
        sinceMs: Long,
        rangesReported: Boolean,
    ): Long {
        val base = elapsedMs(index, wordsInto)
        val lineEnd = startMs(index) + durationMs(index)
        val wordMs = durationMs(index) / (wordCounts.getOrNull(index)?.coerceAtLeast(1) ?: 1)
        val room = if (rangesReported) wordMs else lineEnd - base
        return base + sinceMs.coerceIn(0L, room.coerceAtLeast(0L))
    }

    /** [ms] as a share of the book, 0 to 1. */
    fun fraction(ms: Long): Float = if (totalMs <= 0) 0f else (ms.toDouble() / totalMs).toFloat().coerceIn(0f, 1f)

    /** The line that holds time [ms] (clamped to the book), and how far into it that is. */
    fun lineAt(ms: Long): TimePoint {
        if (wordCounts.isEmpty()) return TimePoint(0, 0)
        val time = ms.coerceIn(0, totalMs)
        var low = 0
        var high = wordCounts.size - 1
        while (low < high) { // the last line that starts at or before the time
            val mid = (low + high + 1) / 2
            if (starts[mid] <= time) low = mid else high = mid - 1
        }
        return TimePoint(low, (time - starts[low]).coerceAtMost(durationMs(low)))
    }

    /** The time at the point [share] (0 to 1) of the way down line [index]. */
    fun msAt(
        index: Int,
        share: Double,
    ): Long = startMs(index) + (durationMs(index) * share.coerceIn(0.0, 1.0)).toLong()

    /**
     * Where the scroll arrow points when the text box shows the time from [topMs] (its top edge) to [bottomMs] (its
     * bottom edge). A single edge cannot reach both ends of the bar, so the point slides from the top edge at the start
     * of the book to the bottom edge at its end: scrolled to the very top it reads 0, scrolled to the very bottom it
     * reads the total, and in between it lies inside the text on screen.
     */
    fun indicatorMs(
        topMs: Long,
        bottomMs: Long,
    ): Long {
        val span = (bottomMs - topMs).coerceAtLeast(0)
        val room = totalMs - span
        if (room <= 0) return topMs + span / 2 // the whole book is on screen
        val slide = (topMs.toDouble() / room).coerceIn(0.0, 1.0)
        return (topMs + span * slide).toLong().coerceIn(0, totalMs)
    }

    companion object {
        /** How many baked words are needed before the baked speed is used to estimate the unbaked rest. */
        const val MIN_CALIBRATION_WORDS = 30
        private const val MS_PER_SECOND = 1000.0
    }
}

/**
 * Whether the text box follows the line being read. It starts locked: the box scrolls with the player. The user
 * scrolling by hand unlocks it, which shows the scroll arrow and the "Return to player position" button; returning
 * (or tapping a line, or seeking) locks it again.
 */
data class ScrollFollow(
    val locked: Boolean = true,
) {
    fun userScrolled() = ScrollFollow(false)

    fun returned() = ScrollFollow(true)

    /** The scroll arrow on the seeker bar shows only while the user has scrolled away. */
    val showArrow: Boolean get() = !locked

    val showReturnButton: Boolean get() = !locked

    /** The box moves by itself to the playing line only while locked. */
    val autoScrolls: Boolean get() = locked
}
