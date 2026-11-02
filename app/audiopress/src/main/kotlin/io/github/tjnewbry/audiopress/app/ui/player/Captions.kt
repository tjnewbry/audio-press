package io.github.tjnewbry.audiopress.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tjnewbry.audiopress.app.PlaybackStatus
import io.github.tjnewbry.audiopress.app.ReaderSession
import io.github.tjnewbry.audiopress.app.ScrollFollow
import io.github.tjnewbry.audiopress.app.Utterance
import io.github.tjnewbry.audiopress.app.ui.OutlinedBoxPadding
import io.github.tjnewbry.audiopress.app.ui.Spacing
import io.github.tjnewbry.audiopress.app.ui.outline
import io.github.tjnewbry.audiopress.core.SegmentKind
import kotlin.math.abs

/** A step of this many lines or fewer to the playing line is animated; a longer way is a jump. */
private const val NEAR_LINES = 2

/** The space above and below each line, which is also part of the line's tap area. */
private val LINE_PADDING = 6.dp

/**
 * The book's text in an outlined box, one line per utterance, with the playing line (and, in live speech, the word) highlighted.
 * While [follow] is locked the box scrolls itself to the playing line; a finger scrolling it calls [onUserScroll], and a
 * "Return to player position" button then shows, which calls [onReturn]. Tapping a line plays from it.
 */
@Composable
fun Captions(
    session: ReaderSession,
    listState: LazyListState,
    follow: ScrollFollow,
    onUserScroll: () -> Unit,
    onReturn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val userScrolled by rememberUpdatedState(onUserScroll)
    LaunchedEffect(listState) {
        // Only a finger on the list counts; the box scrolling itself to the playing line does not.
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolled() }
    }
    val current = session.currentLine
    val wasLocked = remember { mutableStateOf(true) }
    LaunchedEffect(current, follow.autoScrolls) {
        val lockedBefore = wasLocked.value
        wasLocked.value = follow.autoScrolls
        if (!follow.autoScrolls) return@LaunchedEffect
        val target = (current - 1).coerceAtLeast(0) // the line before the current one stays in view for context
        // Only a step to the next line is animated. Returning to the player, or any far jump, is instant: animating across a
        // very long text would lag.
        if (lockedBefore && abs(listState.firstVisibleItemIndex - target) <= NEAR_LINES) {
            listState.animateScrollToItem(target)
        } else {
            listState.scrollToItem(target)
        }
    }
    Box(modifier.outline(dotted = false).padding(OutlinedBoxPadding)) {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(session.lines, key = lineKey, contentType = {
                _,
                line,
                ->
                session.paragraphs[line.paragraph].kind
            }) { index, line ->
                val isCurrent = index == current && session.player.status != PlaybackStatus.STOPPED
                CaptionLine(
                    line,
                    heading = session.paragraphs[line.paragraph].kind == SegmentKind.HEADING,
                    isCurrent = isCurrent,
                    word = if (isCurrent) session.currentWord else null, // only the current line follows the word
                    onClick = {
                        onReturn() // the tapped line is where the player goes, so the box follows it again
                        session.jumpTo(index)
                    },
                )
            }
        }
        if (follow.showReturnButton) {
            FilledTonalButton(onClick = onReturn, modifier = Modifier.align(Alignment.BottomCenter)) { Text("Return to player position") }
        }
    }
}

/** Line i is list item i. */
private val lineKey: (Int, Utterance) -> Any = { index, _ -> index }

/** One line of the book: a heading in bold, the playing line in the primary colour, and its current [word] highlighted. */
@Composable
private fun CaptionLine(
    line: Utterance,
    heading: Boolean,
    isCurrent: Boolean,
    word: IntRange?,
    onClick: () -> Unit,
) {
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val text =
        buildAnnotatedString {
            append(line.text)
            if (word != null && word.last < line.text.length) {
                addStyle(SpanStyle(background = highlight, fontWeight = FontWeight.Bold), word.first, word.last + 1)
            }
        }
    Text(
        text,
        style = if (heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
        fontWeight = if (heading) FontWeight.Bold else null,
        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = LINE_PADDING),
    )
}
