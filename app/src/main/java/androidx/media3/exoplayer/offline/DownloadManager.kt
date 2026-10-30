package androidx.media3.exoplayer.offline

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import androidx.annotation.IntRange
import androidx.media3.common.C
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Util.closeQuietly
import androidx.media3.common.util.Util.createHandlerForCurrentOrMainLooper
import androidx.media3.common.util.Util.toLong
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Requirements.RequirementFlags
import androidx.media3.exoplayer.scheduler.RequirementsWatcher
import com.google.common.base.Preconditions
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import kotlin.Boolean
import kotlin.Comparator
import kotlin.Exception
import kotlin.Float
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.String
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.synchronized

class DownloadManager(
    context: Context,
    private val downloadIndex: WritableDownloadIndex,
    downloaderFactory: DownloaderFactory
) {
    interface Listener {
        fun onInitialized(downloadManager: DownloadManager) {}
        fun onDownloadsPausedChanged(
            downloadManager: DownloadManager, downloadsPaused: Boolean
        ) {
        }

        fun onDownloadChanged(
            downloadManager: DownloadManager, download: Download, finalException: Exception?
        ) {
        }

        fun onDownloadRemoved(downloadManager: DownloadManager?, download: Download?) {}
        fun onIdle(downloadManager: DownloadManager?) {}
        fun onRequirementsStateChanged(
            downloadManager: DownloadManager?,
            requirements: Requirements?,
            @RequirementFlags notMetRequirements: @RequirementFlags Int
        ) {
        }

        fun onWaitingForRequirementsChanged(
            downloadManager: DownloadManager, waitingForRequirements: Boolean
        ) {
        }
    }

    private val context = context.applicationContext
    private val applicationHandler: Handler =
        createHandlerForCurrentOrMainLooper { message: Message ->
            handleMainMessage(message)
        }
    private val internalHandler: InternalHandler
    private val requirementsListener: RequirementsWatcher.Listener
    private val listeners = CopyOnWriteArraySet<Listener>()

    private var pendingMessages = 1
    private var activeTaskCount = 0

    var isInitialized: Boolean = false
        private set
    private var downloadsPaused = true
    private var maxParallelDownloads = DEFAULT_MAX_PARALLEL_DOWNLOADS
    private var minRetryCount = DEFAULT_MIN_RETRY_COUNT

    @get:RequirementFlags
    var notMetRequirements: Int
        private set

    var isWaitingForRequirements: Boolean = false
        private set

    var currentDownloads = mutableListOf<Download?>()
        private set
    private var requirementsWatcher: RequirementsWatcher

    constructor(
        context: Context,
        databaseProvider: DatabaseProvider,
        cache: Cache,
        upstreamFactory: DataSource.Factory,
        executor: Executor
    ) : this(
        context,
        DefaultDownloadIndex(databaseProvider),
        DefaultDownloaderFactory(
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory),
            executor
        )
    )

    init {
        val internalThread = HandlerThread("ExoPlayer:DownloadManager")
        internalThread.start()
        internalHandler =
            InternalHandler(
                internalThread,
                downloadIndex,
                downloaderFactory,
                applicationHandler,
                maxParallelDownloads,
                minRetryCount,
                downloadsPaused
            )

        val requirementsListener =
            RequirementsWatcher.Listener { requirementsWatcher: RequirementsWatcher?, notMetRequirements: Int ->
                this.onRequirementsStateChanged(
                    requirementsWatcher!!,
                    notMetRequirements
                )
            }
        this.requirementsListener = requirementsListener
        requirementsWatcher =
            RequirementsWatcher(context, requirementsListener, DEFAULT_REQUIREMENTS)
        notMetRequirements = requirementsWatcher.start()

        internalHandler
            .obtainMessage(MSG_INITIALIZE, notMetRequirements, 0)
            .sendToTarget()
    }

    val applicationLooper: Looper
        get() = applicationHandler.looper

    val isIdle: Boolean
        get() = activeTaskCount == 0 && pendingMessages == 0

    fun addListener(listener: Listener?) {
        listeners.add(listener!!)
    }

    fun removeListener(listener: Listener?) {
        listeners.remove(listener)
    }

    var requirements: Requirements?
        get() = requirementsWatcher.requirements
        set(requirements) {
            if (requirements == requirementsWatcher.requirements) {
                return
            }
            requirementsWatcher.stop()
            requirementsWatcher = RequirementsWatcher(context, requirementsListener, requirements!!)
            val notMetRequirements = requirementsWatcher.start()
            onRequirementsStateChanged(requirementsWatcher, notMetRequirements)
        }

    fun getMaxParallelDownloads(): Int {
        return maxParallelDownloads
    }

    fun setMaxParallelDownloads(@IntRange(from = 1) maxParallelDownloads: Int) {
        Preconditions.checkArgument(maxParallelDownloads > 0)
        if (this.maxParallelDownloads == maxParallelDownloads) {
            return
        }
        this.maxParallelDownloads = maxParallelDownloads
        pendingMessages++
        internalHandler
            .obtainMessage(MSG_SET_MAX_PARALLEL_DOWNLOADS, maxParallelDownloads, 0)
            .sendToTarget()
    }

    fun getMinRetryCount(): Int {
        return minRetryCount
    }

    fun setMinRetryCount(minRetryCount: Int) {
        Preconditions.checkArgument(minRetryCount >= 0)
        if (this.minRetryCount == minRetryCount) {
            return
        }
        this.minRetryCount = minRetryCount
        pendingMessages++
        internalHandler
            .obtainMessage(MSG_SET_MIN_RETRY_COUNT, minRetryCount, 0)
            .sendToTarget()
    }

    fun getDownloadIndex(): DownloadIndex {
        return downloadIndex
    }

    fun getDownloadsPaused(): Boolean {
        return downloadsPaused
    }

    fun resumeDownloads() {
        setDownloadsPaused(false)
    }

    fun pauseDownloads() {
        setDownloadsPaused(true)
    }

    fun setStopReason(id: String?, stopReason: Int) {
        pendingMessages++
        internalHandler
            .obtainMessage(MSG_SET_STOP_REASON, stopReason, 0, id)
            .sendToTarget()
    }

    fun addDownload(
        request: DownloadRequest?,
        stopReason: Int = Download.STOP_REASON_NONE
    ) {
        pendingMessages++
        internalHandler
            .obtainMessage(MSG_ADD_DOWNLOAD, stopReason, 0, request)
            .sendToTarget()
    }

    fun removeDownload(id: String?) {
        pendingMessages++
        internalHandler.obtainMessage(MSG_REMOVE_DOWNLOAD, id).sendToTarget()
    }

    fun removeAllDownloads() {
        pendingMessages++
        internalHandler.obtainMessage(MSG_REMOVE_ALL_DOWNLOADS).sendToTarget()
    }

    fun release() {
        synchronized(internalHandler) {
            if (internalHandler.released) {
                return
            }
            internalHandler.sendEmptyMessage(MSG_RELEASE)
            var wasInterrupted = false
            while (!internalHandler.released) {
                try {
                    (internalHandler as Object).wait()
                } catch (e: InterruptedException) {
                    wasInterrupted = true
                }
            }
            if (wasInterrupted) {
                // Restore the interrupted status.
                Thread.currentThread().interrupt()
            }
            applicationHandler.removeCallbacksAndMessages(null)
            requirementsWatcher.stop()
            // Reset state.
            this.currentDownloads = mutableListOf()
            pendingMessages = 0
            activeTaskCount = 0
            this.isInitialized = false
            notMetRequirements = 0
            this.isWaitingForRequirements = false
        }
    }

    private fun setDownloadsPaused(downloadsPaused: Boolean) {
        if (this.downloadsPaused == downloadsPaused) {
            return
        }
        this.downloadsPaused = downloadsPaused
        pendingMessages++
        internalHandler
            .obtainMessage(MSG_SET_DOWNLOADS_PAUSED, if (downloadsPaused) 1 else 0, 0)
            .sendToTarget()
        val waitingForRequirementsChanged = updateWaitingForRequirements()
        for (listener in listeners) {
            listener.onDownloadsPausedChanged(this, downloadsPaused)
        }
        if (waitingForRequirementsChanged) {
            notifyWaitingForRequirementsChanged()
        }
    }

    private fun onRequirementsStateChanged(
        requirementsWatcher: RequirementsWatcher,
        @RequirementFlags notMetRequirements: @RequirementFlags Int
    ) {
        val requirements = requirementsWatcher.requirements
        if (this.notMetRequirements != notMetRequirements) {
            this.notMetRequirements = notMetRequirements
            pendingMessages++
            internalHandler
                .obtainMessage(MSG_SET_NOT_MET_REQUIREMENTS, notMetRequirements, 0)
                .sendToTarget()
        }
        val waitingForRequirementsChanged = updateWaitingForRequirements()
        for (listener in listeners) {
            listener.onRequirementsStateChanged(this, requirements, notMetRequirements)
        }
        if (waitingForRequirementsChanged) {
            notifyWaitingForRequirementsChanged()
        }
    }

    private fun updateWaitingForRequirements(): Boolean {
        var waitingForRequirements = false
        if (!downloadsPaused && notMetRequirements != 0) {
            for (i in currentDownloads.indices) {
                if (currentDownloads[i]!!.state == Download.STATE_QUEUED) {
                    waitingForRequirements = true
                    break
                }
            }
        }
        val waitingForRequirementsChanged = this.isWaitingForRequirements != waitingForRequirements
        this.isWaitingForRequirements = waitingForRequirements
        return waitingForRequirementsChanged
    }

    private fun notifyWaitingForRequirementsChanged() {
        for (listener in listeners) {
            listener.onWaitingForRequirementsChanged(this, this.isWaitingForRequirements)
        }
    }

    // Main thread message handling.
    private fun handleMainMessage(message: Message): Boolean {
        when (message.what) {
            MSG_INITIALIZED -> {
                val downloads = message.obj as MutableList<Download?>
                onInitialized(downloads)
            }

            MSG_DOWNLOAD_UPDATE -> {
                val update: DownloadUpdate = message.obj as DownloadUpdate
                onDownloadUpdate(update)
            }

            MSG_PROCESSED -> {
                val processedMessageCount = message.arg1
                val activeTaskCount = message.arg2
                onMessageProcessed(processedMessageCount, activeTaskCount)
            }

            else -> throw IllegalStateException()
        }
        return true
    }

    private fun onInitialized(downloads: MutableList<Download?>) {
        this.isInitialized = true
        this.currentDownloads = Collections.unmodifiableList<Download?>(downloads)
        val waitingForRequirementsChanged = updateWaitingForRequirements()
        for (listener in listeners) {
            listener.onInitialized(this@DownloadManager)
        }
        if (waitingForRequirementsChanged) {
            notifyWaitingForRequirementsChanged()
        }
    }

    private fun onDownloadUpdate(update: DownloadUpdate) {
        this.currentDownloads = Collections.unmodifiableList<Download?>(update.downloads)
        val updatedDownload = update.download!!
        val waitingForRequirementsChanged = updateWaitingForRequirements()
        if (update.isRemove) {
            for (listener in listeners) {
                listener.onDownloadRemoved(this, updatedDownload)
            }
        } else {
            for (listener in listeners) {
                listener.onDownloadChanged(this, updatedDownload, update.finalException)
            }
        }
        if (waitingForRequirementsChanged) {
            notifyWaitingForRequirementsChanged()
        }
    }

    private fun onMessageProcessed(processedMessageCount: Int, activeTaskCount: Int) {
        this.pendingMessages -= processedMessageCount
        this.activeTaskCount = activeTaskCount
        if (this.isIdle) {
            for (listener in listeners) {
                listener.onIdle(this)
            }
        }
    }

    private class InternalHandler(
        private val thread: HandlerThread,
        private val downloadIndex: WritableDownloadIndex,
        private val downloaderFactory: DownloaderFactory,
        private val mainHandler: Handler,
        private var maxParallelDownloads: Int,
        private var minRetryCount: Int,
        private var downloadsPaused: Boolean
    ) : Handler(thread.getLooper()) {
        var released: Boolean = false

        private val downloads = ArrayList<Download>()
        private val activeTasks = HashMap<String?, Task>()
        private var notMetRequirements: @RequirementFlags Int = 0
        private var activeDownloadTaskCount = 0
        private var hasActiveRemoveTask = false

        override fun handleMessage(message: Message) {
            var processedExternalMessage = true
            when (message.what) {
                MSG_INITIALIZE -> {
                    val notMetRequirements = message.arg1
                    initialize(notMetRequirements)
                }

                MSG_SET_DOWNLOADS_PAUSED -> {
                    val downloadsPaused = message.arg1 != 0
                    setDownloadsPaused(downloadsPaused)
                }

                MSG_SET_NOT_MET_REQUIREMENTS -> {
                    notMetRequirements = message.arg1
                    setNotMetRequirements(notMetRequirements)
                }

                MSG_SET_STOP_REASON -> {
                    val id = message.obj as String?
                    val stopReason = message.arg1
                    setStopReason(id, stopReason)
                }

                MSG_SET_MAX_PARALLEL_DOWNLOADS -> {
                    val maxParallelDownloads = message.arg1
                    setMaxParallelDownloads(maxParallelDownloads)
                }

                MSG_SET_MIN_RETRY_COUNT -> {
                    val minRetryCount = message.arg1
                    setMinRetryCount(minRetryCount)
                }

                MSG_ADD_DOWNLOAD -> {
                    val request = message.obj as DownloadRequest
                    val stopReason = message.arg1
                    addDownload(request, stopReason)
                }

                MSG_REMOVE_DOWNLOAD -> {
                    val id = message.obj as String?
                    removeDownload(id)
                }

                MSG_REMOVE_ALL_DOWNLOADS -> removeAllDownloads()
                MSG_TASK_STOPPED -> {
                    val task = message.obj as Task
                    onTaskStopped(task)
                    processedExternalMessage = false // This message is posted internally.
                }

                MSG_CONTENT_LENGTH_CHANGED -> {
                    val task = message.obj as Task
                    onContentLengthChanged(task, toLong(message.arg1, message.arg2))
                    return  // No need to post back to mainHandler.
                }

                MSG_UPDATE_PROGRESS -> {
                    updateProgress()
                    return  // No need to post back to mainHandler.
                }

                MSG_RELEASE -> {
                    release()
                    return  // No need to post back to mainHandler.
                }

                else -> throw IllegalStateException()
            }
            mainHandler
                .obtainMessage(
                    MSG_PROCESSED,
                    if (processedExternalMessage) 1 else 0,
                    activeTasks.size
                )
                .sendToTarget()
        }

        fun initialize(notMetRequirements: Int) {
            this.notMetRequirements = notMetRequirements
            var cursor: DownloadCursor? = null
            try {
                downloadIndex.setDownloadingStatesToQueued()
                cursor =
                    downloadIndex.getDownloads(
                        Download.STATE_QUEUED,
                        Download.STATE_STOPPED,
                        Download.STATE_DOWNLOADING,
                        Download.STATE_REMOVING,
                        Download.STATE_RESTARTING
                    )
                while (cursor.moveToNext()) {
                    downloads.add(cursor.download)
                }
            } catch (e: IOException) {
                e(TAG, "Failed to load index.", e)
                downloads.clear()
            } finally {
                closeQuietly(cursor)
            }
            // A copy must be used for the message to ensure that subsequent changes to the downloads list
            // are not visible to the main thread when it processes the message.
            val downloadsForMessage = ArrayList<Download?>(downloads)
            mainHandler.obtainMessage(MSG_INITIALIZED, downloadsForMessage).sendToTarget()
            syncTasks()
        }

        fun setDownloadsPaused(downloadsPaused: Boolean) {
            this.downloadsPaused = downloadsPaused
            syncTasks()
        }

        fun setNotMetRequirements(@RequirementFlags notMetRequirements: @RequirementFlags Int) {
            this.notMetRequirements = notMetRequirements
            syncTasks()
        }

        fun setStopReason(id: String?, stopReason: Int) {
            if (id == null) {
                for (i in downloads.indices) {
                    setStopReason(downloads[i], stopReason)
                }
                try {
                    // Set the stop reason for downloads in terminal states as well.
                    downloadIndex.setStopReason(stopReason)
                } catch (e: IOException) {
                    e(TAG, "Failed to set manual stop reason", e)
                }
            } else {
                val download = getDownload(id, false)
                if (download != null) {
                    setStopReason(download, stopReason)
                } else {
                    try {
                        // Set the stop reason if the download is in a terminal state.
                        downloadIndex.setStopReason(id, stopReason)
                    } catch (e: IOException) {
                        e(TAG, "Failed to set manual stop reason: " + id, e)
                    }
                }
            }
            syncTasks()
        }

        fun setStopReason(download: Download, stopReason: Int) {
            if (stopReason == Download.STOP_REASON_NONE) {
                if (download.state == Download.STATE_STOPPED) {
                    putDownloadWithState(
                        download,
                        Download.STATE_QUEUED,
                        Download.STOP_REASON_NONE
                    )
                }
            } else if (stopReason != download.stopReason) {
                @Download.State var state: @Download.State Int = download.state
                if (state == Download.STATE_QUEUED || state == Download.STATE_DOWNLOADING) {
                    state = Download.STATE_STOPPED
                }
                putDownload(
                    Download(
                        download.request,
                        state,
                        download.startTimeMs,
                        System.currentTimeMillis(),
                        download.contentLength,
                        stopReason,
                        Download.FAILURE_REASON_NONE,
                        download.progress
                    )
                )
            }
        }

        fun setMaxParallelDownloads(maxParallelDownloads: Int) {
            this.maxParallelDownloads = maxParallelDownloads
            syncTasks()
        }

        fun setMinRetryCount(minRetryCount: Int) {
            this.minRetryCount = minRetryCount
        }

        fun addDownload(request: DownloadRequest, stopReason: Int) {
            val download = getDownload(request.id, true)
            val nowMs = System.currentTimeMillis()
            if (download != null) {
                putDownload(mergeRequest(download, request, stopReason, nowMs))
            } else {
                putDownload(
                    Download(
                        request,
                        if (stopReason != Download.STOP_REASON_NONE) Download.STATE_STOPPED else Download.STATE_QUEUED,
                        nowMs,
                        nowMs,
                        C.LENGTH_UNSET.toLong(),
                        stopReason,
                        Download.FAILURE_REASON_NONE
                    )
                )
            }
            syncTasks()
        }

        fun removeDownload(id: String?) {
            val download = getDownload(id, true)
            if (download == null) {
                e(TAG, "Failed to remove nonexistent download: $id")
                return
            }
            putDownloadWithState(
                download,
                Download.STATE_REMOVING,
                Download.STOP_REASON_NONE
            )
            syncTasks()
        }

        fun removeAllDownloads() {
            val terminalDownloads = mutableListOf<Download?>()
            try {
                downloadIndex.getDownloads(
                    Download.STATE_COMPLETED,
                    Download.STATE_FAILED
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        terminalDownloads.add(cursor.download)
                    }
                }
            } catch (e: IOException) {
                e(TAG, "Failed to load downloads.")
            }
            for (i in downloads.indices) {
                downloads[i] = copyDownloadWithState(
                    downloads[i],
                    Download.STATE_REMOVING,
                    Download.STOP_REASON_NONE
                )
            }
            for (i in terminalDownloads.indices) {
                downloads.add(
                    copyDownloadWithState(
                        terminalDownloads[i]!!,
                        Download.STATE_REMOVING,
                        Download.STOP_REASON_NONE
                    )
                )
            }
            Collections.sort<Download?>(
                downloads,
                Comparator { first: Download?, second: Download? ->
                    compareStartTimes(first!!, second!!)
                })
            try {
                downloadIndex.setStatesToRemoving()
            } catch (e: IOException) {
                e(TAG, "Failed to update index.", e)
            }
            val updateList = ArrayList<Download?>(downloads)
            for (i in downloads.indices) {
                val update =
                    DownloadUpdate(
                        downloads[i],
                        false,
                        updateList,
                        null
                    )
                mainHandler.obtainMessage(MSG_DOWNLOAD_UPDATE, update).sendToTarget()
            }
            syncTasks()
        }

        fun release() {
            for (task in activeTasks.values) {
                task.cancel(true)
            }
            try {
                downloadIndex.setDownloadingStatesToQueued()
            } catch (e: IOException) {
                e(TAG, "Failed to update index.", e)
            }
            downloads.clear()
            thread.quit()
            synchronized(this) {
                released = true
                (this as Object).notifyAll()
            }
        }

        // Start and cancel tasks based on the current download and manager states.
        fun syncTasks() {
            var accumulatingDownloadTaskCount = 0
            for (i in downloads.indices) {
                val download = downloads[i]
                var activeTask = activeTasks[download.request.id]
                when (download.state) {
                    Download.STATE_STOPPED -> syncStoppedDownload(activeTask)
                    Download.STATE_QUEUED -> activeTask =
                        syncQueuedDownload(activeTask, download)

                    Download.STATE_DOWNLOADING -> {
                        syncDownloadingDownload(
                            activeTask!!,
                            download,
                            accumulatingDownloadTaskCount
                        )
                    }

                    Download.STATE_REMOVING, Download.STATE_RESTARTING -> syncRemovingDownload(
                        activeTask,
                        download
                    )

                    Download.STATE_COMPLETED, Download.STATE_FAILED -> throw IllegalStateException()
                    else -> throw IllegalStateException()
                }
                if (activeTask != null && !activeTask.isRemove) {
                    accumulatingDownloadTaskCount++
                }
            }
        }

        fun syncStoppedDownload(activeTask: Task?) {
            if (activeTask != null) {
                // We have a task, which must be a download task. Cancel it.
                Preconditions.checkState(!activeTask.isRemove)
                activeTask.cancel(false)
            }
        }

        fun syncQueuedDownload(activeTask: Task?, download: Download): Task? {
            var activeTask = activeTask
            var download = download
            if (activeTask != null) {
                // We have a task, which must be a download task. If the download state is queued we need to
                // cancel it and start a new one, since a new request has been merged into the download.
                Preconditions.checkState(!activeTask.isRemove)
                activeTask.cancel(false)
                return activeTask
            }

            if (!canDownloadsRun() || activeDownloadTaskCount >= maxParallelDownloads) {
                return null
            }

            // We can start a download task.
            download = putDownloadWithState(
                download,
                Download.STATE_DOWNLOADING,
                Download.STOP_REASON_NONE
            )
            val downloader = downloaderFactory.createDownloader(download.request)
            activeTask =
                Task(
                    download.request,
                    downloader,
                    download.progress,
                    false,
                    minRetryCount,
                    this
                )
            activeTasks[download.request.id] = activeTask
            if (activeDownloadTaskCount++ == 0) {
                sendEmptyMessageDelayed(MSG_UPDATE_PROGRESS, UPDATE_PROGRESS_INTERVAL_MS.toLong())
            }
            activeTask.start()
            return activeTask
        }

        fun syncDownloadingDownload(
            activeTask: Task, download: Download, accumulatingDownloadTaskCount: Int
        ) {
            Preconditions.checkState(!activeTask.isRemove)
            if (!canDownloadsRun() || accumulatingDownloadTaskCount >= maxParallelDownloads) {
                putDownloadWithState(
                    download,
                    Download.STATE_QUEUED,
                    Download.STOP_REASON_NONE
                )
                activeTask.cancel(false)
            }
        }

        fun syncRemovingDownload(activeTask: Task?, download: Download) {
            var activeTask = activeTask
            if (activeTask != null) {
                if (!activeTask.isRemove) {
                    // Cancel the downloading task.
                    activeTask.cancel(false)
                }
                // The activeTask is either a remove task, or a downloading task that we just canceled. In
                // the latter case we need to wait for the task to stop before we start a remove task.
                return
            }

            if (hasActiveRemoveTask) {
                return
            }

            // We can start a remove task.
            val downloader = downloaderFactory.createDownloader(download.request)
            activeTask =
                Task(
                    download.request,
                    downloader,
                    download.progress,
                    true,
                    minRetryCount,
                    this
                )
            activeTasks[download.request.id] = activeTask
            hasActiveRemoveTask = true
            activeTask.start()
        }

        // Task event processing.
        fun onContentLengthChanged(task: Task, contentLength: Long) {
            val downloadId = task.request.id
            val download = getDownload(
                downloadId,
                false
            )!!
            if (contentLength == download.contentLength || contentLength == C.LENGTH_UNSET.toLong()) {
                return
            }
            putDownload(
                Download(
                    download.request,
                    download.state,
                    download.startTimeMs,
                    System.currentTimeMillis(),
                    contentLength,
                    download.stopReason,
                    download.failureReason,
                    download.progress
                )
            )
        }

        fun onTaskStopped(task: Task) {
            val downloadId = task.request.id
            activeTasks.remove(downloadId)

            val isRemove = task.isRemove
            if (isRemove) {
                hasActiveRemoveTask = false
            } else if (--activeDownloadTaskCount == 0) {
                removeMessages(MSG_UPDATE_PROGRESS)
            }

            if (task.isCanceled) {
                syncTasks()
                return
            }

            val finalException = task.finalException
            if (finalException != null) {
                e(TAG, "Task failed: " + task.request + ", " + isRemove, finalException)
            }

            val download = getDownload(
                downloadId,
                false
            )!!
            when (download.state) {
                Download.STATE_DOWNLOADING -> {
                    Preconditions.checkState(!isRemove)
                    onDownloadTaskStopped(download, finalException)
                }

                Download.STATE_REMOVING, Download.STATE_RESTARTING -> {
                    Preconditions.checkState(isRemove)
                    onRemoveTaskStopped(download)
                }

                Download.STATE_QUEUED, Download.STATE_STOPPED, Download.STATE_COMPLETED, Download.STATE_FAILED -> throw IllegalStateException()
                else -> throw IllegalStateException()
            }

            syncTasks()
        }

        fun onDownloadTaskStopped(download: Download, finalException: Exception?) {
            var download = download
            download =
                Download(
                    download.request,
                    if (finalException == null) Download.STATE_COMPLETED else Download.STATE_FAILED,
                    download.startTimeMs,
                    System.currentTimeMillis(),
                    download.contentLength,
                    download.stopReason,
                    if (finalException == null) Download.FAILURE_REASON_NONE else Download.FAILURE_REASON_UNKNOWN,
                    download.progress
                )
            // The download is now in a terminal state, so should not be in the downloads list.
            downloads.removeAt(getDownloadIndex(download.request.id))
            // We still need to update the download index and main thread.
            try {
                downloadIndex.putDownload(download)
            } catch (e: IOException) {
                e(TAG, "Failed to update index.", e)
            }
            val update =
                DownloadUpdate(
                    download, false, ArrayList<Download?>(downloads), finalException
                )
            mainHandler.obtainMessage(MSG_DOWNLOAD_UPDATE, update).sendToTarget()
        }

        fun onRemoveTaskStopped(download: Download) {
            if (download.state == Download.STATE_RESTARTING) {
                @Download.State val state: @Download.State Int =
                    if (download.stopReason == Download.STOP_REASON_NONE) Download.STATE_QUEUED else Download.STATE_STOPPED
                putDownloadWithState(download, state, download.stopReason)
                syncTasks()
            } else {
                val removeIndex = getDownloadIndex(download.request.id)
                downloads.removeAt(removeIndex)
                try {
                    downloadIndex.removeDownload(download.request.id)
                } catch (e: IOException) {
                    e(TAG, "Failed to remove from database")
                }
                val update =
                    DownloadUpdate(
                        download,
                        true,
                        ArrayList<Download?>(downloads),
                        null
                    )
                mainHandler.obtainMessage(MSG_DOWNLOAD_UPDATE, update).sendToTarget()
            }
        }

        // Progress updates.
        fun updateProgress() {
            for (i in downloads.indices) {
                val download = downloads[i]
                if (download.state == Download.STATE_DOWNLOADING) {
                    try {
                        downloadIndex.putDownload(download)
                    } catch (e: IOException) {
                        e(TAG, "Failed to update index.", e)
                    }
                }
            }
            sendEmptyMessageDelayed(MSG_UPDATE_PROGRESS, UPDATE_PROGRESS_INTERVAL_MS.toLong())
        }

        // Helper methods.
        fun canDownloadsRun(): Boolean {
            return !downloadsPaused && notMetRequirements == 0
        }

        fun putDownloadWithState(
            download: Download, @Download.State state: @Download.State Int, stopReason: Int
        ): Download {
            // Downloads in terminal states shouldn't be in the downloads list.
            Preconditions.checkState(state != Download.STATE_COMPLETED && state != Download.STATE_FAILED)
            return putDownload(copyDownloadWithState(download, state, stopReason))
        }

        fun putDownload(download: Download): Download {
            // Downloads in terminal states shouldn't be in the downloads list.
            Preconditions.checkState(download.state != Download.STATE_COMPLETED && download.state != Download.STATE_FAILED)
            val changedIndex = getDownloadIndex(download.request.id)
            if (changedIndex == C.INDEX_UNSET) {
                downloads.add(download)
                Collections.sort<Download?>(
                    downloads,
                    Comparator { first: Download?, second: Download? ->
                        compareStartTimes(
                            first!!,
                            second!!
                        )
                    })
            } else {
                val needsSort = download.startTimeMs != downloads.get(changedIndex).startTimeMs
                downloads.set(changedIndex, download)
                if (needsSort) {
                    Collections.sort<Download?>(
                        downloads,
                        Comparator { first: Download?, second: Download? ->
                            compareStartTimes(
                                first!!,
                                second!!
                            )
                        })
                }
            }
            try {
                downloadIndex.putDownload(download)
            } catch (e: IOException) {
                e(TAG, "Failed to update index.", e)
            }
            val update =
                DownloadUpdate(
                    download,
                    false,
                    ArrayList(downloads),
                    null
                )
            mainHandler.obtainMessage(MSG_DOWNLOAD_UPDATE, update).sendToTarget()
            return download
        }

        fun getDownload(id: String?, loadFromIndex: Boolean): Download? {
            val index = getDownloadIndex(id)
            if (index != C.INDEX_UNSET) {
                return downloads[index]
            }
            if (loadFromIndex) {
                try {
                    return downloadIndex.getDownload(id)
                } catch (e: IOException) {
                    e(TAG, "Failed to load download: $id", e)
                }
            }
            return null
        }

        fun getDownloadIndex(id: String?): Int {
            for (i in downloads.indices) {
                val download = downloads[i]
                if (download.request.id == id) {
                    return i
                }
            }
            return C.INDEX_UNSET
        }

        companion object {
            private const val UPDATE_PROGRESS_INTERVAL_MS = 5000

            private fun copyDownloadWithState(
                download: Download, @Download.State state: @Download.State Int, stopReason: Int
            ): Download {
                return Download(
                    download.request,
                    state,
                    download.startTimeMs,
                    System.currentTimeMillis(),
                    download.contentLength,
                    stopReason,
                    Download.FAILURE_REASON_NONE,
                    download.progress
                )
            }

            private fun compareStartTimes(first: Download, second: Download): Int {
                return first.startTimeMs.compareTo(second.startTimeMs)
            }
        }
    }

    private class Task(
        val request: DownloadRequest,
        val downloader: Downloader,
        val downloadProgress: DownloadProgress,
        val isRemove: Boolean,
        val minRetryCount: Int,
        @field:Volatile var internalHandler: InternalHandler?
    ) : Thread(), Downloader.ProgressListener {
        @Volatile
        var isCanceled = false
        var finalException: Exception? = null
        var contentLength= C.LENGTH_UNSET.toLong()

        fun cancel(released: Boolean) {
            if (released) {
                // Download threads are GC roots for as long as they're running. The time taken for
                // cancellation to complete depends on the implementation of the downloader being used. We
                // null the handler reference here so that it doesn't prevent garbage collection of the
                // download manager whilst cancellation is ongoing.
                internalHandler = null
            }
            if (!isCanceled) {
                isCanceled = true
                downloader.cancel()
                interrupt()
            }
        }

        // Methods running on download thread.
        override fun run() {
            try {
                if (isRemove) {
                    downloader.remove()
                } else {
                    var errorCount = 0
                    var errorPosition = C.LENGTH_UNSET.toLong()
                    while (!isCanceled) {
                        try {
                            downloader.download(this)
                            break
                        } catch (e: IOException) {
                            if (!isCanceled) {
                                val bytesDownloaded = downloadProgress.bytesDownloaded
                                if (bytesDownloaded != errorPosition) {
                                    errorPosition = bytesDownloaded
                                    errorCount = 0
                                }
                                if (++errorCount > minRetryCount) {
                                    throw e
                                }
                                sleep(getRetryDelayMillis(errorCount).toLong())
                            }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                currentThread().interrupt()
            } catch (e: Exception) {
                finalException = e
            }
            val internalHandler: Handler? = this.internalHandler
            if (internalHandler != null) {
                internalHandler.obtainMessage(MSG_TASK_STOPPED, this).sendToTarget()
            }
        }

        override fun onProgress(
            contentLength: Long,
            bytesDownloaded: Long,
            percentDownloaded: Float
        ) {
            downloadProgress.bytesDownloaded = bytesDownloaded
            downloadProgress.percentDownloaded = percentDownloaded
            if (contentLength != this.contentLength) {
                this.contentLength = contentLength
                val internalHandler: Handler? = this.internalHandler
                internalHandler?.obtainMessage(
                    MSG_CONTENT_LENGTH_CHANGED,
                    (contentLength shr 32).toInt(),
                    contentLength.toInt(),
                    this
                )?.sendToTarget()
            }
        }

        companion object {
            private fun getRetryDelayMillis(errorCount: Int): Int {
                return min((errorCount - 1) * 1000, 5000)
            }
        }
    }

    private class DownloadUpdate(
        val download: Download?,
        val isRemove: Boolean,
        val downloads: MutableList<Download?>,
        val finalException: Exception?
    )

    companion object {
        const val DEFAULT_MAX_PARALLEL_DOWNLOADS: Int = 3
        const val DEFAULT_MIN_RETRY_COUNT: Int = 5
        val DEFAULT_REQUIREMENTS: Requirements = Requirements(Requirements.NETWORK)

        // Messages posted to the main handler.
        private const val MSG_INITIALIZED = 1
        private const val MSG_PROCESSED = 2
        private const val MSG_DOWNLOAD_UPDATE = 3

        // Messages posted to the background handler.
        private const val MSG_INITIALIZE = 1
        private const val MSG_SET_DOWNLOADS_PAUSED = 2
        private const val MSG_SET_NOT_MET_REQUIREMENTS = 3
        private const val MSG_SET_STOP_REASON = 4
        private const val MSG_SET_MAX_PARALLEL_DOWNLOADS = 5
        private const val MSG_SET_MIN_RETRY_COUNT = 6
        private const val MSG_ADD_DOWNLOAD = 7
        private const val MSG_REMOVE_DOWNLOAD = 8
        private const val MSG_REMOVE_ALL_DOWNLOADS = 9
        private const val MSG_TASK_STOPPED = 10
        private const val MSG_CONTENT_LENGTH_CHANGED = 11
        private const val MSG_UPDATE_PROGRESS = 12
        private const val MSG_RELEASE = 13
        private const val TAG = "DownloadManager"

        fun mergeRequest(
            download: Download, request: DownloadRequest, stopReason: Int, nowMs: kotlin.Long
        ): Download {
            @Download.State var state: @Download.State Int = download.state
            // Treat the merge as creating a new download if we're currently removing the existing one, or
            // if the existing download is in a terminal state. Else treat the merge as updating the
            // existing download.
            val startTimeMs =
                if (state == Download.STATE_REMOVING || download.isTerminalState) nowMs else download.startTimeMs
            state = if (state == Download.STATE_REMOVING || state == Download.STATE_RESTARTING) {
                Download.STATE_RESTARTING
            } else if (stopReason != Download.STOP_REASON_NONE) {
                Download.STATE_STOPPED
            } else {
                Download.STATE_QUEUED
            }
            return Download(
                download.request.copyWithMergedRequest(request),
                state,
                startTimeMs,
                nowMs,
                C.LENGTH_UNSET.toLong(),
                stopReason,
                Download.FAILURE_REASON_NONE
            )
        }
    }
}
