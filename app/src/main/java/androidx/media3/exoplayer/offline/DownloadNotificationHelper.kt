package androidx.media3.exoplayer.offline

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.media3.common.C
import androidx.media3.exoplayer.scheduler.Requirements
import com.example.plr.R

class DownloadNotificationHelper(context: Context, channelId: String?) {
    private val notificationBuilder: Notification.Builder

    init {
        val applicationContext = context.applicationContext
        this.notificationBuilder =
            if (Build.VERSION.SDK_INT >= 26)
                Notification.Builder(applicationContext, channelId)
            else
                Notification.Builder(applicationContext)
    }

    fun buildProgressNotification(
        context: Context,
        @DrawableRes smallIcon: Int,
        contentIntent: PendingIntent?,
        message: String?,
        downloads: List<Download>,
        @Requirements.RequirementFlags notMetRequirements: @Requirements.RequirementFlags Int
    ): Notification {
        var totalPercentage = 0f
        var downloadTaskCount = 0
        var allDownloadPercentagesUnknown = true
        var haveDownloadedBytes = false
        var haveDownloadingTasks = false
        var haveQueuedTasks = false
        var haveRemovingTasks = false
        for (i in downloads.indices) {
            val download = downloads[i]
            when (download.state) {
                Download.STATE_REMOVING -> haveRemovingTasks = true
                Download.STATE_QUEUED -> haveQueuedTasks = true
                Download.STATE_RESTARTING, Download.STATE_DOWNLOADING -> {
                    haveDownloadingTasks = true
                    val downloadPercentage = download.percentDownloaded
                    if (downloadPercentage != C.PERCENTAGE_UNSET.toFloat()) {
                        allDownloadPercentagesUnknown = false
                        totalPercentage += downloadPercentage
                    }
                    haveDownloadedBytes = haveDownloadedBytes or (download.bytesDownloaded > 0)
                    downloadTaskCount++
                }

                Download.STATE_STOPPED, Download.STATE_COMPLETED, Download.STATE_FAILED -> {}
                else -> {}
            }
        }

        val titleStringId: Int
        var showProgress = true
        if (haveDownloadingTasks) {
            titleStringId = R.string.exo_download_downloading
        } else if (haveQueuedTasks && notMetRequirements != 0) {
            showProgress = false
            titleStringId = if ((notMetRequirements and Requirements.NETWORK_UNMETERED) != 0) {
                // Note: This assumes that "unmetered" == "WiFi", since it provides a clearer message that's
                // correct in the majority of cases.
                R.string.exo_download_paused_for_wifi
            } else if ((notMetRequirements and Requirements.NETWORK) != 0) {
                R.string.exo_download_paused_for_network
            } else {
                R.string.exo_download_paused
            }
        } else if (haveRemovingTasks) {
            titleStringId = R.string.exo_download_removing
        } else {
            // There are either no downloads, or all downloads are in terminal states.
            titleStringId = NULL_STRING_ID
        }

        var maxProgress = 0
        var currentProgress = 0
        var indeterminateProgress = false
        if (showProgress) {
            maxProgress = 100
            if (haveDownloadingTasks) {
                currentProgress = (totalPercentage / downloadTaskCount).toInt()
                indeterminateProgress = allDownloadPercentagesUnknown && haveDownloadedBytes
            } else {
                indeterminateProgress = true
            }
        }

        return buildNotification(
            context,
            smallIcon,
            contentIntent,
            message,
            titleStringId,
            maxProgress,
            currentProgress,
            indeterminateProgress,
            true,
            false
        )
    }

    fun buildDownloadCompletedNotification(
        context: Context,
        @DrawableRes smallIcon: Int,
        contentIntent: PendingIntent?,
        message: String?
    ): Notification {
        val titleStringId: Int = R.string.exo_download_completed
        return buildEndStateNotification(context, smallIcon, contentIntent, message, titleStringId)
    }

    fun buildDownloadFailedNotification(
        context: Context,
        @DrawableRes smallIcon: Int,
        contentIntent: PendingIntent?,
        message: String?
    ): Notification {
        @StringRes val titleStringId: Int = R.string.exo_download_failed
        return buildEndStateNotification(context, smallIcon, contentIntent, message, titleStringId)
    }

    private fun buildEndStateNotification(
        context: Context,
        @DrawableRes smallIcon: Int,
        contentIntent: PendingIntent?,
        message: String?,
        @StringRes titleStringId: Int
    ): Notification {
        return buildNotification(
            context,
            smallIcon,
            contentIntent,
            message,
            titleStringId,
            0,
            0,
            false,
            false,
            true
        )
    }

    // Missing annotations on Notification.Builder
    private fun buildNotification(
        context: Context,
        @DrawableRes smallIcon: Int,
        contentIntent: PendingIntent?,
        message: String?,
        @StringRes titleStringId: Int,
        maxProgress: Int,
        currentProgress: Int,
        indeterminateProgress: Boolean,
        ongoing: Boolean,
        showWhen: Boolean
    ): Notification {
        notificationBuilder.setSmallIcon(smallIcon)
        notificationBuilder.setContentTitle(
            if (titleStringId == NULL_STRING_ID) null else context.getResources()
                .getString(titleStringId)
        )
        notificationBuilder.setContentIntent(contentIntent)
        notificationBuilder.setStyle(
            if (message == null) null else Notification.BigTextStyle().bigText(message)
        )
        notificationBuilder.setProgress(maxProgress, currentProgress, indeterminateProgress)
        notificationBuilder.setOngoing(ongoing)
        notificationBuilder.setShowWhen(showWhen)
        if (Build.VERSION.SDK_INT >= 31) {
            notificationBuilder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return notificationBuilder.build()
    }

    companion object {
        @StringRes
        private const val NULL_STRING_ID = 0
    }
}
