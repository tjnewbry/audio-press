package io.github.tjnewbry.audiopress.bake

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import io.github.tjnewbry.audiopress.core.CancellationToken
import java.io.File
import java.io.IOException

/** Why an export did not finish. The message is written for the person using the app. */
open class ExportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The bake is missing or has not finished, so there is no complete book to export. */
class ExportIncompleteException : ExportException("This book is not fully baked yet. Finish baking it, then export.")

/** The export was cancelled with its [CancellationToken]. The partial file has been deleted. */
class ExportCancelledException : ExportException("The export was cancelled")

/** There is not enough free space for the file, which needs about [neededBytes]. */
class ExportStorageFullException(
    val neededBytes: Long,
) : ExportException("Not enough free storage to export this book. It needs about ${neededBytes / BYTES_PER_MB + 1} MB.")

/** The device has no AAC encoder. */
class ExportEncoderMissingException(
    cause: Throwable? = null,
) : ExportException("This device cannot encode AAC audio, so the book cannot be exported.", cause)

/** The finished file: its [sizeBytes] and the length of the audio in [durationMs]. */
data class ExportResult(
    val sizeBytes: Long,
    val durationMs: Long,
)

private const val BYTES_PER_MB = 1024L * 1024L

/**
 * Merges a finished bake into one AAC-LC mono `.m4a` file with Android's own `MediaCodec` encoder and `MediaMuxer`.
 * All clips go through one encoder as one continuous stream, so there are no gaps or clicks between them, and the
 * audio is read a buffer at a time, so memory stays small for a book of any length.
 *
 * It is blocking: call [export] off the main thread. The output must be a real file (the muxer needs to seek), so
 * the app exports to its own storage and then copies the file where the user chose.
 */
object M4aExporter {
    const val BITRATE = 48_000

    /** About how many bytes an export of [durationMs] of audio takes, with a margin. */
    internal fun estimatedBytes(durationMs: Long): Long = durationMs * BITRATE / 8000 * 12 / 10 + BYTES_PER_MB

    /**
     * Exports [book] to [target] (replaced if it exists). [onProgress] gets a fraction from 0 to 1 on the calling thread.
     *
     * @throws ExportIncompleteException if the bake is not complete.
     * @throws ExportStorageFullException if the device lacks the space, checked first and again if a write fails.
     * @throws ExportEncoderMissingException if the device has no AAC encoder.
     * @throws ExportCancelledException if [cancellation] was cancelled (the partial file is deleted).
     * @throws ExportException for any other failure, including unreadable clips.
     */
    fun export(
        book: BakedBook,
        target: File,
        cancellation: CancellationToken? = null,
        onProgress: (Float) -> Unit = {},
    ): ExportResult {
        val files = book.utterances.map { book.file(it) }
        if (!book.isComplete || files.isEmpty()) throw ExportIncompleteException()
        val parent = target.absoluteFile.parentFile ?: throw ExportException("The export location is not valid.")
        if (!parent.isDirectory && !parent.mkdirs()) throw ExportException("Cannot create the folder for the export.")
        val needed = estimatedBytes(book.totalDurationMs)
        if (parent.usableSpace < needed) throw ExportStorageFullException(needed)

        try {
            return encode(files, target, cancellation, onProgress)
        } catch (e: Exception) {
            target.delete()
            throw when (e) {
                is ExportException -> {
                    e
                }

                is IOException -> {
                    if (parent.usableSpace < BYTES_PER_MB) {
                        ExportStorageFullException(needed)
                    } else {
                        ExportException("The book's audio could not be read or written: ${e.message}", e)
                    }
                }

                else -> {
                    ExportException("The export failed: ${e.message}", e)
                }
            }
        }
    }

    private fun encode(
        files: List<File>,
        target: File,
        cancellation: CancellationToken?,
        onProgress: (Float) -> Unit,
    ): ExportResult {
        PcmClipStream(files).use { pcm ->
            val format =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, pcm.sampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BYTES)
                }
            val codec =
                try {
                    MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                } catch (e: IOException) {
                    throw ExportEncoderMissingException(e)
                } catch (e: IllegalArgumentException) {
                    throw ExportEncoderMissingException(e)
                }
            var muxer: MediaMuxer? = null
            var muxerStarted = false
            try {
                try {
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                } catch (e: IllegalArgumentException) {
                    throw ExportEncoderMissingException(e)
                }
                codec.start()
                val out = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = out
                var track = -1
                val info = MediaCodec.BufferInfo()
                val chunk = ByteArray(INPUT_BYTES)
                var bytesFed = 0L
                var inputDone = false
                var outputDone = false
                var lastProgress = -1f
                while (!outputDone) {
                    if (cancellation?.isCancelled == true) throw ExportCancelledException()
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex)!!
                            val n = pcm.read(chunk, 0, minOf(chunk.size, buffer.capacity()))
                            val ptsUs = bytesFed / 2 * 1_000_000L / pcm.sampleRate
                            if (n <= 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buffer.clear()
                                buffer.put(chunk, 0, n)
                                codec.queueInputBuffer(inIndex, 0, n, ptsUs, 0)
                                bytesFed += n
                                val fraction = (bytesFed.toDouble() / pcm.totalBytes).toFloat().coerceIn(0f, 1f)
                                if (fraction - lastProgress >= PROGRESS_STEP) {
                                    lastProgress = fraction
                                    onProgress(fraction)
                                }
                            }
                        }
                    }
                    var outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    while (outIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = out.addTrack(codec.outputFormat)
                            out.start()
                            muxerStarted = true
                        } else if (outIndex >= 0) {
                            val data = codec.getOutputBuffer(outIndex)!!
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxerStarted) { "the encoder gave audio before its format" }
                                data.position(info.offset)
                                data.limit(info.offset + info.size)
                                out.writeSampleData(track, data, info)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                                break
                            }
                        }
                        outIndex = codec.dequeueOutputBuffer(info, 0)
                    }
                }
                onProgress(1f)
                out.stop()
                muxerStarted = false
                return ExportResult(target.length(), bytesFed / 2 * 1000L / pcm.sampleRate)
            } finally {
                runCatching { codec.stop() }
                codec.release()
                if (muxer != null) {
                    if (muxerStarted) runCatching { muxer.stop() }
                    runCatching { muxer.release() }
                }
            }
        }
    }

    private const val INPUT_BYTES = 16 * 1024
    private const val TIMEOUT_US = 10_000L
    private const val PROGRESS_STEP = 0.005f
}
