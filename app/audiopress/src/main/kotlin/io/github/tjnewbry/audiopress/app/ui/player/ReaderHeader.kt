package io.github.tjnewbry.audiopress.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.tjnewbry.audiopress.app.BakeState
import io.github.tjnewbry.audiopress.app.ReaderSession
import io.github.tjnewbry.audiopress.app.SpeechState
import io.github.tjnewbry.audiopress.app.formatSize
import io.github.tjnewbry.audiopress.app.label
import io.github.tjnewbry.audiopress.app.ui.CancellableProgress
import io.github.tjnewbry.audiopress.app.ui.Spacing

/**
 * The message shown when the speech engine cannot be used (unless the book is baked, so it plays anyway). Nothing shows while
 * the engine starts (a fraction of a second on a normal open), so the screen keeps one height as it opens.
 */
@Composable
fun SpeechMessage(session: ReaderSession) {
    val speech = session.speech
    if (speech is SpeechState.Unavailable && session.bakeState !is BakeState.Baked) {
        Text(speech.message, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * The bake controls above the text: the Bake button (or Resume), the progress of a download or bake with Cancel, a failure
 * with Try again, or, once baked, the size and the switch between baked audio and live speech.
 */
@Composable
fun BakeSection(session: ReaderSession) {
    when (val state = session.bakeState) {
        BakeState.Unavailable -> {
            // This build cannot bake: no controls.
        }

        is BakeState.NotBaked -> {
            OutlinedButton(onClick = session::openVoiceScreen, modifier = Modifier.fillMaxWidth()) { Text(bakeButtonLabel(state)) }
        }

        is BakeState.Downloading -> {
            // Tapping the progress opens the full-screen page; the Cancel button inside still cancels.
            Box(Modifier.clickable(onClickLabel = "Show the full progress", onClick = session::openProgress)) {
                CancellableProgress(
                    message = "Downloading ${state.quality.label}...",
                    fraction = state.fraction,
                    cancelling = state.cancelling,
                    onCancel = session::cancelBake,
                    spacing = Spacing.ExtraSmall,
                )
            }
        }

        is BakeState.Baking -> {
            Box(Modifier.clickable(onClickLabel = "Show the full progress", onClick = session::openProgress)) {
                CancellableProgress(
                    message = bakingLine(state),
                    fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f,
                    cancelling = state.cancelling,
                    onCancel = session::cancelBake,
                    cancelLabel = "Cancel baking",
                    spacing = Spacing.ExtraSmall,
                )
            }
        }

        is BakeState.Failed -> {
            Column {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = session::openVoiceScreen) { Text("Try again") }
            }
        }

        is BakeState.Baked -> {
            Column {
                Text("Baked audio on this phone: ${formatSize(state.bytes)}", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { session.choosePlayback(live = !session.preferLive) }) {
                    Text(if (session.preferLive) "Play the baked audio" else "Read with live speech instead")
                }
            }
        }
    }
}

/** The Bake button: `Bake audio for this book`, or `Resume baking (3 of 18 done)` after an interrupted bake. */
internal fun bakeButtonLabel(state: BakeState.NotBaked): String =
    if (state.done > 0) "Resume baking (${state.done} of ${state.total} done)" else "Bake audio for this book"

/** The line over the bake's progress bar in the player: `Baking audio using Bella: 3 of 18`. */
internal fun bakingLine(state: BakeState.Baking): String {
    val using =
        state.voice
            ?.voice
            ?.name
            ?.let { " using $it" }
            .orEmpty()
    return "Baking audio$using: ${state.done} of ${state.total}"
}
