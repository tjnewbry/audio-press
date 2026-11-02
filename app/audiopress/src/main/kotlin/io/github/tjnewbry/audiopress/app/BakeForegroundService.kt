package io.github.tjnewbry.audiopress.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat

/**
 * Keeps a long download and bake running with the screen off or the app in the background. The work itself runs in
 * the [BakeCenter]; this service gives the process foreground priority, holds the CPU awake, and shows the
 * notification ("Downloading Lite...", "Baking audio using Santa...") with progress and a Cancel button.
 *
 * The type is `dataSync`: it covers downloading the model, and exists on every Android version the app supports (13
 * to 16). From Android 15 a `dataSync` service may only run about 6 hours in a 24-hour period; the system then calls
 * [onTimeout], and the bake is stopped cleanly (the clips made so far are kept). The user resumes it from the book.
 * If the process is killed, nothing is lost either: the next launch offers "Resume baking".
 */
class BakeForegroundService : Service() {
    private lateinit var center: BakeCenter
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastText = ""
    private var lastDone = -1
    private var lastShownAt = 0L

    override fun onCreate() {
        super.onCreate()
        center = (application as AudioPressApp).bakeCenter
        center.notifier.ensureChannel()
        val first = center.active?.let { noticeFor(it.bookTitle, it.state) }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            first?.let(center.notifier::progress) ?: center.notifier.starting(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        wakeLock =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioPress:bake")
                .also { it.acquire() }
        center.serviceListener = ::onActiveChanged
        // The bake may have ended between the start request and now.
        if (center.active == null) finish()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_CANCEL) center.cancelAll()
        return START_NOT_STICKY // after a kill the bake is resumed from the book, not restarted here
    }

    /** Android 15 and newer: the 6-hour limit of a `dataSync` service is up, and the service must stop now. */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        center.cancelAll()
        center.notifier.showResult("Baking paused", "Android limits long background work. Open Audio Press and tap Resume baking.")
        finish()
    }

    override fun onDestroy() {
        center.serviceListener = null
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onActiveChanged(active: ActiveBake?) {
        val notice = active?.let { noticeFor(it.bookTitle, it.state) }
        if (notice == null) {
            finish()
            return
        }
        // Update when the words change, or at most about once a second while the numbers move.
        val now = SystemClock.elapsedRealtime()
        if (notice.text == lastText && notice.done == lastDone) return
        if (notice.text == lastText && now - lastShownAt < MIN_UPDATE_MS) return
        lastText = notice.text
        lastDone = notice.done
        lastShownAt = now
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, center.notifier.progress(notice))
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_CANCEL = "io.github.tjnewbry.audiopress.app.CANCEL_BAKE"
        private const val NOTIFICATION_ID = 17
        private const val MIN_UPDATE_MS = 1000L
    }
}
