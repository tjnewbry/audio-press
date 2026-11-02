package io.github.tjnewbry.audiopress.app

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Every size, time and count the screens show, in one place. The size formats differ on purpose: each is the one tjnewbry
// chose for the place it appears, so they are kept apart rather than merged.

/** `85 MB`, `1.2 GB`, `640 KB`: sizes in units of 1024, for the baked size in the player and the free-space messages. */
fun formatSize(bytes: Long): String {
    val kb = 1024.0
    return when {
        bytes >= kb * kb * kb -> "%.1f GB".format(bytes / (kb * kb * kb))
        bytes >= kb * kb -> "%.0f MB".format(bytes / (kb * kb))
        bytes >= kb -> "%.0f KB".format(bytes / kb)
        else -> "$bytes bytes"
    }
}

/** `137 MB`: whole decimal megabytes, as the download page shows them. */
fun formatMegabytes(bytes: Long): String = "${(bytes / DECIMAL_MB).roundToLong()} MB"

private const val DECIMAL_MB = 1_000_000.0

/** A size for the export dialog, in decimal units like the voice download sizes: `21.4 MB`, `1.82 GB`, `640 KB`. */
fun formatExportSize(bytes: Long): String {
    val kb = 1_000.0
    val value = bytes.toDouble()
    return when {
        value >= kb * kb * kb -> String.format(Locale.US, "%.2f GB", value / (kb * kb * kb))
        value >= kb * kb -> String.format(Locale.US, "%.1f MB", value / (kb * kb))
        value >= kb -> String.format(Locale.US, "%.0f KB", value / kb)
        else -> "$bytes bytes"
    }
}

private const val INFO_KB = 1_000.0
private const val INFO_MB = 1_000_000.0
private const val INFO_GB = 1_000_000_000.0
private const val KB_PER_MB = 1_000

/** A size for the Info popup, in decimal units: "640 KB", "83 MB" (whole), "1.2 GB" (one decimal). */
fun formatInfoSize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L).toDouble()
    val megabytes = Math.round(b / INFO_MB)
    return when {
        b >= INFO_GB || megabytes >= KB_PER_MB -> String.format(Locale.US, "%.1f GB", b / INFO_GB)
        b >= INFO_MB -> "$megabytes MB"
        else -> "${minOf(Math.round(b / INFO_KB), (KB_PER_MB - 1).toLong())} KB"
    }
}

/** `1:05` for 65 seconds, and `1:02:03` once an hour or more. */
fun formatDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / SECONDS_PER_HOUR
    val minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val secs = total % SECONDS_PER_MINUTE
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_MINUTE = 60L

/** `less than a minute left`, `about 12 min left`, `about 2 h 5 min left`. */
fun formatRemaining(ms: Long): String {
    val minutes = (ms + MS_PER_MINUTE - 1) / MS_PER_MINUTE
    return when {
        minutes < 1 -> "less than a minute left"
        minutes < MINUTES_PER_HOUR -> "about $minutes min left"
        else -> "about ${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min left"
    }
}

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L

/** `1 page`, `30 pages`. */
fun formatPages(count: Int): String = if (count == 1) "1 page" else "$count pages"

/** `42%` for a [fraction] of 0.42. */
fun formatPercent(fraction: Float): String = "${(fraction * PERCENT).roundToInt()}%"

private const val PERCENT = 100
