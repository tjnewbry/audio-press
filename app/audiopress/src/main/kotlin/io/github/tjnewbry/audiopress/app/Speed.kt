package io.github.tjnewbry.audiopress.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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

    private const val MIN_HUNDREDTHS = 50

    /** [speed] on the nearest step, within [MIN] to [MAX]. */
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
        return MIN_HUNDREDTHS + steps * STEP_HUNDREDTHS
    }

    private const val HUNDRED = 100f
    private const val HUNDRED_INT = 100
    private const val TEN = 10
}

/**
 * The speed control: closed, a large label of the speed; tapped, a popup over it with the speed in two decimals (monospace,
 * in a box as wide as `0.00x`, so nothing moves while sliding) and a vertical slider, top 2.00x and bottom 0.50x. It stays
 * open until a tap outside it.
 */
@Composable
fun SpeedControl(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val style = MaterialTheme.typography.headlineMedium
    Box(
        modifier
            .clickable { open = true }
            .semantics {
                contentDescription = "Reading speed"
                stateDescription = PlaybackSpeed.closedLabel(speed)
            }.padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(PlaybackSpeed.closedLabel(speed), style = style)
        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                SpeedPanel(speed, onSpeedChange)
            }
        }
    }
}

@Composable
private fun SpeedPanel(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
) {
    val style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
    val measurer: TextMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // As wide as the widest label, so the box never changes size while the number does.
    val width =
        with(density) {
            measurer
                .measure("0.00x", style)
                .size.width
                .toDp()
        }
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.End) {
            Text(PlaybackSpeed.openLabel(speed), style = style, modifier = Modifier.width(width))
            VerticalSlider(
                fraction = PlaybackSpeed.toFraction(speed),
                onFraction = { onSpeedChange(PlaybackSpeed.fromFraction(it)) },
                modifier = Modifier.width(width).height(SLIDER_HEIGHT),
            )
        }
    }
}

private val SLIDER_HEIGHT = 180.dp
private val THUMB_RADIUS = 11.dp

/** A slider that goes up and down: [fraction] is 0 at the bottom and 1 at the top. Tapping or dragging calls [onFraction]. */
@Composable
private fun VerticalSlider(
    fraction: Float,
    onFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var height by remember { mutableIntStateOf(1) }
    val update by rememberUpdatedState(onFraction)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Canvas(
        modifier
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { update(fractionAt(it.y, height, THUMB_RADIUS.toPx())) }
            }.pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    update(fractionAt(change.position.y, height, THUMB_RADIUS.toPx()))
                }
            }.semantics { contentDescription = "Speed slider" },
    ) {
        val radius = THUMB_RADIUS.toPx()
        val x = size.width / 2
        val trackWidth = 6.dp.toPx()
        val thumbY = radius + (size.height - 2 * radius) * (1f - fraction.coerceIn(0f, 1f))
        drawRoundRect(track, Offset(x - trackWidth / 2, radius), Size(trackWidth, size.height - 2 * radius), CornerRadius(trackWidth / 2))
        drawRoundRect(
            fill,
            Offset(x - trackWidth / 2, thumbY),
            Size(trackWidth, size.height - radius - thumbY),
            CornerRadius(trackWidth / 2),
        )
        drawCircle(fill, radius, Offset(x, thumbY))
    }
}

/** The slider position (0 at the bottom, 1 at the top) of a touch [y] pixels from the top of a slider [height] pixels tall. */
internal fun fractionAt(
    y: Float,
    height: Int,
    inset: Float,
): Float = 1f - ((y - inset) / (height - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
