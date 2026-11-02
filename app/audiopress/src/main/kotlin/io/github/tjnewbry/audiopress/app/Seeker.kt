package io.github.tjnewbry.audiopress.app

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val TICK_MS = 500L

/** Space kept free at each end of the seeker bar for the round marker. */
private val INSET = 8.dp

/** The share of the bar (0 to 1) that a touch at [x] pixels is at, for a bar [width] pixels wide with [inset] pixels free at each end. */
internal fun shareAt(
    x: Float,
    width: Int,
    inset: Float,
): Float = ((x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)

/** The grey of the buffer bar, the baked share of the book. */
private val BufferGrey = Color(0xFF9E9E9E)

/**
 * The bar under the text box: the play and pause icon, then the seeker bar (played time, baked time, and the scroll arrow
 * while the user has scrolled away), and the time text under it. [onSeek] is given a share of the book, 0 to 1.
 */
@Composable
fun PlayerBar(
    session: ReaderSession,
    listState: LazyListState,
    follow: ScrollFollow,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Baked audio moves between words, so redraw twice a second while it plays.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(session.player.status, session.mode) {
        while (session.player.status == PlaybackStatus.PLAYING && session.mode == PlaybackMode.BAKED) {
            tick = SystemClock.uptimeMillis()
            delay(TICK_MS)
        }
    }
    val timeline = session.timeline()
    val elapsed = session.elapsedMs()
    val indicator = if (follow.showArrow) scrollIndicator(listState, session, timeline) else null
    val enabled = session.speech == SpeechState.Ready || session.bakeState is BakeState.Baked
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayPauseButton(playing = session.player.status == PlaybackStatus.PLAYING, enabled = enabled, onClick = session::togglePlayback)
            SeekBar(
                played = timeline.fraction(elapsed),
                buffer = timeline.bufferFraction,
                indicator = indicator,
                description = "${formatDuration(elapsed / MS_PER_SECOND)} of ${formatDuration(timeline.totalMs / MS_PER_SECOND)}",
                onSeek = onSeek,
                modifier = Modifier.weight(1f),
            )
        }
        val baked = session.mode == PlaybackMode.BAKED
        val times = "${formatDuration(elapsed / MS_PER_SECOND)} / ${formatDuration(timeline.totalMs / MS_PER_SECOND)}"
        Text(
            when {
                session.player.waiting -> "Waiting for audio…"
                session.listeningWhileBaking -> "Listening while baking"
                baked -> "$times (baked audio)"
                else -> "$times (estimated, live speech)"
            },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { stateDescription = "tick $tick" },
        )
    }
}

private const val MS_PER_SECOND = 1000.0

/**
 * Where the scroll arrow points, as a share of the book. The text box shows lines from the top one to the bottom one;
 * each edge is placed inside its line by how much of the line is scrolled off, and [BookTimeline.indicatorMs] turns
 * the two edge times into one point that reaches both ends of the bar.
 */
private fun scrollIndicator(
    listState: LazyListState,
    session: ReaderSession,
    timeline: BookTimeline,
): Float? {
    val info = listState.layoutInfo
    val first = info.visibleItemsInfo.firstOrNull() ?: return null
    val last = info.visibleItemsInfo.last()
    val topShare = ((info.viewportStartOffset - first.offset).toDouble() / first.size.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    val bottomShare = ((info.viewportEndOffset - last.offset).toDouble() / last.size.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    if (first.index >= session.lines.size) return null
    val top = timeline.msAt(first.index, topShare)
    val bottom = timeline.msAt(last.index.coerceAtMost(session.lines.size - 1), bottomShare)
    return timeline.fraction(timeline.indicatorMs(top, bottom))
}

/** A play triangle, or two pause bars, drawn so no icon library is needed. Icon only; the words are for screen readers. */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(
        modifier
            .size(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (playing) "Pause" else "Play" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(28.dp)) {
            if (playing) {
                val bar = size.width * 0.28f
                drawRoundRect(color, Offset(size.width * 0.12f, 0f), Size(bar, size.height), CornerRadius(2.dp.toPx()))
                drawRoundRect(color, Offset(size.width * 0.60f, 0f), Size(bar, size.height), CornerRadius(2.dp.toPx()))
            } else {
                val triangle =
                    Path().apply {
                        moveTo(size.width * 0.18f, 0f)
                        lineTo(size.width, size.height / 2)
                        lineTo(size.width * 0.18f, size.height)
                        close()
                    }
                drawPath(triangle, color)
            }
        }
    }
}

/**
 * The seeker bar: a track, a grey fill for how much is baked ([buffer], 0 for none), a coloured fill for how much has
 * been read ([played]) and, when [indicator] is not null, a downward arrow above the track at that share. Tapping or
 * dragging calls [onSeek] with the share chosen (once, when the finger lifts after a drag).
 */
@Composable
fun SeekBar(
    played: Float,
    buffer: Float,
    indicator: Float?,
    description: String,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val arrow = MaterialTheme.colorScheme.tertiary
    val shown = dragging ?: played
    Canvas(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { seek(shareAt(it.x, width, INSET.toPx()).toDouble()) }
            }.pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = shareAt(it.x, width, INSET.toPx()) },
                    onDragEnd = {
                        dragging?.let { share -> seek(share.toDouble()) }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                    onHorizontalDrag = { change, _ -> dragging = shareAt(change.position.x, width, INSET.toPx()) },
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
        val thickness = 8.dp.toPx()
        val top = size.height - thickness - 8.dp.toPx()
        val corner = CornerRadius(thickness / 2)
        val inset = INSET.toPx()
        // The bar runs between the insets, which leave room for the round marker at each end.
        val usable = (size.width - 2 * inset).coerceAtLeast(1f)
        drawRoundRect(track, Offset(inset, top), Size(usable, thickness), corner)
        if (buffer > 0f) drawRoundRect(BufferGrey, Offset(inset, top), Size(usable * buffer.coerceIn(0f, 1f), thickness), corner)
        if (shown > 0f) drawRoundRect(fill, Offset(inset, top), Size(usable * shown.coerceIn(0f, 1f), thickness), corner)
        drawCircle(fill, 8.dp.toPx(), Offset(inset + usable * shown.coerceIn(0f, 1f), top + thickness / 2))
        if (indicator != null) {
            val x = inset + usable * indicator.coerceIn(0f, 1f)
            val half = 7.dp.toPx()
            val tip = top - 3.dp.toPx()
            val pointer =
                Path().apply {
                    moveTo(x - half, tip - 11.dp.toPx())
                    lineTo(x + half, tip - 11.dp.toPx())
                    lineTo(x, tip)
                    close()
                }
            drawPath(pointer, arrow)
        }
    }
}
