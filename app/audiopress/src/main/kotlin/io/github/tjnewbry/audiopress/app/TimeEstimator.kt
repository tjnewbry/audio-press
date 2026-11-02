package io.github.tjnewbry.audiopress.app

/**
 * Estimates how long a book takes to read aloud and how much of it has been read. It starts at [priorWpm]
 * words per minute (the speech engine's default is about 170) and refines the rate as utterances are
 * measured: the prior counts as [PRIOR_WORDS] words of evidence, so a few measurements move it a little and
 * many measurements take over.
 */
class TimeEstimator(
    private val wordsPerUtterance: List<Int>,
    priorWpm: Int = DEFAULT_WPM,
) {
    private val priorSecondsPerWord = 60.0 / priorWpm
    private var measuredWords = 0
    private var measuredSeconds = 0.0
    private val wordsBefore: IntArray =
        IntArray(wordsPerUtterance.size + 1).also { sums ->
            wordsPerUtterance.forEachIndexed { i, count -> sums[i + 1] = sums[i] + count }
        }

    val secondsPerWord: Double
        get() = (PRIOR_WORDS * priorSecondsPerWord + measuredSeconds) / (PRIOR_WORDS + measuredWords)

    val wordsPerMinute: Double get() = 60.0 / secondsPerWord

    /** Records that speaking [words] words took [seconds]. Tiny or implausible samples are ignored. */
    fun record(
        words: Int,
        seconds: Double,
    ) {
        if (words < MIN_SAMPLE_WORDS || seconds <= 0.0) return
        val wpm = words / seconds * 60.0
        if (wpm < MIN_WPM || wpm > MAX_WPM) return
        measuredWords += words
        measuredSeconds += seconds
    }

    fun totalSeconds(): Double = wordsBefore.last() * secondsPerWord

    /** Time before the word that is [wordsIntoUtterance] words into utterance number [utterance]. */
    fun elapsedSeconds(
        utterance: Int,
        wordsIntoUtterance: Int,
    ): Double = (wordsBefore[utterance.coerceIn(0, wordsPerUtterance.size)] + wordsIntoUtterance) * secondsPerWord

    companion object {
        const val DEFAULT_WPM = 170
        private const val PRIOR_WORDS = 100
        private const val MIN_SAMPLE_WORDS = 5
        private const val MIN_WPM = 60.0
        private const val MAX_WPM = 500.0
    }
}
