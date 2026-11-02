package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.CancellationToken
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

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
     * Reports the share done, 0 to 1. Throws [BakeCancelledException] if [cancellation] is cancelled (partial files
     * are kept), and [BakeProblem] with a message for the user when it fails (no network, a damaged download, a full disk).
     */
    fun downloadModel(
        quality: VoiceQuality,
        cancellation: CancellationToken,
        onProgress: (fraction: Float) -> Unit,
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
        onProgress: (fraction: Float) -> Unit,
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

    /** The model of [quality] is being downloaded, [fraction] done (0 to 1). */
    data class Downloading(
        val quality: VoiceQuality,
        val fraction: Float,
        val cancelling: Boolean = false,
    ) : BakeState

    data class Baking(
        val done: Int,
        val total: Int,
        val cancelling: Boolean = false,
        val voice: VoiceChoice? = null,
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
) {
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

    init {
        refresh()
    }

    fun isModelDownloaded(quality: VoiceQuality): Boolean = baker.isModelDownloaded(quality)

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
        publish(if (download) BakeState.Downloading(use.quality, 0f) else BakeState.Baking(done, utterances.size, voice = use))
        executor.execute { run(use, download, done, cancellation) }
    }

    private fun run(
        use: VoiceChoice,
        download: Boolean,
        done: Int,
        cancellation: CancellationToken,
    ) {
        try {
            if (download) {
                baker.downloadModel(use.quality, cancellation) { publish(BakeState.Downloading(use.quality, it, cancellation.isCancelled)) }
                publish(BakeState.Baking(done, utterances.size, voice = use))
            }
            baker.bake(utterances, folder, use, cancellation) { n, total ->
                publish(BakeState.Baking(n, total, cancellation.isCancelled, use))
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

private const val BYTES_PER_WORD = 18_000L
private const val SPACE_MARGIN_BYTES = 50L * 1024 * 1024
private const val LOW_SPACE_BYTES = 20L * 1024 * 1024

/** The total size in bytes of the files under [folder]; 0 when it does not exist. */
fun folderSize(folder: File): Long = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }

/** `85 MB`, `1.2 GB`, `640 KB`: sizes for people, in units of 1024. */
fun formatSize(bytes: Long): String {
    val kb = 1024.0
    return when {
        bytes >= kb * kb * kb -> "%.1f GB".format(bytes / (kb * kb * kb))
        bytes >= kb * kb -> "%.0f MB".format(bytes / (kb * kb))
        bytes >= kb -> "%.0f KB".format(bytes / kb)
        else -> "$bytes bytes"
    }
}
