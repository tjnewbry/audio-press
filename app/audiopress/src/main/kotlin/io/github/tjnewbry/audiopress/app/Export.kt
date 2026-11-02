package io.github.tjnewbry.audiopress.app

import io.github.tjnewbry.audiopress.bake.ExportCancelledException
import io.github.tjnewbry.audiopress.bake.ExportException
import io.github.tjnewbry.audiopress.bake.ExportResult
import io.github.tjnewbry.audiopress.core.CancellationToken
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/** Where an export of a baked book to one audio file stands. */
sealed interface ExportState {
    data object Idle : ExportState

    /** Encoding (and then copying) [title]; [fraction] is 0 to 1. */
    data class Working(
        val title: String,
        val fraction: Float,
        val cancelling: Boolean = false,
    ) : ExportState

    /** Finished: the exported file is [exportBytes], and the book still takes [footprintBytes] in the app. */
    data class Done(
        val title: String,
        val exportBytes: Long,
        val footprintBytes: Long,
    ) : ExportState

    data class Failed(
        val title: String,
        val message: String,
    ) : ExportState
}

/**
 * One export: encode the baked book into [temp] (the encoder needs a seekable file), copy that to the place the user chose
 * ([openTarget]), and always delete [temp]. On a failure or a cancel the half-made target is removed ([deleteTarget]).
 * Blocks; the encoder and the places are passed in, so this can be checked without Android. [report] gets every change.
 */
class ExportJob(
    private val title: String,
    private val temp: File,
    private val export: (File, CancellationToken, (Float) -> Unit) -> ExportResult,
    private val openTarget: () -> OutputStream,
    private val deleteTarget: () -> Unit,
    private val footprint: () -> Long,
    private val report: (ExportState) -> Unit,
) {
    private val token = CancellationToken()

    fun cancel() = token.cancel()

    /** Runs the export and returns how it ended: Done, Failed, or Idle when it was cancelled. */
    fun run(): ExportState {
        report(ExportState.Working(title, 0f))
        return try {
            val result = export(temp, token) { report(ExportState.Working(title, it, token.isCancelled)) }
            copy()
            ExportState.Done(title, result.sizeBytes, footprint())
        } catch (e: ExportCancelledException) {
            deleteTarget()
            ExportState.Idle
        } catch (e: ExportException) {
            deleteTarget()
            ExportState.Failed(title, e.message.orEmpty())
        } catch (e: IOException) {
            deleteTarget()
            ExportState.Failed(title, "The file could not be saved: ${e.message}. Check that there is enough free storage.")
        } finally {
            temp.delete()
        }
    }

    private fun copy() {
        temp.inputStream().use { input ->
            openTarget().use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    if (token.isCancelled) throw ExportCancelledException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
    }
}

/** A size for people, in decimal units like the voice download sizes: `21.4 MB`, `1.82 GB`, `640 KB`. */
fun formatExportSize(bytes: Long): String {
    val kb = 1_000.0
    val value = bytes.toDouble()
    return when {
        value >= kb * kb * kb -> String.format(Locale.US, "%.2f GB", value / (kb * kb * kb))
        value >= kb * kb -> String.format(Locale.US, "%.1f MB", value / (kb * kb))
        value >= kb -> String.format(Locale.US, "%.0f KB", value / kb)
        else -> "$bytes bytes"
    }
}

/** The text of the dialog shown when an export has finished. */
fun exportSuccessText(
    exportBytes: Long,
    footprintBytes: Long,
): String =
    "Export successful!\n\n" +
        "The exported audio file is ${formatExportSize(exportBytes)}.\n\n" +
        "The book in Audio Press still takes up ${formatExportSize(footprintBytes)}.\n\n" +
        "You can use the player in Audio Press to follow along with the text, " +
        "but if that is not important to you then consider deleting the bake data to save space."
