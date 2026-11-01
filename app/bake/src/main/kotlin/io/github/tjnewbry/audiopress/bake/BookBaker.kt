package io.github.tjnewbry.audiopress.bake

import io.github.tjnewbry.audiopress.core.CancellationToken
import io.github.tjnewbry.audiopress.core.Utterance
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** How a [SpeechSynthesizer] call ended. */
enum class SynthesisResult { SUCCESS, FAILED, CANCELLED }

/**
 * Turns text into a WAV file. The Android implementation is [TtsFileSynthesizer]; tests use a fake. [synthesize]
 * blocks until the file is written, and gives up with [SynthesisResult.CANCELLED] soon after [cancellation] is cancelled.
 */
interface SpeechSynthesizer {
    fun synthesize(
        text: String,
        target: File,
        cancellation: CancellationToken?,
    ): SynthesisResult
}

/** [done] of [total] utterances are baked, counting the ones that were already baked by an earlier run. */
data class BakeProgress(
    val done: Int,
    val total: Int,
)

/** The bake was cancelled with its [CancellationToken]. The files baked so far are kept, so the next run resumes. */
class BakeCancelledException : Exception("The bake was cancelled")

/** The voice could not produce audio for utterance [index], even after a retry. Run the bake again to resume. */
class BakeFailedException(
    val index: Int,
    message: String,
) : Exception(message)

/**
 * Turns the engine's utterances (from `SpeechChunker`) into numbered audio files and a manifest in one folder:
 * `00000.wav`, `00001.wav`, and so on, plus `manifest.txt` (read it back with [BakedBook.read]).
 *
 * It is blocking and runs one utterance at a time, so call [bake] off the main thread (a foreground service for a
 * whole book, since it can take a while).
 *
 * **Resumable.** Each file is written as `NNNNN.wav.tmp` and renamed when complete, and a file that is already
 * there and readable is skipped, so a bake that was cancelled or killed carries on where it stopped. The manifest
 * gets a `complete` line only when every utterance is done ([BakedBook.isComplete]). If the utterances or
 * [settingsKey] differ from the earlier run, the old audio is deleted and the bake starts over.
 */
class BookBaker(
    private val synthesizer: SpeechSynthesizer,
) {
    /**
     * Bakes [utterances] into [folder] (created if needed). [settingsKey] must change whenever the voice settings
     * change (voice name, speech rate, pitch), because audio made with other settings must not be reused.
     * [onProgress] is called on the calling thread, first with `0` done and then after every utterance.
     *
     * @throws BakeCancelledException if [cancellation] was cancelled.
     * @throws BakeFailedException if an utterance could not be synthesized.
     * @throws IOException if the folder cannot be written.
     */
    fun bake(
        utterances: List<Utterance>,
        folder: File,
        settingsKey: String = "",
        cancellation: CancellationToken? = null,
        onProgress: (BakeProgress) -> Unit = {},
    ): BakedBook {
        require(utterances.isNotEmpty()) { "there is nothing to bake" }
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("cannot create ${folder.path}")
        val fingerprint = fingerprint(utterances, settingsKey)
        if (BakedBook.read(folder)?.fingerprint != fingerprint) clear(folder)
        folder.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() } // half-written files from a killed run

        val baked = ArrayList<BakedUtterance>(utterances.size)
        writeManifest(folder, fingerprint, utterances.size, baked, complete = false)
        onProgress(BakeProgress(0, utterances.size))
        for ((i, utterance) in utterances.withIndex()) {
            if (cancellation?.isCancelled == true) throw BakeCancelledException()
            val name = fileName(i)
            val duration = existingDuration(File(folder, name)) ?: synthesizeOne(i, utterance, folder, name, cancellation)
            baked +=
                BakedUtterance(i, name, utterance.segmentIndex, utterance.start, utterance.end, utterance.page, duration)
            onProgress(BakeProgress(i + 1, utterances.size))
        }
        writeManifest(folder, fingerprint, utterances.size, baked, complete = true)
        return BakedBook(folder, fingerprint, baked, isComplete = true)
    }

    private fun existingDuration(file: File): Long? = Wav.durationMs(file)?.takeIf { it > 0 }

    /** Synthesizes into a temporary file and renames it, trying twice before giving up. Returns the duration. */
    private fun synthesizeOne(
        index: Int,
        utterance: Utterance,
        folder: File,
        name: String,
        cancellation: CancellationToken?,
    ): Long {
        val target = File(folder, name)
        val temporary = File(folder, "$name.tmp")
        repeat(ATTEMPTS) { attempt ->
            temporary.delete()
            val result = synthesizer.synthesize(utterance.text, temporary, cancellation)
            if (result == SynthesisResult.CANCELLED) {
                temporary.delete()
                throw BakeCancelledException()
            }
            if (result == SynthesisResult.SUCCESS) {
                var duration = Wav.durationMs(temporary)
                if (duration != null && duration <= 0) {
                    // The voice had nothing to say (for example "* * *"): keep the timeline with a moment of silence.
                    Wav.writeSilence(temporary, SILENCE_MS)
                    duration = Wav.durationMs(temporary)
                }
                if (duration != null && duration > 0) {
                    target.delete()
                    if (!temporary.renameTo(target)) throw IOException("cannot rename ${temporary.path}")
                    return duration
                }
            }
            temporary.delete()
            if (cancellation?.isCancelled == true) throw BakeCancelledException()
            if (attempt == ATTEMPTS - 1) throw BakeFailedException(index, "no audio for utterance $index")
        }
        throw BakeFailedException(index, "no audio for utterance $index")
    }

    private fun writeManifest(
        folder: File,
        fingerprint: String,
        count: Int,
        baked: List<BakedUtterance>,
        complete: Boolean,
    ) {
        val temporary = File(folder, "${BakedBook.MANIFEST_NAME}.tmp")
        temporary.writeText(BakeManifest.format(fingerprint, count, baked, complete), Charsets.UTF_8)
        val manifest = File(folder, BakedBook.MANIFEST_NAME)
        manifest.delete()
        if (!temporary.renameTo(manifest)) throw IOException("cannot write ${manifest.path}")
    }

    /** Deletes the audio, partial files, and manifest of an earlier bake (and nothing else in the folder). */
    private fun clear(folder: File) {
        folder
            .listFiles { f ->
                f.name.endsWith(".wav") || f.name.endsWith(".tmp") || f.name == BakedBook.MANIFEST_NAME
            }?.forEach { it.delete() }
    }

    companion object {
        private const val ATTEMPTS = 2
        private const val SILENCE_MS = 250L

        /** `00000.wav`: five digits keep the files in order in a file listing for up to 100,000 utterances. */
        internal fun fileName(index: Int): String = "%05d.wav".format(index)

        /** A short hash of what the audio is made from, so a changed book or voice is not mixed with old files. */
        internal fun fingerprint(
            utterances: List<Utterance>,
            settingsKey: String,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")

            fun add(text: String) {
                digest.update(text.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            add(settingsKey)
            for (u in utterances) {
                add("${u.segmentIndex}:${u.start}:${u.end}")
                add(u.text)
            }
            return digest.digest().take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }
        }

        private const val FINGERPRINT_BYTES = 12
    }
}
