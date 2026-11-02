package io.github.tjnewbry.audiopress.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.tjnewbry.audiopress.app.Book
import io.github.tjnewbry.audiopress.app.ui.ChoiceDialog
import io.github.tjnewbry.audiopress.app.ui.DialogChoice
import io.github.tjnewbry.audiopress.app.ui.MessageDialog
import io.github.tjnewbry.audiopress.app.ui.RenameDialog
import io.github.tjnewbry.audiopress.app.ui.Spacing
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * One book in the library: its title, length and import date, "Baked audio" once it is fully [baked], and a three-dots menu
 * (Export baked audio, Rename, Info, Deletion). Tapping the row opens the book. The menu's dialogs live here too.
 */
@Composable
fun BookRow(
    book: Book,
    baked: Boolean,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onRename: (String) -> Unit,
    onInfo: () -> Unit,
    onDelete: (everything: Boolean) -> Unit,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.padding(start = Spacing.Large, top = Spacing.Small, bottom = Spacing.Small, end = Spacing.ExtraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium)
                Text(bookSummary(book.wordCount, book.importedAt), style = MaterialTheme.typography.bodyMedium)
                if (baked) Text("Baked audio", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            BookMenu(
                book.title,
                baked,
                onExport = { if (baked) onExport() else notice = "Must bake audio first." },
                onRename = { renaming = true },
                onInfo = onInfo,
                onDeletion = { deleting = true },
            )
        }
    }
    notice?.let { message -> MessageDialog(listOf(message), onDismiss = { notice = null }) }
    if (renaming) RenameDialog(book.title, onDismiss = { renaming = false }, onRename = onRename)
    if (deleting) {
        ChoiceDialog(
            title = "Delete \"${book.title}\"?",
            text = "Delete all removes the book and its baked audio. Delete bake removes only the baked audio and keeps the book.",
            choices =
                listOf(
                    DialogChoice("Delete all") { onDelete(true) },
                    DialogChoice("Delete bake") { onDelete(false) },
                    DialogChoice("Cancel") {},
                ),
            onDismiss = { deleting = false },
        )
    }
}

/** The three-dots button and its menu. Export is drawn grey until the book is [baked], but still answers a tap. */
@Composable
private fun BookMenu(
    title: String,
    baked: Boolean,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit,
    onDeletion: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val choose: (() -> Unit) -> Unit = { action ->
        open = false
        action()
    }
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.semantics { contentDescription = "Options for $title" },
        ) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val exportColor = if (baked) Color.Unspecified else MaterialTheme.colorScheme.outline
            DropdownMenuItem(text = { Text("Export baked audio", color = exportColor) }, onClick = { choose(onExport) })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { choose(onRename) })
            DropdownMenuItem(text = { Text("Info") }, onClick = { choose(onInfo) })
            DropdownMenuItem(text = { Text("Deletion") }, onClick = { choose(onDeletion) })
        }
    }
}

/** The line under a book's title: `4,140 words, imported Nov 2, 2026` (in the phone's own number and date formats). */
internal fun bookSummary(
    wordCount: Int,
    importedAt: Long,
    dateFormat: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM),
    locale: Locale = Locale.getDefault(),
): String = String.format(locale, "%,d words, imported %s", wordCount, dateFormat.format(Date(importedAt)))
