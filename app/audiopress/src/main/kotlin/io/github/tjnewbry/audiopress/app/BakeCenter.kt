package io.github.tjnewbry.audiopress.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** The application class: it owns the [BakeCenter], so a bake outlives any screen. */
class AudioPressApp : Application() {
    val bakeCenter: BakeCenter by lazy { BakeCenter(this) }
}

/**
 * Owns the baking for the whole app process: the [BakeService], the thread that does the work, and one
 * [BookBakeController] per book. A bake lives here and not in a screen, so it carries on when the screen turns off
 * or the app is not in front. While a download or bake is running, a foreground service ([BakeForegroundService])
 * keeps the process alive and shows a notification with progress and Cancel.
 */
class BakeCenter(
    private val app: Application,
    val baker: BakeService = KokoroBakeService(),
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "bake") }
    private val controllers = ConcurrentHashMap<String, BookBakeController>()
    private val tracker = ActiveBakeTracker(::onActiveChanged)
    val notifier = BakeNotifier(app)

    /** Set by the foreground service while it runs, to learn of every change of the bake it shows. */
    @Volatile
    var serviceListener: ((ActiveBake?) -> Unit)? = null

    /** The bake being shown, or null. */
    val active: ActiveBake? get() = tracker.current

    /** The controller of [book], made once and kept while the app runs. */
    fun controllerFor(
        book: Book,
        utterances: List<Utterance>,
        folder: File,
        choiceFile: File,
    ): BookBakeController =
        controllers.getOrPut(book.id) {
            BookBakeController(utterances, folder, baker, executor, choiceFile = choiceFile).also { controller ->
                controller.observer = { state -> onBookState(book, state) }
            }
        }

    /** Cancels whatever is being downloaded or baked (the notification's Cancel button). */
    fun cancelAll() {
        controllers.values.forEach { it.cancel() }
    }

    private fun onBookState(
        book: Book,
        state: BakeState,
    ) {
        val wasRunning = tracker.current?.bookId == book.id
        tracker.update(book.id, book.title, state)
        if (wasRunning) {
            when (state) {
                is BakeState.Baked -> notifier.showResult(book.title, "Baking finished. Open Audio Press to play it.")
                is BakeState.Failed -> notifier.showResult(book.title, state.message)
                else -> Unit
            }
        }
    }

    private fun onActiveChanged(active: ActiveBake?) {
        if (active != null) startService()
        serviceListener?.invoke(active)
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, BakeForegroundService::class.java))
        } catch (e: IllegalStateException) {
            // Android refuses a foreground service started from the background; the bake still runs while the process lives.
            Log.w(TAG, "Cannot start the baking service", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot start the baking service", e)
        }
    }

    private companion object {
        const val TAG = "AudioPressBake"
    }
}

/** Builds the notifications for baking: the ongoing one with progress and Cancel, and the result when it ends. */
class BakeNotifier(
    private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL, "Baking audio", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shows the progress of downloading a voice and baking a book"
        manager.createNotificationChannel(channel)
    }

    /** The ongoing notification for [notice]. */
    fun progress(notice: BakeNotice): Notification {
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .setProgress(notice.total.coerceAtLeast(1), notice.done, notice.indeterminate)
        if (!notice.cancelling) builder.addAction(0, "Cancel", cancelIntent())
        return builder.build()
    }

    /** A notification for something to start with: the service must show one the moment it starts. */
    fun starting(): Notification =
        NotificationCompat
            .Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Audio Press")
            .setContentText("Getting ready to bake...")
            .setOngoing(true)
            .setProgress(1, 0, true)
            .setContentIntent(openApp())
            .build()

    /** Shows how a bake ended, where the user can see it after it. */
    fun showResult(
        title: String,
        text: String,
    ) {
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
        try {
            manager.notify(title.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w("AudioPressBake", "Notifications are not allowed", e)
        }
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun cancelIntent(): PendingIntent =
        PendingIntent.getService(
            context,
            1,
            Intent(context, BakeForegroundService::class.java).setAction(BakeForegroundService.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val CHANNEL = "baking"
    }
}
