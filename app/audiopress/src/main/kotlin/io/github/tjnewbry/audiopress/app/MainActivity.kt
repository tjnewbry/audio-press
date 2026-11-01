package io.github.tjnewbry.audiopress.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val model: ReaderViewModel = viewModel()
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    ReaderScreen(model, Modifier.padding(padding).padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun ReaderScreen(
    model: ReaderViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Audio Press", style = MaterialTheme.typography.headlineMedium)
        SpeakButton(model)
        when (val text = model.text) {
            TextState.Loading -> {
                Text("Preparing the text...")
            }

            is TextState.Failed -> {
                Text(text.message, color = MaterialTheme.colorScheme.error)
            }

            is TextState.Ready -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(text.paragraphs) { index, paragraph ->
                        val current = model.player.status != PlaybackStatus.STOPPED && model.player.index == index
                        Text(
                            paragraph,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeakButton(model: ReaderViewModel) {
    val label =
        when (model.player.status) {
            PlaybackStatus.STOPPED -> "Speak"
            PlaybackStatus.PLAYING -> "Pause"
            PlaybackStatus.PAUSED -> "Resume"
        }
    val ready = model.speech == SpeechState.Ready && model.text is TextState.Ready
    Button(onClick = model::togglePlayback, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text(label) }
    when (val speech = model.speech) {
        SpeechState.Starting -> Text("Starting text-to-speech...")
        SpeechState.Ready -> Unit
        is SpeechState.Unavailable -> Text(speech.message, color = MaterialTheme.colorScheme.error)
    }
}
