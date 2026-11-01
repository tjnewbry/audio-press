package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.core.CancellationToken
import java.io.File
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

/** Thrown by [BookBaker.bake] when the cancellation token was cancelled. Clips already written are kept. */
class BakeCancelledException : Exception("The bake was cancelled")

/**
 * The seam between the app and whatever turns text into audio files (the `bake` module). Everything the app
 * needs from it is here, so the player, the screens, and the tests do not depend on how it works.
 */
interface BookBaker {
    /** False when this build cannot bake, in which case the app offers no Bake button. */
    val available: Boolean

    /**
     * Synthesizes [utterances] into [folder], one clip per utterance in order, skipping clips that are already there
     * (so a cancelled or interrupted bake resumes). Blocks. Reports `done` of `total` clips after each one.
     * Throws [BakeCancelledException] if [cancellation] is cancelled; any other exception is a failure.
     */
    fun bake(
        utterances: List<Utterance>,
        folder: File,
        cancellation: CancellationToken,
        onProgress: (done: Int, total: Int) -> Unit,
    )

    /** What is baked in [folder] (with real durations), or null when nothing is. */
    fun readManifest(folder: File): BakeManifest?
}

/** Used while there is no baking module: the app works without it and shows no Bake button. */
object UnavailableBaker : BookBaker {
    override val available = false

    override fun bake(
        utterances: List<Utterance>,
        folder: File,
        cancellation: CancellationToken,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Unit = throw UnsupportedOperationException("Baking is not available")

    override fun readManifest(folder: File): BakeManifest? = null
}

sealed interface BakeState {
    /** This build cannot bake. */
    data object Unavailable : BakeState

    /** Not baked. [done] of [total] clips exist when an earlier bake was cancelled or interrupted; baking resumes there. */
    data class NotBaked(
        val done: Int,
        val total: Int,
    ) : BakeState

    data class Baking(
        val done: Int,
        val total: Int,
        val cancelling: Boolean = false,
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
 * Bakes one book: starts, reports progress, cancels, and knows what is on disk. It outlives the reader screen, so
 * a bake carries on while the book is closed. State changes are reported to [listener] on the thread that made
 * them (the bake thread); [executor] runs the bake. Reading [state] and [clips] is safe from any thread.
 */
class BookBakeController(
    private val utterances: List<Utterance>,
    private val folder: File,
    private val baker: BookBaker,
    private val executor: Executor,
    private val sizeOf: (File) -> Long = ::folderSize,
    private val freeSpace: (File) -> Long = ::usableSpaceOf,
) {
    @Volatile
    var listener: ((BakeState) -> Unit)? = null

    @Volatile
    var state: BakeState = BakeState.Unavailable
        private set

    /** The clips, once the whole book is baked (and they match the book's text); otherwise null. */
    @Volatile
    var clips: List<BakedClip>? = null
        private set

    @Volatile
    private var token: CancellationToken? = null

    init {
        refresh()
    }

    fun start() {
        if (!baker.available || state is BakeState.Baking || clips != null) return
        val done = doneOnDisk()
        val needed = estimatedBytesNeeded(done)
        val free = freeSpace(folder)
        if (free < needed + SPACE_MARGIN_BYTES) {
            publish(BakeState.Failed(notEnoughSpace(needed, free), done, utterances.size))
            return
        }
        val cancellation = CancellationToken()
        token = cancellation
        publish(BakeState.Baking(done, utterances.size))
        executor.execute {
            try {
                baker.bake(utterances, folder, cancellation) { n, total -> publish(BakeState.Baking(n, total, cancellation.isCancelled)) }
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
    }

    /** A rough size for the clips still to make: baked speech is about 2.9 MB a minute and a minute is about 170 words, so about 17 KB a word; this allows a little more. */
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
        if (current is BakeState.Baking) publish(current.copy(cancelling = true))
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
            publish(BakeState.NotBaked(manifest?.clips?.size ?: 0, utterances.size))
        }
    }

    /** What is on disk, if it was baked from a book of this length; otherwise it is treated as not there. */
    private fun manifestOnDisk(): BakeManifest? = baker.readManifest(folder)?.takeIf { it.clips.size <= utterances.size }

    private fun doneOnDisk(): Int = manifestOnDisk()?.clips?.size ?: 0

    private fun publish(next: BakeState) {
        state = next
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
