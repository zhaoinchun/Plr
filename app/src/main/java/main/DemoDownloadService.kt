package main

import android.app.Notification
import android.content.Context
import androidx.media3.common.util.NotificationUtil.setNotification
import androidx.media3.common.util.Util.fromUtf8Bytes
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.example.plr.R

class DemoDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DemoUtil.DOWNLOAD_NOTIFICATION_CHANNEL_ID,
    R.string.exo_download_notification_channel_name,  /* channelDescriptionResourceId= */
    0
) {
    override val downloadManager: DownloadManager
        get() {
            // This will only happen once, because getDownloadManager is guaranteed to be called only once
            // in the life cycle of the process.
            val downloadManager =
                DemoUtil.getDownloadManager( /* context= */this)
            val downloadNotificationHelper =
                DemoUtil.getDownloadNotificationHelper( /* context= */this)
            downloadManager.addListener(
                TerminalStateNotificationHelper(
                    this,
                    downloadNotificationHelper,
                    FOREGROUND_NOTIFICATION_ID + 1
                )
            )
            return downloadManager
        }

    override val scheduler: Scheduler?
        get() = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(
        downloads: List<Download>, notMetRequirements: Int
    ): Notification {
        return DemoUtil.getDownloadNotificationHelper( /* context= */this)
            .buildProgressNotification( /* context= */
                this,
                R.drawable.ic_download,  /* contentIntent= */
                null,  /* message= */
                null,
                downloads,
                notMetRequirements
            )
    }

    /**
     * Creates and displays notifications for downloads when they complete or fail.
     * 
     * 
     * This helper will outlive the lifespan of a single instance of [DemoDownloadService].
     * It is static to avoid leaking the first [DemoDownloadService] instance.
     */
    private class TerminalStateNotificationHelper(
        context: Context,
        private val notificationHelper: DownloadNotificationHelper,
        private var nextNotificationId: Int
    ) : DownloadManager.Listener {
        private val context: Context

        init {
            this.context = context.getApplicationContext()
        }

        override fun onDownloadChanged(
            downloadManager: DownloadManager, download: Download, finalException: Exception?
        ) {
            val notification: Notification?
            if (download.state == Download.STATE_COMPLETED) {
                notification =
                    notificationHelper.buildDownloadCompletedNotification(
                        context,
                        R.drawable.ic_download_done,  /* contentIntent= */
                        null,
                        fromUtf8Bytes(download.request.data)
                    )
            } else if (download.state == Download.STATE_FAILED) {
                notification =
                    notificationHelper.buildDownloadFailedNotification(
                        context,
                        R.drawable.ic_download_done,  /* contentIntent= */
                        null,
                        fromUtf8Bytes(download.request.data)
                    )
            } else {
                return
            }
            setNotification(context, nextNotificationId++, notification)
        }
    }

    companion object {
        private const val JOB_ID = 1
        private const val FOREGROUND_NOTIFICATION_ID = 1
    }
}
