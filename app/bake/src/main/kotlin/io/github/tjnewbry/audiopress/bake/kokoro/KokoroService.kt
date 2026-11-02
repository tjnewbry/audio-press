package io.github.tjnewbry.audiopress.bake.kokoro

import io.github.tjnewbry.audiopress.bake.SpeechSynthesizer
import io.github.tjnewbry.audiopress.core.CancellationToken
import java.util.Locale

/** The Kokoro runtime is not part of this build yet, or the model is not downloaded. */
class KokoroUnavailableException(
    message: String,
) : Exception(message)

/**
 * What the app uses for Kokoro voices: which qualities are downloaded, downloading them, and a synthesizer for
 * `BookBaker`. The real one is [DefaultKokoroService]; [FakeKokoroService] stands in for UI work and tests.
 *
 * ```
 * if (!service.isDownloaded(quality)) service.download(quality, token) { p -> bar.value = p.fraction }
 * val key = service.settingsKey(voice, quality)
 * BookBaker(service.synthesizer(voice, quality)).bake(items, folder, settingsKey = key, cancellation = token) { ... }
 * ```
 */
interface KokoroService {
    /** False when this build cannot synthesize Kokoro speech at all (the runtime is missing). */
    val runtimeAvailable: Boolean

    /** Quick, on any thread: the files for [quality] are fully downloaded (no hashing). */
    fun isDownloaded(quality: KokoroQuality): Boolean

    /** The bytes still to download for [quality], for the "Download lite (120mb)" button. Zero when downloaded. */
    fun downloadSizeBytes(quality: KokoroQuality): Long

    /**
     * Downloads [quality] and the shared voices file, blocking. A download that stopped is resumed. Partial files stay
     * when it fails or is cancelled.
     *
     * @throws io.github.tjnewbry.audiopress.bake.BakeCancelledException if [cancellation] was cancelled.
     * @throws KokoroDownloadException with a user-ready message.
     */
    fun download(
        quality: KokoroQuality,
        cancellation: CancellationToken? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    )

    /**
     * A synthesizer for `BookBaker` that speaks with [voice] at [quality]. It holds the model in memory until
     * [KokoroSynthesizer.close], so close it when the bake is over.
     *
     * @throws KokoroUnavailableException if the runtime is missing or [quality] is not downloaded.
     */
    fun synthesizer(
        voice: KokoroVoice,
        quality: KokoroQuality,
        speed: Float = 1f,
    ): KokoroSynthesizer

    /** The `settingsKey` to give `BookBaker.bake`: a bake made with another voice, quality, or speed is not reused. */
    fun settingsKey(
        voice: KokoroVoice,
        quality: KokoroQuality,
        speed: Float = 1f,
    ): String = KokoroSettings.key(voice, quality, speed)
}

/** A [SpeechSynthesizer] that holds a loaded model; [close] frees it. */
interface KokoroSynthesizer :
    SpeechSynthesizer,
    AutoCloseable

/** How the bake's settings key is made, so every caller agrees. */
object KokoroSettings {
    /** The model release, part of the key so a model update rebakes. */
    const val MODEL_VERSION = "kokoro-v1.0"

    /** For example `kokoro-v1.0|lite|am_santa|speed=1.0`. */
    fun key(
        voice: KokoroVoice,
        quality: KokoroQuality,
        speed: Float = 1f,
    ): String = "$MODEL_VERSION|${quality.id}|${voice.id}|speed=${String.format(Locale.ROOT, "%.2f", speed)}"
}

/**
 * The real service: files in a [KokoroModelStore], and a synthesizer made by [synthesizerFactory] once an ONNX
 * runtime and phonemizer are chosen. Until then [runtimeAvailable] is false and [synthesizer] throws.
 */
class DefaultKokoroService(
    private val store: KokoroModelStore,
    private val synthesizerFactory: ((KokoroModelFiles, KokoroVoice, Float) -> KokoroSynthesizer)? = null,
) : KokoroService {
    override val runtimeAvailable: Boolean get() = synthesizerFactory != null

    override fun isDownloaded(quality: KokoroQuality) = store.isDownloaded(quality)

    override fun downloadSizeBytes(quality: KokoroQuality) = store.downloadSizeBytes(quality)

    override fun download(
        quality: KokoroQuality,
        cancellation: CancellationToken?,
        onProgress: (DownloadProgress) -> Unit,
    ) = store.download(quality, cancellation, onProgress)

    override fun synthesizer(
        voice: KokoroVoice,
        quality: KokoroQuality,
        speed: Float,
    ): KokoroSynthesizer {
        val factory = synthesizerFactory ?: throw KokoroUnavailableException("Kokoro voices are not available in this build.")
        val model = store.modelFile(quality)
        val voices = store.voicesFile()
        if (model == null || voices == null) throw KokoroUnavailableException("The ${quality.displayName} voice files are not downloaded.")
        return factory(KokoroModelFiles(model, voices, quality), voice, speed)
    }
}

/** The downloaded files a Kokoro runtime loads. */
data class KokoroModelFiles(
    val model: java.io.File,
    val voices: java.io.File,
    val quality: KokoroQuality,
)

/** The bytes still to download for [quality] as the whole decimal megabytes the button shows (121, 206, 354 at first). */
fun KokoroService.downloadSizeMegabytes(quality: KokoroQuality): Int = downloadSizeBytes(quality).toDecimalMegabytes()

/**
 * The service the app uses: model files kept in [root] (give it `File(context.noBackupFilesDir, "kokoro")`),
 * downloaded from [GithubKokoroCatalog], and run with ONNX Runtime on [threads] threads (2 is the fastest on the
 * phone tested).
 */
fun kokoroService(
    root: java.io.File,
    threads: Int = OnnxKokoroEngine.DEFAULT_THREADS,
): KokoroService {
    val store = KokoroModelStore(root, GithubKokoroCatalog)
    return DefaultKokoroService(store) { files, voice, speed ->
        val frontEnd =
            io.github.tjnewbry.audiopress.bake.kokoro.text
                .KokoroFrontEnd(store)
        KokoroSpeechSynthesizer(OnnxKokoroEngine(files, KokoroVocab.default(), frontEnd, threads), voice, speed)
    }
}
