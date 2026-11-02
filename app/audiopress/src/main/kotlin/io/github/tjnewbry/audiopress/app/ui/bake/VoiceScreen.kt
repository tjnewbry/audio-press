package io.github.tjnewbry.audiopress.app.ui.bake

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.tjnewbry.audiopress.app.KOKORO_VOICES
import io.github.tjnewbry.audiopress.app.ReaderSession
import io.github.tjnewbry.audiopress.app.SamplePlayer
import io.github.tjnewbry.audiopress.app.VoicePicker
import io.github.tjnewbry.audiopress.app.VoiceQuality
import io.github.tjnewbry.audiopress.app.defaultQuality
import io.github.tjnewbry.audiopress.app.label
import io.github.tjnewbry.audiopress.app.ui.OutlinedBoxPadding
import io.github.tjnewbry.audiopress.app.ui.Spacing
import io.github.tjnewbry.audiopress.app.ui.outline
import io.github.tjnewbry.audiopress.app.voiceById

// Unselected tabs are pure greys (zero saturation); the selected tab is a saturated blue with white text.
private val SelectedTab = Color(0xFF1565C0)
private val SelectedTabOutline = Color(0xFF0D47A1)
private val SelectedTabText = Color.White
private val UnselectedTab = Color(0xFFE0E0E0)
private val UnselectedTabOutline = Color(0xFF616161)
private val UnselectedTabText = Color(0xFF212121)

private val TAB_HEIGHT = 44.dp
private val TAB_CORNER = 8.dp
private val SPEAKER_ICON_SIZE = 22.dp

/** The note under the tabs is always this many lines high, so the list below never moves. */
private const val NOTE_LINES = 3

/**
 * The bottom button takes two lines when it offers a download, so it always keeps room for two: the list above it then keeps
 * its size whichever quality and voice are chosen.
 */
private const val BUTTON_LINES = 2

private val pickerSaver =
    Saver<VoicePicker, List<String>>(
        save = { listOf(it.quality.name, it.voice?.id.orEmpty()) },
        restore = { saved ->
            VoicePicker(VoiceQuality.valueOf(saved[0]), saved[1].takeIf { it.isNotEmpty() }?.let(::voiceById))
        },
    )

/**
 * The screen the Bake button opens: three quality tabs (dotted outline when the model is not downloaded, solid when it
 * is), a note, the 28 voices to listen to, and a button to download if needed and bake. Switching tabs keeps the
 * voice, stops the sample, and the list keeps its scroll position.
 */
@Composable
fun VoiceScreen(
    session: ReaderSession,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var picker by rememberSaveable(stateSaver = pickerSaver) { mutableStateOf(VoicePicker(defaultQuality(session::isModelDownloaded))) }
    var playing: String? by remember { mutableStateOf(null) }
    val samples = remember { SamplePlayer(context) { playing = it } }
    DisposableEffect(Unit) { onDispose { samples.stop() } }
    val listState = rememberLazyListState() // one list for all tabs, so its position is kept when the tab changes

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
        Text("Select model quality", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            for (quality in VoiceQuality.entries) {
                QualityTab(
                    quality,
                    selected = quality == picker.quality,
                    downloaded = session.isModelDownloaded(quality),
                    onClick = {
                        if (quality != picker.quality) samples.stop()
                        picker = picker.withQuality(quality)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(picker.note, minLines = NOTE_LINES, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.ExtraSmall))
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .outline(dotted = false)
                    .padding(OutlinedBoxPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall),
        ) {
            items(KOKORO_VOICES, key = { it.id }) { voice ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (picker.voice == voice) Modifier.outline(dotted = false) else Modifier)
                        .clickable {
                            picker = picker.withVoice(voice)
                            samples.play(voice, picker.quality)
                        }.padding(Spacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(voice.label, modifier = Modifier.weight(1f))
                    if (playing == voice.id) SpeakerIcon()
                }
            }
        }
        val action = picker.action(session.isModelDownloaded(picker.quality), session.downloadSizeMegabytes(picker.quality))
        Button(
            onClick = {
                samples.stop()
                picker.choice?.let(session::chooseVoice)
            },
            enabled = action.enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(contentAlignment = Alignment.Center) {
                // An empty text two lines high keeps the room; the label is centred in it.
                Text("", minLines = BUTTON_LINES, modifier = Modifier.clearAndSetSemantics {})
                Text(action.text, textAlign = TextAlign.Center)
            }
        }
    }
}

/** One quality tab: filled blue when [selected], grey otherwise; its outline is dotted until the model is [downloaded]. */
@Composable
private fun QualityTab(
    quality: VoiceQuality,
    selected: Boolean,
    downloaded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(TAB_HEIGHT)
            .background(if (selected) SelectedTab else UnselectedTab, RoundedCornerShape(TAB_CORNER))
            .outline(dotted = !downloaded, if (selected) SelectedTabOutline else UnselectedTabOutline)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(quality.label, color = if (selected) SelectedTabText else UnselectedTabText) }
}

/** A small speaker with sound waves, drawn so no icon library is needed. */
@Composable
private fun SpeakerIcon() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(SPEAKER_ICON_SIZE)) {
        val w = size.width
        val h = size.height
        val body =
            Path().apply {
                moveTo(w * 0.05f, h * 0.38f)
                lineTo(w * 0.25f, h * 0.38f)
                lineTo(w * 0.5f, h * 0.15f)
                lineTo(w * 0.5f, h * 0.85f)
                lineTo(w * 0.25f, h * 0.62f)
                lineTo(w * 0.05f, h * 0.62f)
                close()
            }
        drawPath(body, color)
        val stroke = Stroke(width = w * 0.08f)
        drawArc(color, -45f, 90f, false, Offset(w * 0.45f, h * 0.3f), Size(w * 0.3f, h * 0.4f), style = stroke)
        drawArc(color, -45f, 90f, false, Offset(w * 0.4f, h * 0.1f), Size(w * 0.5f, h * 0.8f), style = stroke)
    }
}
