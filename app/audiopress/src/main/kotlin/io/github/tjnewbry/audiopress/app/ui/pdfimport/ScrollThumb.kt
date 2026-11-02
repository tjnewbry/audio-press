package io.github.tjnewbry.audiopress.app.ui.pdfimport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.tjnewbry.audiopress.app.pageUnderThumb
import io.github.tjnewbry.audiopress.app.ui.MinTouchTarget
import io.github.tjnewbry.audiopress.app.ui.Spacing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val THUMB_HEIGHT = MinTouchTarget
private val THUMB_SIDE_GAP = 4.dp
private val THUMB_CORNER = 8.dp
private val TRACK_LINE = 2.dp
private val GRIP_HALF_LENGTH = 6.dp
private val GRIP_SPACING = 6.dp
private val BUBBLE_CORNER = 10.dp
private val ThumbGrey = Color(0xFFBDBDBD)

/**
 * The scroll indicator on the right edge: it shows how far through the PDF the list is, and can be grabbed and dragged to
 * scan quickly. While it is held, a bubble to its left says which page it is at. [footerPx] is the gap below the last page.
 */
@Composable
internal fun BoxScope.ScrollThumb(
    state: LazyListState,
    total: Int,
    footerPx: Float,
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var trackHeight by remember { mutableIntStateOf(0) }
    val thumbPx = with(LocalDensity.current) { THUMB_HEIGHT.toPx() }
    val info = state.layoutInfo
    val pagePx =
        info.visibleItemsInfo
            .firstOrNull { it.index < total }
            ?.size
            ?.toFloat() ?: 0f
    val viewportPx = info.viewportSize.height.toFloat()
    val maxScroll = (total * pagePx + footerPx - viewportPx).coerceAtLeast(1f)
    val scrolled = state.firstVisibleItemIndex.coerceAtMost(total) * pagePx + state.firstVisibleItemScrollOffset
    val shown = if (dragging) dragFraction else (scrolled / maxScroll).coerceIn(0f, 1f)
    val top = (shown * (trackHeight - thumbPx).coerceAtLeast(0f)).roundToInt()

    fun dragTo(y: Float) {
        val fraction = ((y - thumbPx / 2) / (trackHeight - thumbPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
        dragFraction = fraction
        if (pagePx > 0f) {
            val target = fraction * maxScroll
            val index = (target / pagePx).toInt().coerceAtMost(total)
            scope.launch { state.scrollToItem(index, (target - index * pagePx).toInt().coerceAtLeast(0)) }
        }
    }
    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .width(THUMB_WIDTH)
            .fillMaxHeight()
            .onSizeChanged { trackHeight = it.height }
            .pointerInput(total) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragTo(it.y)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        dragTo(change.position.y)
                    },
                )
            }.semantics { contentDescription = "Scroll through the pages" },
    ) {
        val track = MaterialTheme.colorScheme.outlineVariant
        val grip = MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(Modifier.fillMaxSize()) {
            val middle = size.width / 2
            drawLine(track, Offset(middle, 0f), Offset(middle, size.height), strokeWidth = TRACK_LINE.toPx())
            val thumbTop = top.toFloat()
            val gap = THUMB_SIDE_GAP.toPx()
            drawRoundRect(ThumbGrey, Offset(gap, thumbTop), Size(size.width - 2 * gap, thumbPx), CornerRadius(THUMB_CORNER.toPx()))
            val half = GRIP_HALF_LENGTH.toPx()
            repeat(3) {
                val y = thumbTop + thumbPx / 2 + (it - 1) * GRIP_SPACING.toPx()
                drawLine(grip, Offset(middle - half, y), Offset(middle + half, y), strokeWidth = TRACK_LINE.toPx())
            }
        }
    }
    if (dragging) {
        val page = pageUnderThumb(dragFraction, dragFraction * maxScroll, viewportPx, pagePx, total)
        Surface(
            shape = RoundedCornerShape(BUBBLE_CORNER),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(-(THUMB_WIDTH.roundToPx() + Spacing.Small.roundToPx()), top) },
        ) {
            Text(
                "Page $page",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
            )
        }
    }
}
