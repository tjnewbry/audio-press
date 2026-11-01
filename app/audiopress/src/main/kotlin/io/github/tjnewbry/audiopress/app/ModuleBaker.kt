package io.github.tjnewbry.audiopress.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import io.github.tjnewbry.audiopress.bake.BakeItem
import io.github.tjnewbry.audiopress.bake.BakedBook
import io.github.tjnewbry.audiopress.bake.TtsFileSynthesizer
import io.github.tjnewbry.audiopress.core.CancellationToken
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.github.tjnewbry.audiopress.bake.BakeCancelledException as ModuleCancelledException
import io.github.tjnewbry.audiopress.bake.BookBaker as ModuleBookBaker

/**
 * [BookBaker] backed by the `bake` module: a text-to-speech of its own writes each utterance to a WAV file in the
 * book's `bake` folder. The module is the only place that knows how the files and manifest are laid out.
 */
class ModuleBaker(
    context: Context,
) : BookBaker {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override val available = true

    override fun bake(
        utterances: List<Utterance>,
        folder: File,
        cancellation: CancellationToken,
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        val synthesizer = openSynthesizer() ?: throw IllegalStateException("This device has no working text-to-speech engine.")
        try {
            ModuleBookBaker(synthesizer).bake(utterances.map { it.toBakeItem() }, folder, SETTINGS_KEY, cancellation) {
                onProgress(it.done, it.total)
            }
        } catch (e: ModuleCancelledException) {
            throw BakeCancelledException()
        } finally {
            synthesizer.shutdown()
        }
    }

    override fun readManifest(folder: File): BakeManifest? {
        val book = BakedBook.read(folder)
        if (book != null && book.isComplete) {
            return BakeManifest(book.utterances.map { BakedClip(book.file(it), it.durationMs) }, complete = true)
        }
        // The module writes the manifest list when a bake ends, so for an unfinished bake count the clip files instead.
        val files = folder.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()
        return if (files.isEmpty()) null else BakeManifest(files.map { BakedClip(it, 0) }, complete = false)
    }

    /** Starts the baking text-to-speech (on the main thread, as the module wants) and waits for it, or returns null. */
    private fun openSynthesizer(): TtsFileSynthesizer? {
        val ready = CountDownLatch(1)
        var result: TtsFileSynthesizer? = null
        main.post {
            TtsFileSynthesizer.open(context, configure = ::configure) {
                result = it
                ready.countDown()
            }
        }
        return if (ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) result else null
    }

    private fun configure(tts: TextToSpeech) {
        tts.setSpeechRate(RATE)
        if (tts.setLanguage(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.US)
    }

    private fun Utterance.toBakeItem() = BakeItem(text, segmentIndex = paragraph, start = start, end = start + text.length, page = page)

    private companion object {
        const val RATE = 1.0f
        const val START_TIMEOUT_SECONDS = 30L

        /** Names the voice settings, so audio made with other settings is not mixed with old files. */
        val SETTINGS_KEY = "voice=default|lang=${Locale.getDefault().toLanguageTag()}|rate=$RATE"
    }
}
