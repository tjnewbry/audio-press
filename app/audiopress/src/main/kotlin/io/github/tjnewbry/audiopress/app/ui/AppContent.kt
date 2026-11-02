package io.github.tjnewbry.audiopress.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.tjnewbry.audiopress.app.ExportCenter
import io.github.tjnewbry.audiopress.app.LibraryViewModel
import io.github.tjnewbry.audiopress.app.ui.about.LicencesScreen
import io.github.tjnewbry.audiopress.app.ui.bake.BakeProgressScreen
import io.github.tjnewbry.audiopress.app.ui.bake.VoiceScreen
import io.github.tjnewbry.audiopress.app.ui.library.ExportDialogs
import io.github.tjnewbry.audiopress.app.ui.library.LibraryScreen
import io.github.tjnewbry.audiopress.app.ui.pdfimport.ImportScreen
import io.github.tjnewbry.audiopress.app.ui.player.ReaderScreen

/**
 * The whole app on one screen at a time: the library (or About / Licences, or the import screen for a picked PDF) while no
 * book is open; with a book open, the player, or the voice screen or bake progress over it. Back leaves the innermost of
 * them. The export dialogs show over any screen. [onVoiceScreenShown] is called when the voice screen opens (the bake it
 * starts shows a notification), and [onRunInBackground] sends the app to the home screen.
 */
@Composable
fun AppContent(
    library: LibraryViewModel,
    exports: ExportCenter,
    onVoiceScreenShown: () -> Unit,
    onRunInBackground: () -> Unit,
) {
    val reader = library.reader
    val pending = library.pending
    var showLicences by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(reader?.showVoices) { if (reader?.showVoices == true) onVoiceScreenShown() }
    BackHandler(enabled = reader != null) { library.closeReader() }
    BackHandler(enabled = reader == null && showLicences) { showLicences = false }
    BackHandler(enabled = reader == null && pending != null) { library.cancelPending() }
    // Declared after the one above, so it wins: back leaves the voice or progress screen first.
    BackHandler(enabled = reader != null && (reader.showVoices || reader.showProgress)) { reader?.back() }
    ExportDialogs(exports)
    Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
        val modifier = Modifier.padding(insets).padding(horizontal = ScreenPadding)
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
                LicencesScreen(onBack = { showLicences = false }, modifier = modifier)
            }

            reader == null -> {
                LibraryScreen(library, onAbout = { showLicences = true }, modifier = modifier)
            }

            reader.showProgress -> {
                BakeProgressScreen(
                    reader.bakeState,
                    onCancel = reader::cancelBake,
                    onBackground = onRunInBackground,
                    onGoToPlayer = reader::goToPlayer,
                    modifier = modifier,
                )
            }

            reader.showVoices -> {
                VoiceScreen(reader, modifier)
            }

            else -> {
                ReaderScreen(reader, onBack = library::closeReader, modifier = modifier)
            }
        }
    }
}
