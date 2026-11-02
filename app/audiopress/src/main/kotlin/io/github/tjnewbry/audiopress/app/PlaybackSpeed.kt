package io.github.tjnewbry.audiopress.app

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The reading speed: 0.5x to 2x in steps of 0.05, 1x being the speed the book was baked at. It changes how fast the audio is
 * played or the voice speaks, never the length of the book on the bar, the buffer, the dots or the time text, which stay on
 * the book's 1x timeline.
 */
object PlaybackSpeed {
    const val MIN = 0.5f
    const val MAX = 2.0f
    const val DEFAULT = 1.0f

    /** The step in hundredths, and the number of steps from [MIN] to [MAX]. */
    private const val STEP_HUNDREDTHS = 5
    const val STEPS = 30

    /** The number of different speeds: the steps from [MIN] to [MAX] without 0.95x and 1.05x. */
    const val POSITIONS = STEPS + 1 - 2

    /** The speeds that snap to 1x instead of being offered. */
    private val SKIPPED = setOf(95, 105)

    private const val MIN_HUNDREDTHS = 50
    private const val DEFAULT_HUNDREDTHS = 100

    /** [speed] on the nearest step, within [MIN] to [MAX]; 0.95x and 1.05x are left out and become 1x. */
    fun snap(speed: Float): Float = hundredths(speed) / HUNDRED

    /** The speed for a slider position: 0 at the bottom (0.5x) to 1 at the top (2x). */
    fun fromFraction(fraction: Float): Float = snap(MIN + fraction.coerceIn(0f, 1f) * (MAX - MIN))

    /** The slider position (0 at the bottom, 1 at the top) of [speed]. */
    fun toFraction(speed: Float): Float = (snap(speed) - MIN) / (MAX - MIN)

    /** The compact label: trailing zeros trimmed, like `1x`, `1.1x`, `1.15x`, `0.5x`, `2x`. */
    fun closedLabel(speed: Float): String {
        val h = hundredths(speed)
        val whole = h / HUNDRED_INT
        val part = h % HUNDRED_INT
        val digits =
            when {
                part == 0 -> ""
                part % TEN == 0 -> ".${part / TEN}"
                else -> String.format(Locale.US, ".%02d", part)
            }
        return "$whole${digits}x"
    }

    /** The label of the open control: always two decimals, like `1.00x`, `1.10x`, `0.50x`, `2.00x`. */
    fun openLabel(speed: Float): String {
        val h = hundredths(speed)
        return String.format(Locale.US, "%d.%02dx", h / HUNDRED_INT, h % HUNDRED_INT)
    }

    private fun hundredths(speed: Float): Int {
        val steps = ((speed * HUNDRED - MIN_HUNDREDTHS) / STEP_HUNDREDTHS).roundToInt().coerceIn(0, STEPS)
        val value = MIN_HUNDREDTHS + steps * STEP_HUNDREDTHS
        return if (value in SKIPPED) DEFAULT_HUNDREDTHS else value
    }

    private const val HUNDRED = 100f
    private const val HUNDRED_INT = 100
    private const val TEN = 10
}
