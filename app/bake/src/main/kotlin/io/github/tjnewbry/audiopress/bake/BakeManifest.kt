package io.github.tjnewbry.audiopress.bake

import java.io.File

/**
 * The manifest text format, one tab-separated record per line:
 *
 * ```
 * audiopress-bake <TAB> 1
 * format <TAB> wav
 * fingerprint <TAB> hex
 * settings <TAB> voice settings text (optional)
 * utterances <TAB> count
 * u <TAB> index <TAB> file <TAB> segment <TAB> start <TAB> end <TAB> page <TAB> durationMs
 * complete <TAB> totalDurationMs
 * ```
 *
 * `complete` is written last, only when every utterance is baked. A manifest without it is a bake in progress.
 */
internal object BakeManifest {
    private const val MAGIC = "audiopress-bake"
    private const val VERSION = "1"

    fun format(
        fingerprint: String,
        count: Int,
        utterances: List<BakedUtterance>,
        complete: Boolean,
        settingsKey: String = "",
    ): String =
        buildString {
            append(MAGIC).append('\t').append(VERSION).append('\n')
            append("format\twav\n")
            append("fingerprint\t").append(fingerprint).append('\n')
            if (settingsKey.isNotEmpty()) append("settings\t").append(settingsKey.replace('\t', ' ').replace('\n', ' ')).append('\n')
            append("utterances\t").append(count).append('\n')
            for (u in utterances) {
                append("u\t${u.index}\t${u.fileName}\t${u.segmentIndex}\t${u.start}\t${u.end}\t${u.page}\t${u.durationMs}\n")
            }
            if (complete) append("complete\t").append(utterances.sumOf { it.durationMs }).append('\n')
        }

    /** Parses [text]; null if it is not a manifest this version understands. */
    fun parse(
        folder: File,
        text: String,
    ): BakedBook? {
        val lines = text.split('\n').filter { it.isNotEmpty() }
        if (lines.firstOrNull()?.split('\t') != listOf(MAGIC, VERSION)) return null
        var fingerprint: String? = null
        var settingsKey: String? = null
        var count = -1
        var complete = false
        val utterances = mutableListOf<BakedUtterance>()
        for (line in lines.drop(1)) {
            val parts = line.split('\t')
            when (parts[0]) {
                "fingerprint" -> fingerprint = parts.getOrNull(1)
                "settings" -> settingsKey = parts.getOrNull(1)
                "utterances" -> count = parts.getOrNull(1)?.toIntOrNull() ?: return null
                "complete" -> complete = true
                "u" -> utterances += parseUtterance(parts) ?: return null
            }
        }
        if (fingerprint == null || count < 0) return null
        if (utterances.withIndex().any { (i, u) -> u.index != i }) return null
        return BakedBook(folder, fingerprint, utterances, complete && utterances.size == count, settingsKey)
    }

    private fun parseUtterance(parts: List<String>): BakedUtterance? {
        if (parts.size != 8) return null
        val numbers = listOf(1, 3, 4, 5, 6, 7).map { parts[it].toLongOrNull() ?: return null }
        return BakedUtterance(
            index = numbers[0].toInt(),
            fileName = parts[2],
            segmentIndex = numbers[1].toInt(),
            start = numbers[2].toInt(),
            end = numbers[3].toInt(),
            page = numbers[4].toInt(),
            durationMs = numbers[5],
        )
    }
}
