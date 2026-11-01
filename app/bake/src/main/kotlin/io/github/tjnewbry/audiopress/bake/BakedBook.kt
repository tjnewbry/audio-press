package io.github.tjnewbry.audiopress.bake

import java.io.File
import java.io.IOException

/** One baked utterance: its audio file and where its text came from in the book. */
data class BakedUtterance(
    /** Position in the book's utterance list, from 0. */
    val index: Int,
    /** The audio file name inside the bake folder. */
    val fileName: String,
    /** The engine `Utterance.segmentIndex`; with [start] and [end] it is the character range `[start, end)` in that segment's text. */
    val segmentIndex: Int,
    val start: Int,
    val end: Int,
    /** The book page the utterance starts on. */
    val page: Int,
    /** The real length of the audio file. */
    val durationMs: Long,
)

/** Where a playback position falls: utterance [utteranceIndex], [offsetMs] into it. */
data class BakedPosition(
    val utteranceIndex: Int,
    val offsetMs: Long,
    /** A guess at the character in the segment's text being spoken, by proportion; see [BakedBook]. */
    val approximateSegmentChar: Int,
)

/**
 * A finished (or partly finished) bake, read from its folder with [read]. It maps between audio positions and
 * utterances so a player can play the files as one continuous book.
 *
 * Positions are measured in milliseconds from the start of the whole book, with the utterances played one after
 * another. `synthesizeToFile` gives no timing for the words inside an utterance, so the player should highlight
 * by utterance (a paragraph, or a part of one); [BakedPosition.approximateSegmentChar] assumes an even speaking
 * pace across the utterance and is only good enough to scroll to roughly the right place.
 */
class BakedBook(
    val folder: File,
    /** Identifies the utterances and voice settings the audio was made from; see [BookBaker.bake]. */
    val fingerprint: String,
    val utterances: List<BakedUtterance>,
    /** True once every utterance has been baked. A player should only use complete bakes. */
    val isComplete: Boolean,
    /** The voice settings text the bake was made with (the `settingsKey` given to [BookBaker.bake]), if recorded. */
    val settingsKey: String? = null,
) {
    /** The start of each utterance in the whole book, in milliseconds. */
    private val startsMs: LongArray =
        LongArray(utterances.size).also { starts ->
            var total = 0L
            utterances.forEachIndexed { i, u ->
                starts[i] = total
                total += u.durationMs
            }
        }

    val totalDurationMs: Long = utterances.sumOf { it.durationMs }

    fun file(utterance: BakedUtterance): File = File(folder, utterance.fileName)

    /** Where the utterance [index] starts in the whole book, in milliseconds. */
    fun startMs(index: Int): Long = startsMs[index]

    /** The utterance playing at [positionMs] (clamped to the book) and how far into it, or null for an empty bake. */
    fun locate(positionMs: Long): BakedPosition? {
        if (utterances.isEmpty()) return null
        val position = positionMs.coerceIn(0, maxOf(0, totalDurationMs - 1))
        var low = 0
        var high = utterances.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (startsMs[mid] <= position) low = mid else high = mid - 1
        }
        val offset = position - startsMs[low]
        return BakedPosition(low, offset, approximateChar(utterances[low], offset))
    }

    /** The position in the whole book of [offsetMs] into utterance [index] (the reverse of [locate]). */
    fun positionMs(
        index: Int,
        offsetMs: Long = 0,
    ): Long = startsMs[index] + offsetMs.coerceIn(0, utterances[index].durationMs)

    /**
     * The position in the whole book of character [charOffset] of segment [segmentIndex], for starting playback
     * where the reader tapped. It finds the utterance holding that character and estimates the time into it by
     * proportion. Null if no utterance holds it.
     */
    fun positionOfSegmentChar(
        segmentIndex: Int,
        charOffset: Int,
    ): Long? {
        val index = utterances.indexOfFirst { it.segmentIndex == segmentIndex && charOffset >= it.start && charOffset < it.end }
        if (index < 0) return null
        val u = utterances[index]
        val share = (charOffset - u.start).toDouble() / (u.end - u.start)
        return positionMs(index, (share * u.durationMs).toLong())
    }

    private fun approximateChar(
        utterance: BakedUtterance,
        offsetMs: Long,
    ): Int {
        if (utterance.durationMs <= 0) return utterance.start
        val share = offsetMs.toDouble() / utterance.durationMs
        return (utterance.start + share * (utterance.end - utterance.start)).toInt().coerceIn(utterance.start, utterance.end)
    }

    companion object {
        const val MANIFEST_NAME = "manifest.txt"

        /** Reads the bake in [folder], or null if there is no readable manifest. */
        fun read(folder: File): BakedBook? {
            val file = File(folder, MANIFEST_NAME)
            if (!file.isFile) return null
            return try {
                BakeManifest.parse(folder, file.readText(Charsets.UTF_8))
            } catch (e: IOException) {
                null
            }
        }
    }
}
