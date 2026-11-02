package io.github.tjnewbry.audiopress.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** The height of the bar's touch area; the track is drawn across its middle, level with the play button's centre. */
private val BAR_HEIGHT = 40.dp

/** Space kept free at each end of the seeker bar for the round marker. */
private val INSET = 8.dp

/** How thick the track is; its end caps are half of that in radius. */
private val THICKNESS = 8.dp

/** The radius of the thumb, the round marker of the played position. */
private val THUMB_RADIUS = 8.dp

/** The radius of a block dot as a share of the track thickness; two dot-widths (4 radii) is the least gap between dots. */
private const val DOT_SHARE = 0.25f

/** The scroll arrow: half its width, its height, and the gap between its tip and the track. */
private val ARROW_HALF_WIDTH = 7.dp
private val ARROW_HEIGHT = 11.dp
private val ARROW_GAP = 3.dp
private val ARROW_EDGE = 1.5.dp

/** The grey of the buffer bar, the baked share of the book. */
private val BufferGrey = Color(0xFF9E9E9E)

/** The scroll arrow is white with a dark edge, so it reads on light and dark themes alike. */
private val ArrowFill = Color.White
private val ArrowEdge = Color(0xCC000000)

/**
 * Maps shares of the book (0 to 1) to places on the bar and back, for one scale shared by the played fill, the thumb, the
 * buffer, the scroll arrow and touches. The track runs from [left] to [right]; the start of the book is one end-cap radius
 * ([cap]) in from the left end and the end of the book one cap radius in from the right end, so nothing rides over a cap.
 */
internal class BarScale(
    val left: Float,
    val right: Float,
    val cap: Float,
) {
    private val start get() = left + cap
    private val length get() = (right - cap - start).coerceAtLeast(1f)

    fun x(share: Float): Float = start + length * share.coerceIn(0f, 1f)

    fun share(x: Float): Float = ((x - start) / length).coerceIn(0f, 1f)
}

/** The scale of a bar [width] pixels wide with [inset] pixels free at each end and a track [thickness] pixels thick. */
internal fun barScale(
    width: Int,
    inset: Float,
    thickness: Float,
): BarScale = BarScale(inset, width - inset, thickness / 2)

/**
 * Where to draw the block dots: the places of the [shares] on [scale], leaving out any that is closer than [minGap] pixels to
 * the one drawn before it, so a book with hundreds of blocks shows only as many dots as fit.
 */
internal fun dotPositions(
    shares: List<Float>,
    scale: BarScale,
    minGap: Float,
): List<Float> {
    val xs = ArrayList<Float>()
    for (share in shares) {
        val x = scale.x(share)
        if (xs.isEmpty() || x - xs.last() >= minGap) xs += x
    }
    return xs
}

/**
 * The seeker bar: a track, a grey fill for how much is baked ([buffer], 0 for none), the block [boundaries] as punched-out
 * dots (over the buffer when [dotsAboveBuffer]), a coloured fill for how much has been read ([played]) and, when [indicator]
 * is not null, a downward arrow above the track at that share. While a finger drags it, [dragShare] is where the finger is
 * (reported through [onDragShare]) and is drawn instead of [played]. A tap, or a drag when the finger lifts, calls [onSeek]
 * with the share chosen.
 */
@Composable
fun SeekBar(
    played: Float,
    buffer: Float,
    boundaries: List<Float>,
    dotsAboveBuffer: Boolean,
    dragShare: Float?,
    onDragShare: (Float?) -> Unit,
    indicator: Float?,
    description: String,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableIntStateOf(1) }
    val dragging by rememberUpdatedState(dragShare)
    val drag by rememberUpdatedState(onDragShare)
    val seek by rememberUpdatedState(onSeek)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val notch = MaterialTheme.colorScheme.background
    val shown = dragging ?: played
    Canvas(
        modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { seek(barScale(width, INSET.toPx(), THICKNESS.toPx()).share(it.x).toDouble()) }
            }.pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag(barScale(width, INSET.toPx(), THICKNESS.toPx()).share(it.x)) },
                    onDragEnd = {
                        dragging?.let { share -> seek(share.toDouble()) }
                        drag(null)
                    },
                    onDragCancel = { drag(null) },
                    onHorizontalDrag = { change, _ -> drag(barScale(width, INSET.toPx(), THICKNESS.toPx()).share(change.position.x)) },
                )
            }.semantics {
                contentDescription = "Seek bar"
                stateDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(played, 0f..1f)
                setProgress { target ->
                    seek(target.toDouble().coerceIn(0.0, 1.0))
                    true
                }
            },
    ) {
        val thickness = THICKNESS.toPx()
        val top = (size.height - thickness) / 2 // the middle of the bar is the middle of the row, level with the button
        val middle = top + thickness / 2
        val corner = CornerRadius(thickness / 2)
        val scale = barScale(size.width.toInt(), INSET.toPx(), thickness)
        drawRoundRect(track, Offset(scale.left, top), Size(scale.right - scale.left, thickness), corner)

        // A full fill goes to the end of the track; otherwise the fills end where the same scale puts their share.
        fun fillTo(share: Float) = if (share >= 1f) scale.right else scale.x(share)
        val dot = thickness * DOT_SHARE

        fun drawDots() = dotPositions(boundaries, scale, minGap = 4 * dot).forEach { x -> drawCircle(notch, dot, Offset(x, middle)) }
        // The block boundaries are punched-out dots. Under the buffer when baked audio is in use, over it when live speech is.
        if (!dotsAboveBuffer) drawDots()
        if (buffer > 0f) drawRoundRect(BufferGrey, Offset(scale.left, top), Size(fillTo(buffer) - scale.left, thickness), corner)
        if (dotsAboveBuffer) drawDots()
        if (shown > 0f) drawRoundRect(fill, Offset(scale.left, top), Size(fillTo(shown) - scale.left, thickness), corner)
        drawCircle(fill, THUMB_RADIUS.toPx(), Offset(scale.x(shown), middle))
        if (indicator != null) drawScrollArrow(scale.x(indicator), tip = top - ARROW_GAP.toPx())
    }
}

/** The scroll arrow: a white downward triangle with a dark edge, its [tip] just above the track at [x]. */
private fun DrawScope.drawScrollArrow(
    x: Float,
    tip: Float,
) {
    val half = ARROW_HALF_WIDTH.toPx()
    val base = tip - ARROW_HEIGHT.toPx()
    val pointer =
        Path().apply {
            moveTo(x - half, base)
            lineTo(x + half, base)
            lineTo(x, tip)
            close()
        }
    drawPath(pointer, ArrowFill)
    drawPath(pointer, ArrowEdge, style = Stroke(ARROW_EDGE.toPx(), join = StrokeJoin.Round))
}
