package io.github.tjnewbry.audiopress.app.ui.player

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.tjnewbry.audiopress.app.BakeState
import io.github.tjnewbry.audiopress.app.BookTimeline
import io.github.tjnewbry.audiopress.app.PlaybackMode
import io.github.tjnewbry.audiopress.app.PlaybackStatus
import io.github.tjnewbry.audiopress.app.ReaderSession
import io.github.tjnewbry.audiopress.app.ScrollFollow
import io.github.tjnewbry.audiopress.app.SpeechState
import io.github.tjnewbry.audiopress.app.formatDuration
import io.github.tjnewbry.audiopress.app.playerStatusText
import io.github.tjnewbry.audiopress.app.ui.Spacing
import kotlinx.coroutines.delay

/** How often the played position is redrawn while playing. */
private const val TICK_MS = 150L

private const val MS_PER_SECOND = 1000.0

/**
 * The controls under the text box: the play and pause icon, then the seeker bar (played time, baked time, and the scroll arrow
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
    // The position moves all the time while playing (inside a clip, or between the words live speech reports), so the bar is
    // redrawn on every tick: reading the tick's time below makes this part of the screen follow it.
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(session.player.status, session.mode) {
        while (session.player.status == PlaybackStatus.PLAYING) {
            now = SystemClock.uptimeMillis()
            delay(TICK_MS)
        }
    }
    val timeline = session.timeline()
    var dragShare by remember { mutableStateOf<Float?>(null) } // where the finger is while the bar is being dragged
    val elapsed = dragShare?.let { (it * timeline.totalMs).toLong() } ?: session.elapsedMs(now) // the text follows the finger
    val indicator = if (follow.showArrow) scrollIndicator(listState, session.lines.size, timeline) else null
    // Enabled while the speech engine starts too (a play tapped then waits for it), so the button does not change on opening.
    val enabled = session.speech !is SpeechState.Unavailable || session.bakeState is BakeState.Baked
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Hairline)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayPauseButton(playing = session.player.status == PlaybackStatus.PLAYING, enabled = enabled, onClick = session::togglePlayback)
            SeekBar(
                played = timeline.fraction(elapsed),
                buffer = timeline.bufferFraction,
                boundaries = timeline.boundaryShares,
                dotsAboveBuffer = session.mode == PlaybackMode.LIVE,
                dragShare = dragShare,
                onDragShare = { dragShare = it },
                indicator = indicator,
                description = "${formatDuration(elapsed / MS_PER_SECOND)} of ${formatDuration(timeline.totalMs / MS_PER_SECOND)}",
                onSeek = onSeek,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            playerStatusText(session.mode, session.bakeState, session.player.waiting, elapsed, timeline.totalMs),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Where the scroll arrow points, as a share of the book. The text box shows lines from the top one to the bottom one; each
 * edge is placed inside its line by how much of the line is scrolled off, and [BookTimeline.indicatorMs] turns the two edge
 * times into one point that reaches both ends of the bar. [lineCount] is the number of lines (the list may hold more items).
 */
private fun scrollIndicator(
    listState: LazyListState,
    lineCount: Int,
    timeline: BookTimeline,
): Float? {
    val info = listState.layoutInfo
    val first = info.visibleItemsInfo.firstOrNull() ?: return null
    val last = info.visibleItemsInfo.last()
    if (first.index >= lineCount) return null
    val topShare = ((info.viewportStartOffset - first.offset).toDouble() / first.size.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    val bottomShare = ((info.viewportEndOffset - last.offset).toDouble() / last.size.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    val top = timeline.msAt(first.index, topShare)
    val bottom = timeline.msAt(last.index.coerceAtMost(lineCount - 1), bottomShare)
    return timeline.fraction(timeline.indicatorMs(top, bottom))
}
