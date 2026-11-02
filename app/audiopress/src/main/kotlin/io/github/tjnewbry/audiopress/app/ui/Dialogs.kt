package io.github.tjnewbry.audiopress.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment

/** A message of one or more [lines] with an OK button; tapping outside also closes it. */
@Composable
fun MessageDialog(
    lines: List<String>,
    onDismiss: () -> Unit,
    title: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = if (title != null) ({ Text(title) }) else null,
        text = { Column { lines.forEach { Text(it) } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** One button of a [ChoiceDialog]: its [label], and what it does after the dialog closes. */
class DialogChoice(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * A question with its answers as buttons stacked at the right, one under the other, so long labels fit. Every button closes
 * the dialog first, then does what it does; tapping outside only closes it.
 */
@Composable
fun ChoiceDialog(
    title: String,
    choices: List<DialogChoice>,
    onDismiss: () -> Unit,
    text: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = if (text != null) ({ Text(text) }) else null,
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                for (choice in choices) {
                    TextButton(onClick = {
                        onDismiss()
                        choice.onClick()
                    }) { Text(choice.label) }
                }
            }
        },
    )
}

/** Asks for a new name, starting from [title]. [onRename] gets the text as typed; the caller trims and checks it. */
@Composable
fun RenameDialog(
    title: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename book") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Title") }) },
        confirmButton = {
            TextButton(onClick = {
                onRename(text)
                onDismiss()
            }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
