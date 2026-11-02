package io.github.tjnewbry.audiopress.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.tjnewbry.audiopress.app.ImportState
import io.github.tjnewbry.audiopress.app.LibraryViewModel
import io.github.tjnewbry.audiopress.app.suggestedExportName
import io.github.tjnewbry.audiopress.app.ui.CancellableProgress
import io.github.tjnewbry.audiopress.app.ui.MessageDialog
import io.github.tjnewbry.audiopress.app.ui.Spacing

/**
 * The first screen: Import PDF, the progress of an import, and the list of books (tap one to open it in the player, or use its
 * three-dots menu). About / Licences is at the bottom.
 */
@Composable
fun LibraryScreen(
    library: LibraryViewModel,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) library.importPdf(uri)
        }
    // The book being exported while the save dialog is open (by id, so it survives a rotation).
    var exportingId by rememberSaveable { mutableStateOf<String?>(null) }
    val savePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
            val book = library.books.firstOrNull { it.id == exportingId }
            exportingId = null
            if (uri != null && book != null) library.exportBakedAudio(book, uri)
        }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
        Text("Audio Press", style = MaterialTheme.typography.headlineMedium)
        Button(
            onClick = { picker.launch(arrayOf("application/pdf")) },
            enabled = !library.importState.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import PDF") }
        ImportStatus(library.importState, onCancel = library::cancelImport, onDismissError = library::dismissImportError)
        library.openError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        library.info?.let { lines -> MessageDialog(lines, onDismiss = library::dismissInfo) }
        Box(Modifier.weight(1f)) {
            when {
                !library.loaded -> {
                    // The list is still being read.
                }

                library.books.isEmpty() -> {
                    Text(
                        "No books yet. Tap Import PDF and choose a PDF with real text on its pages (not a scan or a photo) " +
                            "from your phone. Audio Press cleans it up and adds it here, ready to read aloud.",
                    )
                }

                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                        items(library.books, key = { it.id }) { book ->
                            BookRow(
                                book,
                                baked = book.id in library.bakedBooks,
                                onOpen = { library.open(book) },
                                onExport = {
                                    exportingId = book.id
                                    savePicker.launch(suggestedExportName(book.title))
                                },
                                onRename = { title -> library.rename(book, title) },
                                onInfo = { library.showInfo(book) },
                                onDelete = { everything -> library.delete(book, everything) },
                            )
                        }
                    }
                }
            }
        }
        TextButton(onClick = onAbout) { Text("About / Licences") }
    }
}

/** True while a picked PDF is being opened or imported; Import PDF is greyed meanwhile. */
private val ImportState.isBusy: Boolean get() = this is ImportState.Working || this is ImportState.Preparing

/** The state of an import under the Import PDF button: opening the PDF, the progress with Cancel, or what went wrong. */
@Composable
private fun ImportStatus(
    state: ImportState,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
) {
    when (state) {
        ImportState.Idle -> {
            // Nothing to show.
        }

        is ImportState.Preparing -> {
            Text("Opening \"${state.fileName}\"...")
        }

        is ImportState.Working -> {
            CancellableProgress(
                message = "Importing \"${state.fileName}\"... this can take a while for a long book.",
                fraction = state.progress,
                cancelling = state.cancelling,
                onCancel = onCancel,
            )
        }

        is ImportState.Failed -> {
            Column {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onDismissError) { Text("Dismiss") }
            }
        }
    }
}
