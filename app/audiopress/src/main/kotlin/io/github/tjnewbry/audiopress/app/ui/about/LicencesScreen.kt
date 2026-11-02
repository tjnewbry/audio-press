package io.github.tjnewbry.audiopress.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.github.tjnewbry.audiopress.app.ui.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** The asset the build puts together from the app's notices and those of the bake module (see `generateNotices`). */
private const val LICENCES_ASSET = "licences.txt"

/** About Audio Press: the licences and notices of everything it is built from, as one scrollable text. */
@Composable
fun LicencesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val text by produceState("Loading...") {
        value =
            withContext(Dispatchers.IO) {
                try {
                    context.assets
                        .open(LICENCES_ASSET)
                        .bufferedReader()
                        .use { it.readText() }
                } catch (e: IOException) {
                    "The licence notices could not be read."
                }
            }
    }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("About / Licences", style = MaterialTheme.typography.titleLarge)
        }
        SelectionContainer {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            )
        }
    }
}
