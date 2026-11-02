package io.github.tjnewbry.audiopress.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.tjnewbry.audiopress.bake.BakedBook
import io.github.tjnewbry.audiopress.bake.ExportIncompleteException
import io.github.tjnewbry.audiopress.bake.M4aExporter
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Owns the one export that can run at a time, for the whole app process, so it carries on when the screen turns off. While
 * it runs a foreground service ([ExportForegroundService]) shows the same kind of silent, ongoing notification as a bake.
 * [state] is read by the screen; it changes on the main thread.
 */
class ExportCenter(
    private val app: Application,
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "export") }
    private val main = Handler(Looper.getMainLooper())
    val notifier = BakeNotifier(app)

    var state: ExportState by mutableStateOf(ExportState.Idle)
        private set

    /** Set by the foreground service while it runs, to learn of every change. */
    @Volatile
    var serviceListener: ((ExportState) -> Unit)? = null

    @Volatile
    private var job: ExportJob? = null

    /** The latest state, readable from any thread (the service reads it). */
    @Volatile
    var latest: ExportState = ExportState.Idle
        private set

    private var lastFraction = -1f

    /** Exports the baked book of [book] (its clips are in [audioFolder]) to the document [target] the user chose. */
    fun start(
        book: Book,
        audioFolder: File,
        footprint: () -> Long,
        target: Uri,
    ) {
        if (latest is ExportState.Working) return
        val resolver = app.contentResolver
        val next =
            ExportJob(
                title = book.title,
                temp = File(app.cacheDir, "export-${book.id}.m4a"),
                export = { out, token, progress ->
                    val baked = BakedBook.read(audioFolder) ?: throw ExportIncompleteException()
                    M4aExporter.export(baked, out, token, progress)
                },
                openTarget = { resolver.openOutputStream(target, "wt") ?: throw IOException("the chosen place cannot be written") },
                deleteTarget = {
                    try {
                        DocumentsContract.deleteDocument(resolver, target) // the empty file the save dialog made
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot remove the unfinished export", e)
                    }
                },
                footprint = footprint,
                report = ::publishProgress,
            )
        job = next
        publish(ExportState.Working(book.title, 0f))
        startService()
        executor.execute {
            val ended = next.run()
            job = null
            publish(ended)
            when (ended) {
                is ExportState.Done -> notifier.showResult(book.title, "Export finished. Open Audio Press for details.")
                is ExportState.Failed -> notifier.showResult(book.title, ended.message)
                else -> Unit
            }
        }
    }

    /** Cancels the running export (the dialog's and the notification's Cancel). */
    fun cancel() {
        job?.cancel()
        (latest as? ExportState.Working)?.let { publish(it.copy(cancelling = true)) }
    }

    /** The finished or failed dialog was acknowledged. */
    fun acknowledge() {
        if (latest !is ExportState.Working) publish(ExportState.Idle)
    }

    // Progress comes many times a second; the screen and the notification only need a change of about half a percent.
    private fun publishProgress(next: ExportState) {
        if (next is ExportState.Working && next.fraction - lastFraction < MIN_STEP && !next.cancelling) return
        if (next is ExportState.Working) lastFraction = next.fraction
        publish(next)
    }

    private fun publish(next: ExportState) {
        if (next !is ExportState.Working) lastFraction = -1f
        latest = next
        main.post { state = next }
        serviceListener?.invoke(next)
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, ExportForegroundService::class.java))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot start the export service", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot start the export service", e)
        }
    }

    private companion object {
        const val TAG = "AudioPressExport"
        const val MIN_STEP = 0.005f
    }
}
