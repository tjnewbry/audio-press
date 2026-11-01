package io.github.tjnewbry.audiopress.bake.kokoro

import io.github.tjnewbry.audiopress.bake.BakeCancelledException
import io.github.tjnewbry.audiopress.bake.SynthesisResult
import io.github.tjnewbry.audiopress.bake.Wav
import io.github.tjnewbry.audiopress.core.CancellationToken
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A stand-in for [KokoroService] for building screens and for tests: downloads take a moment and are remembered in
 * memory, and the synthesizer writes silence as long as the text would take to read. It needs no network or model.
 *
 * [failNextDownloadWith] makes the next download fail once, to try the error messages.
 */
class FakeKokoroService(
    /** Real time one fake download takes, in milliseconds, spread over [steps] progress reports. */
    private val downloadMillis: Long = 1500,
    private val steps: Int = 20,
    downloaded: Set<KokoroQuality> = emptySet(),
    override val runtimeAvailable: Boolean = true,
) : KokoroService {
    private val done = ConcurrentHashMap.newKeySet<KokoroQuality>().apply { addAll(downloaded) }

    @Volatile
    var failNextDownloadWith: DownloadFailure? = null

    override fun isDownloaded(quality: KokoroQuality) = quality in done

    override fun downloadSizeBytes(quality: KokoroQuality): Long = if (isDownloaded(quality)) 0 else APPROXIMATE_BYTES.getValue(quality)

    override fun download(
        quality: KokoroQuality,
        cancellation: CancellationToken?,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val total = APPROXIMATE_BYTES.getValue(quality)
        onProgress(DownloadProgress(0, total))
        for (step in 1..steps) {
            if (cancellation?.isCancelled == true) throw BakeCancelledException()
            Thread.sleep(downloadMillis / steps)
            failNextDownloadWith?.let { reason ->
                failNextDownloadWith = null
                throw KokoroDownloadException(reason, "Fake download failure: $reason")
            }
            onProgress(DownloadProgress(total * step / steps, total))
        }
        done += quality
    }

    override fun synthesizer(
        voice: KokoroVoice,
        quality: KokoroQuality,
        speed: Float,
    ): KokoroSynthesizer {
        if (!runtimeAvailable) throw KokoroUnavailableException("Kokoro voices are not available in this build.")
        if (!isDownloaded(quality)) throw KokoroUnavailableException("The ${quality.displayName} voice files are not downloaded.")
        return object : KokoroSynthesizer {
            override fun synthesize(
                text: String,
                target: File,
                cancellation: CancellationToken?,
            ): SynthesisResult {
                if (cancellation?.isCancelled == true) return SynthesisResult.CANCELLED
                // About 15 characters a second, like speech at a normal pace.
                Wav.writeSilence(target, maxOf(300L, (text.length * 1000.0 / 15 / speed.coerceAtLeast(0.1f)).toLong()))
                return SynthesisResult.SUCCESS
            }

            override fun close() = Unit
        }
    }

    private companion object {
        /** The sizes the button shows: about 120, 205, and 355 MB with the voices file. */
        val APPROXIMATE_BYTES =
            mapOf(
                KokoroQuality.LITE to 120L * 1024 * 1024,
                KokoroQuality.MEDIUM to 205L * 1024 * 1024,
                KokoroQuality.HEAVY to 355L * 1024 * 1024,
            )
    }
}
