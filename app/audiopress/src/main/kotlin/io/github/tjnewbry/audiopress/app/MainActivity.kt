package io.github.tjnewbry.audiopress.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.tjnewbry.audiopress.core.SegmentKind
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

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
                BackHandler(enabled = reader == null && library.pending != null) { library.cancelPending() }
                // Declared after the one above, so it wins: back leaves the voice or progress screen first.
                BackHandler(enabled = reader != null && (reader.showVoices || reader.showProgress)) { reader?.back() }
                ExportDialogs((application as AudioPressApp).exportCenter)
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    val modifier = Modifier.padding(padding).padding(horizontal = 16.dp)
                    val pending = library.pending
                    when {
                        reader == null && pending != null -> {
                            ImportScreen(
                                pending,
                                onCancel = library::cancelPending,
                                onImport = { range, name -> library.importPending(range, name) },
                                modifier = modifier,
                            )
                        }

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
                                onGoToPlayer = reader::goToPlayer,
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
    val importing = library.importState is ImportState.Working || library.importState is ImportState.Preparing
    // The book being exported while the save dialog is open (by id, so it survives a rotation).
    var exportingId by rememberSaveable { mutableStateOf<String?>(null) }
    val savePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
            val book = library.books.firstOrNull { it.id == exportingId }
            exportingId = null
            if (uri != null && book != null) library.exportBakedAudio(book, uri)
        }
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

            is ImportState.Preparing -> {
                Text("Opening \"${state.fileName}\"...")
            }

            is ImportState.Working -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val message = "Importing \"${state.fileName}\"... this can take a while for a long book."
                    Text(if (state.cancelling) "Cancelling..." else message)
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
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
        library.info?.let { lines ->
            AlertDialog(
                onDismissRequest = library::dismissInfo,
                text = { Column { lines.forEach { Text(it) } } },
                confirmButton = { TextButton(onClick = library::dismissInfo) { Text("OK") } },
            )
        }
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
                                onRename = { title -> library.rename(book, title) },
                                onInfo = { library.showInfo(book) },
                                onExport = {
                                    exportingId = book.id
                                    savePicker.launch(suggestedExportName(book.title))
                                },
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
    onRename: (String) -> Unit,
    onInfo: () -> Unit,
    onExport: () -> Unit,
    onClick: () -> Unit,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium)
                val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(book.importedAt))
                Text("%,d words, imported %s".format(book.wordCount, date), style = MaterialTheme.typography.bodyMedium)
                if (baked) Text("Baked audio", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Box {
                TextButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.semantics { contentDescription = "Options for ${book.title}" },
                ) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // Not enabled-greyed in the sense of a dead click: a tap on it explains what is missing.
                    DropdownMenuItem(
                        text = {
                            Text("Export baked audio", color = if (baked) Color.Unspecified else MaterialTheme.colorScheme.outline)
                        },
                        onClick = {
                            menuOpen = false
                            if (baked) onExport() else notice = "Must bake audio first."
                        },
                    )
                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                        menuOpen = false
                        renaming = true
                    })
                    DropdownMenuItem(text = { Text("Info") }, onClick = {
                        menuOpen = false
                        onInfo()
                    })
                    DropdownMenuItem(text = { Text("Deletion") }, onClick = {
                        menuOpen = false
                        asking = true
                    })
                }
            }
        }
    }
    notice?.let { message ->
        AlertDialog(
            onDismissRequest = { notice = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } },
        )
    }
    if (renaming) RenameDialog(book.title, onDismiss = { renaming = false }, onRename = onRename)
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
internal fun RenameDialog(
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

@Composable
private fun ReaderScreen(
    session: ReaderSession,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Line i is list item i. The box follows the playing line while locked; scrolling by hand unlocks it.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (session.currentLine - 1).coerceAtLeast(0))
    var follow by rememberSaveable(stateSaver = followSaver) { mutableStateOf(ScrollFollow()) }
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
        // The bake and speech messages, and the speed, are at the top; the player controls are at the bottom.
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                SpeechStatus(session)
                BakeSection(session)
            }
            SpeedControl(session.speed, session::changeSpeed)
        }
        Captions(
            session,
            listState,
            follow,
            onUserScroll = { follow = follow.userScrolled() },
            onReturn = { follow = follow.returned() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        PlayerBar(session, listState, follow, onSeek = { share ->
            follow = follow.returned() // show the place that was chosen
            session.seekTo(share)
        })
    }
}

/** A step of this many lines or fewer to the playing line is animated; a longer way is a jump. */
private const val NEAR_LINES = 2

private val followSaver = Saver<ScrollFollow, Boolean>(save = { it.locked }, restore = { ScrollFollow(it) })

@Composable
private fun Captions(
    session: ReaderSession,
    listState: LazyListState,
    follow: ScrollFollow,
    onUserScroll: () -> Unit,
    onReturn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val userScrolled by rememberUpdatedState(onUserScroll)
    LaunchedEffect(listState) {
        // Only a finger on the list counts; the box scrolling itself to the playing line does not.
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolled() }
    }
    val current = session.currentLine
    val wasLocked = remember { mutableStateOf(true) }
    LaunchedEffect(current, follow.autoScrolls) {
        val lockedBefore = wasLocked.value
        wasLocked.value = follow.autoScrolls
        if (!follow.autoScrolls) return@LaunchedEffect
        val target = (current - 1).coerceAtLeast(0) // the line before the current one stays in view for context
        // Only a step to the next line is animated. Returning to the player, or any far jump, is instant: animating across a
        // very long text would lag.
        if (lockedBefore && abs(listState.firstVisibleItemIndex - target) <= NEAR_LINES) {
            listState.animateScrollToItem(target)
        } else {
            listState.scrollToItem(target)
        }
    }
    Box(modifier.outline(dotted = false).padding(6.dp)) {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(session.lines, key = lineKey, contentType = {
                _,
                line,
                ->
                session.paragraphs[line.paragraph].kind
            }) { index, line ->
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
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onReturn() // the tapped line is where the player goes, so the box follows it again
                                session.jumpTo(index)
                            }.padding(vertical = 6.dp),
                )
            }
        }
        if (follow.showReturnButton) {
            FilledTonalButton(onClick = onReturn, modifier = Modifier.align(Alignment.BottomCenter)) { Text("Return to player position") }
        }
    }
}

private val lineKey: (Int, Utterance) -> Any = { index, _ -> index }

/** The messages about the speech engine that used to sit under the Speak button. */
@Composable
private fun SpeechStatus(session: ReaderSession) {
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
                LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
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
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
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

/** The name the save dialog suggests: the book's title with `.m4a`, without characters files cannot have. */
fun suggestedExportName(title: String): String = title.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifEmpty { "audiobook" } + ".m4a"

/** The progress, failure and success dialogs of an export; shown over whatever screen is open. */
@Composable
private fun ExportDialogs(center: ExportCenter) {
    when (val state = center.state) {
        ExportState.Idle -> {
            // No export to show.
        }

        is ExportState.Working -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Exporting \"${state.title}\"") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.cancelling) "Cancelling..." else "${(state.fraction * PERCENT).roundToInt()}%")
                        LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                    }
                },
                confirmButton = { TextButton(onClick = center::cancel, enabled = !state.cancelling) { Text("Cancel") } },
            )
        }

        is ExportState.Failed -> {
            AlertDialog(
                onDismissRequest = center::acknowledge,
                title = { Text("Export failed") },
                text = { Text(state.message) },
                confirmButton = { TextButton(onClick = center::acknowledge) { Text("OK") } },
            )
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

private const val PERCENT = 100
