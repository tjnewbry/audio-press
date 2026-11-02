package io.github.tjnewbry.audiopress.app

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroVoice
import java.io.IOException

/** Plays the short sample of a voice from the app's assets, one at a time. Use on the main thread. */
class SamplePlayer(
    private val context: Context,
    private val onPlayingChanged: (voiceId: String?) -> Unit,
) {
    private var player: MediaPlayer? = null

    fun play(
        voice: KokoroVoice,
        quality: VoiceQuality,
    ) {
        stop()
        try {
            val file = context.assets.openFd(voice.sampleAsset(quality))
            val next = MediaPlayer()
            player = next
            next.setDataSource(file.fileDescriptor, file.startOffset, file.length)
            file.close()
            next.setOnPreparedListener { if (player === it) it.start() }
            next.setOnCompletionListener { if (player === it) stop() }
            next.setOnErrorListener { failed, _, _ ->
                if (player === failed) stop()
                true
            }
            next.prepareAsync()
            onPlayingChanged(voice.id)
        } catch (e: IOException) {
            Log.w(TAG, "Cannot play the sample of ${voice.id}", e)
            stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot play the sample of ${voice.id}", e)
            stop()
        }
    }

    fun stop() {
        player?.let {
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            it.release()
        }
        player = null
        onPlayingChanged(null)
    }

    private companion object {
        const val TAG = "AudioPressVoice"
    }
}

private val pickerSaver =
    Saver<VoicePicker, List<String>>(
        save = { listOf(it.quality.name, it.voice?.id.orEmpty()) },
        restore = { saved ->
            VoicePicker(VoiceQuality.valueOf(saved[0]), saved[1].takeIf { it.isNotEmpty() }?.let(::voiceById))
        },
    )

// Unselected tabs are pure greys (zero saturation); the selected tab is a saturated blue with white text.
private val SelectedTab = Color(0xFF1565C0)
private val SelectedOutline = Color(0xFF0D47A1)
private val UnselectedTab = Color(0xFFE0E0E0)
private val UnselectedOutline = Color(0xFF616161)
private val OutlineColor = Color(0xFF455A64)

/** A rounded outline that is dotted ([dotted]) or solid. */
private fun Modifier.outline(
    dotted: Boolean,
    color: Color = OutlineColor,
): Modifier =
    drawBehind {
        val width = 2.dp.toPx()
        val effect = if (dotted) PathEffect.dashPathEffect(floatArrayOf(width * 2, width * 2)) else null
        drawRoundRect(
            color,
            topLeft = Offset(width / 2, width / 2),
            size = Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius(8.dp.toPx()),
            style = Stroke(width, pathEffect = effect),
        )
    }

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
    var picker by rememberSaveable(stateSaver = pickerSaver) { mutableStateOf(VoicePicker(VoiceQuality.MEDIUM)) }
    var playing: String? by remember { mutableStateOf(null) }
    val samples = remember { SamplePlayer(context) { playing = it } }
    DisposableEffect(Unit) { onDispose { samples.stop() } }
    val listState = rememberLazyListState() // one list for all tabs, so its position is kept when the tab changes

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Select model quality", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (quality in VoiceQuality.entries) {
                val selected = quality == picker.quality
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(if (selected) SelectedTab else UnselectedTab, RoundedCornerShape(8.dp))
                        .outline(!session.isModelDownloaded(quality), if (selected) SelectedOutline else UnselectedOutline)
                        .clickable {
                            if (!selected) samples.stop()
                            picker = picker.withQuality(quality)
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(quality.label, color = if (selected) Color.White else Color(0xFF212121)) }
            }
        }
        Text(picker.note, minLines = 3, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .outline(dotted = false)
                    .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(KOKORO_VOICES, key = { it.id }) { voice ->
                val selected = picker.voice == voice
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (selected) Modifier.outline(dotted = false) else Modifier)
                        .clickable {
                            picker = picker.withVoice(voice)
                            samples.play(voice, picker.quality)
                        }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(voice.label, modifier = Modifier.weight(1f))
                    if (playing == voice.id) SpeakerIcon()
                }
            }
        }
        val downloaded = session.isModelDownloaded(picker.quality)
        val action = picker.action(downloaded, session.downloadSizeMegabytes(picker.quality))
        Button(
            onClick = {
                samples.stop()
                picker.choice?.let(session::chooseVoice)
            },
            enabled = action.enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(action.text, textAlign = TextAlign.Center) }
    }
}

/** A small speaker with sound waves, drawn so no icon library is needed. */
@Composable
private fun SpeakerIcon() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
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
