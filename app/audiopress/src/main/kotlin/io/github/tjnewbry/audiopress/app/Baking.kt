package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.CancellationToken
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** One baked utterance: an audio [file] and its real [durationMs]. Clip number i is utterance number i. */
data class BakedClip(
    val file: File,
    val durationMs: Long,
)

/** What a bake has produced so far: the clips that exist, and whether every utterance is done. */
data class BakeManifest(
    val clips: List<BakedClip>,
    val complete: Boolean,
)

/** A failure with a message that is ready to show the user. */
class BakeProblem(
    message: String,
) : Exception(message)

/** Thrown by [BakeService.bake] and [BakeService.downloadModel] when the token was cancelled. Files already written are kept. */
class BakeCancelledException : Exception("The bake was cancelled")

/**
 * The seam between the app and whatever downloads the voice models and turns text into audio files (the `bake`
 * module). Everything the app needs from it is here, so the player, the screens, and the tests do not depend on
 * how it works.
 */
interface BakeService {
    /** False when this build cannot bake, in which case the app offers no Bake button. */
    val available: Boolean

    /** Whether the model of [quality] is on the phone and ready. Quick: it may be called on the main thread. */
    fun isModelDownloaded(quality: VoiceQuality): Boolean

    /** How many bytes are still to download for [quality]: the whole model when none of it is here, 0 when it is ready. */
    fun downloadSizeBytes(quality: VoiceQuality): Long

    /** The same, in the whole decimal megabytes the voice screen shows (Lite 121, Medium 206, Heavy 354). 0 once downloaded. */
    fun downloadSizeMegabytes(quality: VoiceQuality): Int

    /**
     * Downloads the model of [quality] (and the shared voices file, once), resuming a partial download. Blocks.
     * Reports the bytes done of the bytes to fetch. Throws [BakeCancelledException] if [cancellation] is cancelled (partial files
     * are kept), and [BakeProblem] with a message for the user when it fails (no network, a damaged download, a full disk).
     */
    fun downloadModel(
        quality: VoiceQuality,
        cancellation: CancellationToken,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit,
    )

    /**
     * Synthesizes [utterances] in [voice] into [folder], one clip per utterance in order, skipping clips that are
     * already there (so a cancelled or interrupted bake resumes; a different voice starts over). Blocks. Reports
     * `done` of `total` clips after each one. Throws [BakeCancelledException] if [cancellation] is cancelled;
     * [BakeProblem] carries a message for the user; any other exception is a failure.
     */
    fun bake(
        utterances: List<Utterance>,
        folder: File,
        voice: VoiceChoice,
        cancellation: CancellationToken,
        onProgress: (done: Int, total: Int) -> Unit,
    )

    /** What is baked in [folder] (with real durations), or null when nothing is. */
    fun readManifest(folder: File): BakeManifest?
}

/** Used when this build cannot bake: the app works without it and shows no Bake button. */
object UnavailableBaker : BakeService {
    override val available = false

    override fun isModelDownloaded(quality: VoiceQuality) = false

    override fun downloadSizeBytes(quality: VoiceQuality) = 0L

    override fun downloadSizeMegabytes(quality: VoiceQuality) = 0

    override fun downloadModel(
        quality: VoiceQuality,
        cancellation: CancellationToken,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit,
    ): Unit = throw UnsupportedOperationException("Baking is not available")

    override fun bake(
        utterances: List<Utterance>,
        folder: File,
        voice: VoiceChoice,
        cancellation: CancellationToken,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Unit = throw UnsupportedOperationException("Baking is not available")

    override fun readManifest(folder: File): BakeManifest? = null
}

sealed interface BakeState {
    /** This build cannot bake. */
    data object Unavailable : BakeState

    /**
     * Not baked. [done] of [total] clips exist when an earlier bake was cancelled or interrupted; [choice] is the
     * voice that bake used, so baking can resume with it.
     */
    data class NotBaked(
        val done: Int,
        val total: Int,
        val choice: VoiceChoice? = null,
    ) : BakeState

