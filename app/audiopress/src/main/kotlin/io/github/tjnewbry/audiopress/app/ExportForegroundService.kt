package io.github.tjnewbry.audiopress.app

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import kotlin.math.roundToInt

/**
 * Keeps an export running with the screen off, with the same kind of silent, ongoing notification as a bake (with Cancel).
 * The work is in the [ExportCenter]; this service only gives the process foreground priority and shows progress.
 */
class ExportForegroundService : Service() {
    private lateinit var center: ExportCenter
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        center = (application as AudioPressApp).exportCenter
        center.notifier.ensureChannel()
        val first = (center.latest as? ExportState.Working)?.let(::noticeOf)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            center.notifier.progress(first ?: starting(), center.notifier.exportCancelIntent()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        wakeLock =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioPress:export")
                .also { it.acquire() }
        center.serviceListener = ::onState
        if (center.latest !is ExportState.Working) finish() // it ended before the service started
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_CANCEL) center.cancel()
        return START_NOT_STICKY
    }

    /** Android 15 and newer: the time limit of a `dataSync` service is up. */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        center.cancel()
        finish()
    }

    override fun onDestroy() {
        center.serviceListener = null
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onState(state: ExportState) {
        if (state !is ExportState.Working) {
            finish()
            return
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, center.notifier.progress(noticeOf(state), center.notifier.exportCancelIntent()))
    }

    private fun noticeOf(state: ExportState.Working) =
        BakeNotice(
            title = state.title,
            text = if (state.cancelling) "Cancelling..." else "Exporting audio...",
            done = (state.fraction * PERCENT).roundToInt(),
            total = PERCENT,
            indeterminate = false,
            cancelling = state.cancelling,
        )

    private fun starting() = BakeNotice("Audio Press", "Getting ready to export...", 0, 1, indeterminate = true, cancelling = false)

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_CANCEL = "io.github.tjnewbry.audiopress.app.CANCEL_EXPORT"
        private const val NOTIFICATION_ID = 18
        private const val PERCENT = 100
    }
}
