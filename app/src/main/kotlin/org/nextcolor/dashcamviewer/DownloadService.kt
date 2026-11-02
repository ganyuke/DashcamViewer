package org.nextcolor.dashcamviewer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class DownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transfer: Job? = null
    private var observer: Job? = null
    private val container
        get() = (application as DashcamApplication).container

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        if (intent?.action == ACTION_CANCEL) {
            val transferRunning = transfer?.isActive == true
            container.cancelDownload()
            container.abandonDownloadReservation()
            transfer?.cancel(CancellationException("User cancelled"))
            if (!transferRunning) serviceScope.launch { container.releaseOwnedNetworkIfIdle() }
            return START_NOT_STICKY
        }
        startForegroundCompat(buildNotification("Preparing download", 0, null))
        if (transfer?.isActive == true) return START_NOT_STICKY
        val path = intent?.getStringExtra(EXTRA_MEDIA_PATH)
        val media = path?.let(container::takeSelectedDownload)
        if (media == null) {
            container.abandonDownloadReservation()
            serviceScope.launch {
                container.releaseOwnedNetworkIfIdle()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        observer?.cancel()
        observer = serviceScope.launch {
            container.downloadState.collect { state ->
                if (state.active)
                    updateNotification(state.name ?: "Downloading", state.bytes, state.expected)
            }
        }
        transfer = serviceScope.launch {
            try {
                container.startDownload(media)
            } catch (_: CancellationException) {} catch (_: Throwable) {} finally {
                observer?.cancel()
                observer = null
                container.releaseOwnedNetworkIfIdle()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Dashcam downloads",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
    }

    private fun buildNotification(title: String, bytes: Long, expected: Long?): Notification {
        val cancel =
            PendingIntent.getService(
                this,
                42,
                Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val progress =
            if (expected != null && expected > 0) (bytes * 100 / expected).toInt().coerceIn(0, 100)
            else 0
        val openDownloads =
            PendingIntent.getActivity(
                this,
                43,
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_OPEN_DOWNLOADS, true)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentIntent(openDownloads)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle("DashcamViewer download")
            .setContentText(title)
            .setProgress(100, progress, expected == null)
            .setOngoing(true)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun updateNotification(title: String, bytes: Long, expected: Long?) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, bytes, expected))
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        container.cancelDownload()
        container.abandonDownloadReservation("Android dataSync time limit reached")
        transfer?.cancel(CancellationException("Android dataSync time limit reached"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observer?.cancel()
        transfer?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.nextcolor.dashcamviewer.action.DOWNLOAD"
        const val ACTION_CANCEL = "org.nextcolor.dashcamviewer.action.CANCEL_DOWNLOAD"
        const val EXTRA_MEDIA_PATH = "media_path"
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
        const val EXTRA_FOLDER = "media_folder"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4
    }
}
