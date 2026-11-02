package io.github.tjnewbry.audiopress.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
                onChange = {
                    noteWordChange(it)
                    if (mode == PlaybackMode.LIVE) player = it
                },
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
            val durations = bake.bakedDurationsMs() // read here, on the bake's own thread, not on the main one
            main.post {
                bakeState = state
                bakedDurations = durations
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

    /** The real lengths of the clips that can be played (all of them, or those made so far); read by the screen, so it follows a bake. */
    private var bakedDurations: List<Long> by mutableStateOf(bake.bakedDurationsMs())
    private var cachedTimeline: BookTimeline? = null
    private var cachedFor: Pair<List<Long>, Double>? = null

    /**
     * The time scale of the whole book: real lengths for what is baked, estimates for the rest. The seeker bar, the buffer
     * and the scroll arrow all use it. It is rebuilt when more clips are baked or the speaking-rate estimate changes.
     */
    fun timeline(): BookTimeline {
        val key = bakedDurations to estimator.secondsPerWord
        cachedTimeline?.let { if (cachedFor?.first === key.first && cachedFor?.second == key.second) return it }
        return BookTimeline(utterances.map { it.words.size }, key.first, key.second).also {
            cachedTimeline = it
            cachedFor = key
        }
    }

    /** Time before the current point, in milliseconds: the clip's real position in baked mode, else the estimate for the current word. */
    fun elapsedMs(): Long {
        val timeline = timeline()
        bakedPlayer?.takeIf { mode == PlaybackMode.BAKED }?.let { return timeline.startMs(player.utterance) + it.intoClipMs }
        val utterance = utterances.getOrNull(player.utterance) ?: return 0L
        val wordsBefore = utterance.words.count { it.first < player.wordStart }
        // The speech engine reports each word as it starts, so between reports the position advances at the estimated speed.
        val since = if (player.status == PlaybackStatus.PLAYING) SystemClock.uptimeMillis() - wordChangedAt else 0L
        return timeline.elapsedMs(player.utterance, wordsBefore, since, rangesReported)
    }

    private var wordChangedAt = 0L
    private var rangesReported = false
    private var lastWord: Pair<Int, Int>? = null

    /** Notes when the live player moved to a new word (and whether the engine reports words at all), for the smooth position. */
    private fun noteWordChange(state: PlayerState) {
        val word = state.utterance to state.wordStart
        val previous = lastWord
        if (word == previous) return
        rangesReported = previous != null && previous.first == state.utterance && state.status == PlaybackStatus.PLAYING
        lastWord = word
        wordChangedAt = SystemClock.uptimeMillis()
    }

    fun totalMs(): Long = timeline().totalMs

    /** Moves playback to [share] (0 to 1) of the book's time; keeps playing if it was playing, else waits there. */
    fun seekTo(share: Double) {
        val timeline = timeline()
        val point = timeline.lineAt((share.coerceIn(0.0, 1.0) * timeline.totalMs).toLong())
        val line = utterances.getOrNull(point.index) ?: return
        switcher?.seekTo(Position(line.paragraph, line.start), point.intoMs, player.status == PlaybackStatus.PLAYING)
    }

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
}
