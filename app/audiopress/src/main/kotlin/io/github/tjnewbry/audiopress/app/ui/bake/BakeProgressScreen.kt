package io.github.tjnewbry.audiopress.app.ui.bake

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.tjnewbry.audiopress.app.BakeState
import io.github.tjnewbry.audiopress.app.formatMegabytes
import io.github.tjnewbry.audiopress.app.formatPercent
import io.github.tjnewbry.audiopress.app.formatRemaining
import io.github.tjnewbry.audiopress.app.isCancelling
import io.github.tjnewbry.audiopress.app.label
import io.github.tjnewbry.audiopress.app.ui.CANCELLING
import io.github.tjnewbry.audiopress.app.ui.ProgressBar
import io.github.tjnewbry.audiopress.app.ui.Spacing

/**
 * The full-screen progress of a bake: the download of the model (when needed), then the baking. Cancel is top left.
 * While baking it shows the time left, and offers to run in the background (the app goes to the home screen and the
 * bake carries on) or to go to the player, where the clips can be played as they are made.
 */
@Composable
fun BakeProgressScreen(
    state: BakeState,
    onCancel: () -> Unit,
    onBackground: () -> Unit,
    onGoToPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        TextButton(onClick = onCancel, enabled = !state.isCancelling, modifier = Modifier.align(Alignment.TopStart)) { Text("Cancel") }
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val title = MaterialTheme.typography.titleLarge
            val body = MaterialTheme.typography.bodyLarge
            val bar = Modifier.padding(vertical = Spacing.Large)
            when (state) {
                is BakeState.Downloading -> {
                    Text(if (state.cancelling) CANCELLING else "Downloading ${state.quality.label}...", style = title)
                    ProgressBar(state.fraction, bar)
                    Text("${formatMegabytes(state.doneBytes)} / ${formatMegabytes(state.totalBytes)}", style = body)
                    Text(formatPercent(state.fraction), style = body)
                    OutlinedButton(onClick = onBackground, modifier = Modifier.padding(top = Spacing.Huge)) { Text("Run in background") }
                }

                is BakeState.Baking -> {
                    Text(bakingTitle(state), style = title)
                    ProgressBar(if (state.total > 0) state.done.toFloat() / state.total else 0f, bar)
                    Text("${state.done} of ${state.total}", style = body)
                    Text(remainingText(state), style = body)
                    Button(onClick = onGoToPlayer, enabled = !state.cancelling, modifier = Modifier.padding(top = Spacing.Huge)) {
                        Text("Go to player")
                    }
                    OutlinedButton(onClick = onBackground, modifier = Modifier.padding(top = Spacing.Small)) { Text("Run in background") }
                }

                else -> {
                    Text("Finishing...", style = title)
                }
            }
        }
    }
}

/** The title while baking: `Baking audio using Bella...`, or `Cancelling...`. */
internal fun bakingTitle(state: BakeState.Baking): String {
    val name = state.voice?.voice?.name
    return when {
        state.cancelling -> CANCELLING
        name != null -> "Baking audio using $name..."
        else -> "Baking audio..."
    }
}

/** The time left under the progress: `about 4 min left`, a note while there is no rate yet, and nothing while cancelling. */
internal fun remainingText(state: BakeState.Baking): String =
    when {
        state.cancelling -> ""
        state.etaMs != null -> formatRemaining(state.etaMs)
        else -> "Working out how long it will take..."
    }
