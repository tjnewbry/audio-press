package io.github.tjnewbry.audiopress.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.tjnewbry.audiopress.core.SegmentKind
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val library: LibraryViewModel by viewModels()

    // A bake keeps running in the background with a notification; Android 13 and newer ask before showing one.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun askForNotifications() {
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (needed &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStop() {
        // Leaving the app or turning the screen off pauses playback and saves the position. A rotation also stops the
        // activity, but the reading carries on through it.
        if (!isChangingConfigurations) library.pausePlayback()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val reader = library.reader
                var showLicences by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(reader?.showVoices) { if (reader?.showVoices == true) askForNotifications() }
                BackHandler(enabled = reader != null) { library.closeReader() }
                BackHandler(enabled = reader == null && showLicences) { showLicences = false }
                // Declared after the one above, so it wins: back leaves the voice or progress screen first.
                BackHandler(enabled = reader != null && (reader.showVoices || reader.showProgress)) { reader?.back() }
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    val modifier = Modifier.padding(padding).padding(horizontal = 16.dp)
                    when {
                        reader == null && showLicences -> {
                            LicencesScreen({ showLicences = false }, modifier)
                        }

                        reader == null -> {
                            LibraryScreen(library, { showLicences = true }, modifier)
                        }

                        reader.showProgress -> {
                            BakeProgressScreen(
                                reader.bakeState,
                                onCancel = reader::cancelBake,
                                onBackground = { moveTaskToBack(true) },
                                onListen = reader::listenWhileBaking,
                                modifier = modifier,
                            )
                        }

                        reader.showVoices -> {
                            VoiceScreen(reader, modifier)
                        }

                        else -> {
                            ReaderScreen(reader, library::closeReader, modifier)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    library: LibraryViewModel,
    onAbout: () -> Unit,
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
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            library.books,
                            key = { it.id },
                        ) { book ->
                            BookRow(
                                book,
                                baked = book.id in library.bakedBooks,
                                onDelete = { everything -> library.delete(book, everything) },
                                onClick = { library.open(book) },
                            )
                        }
                    }
                }
            }
        }
        TextButton(onClick = onAbout) { Text("About / Licences") }
    }
}

@Composable
private fun BookRow(
    book: Book,
    baked: Boolean,
    onDelete: (everything: Boolean) -> Unit,
    onClick: () -> Unit,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium)
                val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(book.importedAt))
                Text("%,d words, imported %s".format(book.wordCount, date), style = MaterialTheme.typography.bodyMedium)
                if (baked) Text("Baked audio", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = { asking = true }) { Text("Delete") }
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Delete \"${book.title}\"?") },
            text = {
                Text(
                    "Delete all removes the book and its baked audio. " +
                        "Delete bake removes only the baked audio and keeps the book.",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        asking = false
                        onDelete(true)
                    }) { Text("Delete all") }
                    TextButton(onClick = {
                        asking = false
                        onDelete(false)
                    }) { Text("Delete bake") }
                    TextButton(onClick = { asking = false }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun ReaderScreen(
    session: ReaderSession,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        SpeakButton(session)
        Timestamp(session)
        Captions(session) { BakeSection(session) }
    }
}

/** How long automatic scrolling waits after the user scrolls by hand. */
private const val USER_SCROLL_PAUSE_MS = 4000L

@Composable
private fun Captions(
    session: ReaderSession,
    header: @Composable () -> Unit,
) {
    // Line i is list item i + 1, after the header, so showing the line before the current one means item `current`.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = session.currentLine)
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) lastUserScroll = SystemClock.uptimeMillis()
        }
    }
    val current = session.currentLine
    LaunchedEffect(current, session.player.status) {
        val idle = SystemClock.uptimeMillis() - lastUserScroll > USER_SCROLL_PAUSE_MS
        if (session.player.status == PlaybackStatus.PLAYING && idle) {
            autoScrolling = true
            try {
                listState.animateScrollToItem(current) // the line before it stays in view for context
            } finally {
                autoScrolling = false
            }
        }
    }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "header", contentType = "header") { header() }
        itemsIndexed(session.lines, key = lineKey, contentType = { _, line -> session.paragraphs[line.paragraph].kind }) { index, line ->
            val isCurrent = index == current && session.player.status != PlaybackStatus.STOPPED
            val heading = session.paragraphs[line.paragraph].kind == SegmentKind.HEADING
            val highlight = MaterialTheme.colorScheme.primaryContainer
            val word = if (isCurrent) session.currentWord else null // only the current line follows the word
            val text =
                buildAnnotatedString {
                    append(line.text)
                    if (word != null && word.last < line.text.length) {
                        addStyle(SpanStyle(background = highlight, fontWeight = FontWeight.Bold), word.first, word.last + 1)
                    }
                }
            Text(
                text,
                style = if (heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (heading) FontWeight.Bold else null,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().clickable { session.jumpTo(index) }.padding(vertical = 6.dp),
            )
        }
    }
}

private val lineKey: (Int, Utterance) -> Any = { index, _ -> index }

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
        enabled = session.speech == SpeechState.Ready || session.bakeState is BakeState.Baked,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(label) }
    when (val speech = session.speech) {
        SpeechState.Starting -> {
            Text("Starting text-to-speech...")
        }

        SpeechState.Ready -> {
            // Nothing to show.
        }

        is SpeechState.Unavailable -> {
            if (session.bakeState !is BakeState.Baked) {
                Text(
                    speech.message,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun Timestamp(session: ReaderSession) {
    // Baked audio moves between words, so redraw twice a second while it plays.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(session.player.status, session.mode) {
        while (session.player.status == PlaybackStatus.PLAYING && session.mode == PlaybackMode.BAKED) {
            tick = SystemClock.uptimeMillis()
            delay(TICK_MS)
        }
    }
    val baked = session.mode == PlaybackMode.BAKED
    val times = "${formatDuration(session.elapsedSeconds())} / ${formatDuration(session.totalSeconds())}"
    Text(
        when {
            session.player.waiting -> "Waiting for audio…"
            session.listeningWhileBaking -> "Listening while baking"
            baked -> "$times (baked audio)"
            else -> "$times (estimated, live speech)"
        },
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.semantics { stateDescription = "tick $tick" },
    )
}

private const val TICK_MS = 500L

@Composable
private fun BakeSection(session: ReaderSession) {
    when (val state = session.bakeState) {
        BakeState.Unavailable -> {
            // This build cannot bake: no controls.
        }

        is BakeState.NotBaked -> {
            val label = if (state.done > 0) "Resume baking (${state.done} of ${state.total} done)" else "Bake audio for this book"
            OutlinedButton(onClick = session::openVoiceScreen, modifier = Modifier.fillMaxWidth()) { Text(label) }
        }

        is BakeState.Downloading -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (state.cancelling) "Cancelling..." else "Downloading ${state.quality.label}...")
                LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = session::cancelBake, enabled = !state.cancelling) { Text("Cancel") }
            }
        }

        is BakeState.Baking -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f
                val using =
                    state.voice
                        ?.voice
                        ?.name
                        ?.let { " using $it" }
                        .orEmpty()
                Text(if (state.cancelling) "Cancelling..." else "Baking audio$using: ${state.done} of ${state.total}")
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = session::cancelBake, enabled = !state.cancelling) { Text("Cancel baking") }
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
