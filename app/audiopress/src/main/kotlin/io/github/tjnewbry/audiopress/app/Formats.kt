package io.github.tjnewbry.audiopress.app

import java.util.Locale
import kotlin.math.roundToInt

// Every size, time and count the screens show, in one place.

private const val BYTES_PER_KB = 1_000.0
private const val BYTES_PER_TENTH_MB = 100_000.0
private const val BYTES_PER_TENTH_GB = 100_000_000.0
private const val KB_LIMIT = 1_000
private const val TENTH_MB_LIMIT = 10_000

/**
 * The one format for every size on screen, in decimal units (1 MB is 1,000,000 bytes): `999 bytes`, `640 KB`, `21.4 MB`,
 * `1.2 GB`. It never reads `1000 KB` or `1000.0 MB`: a size that rounds up to the next unit is shown in it.
 */
fun formatSize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    val kilobytes = Math.round(b / BYTES_PER_KB)
    val tenthsOfMb = Math.round(b / BYTES_PER_TENTH_MB)
    return when {
        b < BYTES_PER_KB -> "$b bytes"
        kilobytes < KB_LIMIT -> "$kilobytes KB"
        tenthsOfMb < TENTH_MB_LIMIT -> String.format(Locale.US, "%.1f MB", tenthsOfMb / 10.0)
        else -> String.format(Locale.US, "%.1f GB", Math.round(b / BYTES_PER_TENTH_GB) / 10.0)
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
