package io.github.tjnewbry.audiopress.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** A progress bar across the width, [fraction] 0 to 1, with no dot at its end. */
@Composable
fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    LinearProgressIndicator(progress = { fraction }, modifier = modifier.fillMaxWidth(), drawStopIndicator = {})
}

/** Shown in place of the message of work that is stopping after Cancel was pressed. */
const val CANCELLING = "Cancelling..."

/**
 * Work in progress inside a screen: the [message] (or "Cancelling..." once [cancelling]), a progress bar, and a Cancel
 * button labelled [cancelLabel], which is greyed while cancelling.
 */
@Composable
fun CancellableProgress(
    message: String,
    fraction: Float,
    cancelling: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancelLabel: String = "Cancel",
    spacing: Dp = Spacing.Small,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        Text(if (cancelling) CANCELLING else message)
        ProgressBar(fraction)
        TextButton(onClick = onCancel, enabled = !cancelling) { Text(cancelLabel) }
    }
}
