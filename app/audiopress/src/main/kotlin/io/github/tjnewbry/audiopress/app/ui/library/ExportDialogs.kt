package io.github.tjnewbry.audiopress.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.tjnewbry.audiopress.app.ExportCenter
import io.github.tjnewbry.audiopress.app.ExportState
import io.github.tjnewbry.audiopress.app.exportSuccessText
import io.github.tjnewbry.audiopress.app.formatPercent
import io.github.tjnewbry.audiopress.app.ui.CANCELLING
import io.github.tjnewbry.audiopress.app.ui.MessageDialog
import io.github.tjnewbry.audiopress.app.ui.ProgressBar
import io.github.tjnewbry.audiopress.app.ui.Spacing

/** The progress, failure and success dialogs of an export; shown over whatever screen is open. */
@Composable
fun ExportDialogs(center: ExportCenter) {
    when (val state = center.state) {
        ExportState.Idle -> {
            // No export to show.
        }

        is ExportState.Working -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Exporting \"${state.title}\"") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                        Text(if (state.cancelling) CANCELLING else formatPercent(state.fraction))
                        ProgressBar(state.fraction)
                    }
                },
                confirmButton = { TextButton(onClick = center::cancel, enabled = !state.cancelling) { Text("Cancel") } },
            )
        }

        is ExportState.Failed -> {
            MessageDialog(listOf(state.message), onDismiss = center::acknowledge, title = "Export failed")
        }

        is ExportState.Done -> {
            // The one button must be used; tapping outside does not close it.
            AlertDialog(
                onDismissRequest = {},
                text = { Text(exportSuccessText(state.exportBytes, state.footprintBytes)) },
                confirmButton = { TextButton(onClick = center::acknowledge) { Text("Acknowledge") } },
            )
        }
    }
}