    /** The model of [quality] is being downloaded: [doneBytes] of [totalBytes] so far. */
    data class Downloading(
        val quality: VoiceQuality,
        val doneBytes: Long,
        val totalBytes: Long,
        val cancelling: Boolean = false,
    ) : BakeState {
        /** The share done, 0 to 1. */
        val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    }

    data class Baking(
        val done: Int,
        val total: Int,
        val cancelling: Boolean = false,
        val voice: VoiceChoice? = null,
        /** About how long is left, in milliseconds, from the rate so far; null before there is a rate. */
        val etaMs: Long? = null,
    ) : BakeState

    data class Failed(
        val message: String,
        val done: Int,
        val total: Int,
    ) : BakeState

    /** Fully baked, using [bytes] of storage. */
    data class Baked(
        val bytes: Long,
    ) : BakeState
}

/** True after Cancel was pressed on a download or bake that has not stopped yet. */
val BakeState.isCancelling: Boolean
    get() = (this as? BakeState.Downloading)?.cancelling ?: (this as? BakeState.Baking)?.cancelling ?: false

/** True while a model is being downloaded or the book is being baked. */
val BakeState.isRunning: Boolean get() = this is BakeState.Downloading || this is BakeState.Baking

/**
 * Bakes one book: downloads the model if needed, then bakes, reports progress, cancels, and knows what is on disk.
 * It outlives the reader screen, so a bake carries on while the book is closed. State changes are reported to
 * [listener] on the thread that made them (the bake thread); [executor] runs the work. The voice of the last bake is
 * kept in [choiceFile] so an interrupted bake can resume with it. Reading [state] and [clips] is safe from any thread.
 */
class BookBakeController(
    private val utterances: List<Utterance>,
    private val folder: File,
    private val baker: BakeService,
    private val executor: Executor,
    private val sizeOf: (File) -> Long = ::folderSize,
    private val freeSpace: (File) -> Long = ::usableSpaceOf,
    private val choiceFile: File? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val wordsBefore: LongArray =
        LongArray(utterances.size + 1).also { sums -> utterances.forEachIndexed { i, u -> sums[i + 1] = sums[i] + u.words.size } }

    @Volatile
    var listener: ((BakeState) -> Unit)? = null

    /** Told of every state change, for the notification of a bake that runs in the background (set by the [BakeCenter]). */
    @Volatile
    var observer: ((BakeState) -> Unit)? = null

    @Volatile
    var state: BakeState = BakeState.Unavailable
        private set

    /** The clips, once the whole book is baked (and they match the book's text); otherwise null. */
    @Volatile
    var clips: List<BakedClip>? = null
        private set

    /** The voice and quality of the last bake started for this book, or null if none. */
    @Volatile
    var savedChoice: VoiceChoice? = readChoice()
        private set

    @Volatile
    private var token: CancellationToken? = null

    /** Counts down when the work of the last [start] has ended; null before the first start. */
    @Volatile
    private var running: CountDownLatch? = null

    init {
        refresh()
    }

    fun isModelDownloaded(quality: VoiceQuality): Boolean = baker.isModelDownloaded(quality)

    /**
     * Clip [index] if it can be played now: any clip once the whole book is baked, and while baking only the clips the
     * baker has reported as done (a clip is a finished file only after it is reported, never a half-written one).
     */
    fun clipAt(index: Int): BakedClip? {
        clips?.let { return it.getOrNull(index) }
        val reported = (state as? BakeState.Baking)?.done ?: return null
        if (index >= reported) return null
        return baker.readManifest(folder)?.clips?.getOrNull(index)
    }

    /**
     * The real lengths, in milliseconds, of the clips that can be played now, in order: all of them once the book is
     * baked, the finished ones while it is being baked, and none otherwise.
     */
    fun bakedDurationsMs(): List<Long> {
        clips?.let { all -> return all.map { it.durationMs } }
        val reported = (state as? BakeState.Baking)?.done ?: return emptyList()
        return baker
            .readManifest(folder)
            ?.clips
            ?.take(reported)
            ?.map { it.durationMs }
            .orEmpty()
    }

    fun downloadSizeBytes(quality: VoiceQuality): Long = baker.downloadSizeBytes(quality)

    fun downloadSizeMegabytes(quality: VoiceQuality): Int = baker.downloadSizeMegabytes(quality)

    /** Starts baking with [choice], or with the voice of the earlier bake when none is given. Does nothing without a voice. */
    fun start(choice: VoiceChoice? = null) {
        val use = choice ?: savedChoice ?: return
        if (!baker.available || state is BakeState.Baking || state is BakeState.Downloading || clips != null) return
        val done = if (use == savedChoice) doneOnDisk() else 0 // another voice starts the book over
        val download = !baker.isModelDownloaded(use.quality)
        val needed = estimatedBytesNeeded(done) + if (download) baker.downloadSizeBytes(use.quality) else 0L
        val free = freeSpace(folder)
        if (free < needed + SPACE_MARGIN_BYTES) {
            publish(BakeState.Failed(notEnoughSpace(needed, free), done, utterances.size))
            return
        }
        writeChoice(use)
        val cancellation = CancellationToken()
        token = cancellation
        publish(
            if (download) {
                BakeState.Downloading(
                    use.quality,
                    0L,
                    baker.downloadSizeBytes(use.quality),
                )
            } else {
                BakeState.Baking(done, utterances.size, voice = use)
            },
        )
        val finished = CountDownLatch(1)
        running = finished
        executor.execute { run(use, download, done, cancellation, finished) }
    }

    /**
     * Cancels a running download or bake and waits until its work has ended (so nothing writes to the folder any more).
     * Returns false if it did not end within [timeoutMs]. Call from a thread other than the one that does the work.
     */
    fun cancelAndWait(timeoutMs: Long = STOP_TIMEOUT_MS): Boolean {
        val finished = running ?: return true
        cancel()
        return finished.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Forgets the saved voice after the bake was deleted from disk, and shows the book as not baked. */
    fun resetAfterDelete() {
        savedChoice = null
        refresh()
    }

    private fun run(
        use: VoiceChoice,
        download: Boolean,
        done: Int,
        cancellation: CancellationToken,
        finished: CountDownLatch,
    ) {
        try {
            work(use, download, done, cancellation)
        } finally {
            finished.countDown()
        }
    }

    private fun work(
        use: VoiceChoice,
        download: Boolean,
        done: Int,
        cancellation: CancellationToken,
    ) {
        try {
            if (download) {
                baker.downloadModel(use.quality, cancellation) { done, total ->
                    publish(BakeState.Downloading(use.quality, done, total, cancellation.isCancelled))
                }
                publish(BakeState.Baking(done, utterances.size, voice = use))
            }
            val startedAt = now()
            baker.bake(utterances, folder, use, cancellation) { n, total ->
                val remaining =
                    estimateRemainingMs(
                        startedAt,
                        now(),
                        wordsBefore[done.coerceAtMost(utterances.size)],
                        wordsBefore[n.coerceAtMost(utterances.size)],
                        wordsBefore.last(),
                    )
                publish(BakeState.Baking(n, total, cancellation.isCancelled, use, remaining))
            }
            refresh()
        } catch (e: BakeCancelledException) {
            refresh()
        } catch (e: Exception) {
            val left = freeSpace(folder)
            val message =
                when {
                    e is BakeProblem -> e.message.orEmpty()
                    left < LOW_SPACE_BYTES -> notEnoughSpace(estimatedBytesNeeded(doneOnDisk()), left)
                    else -> "Baking stopped because of a problem. Try again; it continues where it left off."
                }
            publish(BakeState.Failed(message, doneOnDisk(), utterances.size))
        }
    }

    /** A rough size for the clips still to make: baked speech is about 2.9 MB a minute and 170 words a minute, so about 17 KB a word. */
    private fun estimatedBytesNeeded(done: Int): Long {
        val remaining = utterances.drop(done).sumOf { it.words.size.toLong() }
        return remaining * BYTES_PER_WORD
    }

    private fun notEnoughSpace(
        needed: Long,
        free: Long,
    ) = "Not enough free storage to bake this book. It needs about ${formatSize(needed)} and ${formatSize(free)} is free. " +
        "Free some space and try again."

    fun cancel() {
        val current = state
        token?.cancel()
        when (current) {
            is BakeState.Baking -> publish(current.copy(cancelling = true))
            is BakeState.Downloading -> publish(current.copy(cancelling = true))
            else -> Unit
        }
    }

    /** Reads the disk again and publishes what it shows. */
    fun refresh() {
        if (!baker.available) {
            publish(BakeState.Unavailable)
            return
        }
        val manifest = manifestOnDisk()
        if (manifest != null && manifest.complete && manifest.clips.size == utterances.size) {
            clips = manifest.clips
            publish(BakeState.Baked(sizeOf(folder)))
        } else {
            clips = null
            publish(BakeState.NotBaked(manifest?.clips?.size ?: 0, utterances.size, savedChoice))
        }
    }

    /** What is on disk, if it was baked from a book of this length; otherwise it is treated as not there. */
    private fun manifestOnDisk(): BakeManifest? = baker.readManifest(folder)?.takeIf { it.clips.size <= utterances.size }

    private fun doneOnDisk(): Int = manifestOnDisk()?.clips?.size ?: 0

    private fun readChoice(): VoiceChoice? =
        try {
            val parts =
                choiceFile
                    ?.takeIf { it.isFile }
                    ?.readText(Charsets.UTF_8)
                    ?.trim()
                    ?.split('\t')
            if (parts != null && parts.size == 2) VoiceChoice(parts[0], VoiceQuality.valueOf(parts[1])) else null
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun writeChoice(choice: VoiceChoice) {
        savedChoice = choice
        try {
            choiceFile?.let {
                it.parentFile?.mkdirs()
                it.writeText("${choice.voiceId}\t${choice.quality.name}\n", Charsets.UTF_8)
            }
        } catch (e: IOException) {
            // The choice is only for resuming; a bake that cannot remember its voice still works.
        }
    }

    private fun publish(next: BakeState) {
        state = next
        observer?.invoke(next)
        listener?.invoke(next)
    }
}

/** Free bytes where [folder] is or will be (the nearest folder above it that exists). */
fun usableSpaceOf(folder: File): Long {
    var existing: File? = folder
    while (existing != null && !existing.exists()) existing = existing.parentFile
    return existing?.usableSpace ?: 0L
}

private const val STOP_TIMEOUT_MS = 60_000L
private const val BYTES_PER_WORD = 18_000L
private const val SPACE_MARGIN_BYTES = 50L * 1024 * 1024
private const val LOW_SPACE_BYTES = 20L * 1024 * 1024

/** The total size in bytes of the files under [folder]; 0 when it does not exist. */
fun folderSize(folder: File): Long = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }

/**
 * About how many milliseconds of baking are left, from the speed so far: [wordsDone] minus [wordsAtStart] words in the
 * time from [startMs] to [nowMs], with [wordsTotal] words in all. Null while there is no speed to go by yet.
 */
fun estimateRemainingMs(
    startMs: Long,
    nowMs: Long,
    wordsAtStart: Long,
    wordsDone: Long,
    wordsTotal: Long,
): Long? {
    val doneSince = wordsDone - wordsAtStart
    val elapsed = nowMs - startMs
    if (doneSince <= 0 || elapsed <= 0) return null
    return ((wordsTotal - wordsDone).coerceAtLeast(0) * elapsed.toDouble() / doneSince).toLong()
}

/** The clips of a book as its [controller] has them right now, for playing while the bake is still going on. */
class BakeClipSource(
    private val controller: BookBakeController,
    override val count: Int,
) : ClipSource {
    override fun clip(index: Int): BakedClip? = controller.clipAt(index)
}
