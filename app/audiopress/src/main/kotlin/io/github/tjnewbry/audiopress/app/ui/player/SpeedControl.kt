package io.github.tjnewbry.audiopress.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.tjnewbry.audiopress.app.PlaybackSpeed
import io.github.tjnewbry.audiopress.app.ui.MinTouchTarget
import io.github.tjnewbry.audiopress.app.ui.Spacing

/**
 * The speed control: closed, a large label of the speed; tapped, a popup over it with the speed in two decimals (monospace,
 * in a box as wide as `0.85x`, so nothing moves while sliding) and a vertical slider, top 2.00x and bottom 0.50x. It stays
 * open until a tap outside it. [onSpeedChange] is called at every step while the slider moves, and [onSpeedChangeFinished]
 * when the finger lifts (or after a tap on the slider).
 */
@Composable
fun SpeedControl(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onSpeedChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = TABULAR_FIGURES, lineHeightStyle = CENTERED_LINE)
    Box(
        modifier
            .heightIn(min = MinTouchTarget)
            .clickable { open = true }
            .semantics {
                contentDescription = "Reading speed"
                stateDescription = PlaybackSpeed.closedLabel(speed)
            }.padding(horizontal = LABEL_PADDING_X, vertical = LABEL_PADDING_Y),
        contentAlignment = Alignment.Center,
    ) {
        Box {
            // The closed number is not drawn while the panel is open; its box keeps its place, so nothing beside it moves.
            RightAlignedLabel(PlaybackSpeed.closedLabel(speed), style, visible = !open)
            if (open) {
                // Anchored to the closed number's box and moved out by the panel's padding, so the open number sits exactly
                // where the closed one was and the panel covers its whole footprint.
                val offset = with(LocalDensity.current) { IntOffset(LABEL_PADDING_X.roundToPx(), -LABEL_PADDING_Y.roundToPx()) }
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = offset,
                    onDismissRequest = { open = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    SpeedPanel(speed, onSpeedChange, onSpeedChangeFinished)
                }
            }
        }
    }
}

/** The space around the speed label, closed and open alike. */
private val LABEL_PADDING_X = Spacing.Small
private val LABEL_PADDING_Y = Spacing.ExtraSmall

/** The label that sets the width of the speed boxes, closed and open: the widest label on the phones checked. */
private const val WIDEST_LABEL = "0.85x"

/**
 * [label] right-justified in a box exactly as wide as an invisible [WIDEST_LABEL], which alone sets the size, so the box never
 * changes size or moves whatever the label says. The label takes no part in the size: in a font where it is wider than
 * [WIDEST_LABEL] it spills out to the left on one line, its right edge staying on the box's right edge. The invisible copy is
 * hidden from screen readers. When not [visible] the label is not drawn but the box keeps its size.
 */
@Composable
private fun RightAlignedLabel(
    label: String,
    style: TextStyle,
    visible: Boolean = true,
) {
    Box {
        Text(WIDEST_LABEL, style = style, maxLines = 1, softWrap = false, modifier = Modifier.alpha(0f).clearAndSetSemantics {})
        Text(
            label,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            modifier =
                Modifier
                    .matchParentSize()
                    .wrapContentWidth(Alignment.End, unbounded = true)
                    .alpha(if (visible) 1f else 0f),
        )
    }
}

/** Centres the text in its line, so the digits sit in the middle of the control and of a button beside it. */
private val CENTERED_LINE = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)

/**
 * Tabular figures: every digit as wide as every other, in fonts that have them, so the number does not shift sideways as it
 * changes even where the phone's font theme ignores the request for a monospace font.
 */
private const val TABULAR_FIGURES = "tnum"

@Composable
private fun SpeedPanel(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onSpeedChangeFinished: () -> Unit,
) {
    val style =
        MaterialTheme.typography.headlineMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontFeatureSettings = TABULAR_FIGURES,
            lineHeightStyle = CENTERED_LINE,
        )
    Surface(shape = RoundedCornerShape(PANEL_CORNER), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        // The label box alone sets the width (the slider has none of its own), so the popup keeps one size and one place.
        Column(
            Modifier.width(IntrinsicSize.Min).padding(horizontal = LABEL_PADDING_X, vertical = LABEL_PADDING_Y),
            horizontalAlignment = Alignment.End,
        ) {
            RightAlignedLabel(PlaybackSpeed.openLabel(speed), style)
            VerticalSlider(
                fraction = PlaybackSpeed.toFraction(speed),
                onFraction = { onSpeedChange(PlaybackSpeed.fromFraction(it)) },
                onRelease = onSpeedChangeFinished,
                modifier = Modifier.fillMaxWidth().height(SLIDER_HEIGHT),
            )
        }
    }
}

private val PANEL_CORNER = 12.dp
private val SLIDER_HEIGHT = 180.dp
private val THUMB_RADIUS = 11.dp
private val TRACK_WIDTH = 6.dp

/** The dash across the track at 1x: half its length, and its thickness. */
private val ONE_X_HALF_LENGTH = 10.dp
private val ONE_X_THICKNESS = 2.dp

/**
 * A slider that goes up and down: [fraction] is 0 at the bottom and 1 at the top. Tapping or dragging calls [onFraction];
 * [onRelease] follows when the finger lifts.
 */
@Composable
private fun VerticalSlider(
    fraction: Float,
    onFraction: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var height by remember { mutableIntStateOf(1) }
    val update by rememberUpdatedState(onFraction)
    val release by rememberUpdatedState(onRelease)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val mark = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures {
                    update(fractionAt(it.y, height, THUMB_RADIUS.toPx()))
                    release()
                }
            }.pointerInput(Unit) {
                detectDragGestures(onDragEnd = { release() }, onDragCancel = { release() }) { change, _ ->
                    change.consume()
                    update(fractionAt(change.position.y, height, THUMB_RADIUS.toPx()))
                }
            }.semantics { contentDescription = "Speed slider" },
    ) {
        val radius = THUMB_RADIUS.toPx()
        val x = size.width / 2
        val trackWidth = TRACK_WIDTH.toPx()
        val thumbY = radius + (size.height - 2 * radius) * (1f - fraction.coerceIn(0f, 1f))
        drawRoundRect(track, Offset(x - trackWidth / 2, radius), Size(trackWidth, size.height - 2 * radius), CornerRadius(trackWidth / 2))
        // A thin dash across the track marks 1x, under the fill and the thumb.
        val oneX = radius + (size.height - 2 * radius) * (1f - PlaybackSpeed.toFraction(PlaybackSpeed.DEFAULT))
        val half = ONE_X_HALF_LENGTH.toPx()
        drawLine(mark, Offset(x - half, oneX), Offset(x + half, oneX), strokeWidth = ONE_X_THICKNESS.toPx())
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
