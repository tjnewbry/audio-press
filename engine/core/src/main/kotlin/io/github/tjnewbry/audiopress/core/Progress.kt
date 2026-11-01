package io.github.tjnewbry.audiopress.core

/** The two phases of a conversion, in order. */
enum class ConversionStage {
    /** Reading and normalizing the selected pages from the [TextSource]. This is almost all of the time. */
    READING,

    /** Running the cleanup rules, assembling paragraphs, and rewriting URLs. Fast, and cannot be interrupted midway. */
    ANALYZING,
}

/**
 * How far a conversion has got: [done] of [total] steps of [stage]. In [ConversionStage.READING] a step is one
 * selected page, so [total] is the number of selected pages. In [ConversionStage.ANALYZING] a step is one enabled
 * cleanup rule or the paragraph assembly. Within a stage [done] only goes up and the last report has `done == total`.
 */
data class ConversionProgress(
    val stage: ConversionStage,
    val done: Int,
    val total: Int,
) {
    /** The share of the whole conversion that is finished, 0 to 1, counting reading as [READING_SHARE] of the total. */
    val fraction: Float
        get() {
            val part = if (total == 0) 1f else done.toFloat() / total
            return if (stage == ConversionStage.READING) READING_SHARE * part else READING_SHARE + (1 - READING_SHARE) * part
        }

    companion object {
        /** Reading pages is almost all of the work, so a progress bar should spend most of its length on it. */
        const val READING_SHARE = 0.95f
    }
}

/**
 * Lets one thread stop a conversion running on another. Create one, pass it to [Converter.convert], and call
 * [cancel] from any thread (for example when the user taps Cancel). The conversion notices between pages and
 * between rules and throws [ConversionCancelledException]. A token cancels once and cannot be reset: make a new
 * one for the next conversion.
 */
class CancellationToken {
    @Volatile
    var isCancelled: Boolean = false
        private set

    fun cancel() {
        isCancelled = true
    }

    /** Throws [ConversionCancelledException] if [cancel] has been called. */
    fun throwIfCancelled() {
        if (isCancelled) throw ConversionCancelledException()
    }
}
