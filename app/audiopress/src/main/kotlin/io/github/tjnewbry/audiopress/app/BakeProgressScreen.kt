package io.github.tjnewbry.audiopress.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The full-screen progress of a bake: the download of the model (when needed), then the baking. Cancel is top left.
 * While baking it shows the time left, and offers to run in the background (the app goes to the home screen and the
 * bake carries on) or to listen to the clips as they are made.
 */
@Composable
fun BakeProgressScreen(
    state: BakeState,
    onCancel: () -> Unit,
    onBackground: () -> Unit,
    onListen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cancelling = (state as? BakeState.Downloading)?.cancelling ?: (state as? BakeState.Baking)?.cancelling ?: false
    Box(modifier.fillMaxSize()) {
        TextButton(onClick = onCancel, enabled = !cancelling, modifier = Modifier.align(Alignment.TopStart)) { Text("Cancel") }
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                is BakeState.Downloading -> {
                    Text(
                        if (state.cancelling) "Cancelling..." else "Downloading ${state.quality.label}...",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    ProgressBar(state.fraction)
                    Text(
                        "${formatMegabytes(state.doneBytes)} / ${formatMegabytes(state.totalBytes)}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text("${(state.fraction * PERCENT).roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = onBackground, modifier = Modifier.padding(top = 32.dp)) { Text("Run in background") }
                }

                is BakeState.Baking -> {
                    Text(bakingTitle(state), style = MaterialTheme.typography.titleLarge)
                    ProgressBar(if (state.total > 0) state.done.toFloat() / state.total else 0f)
                    Text("${state.done} of ${state.total}", style = MaterialTheme.typography.bodyLarge)
                    Text(remainingText(state), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onListen, enabled = !state.cancelling, modifier = Modifier.padding(top = 32.dp)) {
                        Text("Listen while baking")
                    }
                    OutlinedButton(onClick = onBackground, modifier = Modifier.padding(top = 8.dp)) { Text("Run in background") }
                }

                else -> {
                    Text("Finishing...", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

private const val PERCENT = 100

private fun remainingText(state: BakeState.Baking): String =
    when {
        state.cancelling -> ""
        state.etaMs != null -> formatRemaining(state.etaMs)
        else -> "Working out how long it will take..."
    }

private fun bakingTitle(state: BakeState.Baking): String {
    val name = state.voice?.voice?.name
    return when {
        state.cancelling -> "Cancelling..."
        name != null -> "Baking audio using $name..."
        else -> "Baking audio..."
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), drawStopIndicator = {})
}
