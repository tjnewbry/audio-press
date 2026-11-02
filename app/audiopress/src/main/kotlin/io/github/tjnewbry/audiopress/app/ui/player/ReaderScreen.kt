package io.github.tjnewbry.audiopress.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.github.tjnewbry.audiopress.app.ReaderSession
import io.github.tjnewbry.audiopress.app.ScrollFollow
import io.github.tjnewbry.audiopress.app.ui.Spacing

/**
 * The player: the title with Library to go back; the speech and bake messages with the speed at the top right; the book's text
 * in a box; and the play button, seeker bar and time at the bottom.
 *
 * The sections read the [session] themselves, so state that changes many times a second (the current word, the played
 * position) redraws only the section that shows it; the smaller parts take plain values.
 */
@Composable
fun ReaderScreen(
    session: ReaderSession,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Line i is list item i. The box follows the playing line while locked; scrolling by hand unlocks it.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (session.currentLine - 1).coerceAtLeast(0))
    var follow by rememberSaveable(stateSaver = followSaver) { mutableStateOf(ScrollFollow()) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Library") }
            Text(
                session.book.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                SpeechMessage(session)
                BakeSection(session)
            }
            SpeedControl(session.speed, session::changeSpeed, session::finishSpeedChange)
        }
        Captions(
            session,
            listState,
            follow,
            onUserScroll = { follow = follow.userScrolled() },
            onReturn = { follow = follow.returned() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        PlayerBar(session, listState, follow, onSeek = { share ->
            follow = follow.returned() // show the place that was chosen
            session.seekTo(share)
        })
    }
}

private val followSaver = Saver<ScrollFollow, Boolean>(save = { it.locked }, restore = { ScrollFollow(it) })
