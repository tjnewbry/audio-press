package io.github.tjnewbry.audiopress.bake.kokoro

import io.github.tjnewbry.audiopress.bake.BakeCancelledException
import io.github.tjnewbry.audiopress.core.CancellationToken
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/** One file to download: where it comes from, its exact size, and its SHA-256 (lower-case hex). */
data class KokoroFileSpec(
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** Where each model and the shared voices file come from. The real values are fixed once the sources are chosen. */
interface KokoroCatalog {
    /** The quality's model file. */
    fun model(quality: KokoroQuality): KokoroFileSpec

    /** `voices-v1.0.bin`, shared by every quality. */
    val voices: KokoroFileSpec

    /**
     * The files the English front end needs, shared by every quality: misaki's pronunciation dictionaries and the
     * small model for unknown words. They download with the model, so a voice always has what it needs to read text.
     */
    val frontEnd: List<KokoroFileSpec> get() = emptyList()
}

/** Why a download failed. Each has a message fit to show the user. */
enum class DownloadFailure { NO_NETWORK, NETWORK, CHECKSUM, DISK_FULL }

class KokoroDownloadException(
    val reason: DownloadFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** [doneBytes] of [totalBytes] for everything this download still needs, counting what an earlier try already fetched. */
data class DownloadProgress(
    val doneBytes: Long,
    val totalBytes: Long,
) {
    /** 0 to 1. */
    val fraction: Float get() = if (totalBytes <= 0) 1f else (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

/** An HTTP response body to read from. [status] is 200 for the whole file or 206 for the part asked for. */
class HttpResponse(
    val status: Int,
    val body: InputStream,
) : Closeable {
    override fun close() = body.close()
}

/** The one thing the store needs from the network, so tests can fake it. */
interface HttpSource {
    /** Opens [url], from byte [rangeStart] on if it is above 0 (servers that cannot may answer 200 with the whole file). */
    fun open(
        url: String,
        rangeStart: Long,
    ): HttpResponse
}

/** [HttpSource] over `HttpURLConnection`, which follows the usual https redirects of the download hosts. */
class UrlConnectionHttp(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpSource {
    override fun open(
        url: String,
        rangeStart: Long,
    ): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        if (rangeStart > 0) connection.setRequestProperty("Range", "bytes=$rangeStart-")
        val status = connection.responseCode
        if (status != 200 && status != 206) {
            connection.disconnect()
            throw IOException("HTTP $status")
        }
        return HttpResponse(status, connection.inputStream)
    }
}

/**
 * Keeps the Kokoro model files in [root] (give it `File(context.noBackupFilesDir, "kokoro")`: private to the app and
 * left out of backups) and downloads them.
 *
 * - **Downloaded** means the final file exists with exactly the expected size. A file only gets its final name after
 *   it is complete and its SHA-256 matched, so this check is quick and needs no hashing.
 * - **Partial downloads** are `<name>.part`. The next [download] resumes them with an HTTP range request, or starts
 *   over if the server cannot resume. Cancelling or losing the network keeps the partial file; a checksum mismatch
 *   deletes it, because it cannot be trusted.
 * - **The voices file** is shared: it is fetched once and kept for every quality.
 */
class KokoroModelStore(
    private val root: File,
    private val catalog: KokoroCatalog,
    private val http: HttpSource = UrlConnectionHttp(),
    private val freeSpace: (File) -> Long = { it.usableSpace },
) {
    /** True when the model for [quality] and the voices file are both fully downloaded. */
    fun isDownloaded(quality: KokoroQuality): Boolean = specsFor(quality).all { isComplete(it) }

    /** The bytes still to fetch for [quality]: its files that are not complete, minus what partial files already hold. */
    fun downloadSizeBytes(quality: KokoroQuality): Long =
        specsFor(quality).filterNot { isComplete(it) }.sumOf {
            it.sizeBytes -
                partialBytes(it)
        }

    /** The model file for [quality], or null if it is not downloaded. */
    fun modelFile(quality: KokoroQuality): File? = File(root, catalog.model(quality).fileName).takeIf { isComplete(catalog.model(quality)) }

    /** `voices-v1.0.bin`, or null if it is not downloaded. */
    fun voicesFile(): File? = File(root, catalog.voices.fileName).takeIf { isComplete(catalog.voices) }

    /** The downloaded front-end file called [fileName] (a dictionary or the unknown-word model), or null. */
    fun frontEndFile(fileName: String): File? =
        catalog.frontEnd
            .firstOrNull { it.fileName == fileName }
            ?.takeIf { isComplete(it) }
            ?.let { File(root, it.fileName) }

    /**
     * Downloads what [quality] still needs, blocking until done. Call it off the main thread.
     *
     * @throws BakeCancelledException if [cancellation] was cancelled; partial files are kept to resume.
     * @throws KokoroDownloadException if the network fails, a checksum does not match, or there is not enough storage.
     */
    fun download(
        quality: KokoroQuality,
        cancellation: CancellationToken? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ) {
        if (!root.isDirectory &&
            !root.mkdirs()
        ) {
            throw KokoroDownloadException(DownloadFailure.DISK_FULL, "The voice files cannot be stored on this device.")
        }
        val needed = specsFor(quality).filterNot { isComplete(it) }
        val total = needed.sumOf { it.sizeBytes }
        val remaining = needed.sumOf { it.sizeBytes - partialBytes(it) }
        if (freeSpace(root) < remaining + SPACE_MARGIN_BYTES) {
            throw KokoroDownloadException(
                DownloadFailure.DISK_FULL,
                "There is not enough free storage for the voice files (${remaining.toDecimalMegabytes()} MB needed).",
            )
        }
        var finished = 0L
        onProgress(DownloadProgress(total - remaining, total))
        for (spec in needed) {
            downloadFile(spec, cancellation) { done -> onProgress(DownloadProgress(finished + done, total)) }
            finished += spec.sizeBytes
            onProgress(DownloadProgress(finished, total))
        }
    }

    /** Deletes partial downloads (not the finished files), to free the space they use. */
    fun deletePartialFiles() {
        root.listFiles { f -> f.name.endsWith(PART) }?.forEach { it.delete() }
    }

    private fun specsFor(quality: KokoroQuality): List<KokoroFileSpec> = listOf(catalog.model(quality), catalog.voices) + catalog.frontEnd

    private fun isComplete(spec: KokoroFileSpec): Boolean = File(root, spec.fileName).let { it.isFile && it.length() == spec.sizeBytes }

    private fun partFile(spec: KokoroFileSpec) = File(root, spec.fileName + PART)

    private fun partialBytes(spec: KokoroFileSpec): Long = partFile(spec).takeIf { it.isFile }?.length()?.coerceAtMost(spec.sizeBytes) ?: 0L

    private fun downloadFile(
        spec: KokoroFileSpec,
        cancellation: CancellationToken?,
        onBytes: (Long) -> Unit,
    ) {
        val part = partFile(spec)
        if (part.length() > spec.sizeBytes) part.delete()
        var attempt = 0
        while (true) {
            attempt++
            try {
                fetch(spec, part, cancellation, onBytes)
            } catch (e: BakeCancelledException) {
                throw e
            } catch (e: KokoroDownloadException) {
                throw e
            } catch (e: UnknownHostException) {
                throw KokoroDownloadException(
                    DownloadFailure.NO_NETWORK,
                    "There is no network connection. Connect and try again; the download will continue where it stopped.",
                    e,
                )
            } catch (e: IOException) {
                if (e.message?.contains("HTTP 416") == true && attempt == 1) {
                    part.delete() // The partial file does not fit the server's copy: start over.
                    continue
                }
                throw KokoroDownloadException(
                    DownloadFailure.NETWORK,
                    "The download was interrupted. Check your connection and try again; it will continue where it stopped.",
                    e,
                )
            }
            break
        }
        if (part.length() != spec.sizeBytes) {
            throw KokoroDownloadException(
                DownloadFailure.NETWORK,
                "The download ended early. Try again; it will continue where it stopped.",
            )
        }
        if (!sha256(part, cancellation).equals(spec.sha256, ignoreCase = true)) {
            part.delete()
            throw KokoroDownloadException(DownloadFailure.CHECKSUM, "The downloaded voice file is damaged. Try the download again.")
        }
        val target = File(root, spec.fileName)
        target.delete()
        if (!part.renameTo(
                target,
            )
        ) {
            throw KokoroDownloadException(DownloadFailure.DISK_FULL, "The voice file cannot be saved on this device.")
        }
    }

    private fun fetch(
        spec: KokoroFileSpec,
        part: File,
        cancellation: CancellationToken?,
        onBytes: (Long) -> Unit,
    ) {
        val resumeFrom = part.length()
        if (resumeFrom == spec.sizeBytes) return
        http.open(spec.url, resumeFrom).use { response ->
            val append = response.status == 206 && resumeFrom > 0
            if (!append) part.delete()
            var done = if (append) resumeFrom else 0L
            FileOutputStream(part, append).use { out ->
                val buffer = ByteArray(BUFFER_BYTES)
                var lastReport = 0L
                while (true) {
                    if (cancellation?.isCancelled == true) throw BakeCancelledException()
                    val n = response.body.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    if (done - lastReport >= REPORT_BYTES) {
                        lastReport = done
                        onBytes(done)
                    }
                }
            }
            onBytes(done)
        }
    }

    private fun sha256(
        file: File,
        cancellation: CancellationToken?,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                if (cancellation?.isCancelled == true) throw BakeCancelledException()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PART = ".part"
        const val BUFFER_BYTES = 64 * 1024
        const val REPORT_BYTES = 256 * 1024L

        const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024
    }
}
