package io.github.tjnewbry.audiopress.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

sealed interface SpeechState {
    data object Starting : SpeechState

    data object Ready : SpeechState

    data class Unavailable(
        val message: String,
    ) : SpeechState
}

/** The utterances of a book, cut to what this device's speech engine accepts. Live reading and baking use the same list. */
fun speechUtterances(paragraphs: List<StoredParagraph>): List<Utterance> =
    splitIntoUtterances(paragraphs.map { it.text }, minOf(TextToSpeech.getMaxSpeechInputLength() - 100, MAX_UTTERANCE_CHARS))
        .map { it.copy(page = paragraphs[it.paragraph].page) }

/**
 * An open book: its [paragraphs], the player (live speech, or baked audio once the book is baked), the current
 * position, the time shown, and the bake. Create it on the main thread and call [close] when the book is closed,
 * which pauses (and so saves the position) and releases the speech engine and the audio player. [saveProgress] is
 * called on a background thread. [bake] belongs to the library, so a bake carries on after the book is closed.
 */
class ReaderSession(
    context: Context,
    val book: Book,
    val paragraphs: List<StoredParagraph>,
    private val utterances: List<Utterance>,
    initialPosition: Position?,
    private val saveProgress: (Position) -> Unit,
    val bake: BookBakeController,
) {
    private val estimator = TimeEstimator(utterances.map { it.words.size })
    private val saver = Executors.newSingleThreadExecutor { Thread(it, "save-progress") }
    private val main = Handler(Looper.getMainLooper())
    private val clipPlayer = MediaClipPlayer()

    var speech: SpeechState by mutableStateOf(SpeechState.Starting)
        private set
    var player: PlayerState by mutableStateOf(PlayerState())
        private set
    var mode: PlaybackMode by mutableStateOf(PlaybackMode.LIVE)
        private set
    var bakeState: BakeState by mutableStateOf(bake.state)
        private set

    private var livePlayer: ReaderPlayer? = null
    private var bakedPlayer: BakedPlayer? = null
    private var switcher: SwitchingPlayback? = null
    private val speaker =
        TtsSpeaker(
            context,
            onUtteranceStart = { livePlayer?.onUtteranceStart(it) },
            onRange = { id, start, end -> livePlayer?.onRange(id, start, end) },
            onUtteranceDone = { livePlayer?.onUtteranceDone(it) },
            onUtteranceError = { livePlayer?.onUtteranceError(it) },
            onReady = { problem -> speech = if (problem == null) SpeechState.Ready else SpeechState.Unavailable(problem) },
        )

    init {
        val live =
            ReaderPlayer(
                utterances,
                speaker,
                initialPosition,
                onMeasured = estimator::record,
                onSave = ::save,
                onChange = { if (mode == PlaybackMode.LIVE) player = it },
            )
        livePlayer = live
        bakedPlayer = if (bake.clips != null) createBakedPlayer(initialPosition) else null
        // Baked audio can be played once the book is baked, or while it is being baked (the clips made so far).
        val switching =
            SwitchingPlayback(live, { bakedPlayer.takeIf { bake.clips != null || bakeState is BakeState.Baking } }, onModeChange = {
                mode =
                    it
            })
        switcher = switching
        mode = switching.mode
        player = switching.state
        bake.listener = { state ->
            main.post {
                bakeState = state
                if (state !is BakeState.Baking && state !is BakeState.Downloading) showProgress = false
                if (bakedPlayer == null && state is BakeState.Baked) bakedPlayer = createBakedPlayer(switching.position)
                bakedPlayer?.onClipsReady() // a clip may have landed that playback is waiting for
                if (state is BakeState.NotBaked || state is BakeState.Failed) bakedPlayer?.stopWaiting()
            }
        }
        bakeState = bake.state
    }

    /** The lines of the reader: one per utterance, which is a whole paragraph unless it was very long. */
    val lines: List<Utterance> get() = utterances

    /** The line being read, or the one where reading resumes. */
    val currentLine: Int get() = player.utterance

    /** The current word as a range of characters in the current line. Null in baked mode, which has no word timing. */
    val currentWord: IntRange?
        get() {
            if (mode == PlaybackMode.BAKED || player.status == PlaybackStatus.STOPPED || player.wordEnd <= player.wordStart) return null
            return player.wordStart until player.wordEnd
        }

    /** Time before the current point: real in baked mode, estimated in live mode (from word counts, refined as it plays). */
    fun elapsedSeconds(): Double {
        bakedPlayer?.takeIf { mode == PlaybackMode.BAKED }?.let { return it.elapsedMs / MS_PER_SECOND }
        val utterance = utterances.getOrNull(player.utterance) ?: return 0.0
        val wordsBefore = utterance.words.count { it.first < player.wordStart }
        return estimator.elapsedSeconds(player.utterance, wordsBefore)
    }

    fun totalSeconds(): Double =
        bakedPlayer?.takeIf { mode == PlaybackMode.BAKED }?.let { it.totalMs / MS_PER_SECOND } ?: estimator.totalSeconds()

    /** True while the book is read from baked clips that are still being made. */
    val listeningWhileBaking: Boolean get() = mode == PlaybackMode.BAKED && bake.clips == null

    /** Opens the reader on this book and plays the clips as they are baked, from where reading is; the bake carries on. */
    fun listenWhileBaking() {
        showProgress = false
        if (bakedPlayer == null) bakedPlayer = createBakedPlayer(switcher?.position)
        preferLive = false
        switcher?.preferLive = false
        switcher?.let { it.jumpTo(it.position) }
    }

    fun togglePlayback() {
        switcher?.toggle()
    }

    /** Starts reading from the beginning of line [index]. */
    fun jumpTo(index: Int) {
        val line = utterances.getOrNull(index) ?: return
        switcher?.jumpTo(Position(line.paragraph, line.start))
    }

    var preferLive: Boolean by mutableStateOf(false)
        private set

    /** Chooses live speech or baked audio for the next time playback starts. Pauses what is playing, so the change is clean. */
    fun choosePlayback(live: Boolean) {
        switcher?.pause()
        preferLive = live
        switcher?.preferLive = live
    }

    /** Pauses (and so saves the position); used when the app goes to the background. */
    fun pausePlayback() {
        switcher?.pause()
    }

    /** True while the voice screen is open (it replaces the reader). */
    var showVoices: Boolean by mutableStateOf(false)
        private set

    /** True while the full-screen download and bake progress is showing. */
    var showProgress: Boolean by mutableStateOf(false)
        private set

    /** Bake was tapped: resume an interrupted bake with its voice, or open the voice screen to choose one. */
    fun openVoiceScreen() {
        val state = bakeState
        val interrupted = (state is BakeState.NotBaked && state.done > 0) || state is BakeState.Failed
        if (interrupted && bake.savedChoice != null) {
            showProgress = true
            bake.start()
        } else {
            showVoices = true
        }
    }

    /** A voice was chosen on the voice screen: show the progress and start. */
    fun chooseVoice(choice: VoiceChoice) {
        showVoices = false
        showProgress = true
        bake.start(choice)
    }

    /** Leaves the voice screen or the progress screen (a bake carries on). Returns false when neither was showing. */
    fun back(): Boolean {
        if (!showVoices && !showProgress) return false
        showVoices = false
        showProgress = false
        return true
    }

    fun isModelDownloaded(quality: VoiceQuality): Boolean = bake.isModelDownloaded(quality)

    fun downloadSizeMegabytes(quality: VoiceQuality): Int = bake.downloadSizeMegabytes(quality)

    fun cancelBake() = bake.cancel()

    fun close() {
        bake.listener = null
        switcher?.pause()
        speaker.shutdown()
        clipPlayer.pause() // releases the media player
        saver.shutdown() // lets the last save finish
    }

    private fun createBakedPlayer(start: Position?): BakedPlayer? {
        if (utterances.isEmpty()) return null
        return BakedPlayer(
            utterances,
            BakeClipSource(bake, utterances.size),
            clipPlayer,
            start,
            onSave = ::save,
            onChange = { if (mode == PlaybackMode.BAKED) player = it },
        )
    }

    private fun save(position: Position) {
        if (!saver.isShutdown) saver.execute { runCatching { saveProgress(position) } }
    }

    private companion object {
        const val MS_PER_SECOND = 1000.0
    }
}
