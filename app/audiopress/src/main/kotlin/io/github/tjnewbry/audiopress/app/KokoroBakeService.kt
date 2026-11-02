package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.bake.BakeFailedException
import io.github.tjnewbry.audiopress.bake.BakeItem
import io.github.tjnewbry.audiopress.bake.BakeOutOfMemoryException
import io.github.tjnewbry.audiopress.bake.BakedBook
import io.github.tjnewbry.audiopress.bake.kokoro.FakeKokoroService
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroDownloadException
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroService
import io.github.tjnewbry.audiopress.bake.kokoro.KokoroUnavailableException
import io.github.tjnewbry.audiopress.bake.kokoro.downloadSizeMegabytes
import io.github.tjnewbry.audiopress.core.CancellationToken
import java.io.File
import io.github.tjnewbry.audiopress.bake.BakeCancelledException as ModuleCancelledException
import io.github.tjnewbry.audiopress.bake.BookBaker as ModuleBookBaker

/**
 * [BakeService] on top of the `bake` module's [KokoroService]: model downloads, and baking with a Kokoro voice. The
 * module is the only place that knows how the files and manifest are laid out; this only translates its types and
 * failures into the app's.
 *
 * The default [service] is [FakeKokoroService] (a quick simulated download, then silent clips of about the right length)
 * until the Kokoro runtime lands in the module; then pass the real one.
 */
class KokoroBakeService(
    private val service: KokoroService = FakeKokoroService(),
) : BakeService {
    override val available: Boolean get() = service.runtimeAvailable

    override fun isModelDownloaded(quality: VoiceQuality) = service.isDownloaded(quality)

    override fun downloadSizeBytes(quality: VoiceQuality) = service.downloadSizeBytes(quality)

    override fun downloadSizeMegabytes(quality: VoiceQuality) = service.downloadSizeMegabytes(quality)

    override fun downloadModel(
        quality: VoiceQuality,
        cancellation: CancellationToken,
        onProgress: (fraction: Float) -> Unit,
    ) {
        try {
            service.download(quality, cancellation) { onProgress(it.fraction) }
        } catch (e: ModuleCancelledException) {
            throw BakeCancelledException()
        } catch (e: KokoroDownloadException) {
            throw BakeProblem(e.message.orEmpty())
        }
    }

    override fun bake(
        utterances: List<Utterance>,
        folder: File,
        voice: VoiceChoice,
        cancellation: CancellationToken,
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        val kokoroVoice = voice.voice
        val synthesizer =
            try {
                service.synthesizer(kokoroVoice, voice.quality)
            } catch (e: KokoroUnavailableException) {
                throw BakeProblem(e.message.orEmpty())
            }
        try {
            val items =
                utterances.map {
                    BakeItem(it.text, segmentIndex = it.paragraph, start = it.start, end = it.start + it.text.length, page = it.page)
                }
            ModuleBookBaker(synthesizer).bake(items, folder, service.settingsKey(kokoroVoice, voice.quality), cancellation) {
                onProgress(it.done, it.total)
            }
        } catch (e: ModuleCancelledException) {
            throw BakeCancelledException()
        } catch (e: BakeOutOfMemoryException) {
            throw BakeProblem("This phone ran out of memory while baking. Try the Lite quality, and close other apps.")
        } catch (e: BakeFailedException) {
            throw BakeProblem("The voice could not read part of this book. Try again, or choose another voice.")
        } finally {
            synthesizer.close()
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
}
