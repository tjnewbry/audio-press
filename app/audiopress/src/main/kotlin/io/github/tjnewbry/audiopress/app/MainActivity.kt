package io.github.tjnewbry.audiopress.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tjnewbry.audiopress.core.SegmentKind
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val library: LibraryViewModel = viewModel()
                val reader = library.reader
                BackHandler(enabled = reader != null) { library.closeReader() }
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    val modifier = Modifier.padding(padding).padding(horizontal = 16.dp)
                    if (reader == null) {
                        LibraryScreen(library, modifier)
                    } else {
                        ReaderScreen(reader, library::closeReader, modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    library: LibraryViewModel,
    modifier: Modifier = Modifier,
) {
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) library.importPdf(uri)
        }
    val importing = library.importState is ImportState.Working
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Audio Press", style = MaterialTheme.typography.headlineMedium)
        Button(
            onClick = { picker.launch(arrayOf("application/pdf")) },
            enabled = !importing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import PDF") }
        when (val state = library.importState) {
            ImportState.Idle -> {
                // Nothing to show.
            }

            is ImportState.Working -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val message = "Importing \"${state.fileName}\"... this can take a while for a long book."
                    Text(if (state.cancelling) "Cancelling..." else message)
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = library::cancelImport, enabled = !state.cancelling) { Text("Cancel") }
                }
            }

            is ImportState.Failed -> {
                Column {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = library::dismissImportError) { Text("Dismiss") }
                }
            }
        }
        library.openError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(library.books, key = { it.id }) { book -> BookRow(book) { library.open(book) } }
                }
            }
        }
    }
}

@Composable
private fun BookRow(
    book: Book,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium)
            val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(book.importedAt))
            Text("%,d words, imported %s".format(book.wordCount, date), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ReaderScreen(
    session: ReaderSession,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Library") }
            Text(session.book.title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        }
        SpeakButton(session)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(session.paragraphs) { index, paragraph ->
                val current = session.player.status != PlaybackStatus.STOPPED && session.player.index == index
                val heading = paragraph.kind == SegmentKind.HEADING
                Text(
                    paragraph.text,
                    style = if (heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = if (heading) FontWeight.Bold else null,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SpeakButton(session: ReaderSession) {
    val label =
        when (session.player.status) {
            PlaybackStatus.STOPPED -> "Speak"
            PlaybackStatus.PLAYING -> "Pause"
            PlaybackStatus.PAUSED -> "Resume"
        }
    Button(
        onClick = session::togglePlayback,
        enabled = session.speech == SpeechState.Ready,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(label) }
    when (val speech = session.speech) {
        SpeechState.Starting -> Text("Starting text-to-speech...")
        SpeechState.Ready -> Unit
        is SpeechState.Unavailable -> Text(speech.message, color = MaterialTheme.colorScheme.error)
    }
}
