package androidx.media3.exoplayer.offline

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.Util.createHandlerForCurrentOrMainLooper
import androidx.media3.common.util.Util.setForegroundServiceNotification
import androidx.media3.common.util.Util.startForegroundService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Requirements.RequirementFlags
import androidx.media3.exoplayer.scheduler.Scheduler
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull

abstract class DownloadService protected constructor(
    foregroundNotificationId: Int,
    foregroundNotificationUpdateInterval: Long = DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    channelId: String? = null,
    channelNameResourceId: Int = 0,
    channelDescriptionResourceId: Int = 0
) : Service() {
    private val foregroundNotificationUpdater: ForegroundNotificationUpdater?
    private val channelId: String?
    private val channelNameResourceId: Int
    private val channelDescriptionResourceId: Int

    private var downloadManagerHelper:DownloadManagerHelper? = null
    private var lastStartId = 0
    private var startedInForeground = false
    private var taskRemoved = false
    private var isStopped = false
    private var isDestroyed = false

    init {
        if (foregroundNotificationId == FOREGROUND_NOTIFICATION_ID_NONE) {
            this.foregroundNotificationUpdater = null
            this.channelId = null
            this.channelNameResourceId = 0
            this.channelDescriptionResourceId = 0
        } else {
            this.foregroundNotificationUpdater =
                ForegroundNotificationUpdater(
                    foregroundNotificationId, foregroundNotificationUpdateInterval
                )
            this.channelId = channelId
            this.channelNameResourceId = channelNameResourceId
            this.channelDescriptionResourceId = channelDescriptionResourceId
        }
    }

    override fun onCreate() {
        if (channelId != null) {
            NotificationUtil.createNotificationChannel(
                this,
                channelId,
                channelNameResourceId,
                channelDescriptionResourceId,
                NotificationUtil.IMPORTANCE_LOW
            )
        }
        val clazz: Class<out DownloadService?> = javaClass
        var downloadManagerHelper: DownloadManagerHelper? = downloadManagerHelpers.get(clazz)
        if (downloadManagerHelper == null) {
            val foregroundAllowed = foregroundNotificationUpdater != null
            // See https://developer.android.com/about/versions/12/foreground-services.
            val canStartForegroundServiceFromBackground = Build.VERSION.SDK_INT < 31
            val scheduler =
                if (foregroundAllowed && canStartForegroundServiceFromBackground) this.scheduler else null
            val downloadManager =
                this.downloadManager
            downloadManager.resumeDownloads()
            downloadManagerHelper =
                DownloadManagerHelper(
                    getApplicationContext(), downloadManager, foregroundAllowed, scheduler, clazz
                )
            downloadManagerHelpers.put(clazz, downloadManagerHelper)
        }
        this.downloadManagerHelper = downloadManagerHelper
        downloadManagerHelper.attachService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        taskRemoved = false
        var intentAction: String? = null
        var contentId: String? = null
        if (intent != null) {
            intentAction = intent.action
            contentId = intent.getStringExtra(KEY_CONTENT_ID)
            startedInForeground = startedInForeground or (
                    intent.getBooleanExtra(KEY_FOREGROUND, false) || ACTION_RESTART == intentAction)
        }
        // intentAction is null if the service is restarted or no action is specified.
        if (intentAction == null) {
            intentAction = ACTION_INIT
        }
        val downloadManager: DownloadManager = downloadManagerHelper!!.downloadManager
        when (intentAction) {
            ACTION_INIT, ACTION_RESTART -> {}
            ACTION_ADD_DOWNLOAD -> {
                val downloadRequest =
                    intent!!.getParcelableExtra<DownloadRequest?>(
                        KEY_DOWNLOAD_REQUEST
                    )
                if (downloadRequest == null) {
                    e(TAG, "Ignored ADD_DOWNLOAD: Missing $KEY_DOWNLOAD_REQUEST extra")
                } else {
                    val stopReason =
                        intent.getIntExtra(KEY_STOP_REASON, Download.STOP_REASON_NONE)
                    downloadManager.addDownload(downloadRequest, stopReason)
                }
            }

            ACTION_REMOVE_DOWNLOAD -> if (contentId == null) {
                e(TAG, "Ignored REMOVE_DOWNLOAD: Missing $KEY_CONTENT_ID extra")
            } else {
                downloadManager.removeDownload(contentId)
            }

            ACTION_REMOVE_ALL_DOWNLOADS -> downloadManager.removeAllDownloads()
            ACTION_RESUME_DOWNLOADS -> downloadManager.resumeDownloads()
            ACTION_PAUSE_DOWNLOADS -> downloadManager.pauseDownloads()
            ACTION_SET_STOP_REASON -> if (!intent!!.hasExtra(
                    KEY_STOP_REASON
                )
            ) {
                e(TAG, "Ignored SET_STOP_REASON: Missing $KEY_STOP_REASON extra")
            } else {
                val stopReason = intent.getIntExtra(KEY_STOP_REASON, 0)
                downloadManager.setStopReason(contentId, stopReason)
            }

            ACTION_SET_REQUIREMENTS -> {
                val requirements =
                    intent!!.getParcelableExtra<Requirements?>(
                        KEY_REQUIREMENTS
                    )
                if (requirements == null) {
                    e(TAG, "Ignored SET_REQUIREMENTS: Missing $KEY_REQUIREMENTS extra")
                } else {
                    downloadManager.requirements = (requirements)
                }
            }

            else -> e(TAG, "Ignored unrecognized action: $intentAction")
        }

        if (Build.VERSION.SDK_INT >= 26 && startedInForeground && foregroundNotificationUpdater != null) {
            // From API level 26, services started in the foreground are required to show a notification.
            foregroundNotificationUpdater.showNotificationIfNotAlready()
        }

        isStopped = false
        if (downloadManager.isIdle) {
            onIdle()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        taskRemoved = true
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        w(TAG, "onTimeout() called by system. Calling stopSelf() to terminate gracefully.")
        stopSelf()
    }

    override fun onDestroy() {
        isDestroyed = true
        downloadManagerHelper!!.detachService(this)
        foregroundNotificationUpdater?.stopPeriodicUpdates()
    }

    override fun onBind(intent: Intent?): IBinder? {
        throw UnsupportedOperationException()
    }

    protected abstract val downloadManager: DownloadManager
    protected abstract val scheduler: Scheduler?

    protected abstract fun getForegroundNotification(
        downloads: List<Download?>?,
        notMetRequirements: @RequirementFlags Int
    ): Notification

    protected fun invalidateForegroundNotification() {
        if (foregroundNotificationUpdater != null && !isDestroyed) {
            foregroundNotificationUpdater.invalidate()
        }
    }

    private fun notifyDownloads(downloads: List<Download?>) {
        if (foregroundNotificationUpdater != null) {
            for (i in downloads.indices) {
                if (needsStartedService(downloads[i]!!.state)) {
                    foregroundNotificationUpdater.startPeriodicUpdates()
                    break
                }
            }
        }
    }

    private fun notifyDownloadChanged(download: Download) {
        if (foregroundNotificationUpdater != null) {
            if (needsStartedService(download.state)) {
                foregroundNotificationUpdater.startPeriodicUpdates()
            } else {
                foregroundNotificationUpdater.invalidate()
            }
        }
    }

    private fun notifyDownloadRemoved() {
        foregroundNotificationUpdater?.invalidate()
    }

    private fun onIdle() {
        foregroundNotificationUpdater?.stopPeriodicUpdates()

        if (!downloadManagerHelper!!.updateScheduler()) {
            // We failed to schedule the service to restart when requirements that the DownloadManager is
            // waiting for are met, so remain started.
            return
        }

        // Stop the service, either because the DownloadManager is not waiting for requirements to be
        // met, or because we've scheduled the service to be restarted when they are.
        if (Build.VERSION.SDK_INT < 28 && taskRemoved) { // See [Internal: b/74248644].
            stopSelf()
            isStopped = true
        } else {
            isStopped = isStopped or stopSelfResult(lastStartId)
        }
    }

    private inner class ForegroundNotificationUpdater(
        private val notificationId: Int,
        private val updateInterval: Long
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var periodicUpdatesStarted = false
        private var notificationDisplayed = false

        fun startPeriodicUpdates() {
            periodicUpdatesStarted = true
            update()
        }

        fun stopPeriodicUpdates() {
            periodicUpdatesStarted = false
            handler.removeCallbacksAndMessages(null)
        }

        fun showNotificationIfNotAlready() {
            if (!notificationDisplayed) {
                update()
            }
        }

        fun invalidate() {
            if (notificationDisplayed) {
                update()
            }
        }

        @SuppressLint("InlinedApi") // Using compile time constant FOREGROUND_SERVICE_TYPE_DATA_SYNC
        fun update() {
            val downloadManager: DownloadManager = downloadManagerHelper!!.downloadManager
            val downloads = downloadManager.currentDownloads
            @RequirementFlags val notMetRequirements: @RequirementFlags Int =
                downloadManager.notMetRequirements
            val notification = getForegroundNotification(downloads, notMetRequirements)
            if (!notificationDisplayed) {
                setForegroundServiceNotification(
                    this@DownloadService,
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    "dataSync"
                )
                notificationDisplayed = true
            } else {
                // Update the notification via NotificationManager rather than by repeatedly calling
                // startForeground, since the latter can cause ActivityManager log spam.
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(notificationId, notification)
            }
            if (periodicUpdatesStarted) {
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed(Runnable { this.update() }, updateInterval)
            }
        }
    }

    private class DownloadManagerHelper(
        val context: Context,
        val downloadManager: DownloadManager,
        val foregroundAllowed: Boolean,
        val scheduler: Scheduler?,
        val serviceClass: Class<out DownloadService?>?
    ) : DownloadManager.Listener {
        var downloadService: DownloadService? = null
        var scheduledRequirements:Requirements? = null

        init {
            downloadManager.addListener(this)
            updateScheduler()
        }

        fun attachService(downloadService: DownloadService) {
            Preconditions.checkState(this.downloadService == null)
            this.downloadService = downloadService
            if (downloadManager.isInitialized) {
                // The call to DownloadService.notifyDownloads is posted to avoid it being called directly
                // from DownloadService.onCreate. This is a good idea because it may in turn call
                // DownloadService.getForegroundNotification, and concrete subclass implementations may
                // not anticipate the possibility of this method being called before their onCreate
                // implementation has finished executing.
                createHandlerForCurrentOrMainLooper()
                    .postAtFrontOfQueue(
                        Runnable { downloadService.notifyDownloads(downloadManager.currentDownloads) })
            }
        }

        fun detachService(downloadService: DownloadService?) {
            Preconditions.checkState(this.downloadService === downloadService)
            this.downloadService = null
        }

        fun updateScheduler(): Boolean {
            val waitingForRequirements = downloadManager.isWaitingForRequirements
            if (scheduler == null) {
                return !waitingForRequirements
            }

            if (!waitingForRequirements) {
                cancelScheduler()
                return true
            }

            val requirements = downloadManager.requirements
            val supportedRequirements = scheduler.getSupportedRequirements(requirements!!)
            if (supportedRequirements != requirements) {
                cancelScheduler()
                return false
            }

            if (!schedulerNeedsUpdate(requirements)) {
                return true
            }

            val servicePackage = context.packageName
            if (scheduler.schedule(requirements, servicePackage, ACTION_RESTART)) {
                scheduledRequirements = requirements
                return true
            } else {
                w(TAG, "Failed to schedule restart")
                cancelScheduler()
                return false
            }
        }

        // DownloadManager.Listener implementation.
        override fun onInitialized(downloadManager: DownloadManager) {
            if (downloadService != null) {
                downloadService!!.notifyDownloads(downloadManager.currentDownloads)
            }
        }

        override fun onDownloadChanged(
            downloadManager: DownloadManager, download: Download, finalException: Exception?
        ) {
            downloadService?.notifyDownloadChanged(download)
            if (serviceMayNeedRestart() && needsStartedService(download.state)) {
                // This shouldn't happen unless (a) application code is changing the downloads by calling
                // the DownloadManager directly rather than sending actions through the service, or (b) if
                // the service is background only and a previous attempt to start it was prevented. Try and
                // restart the service to robust against such cases.
                w(TAG, "DownloadService wasn't running. Restarting.")
                restartService()
            }
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager?, download: Download?) {
            downloadService?.notifyDownloadRemoved()
        }

        override fun onIdle(downloadManager: DownloadManager?) {
            downloadService?.onIdle()
        }

        override fun onRequirementsStateChanged(
            downloadManager: DownloadManager?,
            requirements: Requirements?,
           notMetRequirements: @RequirementFlags Int
        ) {
            updateScheduler()
        }

        override fun onWaitingForRequirementsChanged(
            downloadManager: DownloadManager, waitingForRequirements: Boolean
        ) {
            if (!waitingForRequirements && !downloadManager.getDownloadsPaused() && serviceMayNeedRestart()) {
                // We're no longer waiting for requirements and downloads aren't paused, meaning the manager
                // will be able to resume downloads that are currently queued. If there exist queued
                // downloads then we should ensure the service is started.
                val downloads = downloadManager.currentDownloads
                for (i in downloads.indices) {
                    if (downloads[i]!!.state == Download.STATE_QUEUED) {
                        restartService()
                        return
                    }
                }
            }
        }

        // Internal methods.
        fun schedulerNeedsUpdate(requirements: Requirements?): Boolean {
            return scheduledRequirements != requirements
        }

        @RequiresNonNull("scheduler")
        fun cancelScheduler() {
            val canceledRequirements = Requirements(0)
            if (schedulerNeedsUpdate(canceledRequirements)) {
                scheduler!!.cancel()
                scheduledRequirements = canceledRequirements
            }
        }

        fun serviceMayNeedRestart(): Boolean {
            return downloadService == null || downloadService!!.isStopped
        }

        fun restartService() {
            if (foregroundAllowed) {
                try {
                    val intent: Intent = getIntent(context, serviceClass, ACTION_RESTART)
                    startForegroundService(context, intent)
                } catch (e: IllegalStateException) {
                    // The process is running in the background, and is not allowed to start a foreground
                    // service due to foreground service launch restrictions
                    // (https://developer.android.com/about/versions/12/foreground-services).
                    w(TAG, "Failed to restart (foreground launch restriction)")
                }
            } else {
                // The service is background only. Use ACTION_INIT rather than ACTION_RESTART because
                // ACTION_RESTART is handled as though KEY_FOREGROUND is set to true.
                try {
                    val intent: Intent = getIntent(context, serviceClass, ACTION_INIT)
                    context.startService(intent)
                } catch (e: IllegalStateException) {
                    // The process is classed as idle by the platform. Starting a background service is not
                    // allowed in this state.
                    w(TAG, "Failed to restart (process is idle)")
                }
            }
        }
    }

    companion object {
        const val ACTION_INIT: String = "androidx.media3.exoplayer.downloadService.action.INIT"
        private const val ACTION_RESTART =
            "androidx.media3.exoplayer.downloadService.action.RESTART"
        const val ACTION_ADD_DOWNLOAD: String =
            "androidx.media3.exoplayer.downloadService.action.ADD_DOWNLOAD"
        const val ACTION_REMOVE_DOWNLOAD: String =
            "androidx.media3.exoplayer.downloadService.action.REMOVE_DOWNLOAD"
        const val ACTION_REMOVE_ALL_DOWNLOADS: String =
            "androidx.media3.exoplayer.downloadService.action.REMOVE_ALL_DOWNLOADS"
        const val ACTION_RESUME_DOWNLOADS: String =
            "androidx.media3.exoplayer.downloadService.action.RESUME_DOWNLOADS"
        const val ACTION_PAUSE_DOWNLOADS: String =
            "androidx.media3.exoplayer.downloadService.action.PAUSE_DOWNLOADS"
        const val ACTION_SET_STOP_REASON: String =
            "androidx.media3.exoplayer.downloadService.action.SET_STOP_REASON"
        const val ACTION_SET_REQUIREMENTS: String =
            "androidx.media3.exoplayer.downloadService.action.SET_REQUIREMENTS"
        const val KEY_DOWNLOAD_REQUEST: String = "download_request"
        const val KEY_CONTENT_ID: String = "content_id"
        const val KEY_STOP_REASON: String = "stop_reason"
        const val KEY_REQUIREMENTS: String = "requirements"
        const val KEY_FOREGROUND: String = "foreground"
        const val FOREGROUND_NOTIFICATION_ID_NONE: Int = 0
        const val DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL: Long = 1000
        private const val TAG = "DownloadService"

        // Maps each concrete DownloadService subclass to a single DownloadManagerHelper instance. This
        // ensures getDownloadManager is only called once per subclass, even if a new instance of the
        // service is created. The DownloadManagerHelper wrapper also takes care of restarting the service
        // when there's no scheduler, and is often able to restart the service faster than the scheduler
        // even when there is one.
        private val downloadManagerHelpers: HashMap<Class<out DownloadService?>?, DownloadManagerHelper?> =
            HashMap<Class<out DownloadService?>?, DownloadManagerHelper?>()

        fun buildAddDownloadIntent(
            context: Context?,
            clazz: Class<out DownloadService?>?,
            downloadRequest: DownloadRequest?,
            foreground: Boolean
        ): Intent {
            return buildAddDownloadIntent(
                context,
                clazz,
                downloadRequest,
                Download.STOP_REASON_NONE,
                foreground
            )
        }

        fun buildAddDownloadIntent(
            context: Context?,
            clazz: Class<out DownloadService?>?,
            downloadRequest: DownloadRequest?,
            stopReason: Int,
            foreground: Boolean
        ): Intent {
            return getIntent(context, clazz, ACTION_ADD_DOWNLOAD, foreground)
                .putExtra(KEY_DOWNLOAD_REQUEST, downloadRequest)
                .putExtra(KEY_STOP_REASON, stopReason)
        }

        fun buildRemoveDownloadIntent(
            context: Context?, clazz: Class<out DownloadService?>?, id: String?, foreground: Boolean
        ): Intent {
            return getIntent(context, clazz, ACTION_REMOVE_DOWNLOAD, foreground)
                .putExtra(KEY_CONTENT_ID, id)
        }

        fun buildRemoveAllDownloadsIntent(
            context: Context?, clazz: Class<out DownloadService?>?, foreground: Boolean
        ): Intent {
            return getIntent(context, clazz, ACTION_REMOVE_ALL_DOWNLOADS, foreground)
        }

        fun buildResumeDownloadsIntent(
            context: Context?, clazz: Class<out DownloadService?>?, foreground: Boolean
        ): Intent {
            return getIntent(context, clazz, ACTION_RESUME_DOWNLOADS, foreground)
        }

        fun buildPauseDownloadsIntent(
            context: Context?, clazz: Class<out DownloadService?>?, foreground: Boolean
        ): Intent {
            return getIntent(context, clazz, ACTION_PAUSE_DOWNLOADS, foreground)
        }

        fun buildSetStopReasonIntent(
            context: Context?,
            clazz: Class<out DownloadService?>?,
            id: String?,
            stopReason: Int,
            foreground: Boolean
        ): Intent {
            return getIntent(context, clazz, ACTION_SET_STOP_REASON, foreground)
                .putExtra(KEY_CONTENT_ID, id)
                .putExtra(KEY_STOP_REASON, stopReason)
        }

        fun buildSetRequirementsIntent(
            context: Context?,
            clazz: Class<out DownloadService?>?,
            requirements: Requirements?,
            foreground: Boolean
        ): Intent {
            return getIntent(context, clazz, ACTION_SET_REQUIREMENTS, foreground)
                .putExtra(KEY_REQUIREMENTS, requirements)
        }

        fun sendAddDownload(
            context: Context,
            clazz: Class<out DownloadService?>?,
            downloadRequest: DownloadRequest?,
            foreground: Boolean
        ) {
            val intent: Intent = buildAddDownloadIntent(context, clazz, downloadRequest, foreground)
            startService(context, intent, foreground)
        }

        fun sendAddDownload(
            context: Context,
            clazz: Class<out DownloadService?>?,
            downloadRequest: DownloadRequest?,
            stopReason: Int,
            foreground: Boolean
        ) {
            val intent: Intent =
                buildAddDownloadIntent(context, clazz, downloadRequest, stopReason, foreground)
            startService(context, intent, foreground)
        }

        fun sendRemoveDownload(
            context: Context, clazz: Class<out DownloadService?>?, id: String?, foreground: Boolean
        ) {
            val intent: Intent = buildRemoveDownloadIntent(context, clazz, id, foreground)
            startService(context, intent, foreground)
        }

        fun sendRemoveAllDownloads(
            context: Context, clazz: Class<out DownloadService?>?, foreground: Boolean
        ) {
            val intent: Intent = buildRemoveAllDownloadsIntent(context, clazz, foreground)
            startService(context, intent, foreground)
        }

        fun sendResumeDownloads(
            context: Context, clazz: Class<out DownloadService?>?, foreground: Boolean
        ) {
            val intent: Intent = buildResumeDownloadsIntent(context, clazz, foreground)
            startService(context, intent, foreground)
        }

        fun sendPauseDownloads(
            context: Context, clazz: Class<out DownloadService?>?, foreground: Boolean
        ) {
            val intent: Intent = buildPauseDownloadsIntent(context, clazz, foreground)
            startService(context, intent, foreground)
        }

        fun sendSetStopReason(
            context: Context,
            clazz: Class<out DownloadService?>?,
            id: String?,
            stopReason: Int,
            foreground: Boolean
        ) {
            val intent: Intent =
                buildSetStopReasonIntent(context, clazz, id, stopReason, foreground)
            startService(context, intent, foreground)
        }

        fun sendSetRequirements(
            context: Context,
            clazz: Class<out DownloadService?>?,
            requirements: Requirements?,
            foreground: Boolean
        ) {
            val intent: Intent =
                buildSetRequirementsIntent(context, clazz, requirements, foreground)
            startService(context, intent, foreground)
        }

        fun start(context: Context, clazz: Class<out DownloadService?>?) {
            context.startService(getIntent(context, clazz, ACTION_INIT))
        }

        fun startForeground(context: Context, clazz: Class<out DownloadService?>?) {
            val intent: Intent = getIntent(context, clazz, ACTION_INIT, true)
            startForegroundService(context, intent)
        }

        fun clearDownloadManagerHelpers() {
            downloadManagerHelpers.clear()
        }

        private fun needsStartedService(@Download.State state: @Download.State Int): Boolean {
            return state == Download.STATE_DOWNLOADING || state == Download.STATE_REMOVING || state == Download.STATE_RESTARTING
        }

        private fun getIntent(
            context: Context?,
            clazz: Class<out DownloadService?>?,
            action: String?,
            foreground: Boolean
        ): Intent {
            return getIntent(context, clazz, action).putExtra(KEY_FOREGROUND, foreground)
        }

        private fun getIntent(
            context: Context?, clazz: Class<out DownloadService?>?, action: String?
        ): Intent {
            return Intent(context, clazz).setAction(action)
        }

        private fun startService(context: Context, intent: Intent?, foreground: Boolean) {
            if (foreground) {
                startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
