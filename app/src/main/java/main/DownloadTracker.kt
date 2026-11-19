package main

import android.content.Context
import android.content.DialogInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.DrmConfiguration
import androidx.media3.common.MediaItem.LocalConfiguration
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.Log.d
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.getUtf8Bytes
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DrmSession.DrmSessionException
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadHelper.LiveContentUnsupportedException
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.example.plr.R
import com.google.common.base.Preconditions
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Tracks media that has been downloaded.  */
class DownloadTracker(
    context: Context,
    private val dataSourceFactory: DataSource.Factory?,
    downloadManager: DownloadManager
) {
    /** Listens for changes in the tracked downloads.  */
    interface Listener {
        /** Called when the tracked downloads changed.  */
        fun onDownloadsChanged()
    }

    private val context: Context?
    private val downloadHelperFactory: DownloadHelper.Factory
    private val listeners: CopyOnWriteArraySet<Listener>
    private val downloads: HashMap<Uri?, Download?>
    private val downloadIndex: DownloadIndex

    private var startDownloadDialogHelper: StartDownloadDialogHelper? = null

    init {
        this.context = context.getApplicationContext()
        this.downloadHelperFactory =
            DownloadHelper.Factory().setDataSourceFactory(
                dataSourceFactory
            )
        listeners = CopyOnWriteArraySet<Listener>()
        downloads = HashMap<Uri?, Download?>()
        downloadIndex = downloadManager.getDownloadIndex()
        downloadManager.addListener(DownloadManagerListener())
        loadDownloads()
    }

    fun addListener(listener: Listener?) {
        listeners.add(Preconditions.checkNotNull<Listener?>(listener))
    }

    fun removeListener(listener: Listener?) {
        listeners.remove(listener)
    }

    fun isDownloaded(mediaItem: MediaItem): Boolean {
        val download =
            downloads.get(Preconditions.checkNotNull<LocalConfiguration?>(mediaItem.localConfiguration).uri)
        return download != null && download.state != Download.STATE_FAILED
    }

    fun getDownloadRequest(uri: Uri?): DownloadRequest? {
        val download = downloads.get(uri)
        return if (download != null && download.state != Download.STATE_FAILED) download.request else null
    }

    fun toggleDownload(
        fragmentManager: FragmentManager, mediaItem: MediaItem, renderersFactory: RenderersFactory?
    ) {
        val download =
            downloads.get(Preconditions.checkNotNull<LocalConfiguration?>(mediaItem.localConfiguration).uri)
        if (download != null && download.state != Download.STATE_FAILED) {
            DownloadService.sendRemoveDownload(
                context!!,
                DemoDownloadService::class.java,
                download.request.id,  /* foreground= */
                false
            )
        } else {
            if (startDownloadDialogHelper != null) {
                startDownloadDialogHelper!!.release()
            }
            startDownloadDialogHelper =
                StartDownloadDialogHelper(
                    fragmentManager,
                    downloadHelperFactory.setRenderersFactory(renderersFactory).create(mediaItem),
                    mediaItem
                )
        }
    }

    private fun loadDownloads() {
        try {
            downloadIndex.getDownloads().use { loadedDownloads ->
                while (loadedDownloads.moveToNext()) {
                    val download = loadedDownloads.download
                    downloads.put(download.request.uri, download)
                }
            }
        } catch (e: IOException) {
            w(TAG, "Failed to query downloads", e)
        }
    }

    private inner class DownloadManagerListener : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager, download: Download, finalException: Exception?
        ) {
            downloads.put(download.request.uri, download)
            for (listener in listeners) {
                listener.onDownloadsChanged()
            }
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            downloads.remove(download.request.uri)
            for (listener in listeners) {
                listener.onDownloadsChanged()
            }
        }
    }

    private inner class StartDownloadDialogHelper
        (
        private val fragmentManager: FragmentManager,
        private val downloadHelper: DownloadHelper,
        private val mediaItem: MediaItem
    ) : DownloadHelper.Callback, TrackSelectionDialog.TrackSelectionListener,
        DialogInterface.OnDismissListener {
        private var tracksInfoAvailable = false
        private var trackSelectionDialog: TrackSelectionDialog? = null
        private var widevineOfflineLicenseFetchTask: WidevineOfflineLicenseFetchTask? = null
        private var keySetId: ByteArray? = null

        init {
            downloadHelper.prepare(this)
        }

        fun release() {
            downloadHelper.release()
            if (trackSelectionDialog != null) {
                trackSelectionDialog!!.dismiss()
            }
            if (widevineOfflineLicenseFetchTask != null) {
                widevineOfflineLicenseFetchTask!!.cancel()
            }
        }

        // DownloadHelper.Callback implementation.
        override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
            this.tracksInfoAvailable = tracksInfoAvailable
            val format = getFirstFormatWithDrmInitData(helper)
            if (format == null) {
                onDownloadPrepared(helper)
                return
            }

            // The content is DRM protected. We need to acquire an offline license.

            // TODO(internal b/163107948): Support cases where DrmInitData are not in the manifest.
            if (!hasNonNullWidevineSchemaData(format.drmInitData!!)) {
                Toast.makeText(
                    context,
                    R.string.download_start_error_offline_license,
                    Toast.LENGTH_LONG
                )
                    .show()
                e(
                    TAG,
                    "Downloading content where DRM scheme data is not located in the manifest is not"
                            + " supported"
                )
                return
            }
            widevineOfflineLicenseFetchTask =
                WidevineOfflineLicenseFetchTask(
                    format,
                    mediaItem.localConfiguration!!.drmConfiguration!!,
                    dataSourceFactory,  /* dialogHelper= */
                    this,
                    helper
                )
            widevineOfflineLicenseFetchTask!!.execute()
        }

        override fun onPrepareError(helper: DownloadHelper, e: IOException) {
            val isLiveContent = e is LiveContentUnsupportedException
            val toastStringId =
                if (isLiveContent) R.string.download_live_unsupported else R.string.download_start_error
            val logMessage =
                if (isLiveContent) "Downloading live content unsupported" else "Failed to start download"
            Toast.makeText(context, toastStringId, Toast.LENGTH_LONG).show()
            e(TAG, logMessage, e)
        }

        // TrackSelectionListener implementation.
        override fun onTracksSelected(trackSelectionParameters: TrackSelectionParameters) {
            Preconditions.checkState(tracksInfoAvailable)
            for (periodIndex in 0..<downloadHelper.periodCount) {
                downloadHelper.clearTrackSelections(periodIndex)
                downloadHelper.addTrackSelection(periodIndex, trackSelectionParameters)
            }
            val downloadRequest = buildDownloadRequest()
            if (downloadRequest.streamKeys.isEmpty()) {
                // All tracks were deselected in the dialog. Don't start the download.
                return
            }
            startDownload(downloadRequest)
        }

        // DialogInterface.OnDismissListener implementation.
        override fun onDismiss(dialogInterface: DialogInterface?) {
            trackSelectionDialog = null
            downloadHelper.release()
        }

        // Internal methods.
        /**
         * Returns the first [Format] with a non-null [Format.drmInitData] found in the
         * content's tracks, or null if none is found.
         */
        fun getFirstFormatWithDrmInitData(helper: DownloadHelper): Format? {
            if (!tracksInfoAvailable) {
                return null
            }
            for (periodIndex in 0..<helper.periodCount) {
                val mappedTrackInfo = helper.getMappedTrackInfo(periodIndex)
                for (rendererIndex in 0..<mappedTrackInfo.rendererCount) {
                    val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                    for (trackGroupIndex in 0..<trackGroups.length) {
                        val trackGroup = trackGroups.get(trackGroupIndex)
                        for (formatIndex in 0..<trackGroup.length) {
                            val format = trackGroup.getFormat(formatIndex)
                            if (format.drmInitData != null) {
                                return format
                            }
                        }
                    }
                }
            }
            return null
        }

        fun onOfflineLicenseFetched(helper: DownloadHelper, keySetId: ByteArray?) {
            this.keySetId = keySetId
            onDownloadPrepared(helper)
        }

        fun onOfflineLicenseFetchedError(e: DrmSessionException?) {
            Toast.makeText(
                context,
                R.string.download_start_error_offline_license,
                Toast.LENGTH_LONG
            )
                .show()
            e(TAG, "Failed to fetch offline DRM license", e)
        }

        fun onDownloadPrepared(helper: DownloadHelper) {
            if (helper.periodCount == 0) {
                d(TAG, "No periods found. Downloading entire stream.")
                startDownload()
                downloadHelper.release()
                return
            }

            if (!tracksInfoAvailable) {
                d(TAG, "Tracks info is unavailable. Downloading entire stream.")
                startDownload()
                downloadHelper.release()
                return
            }

            val tracks = downloadHelper.getTracks( /* periodIndex= */0)
            if (!TrackSelectionDialog.willHaveContent(tracks)) {
                d(TAG, "No dialog content. Downloading entire stream.")
                startDownload()
                downloadHelper.release()
                return
            }
            trackSelectionDialog =
                TrackSelectionDialog.createForTracksAndParameters( /* titleId= */
                    R.string.exo_download_description,
                    tracks,
                    DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS,  /* allowAdaptiveSelections= */
                    false,  /* allowMultipleOverrides= */
                    true,  /* onTracksSelectedListener= */
                    this,  /* onDismissListener= */
                    this
                )
            trackSelectionDialog!!.show(fragmentManager,  /* tag= */null)
        }

        /**
         * Returns whether any [DrmInitData.SchemeData] that [ ][DrmInitData.SchemeData.matches] [C.WIDEVINE_UUID] has non-null [ ][DrmInitData.SchemeData.data].
         */
        fun hasNonNullWidevineSchemaData(drmInitData: DrmInitData): Boolean {
            for (i in 0..<drmInitData.schemeDataCount) {
                val schemeData = drmInitData.get(i)
                if (schemeData.matches(C.WIDEVINE_UUID) && schemeData.hasData()) {
                    return true
                }
            }
            return false
        }

        fun startDownload(downloadRequest: DownloadRequest? = buildDownloadRequest()) {
            DownloadService.sendAddDownload(
                context!!, DemoDownloadService::class.java, downloadRequest,  /* foreground= */false
            )
        }

        fun buildDownloadRequest(): DownloadRequest {
            return downloadHelper
                .getDownloadRequest(
                    getUtf8Bytes(Preconditions.checkNotNull<String?>(mediaItem.mediaMetadata.title.toString()))
                )
                .copyWithKeySetId(keySetId)
        }
    }

    /** Downloads a Widevine offline license in a background thread.  */
    private class WidevineOfflineLicenseFetchTask(
        format: Format,
        drmConfiguration: DrmConfiguration,
        dataSourceFactory: DataSource.Factory?,
        dialogHelper: StartDownloadDialogHelper,
        downloadHelper: DownloadHelper
    ) {
        private val format: Format
        private val drmConfiguration: DrmConfiguration
        private val dataSourceFactory: DataSource.Factory?
        private val dialogHelper: StartDownloadDialogHelper
        private val downloadHelper: DownloadHelper
        private val executorService: ExecutorService

        var future: Future<*>? = null
        private var keySetId: ByteArray?=null
        private var drmSessionException: DrmSessionException? = null

        init {
            Preconditions.checkState(drmConfiguration.scheme == C.WIDEVINE_UUID)
            this.executorService = Executors.newSingleThreadExecutor()
            this.format = format
            this.drmConfiguration = drmConfiguration
            this.dataSourceFactory = dataSourceFactory
            this.dialogHelper = dialogHelper
            this.downloadHelper = downloadHelper
        }

        fun cancel() {
            if (future != null) {
                future!!.cancel( /* mayInterruptIfRunning= */false)
            }
        }

        fun execute() {
            future =
                executorService.submit(
                    Runnable {
                        val offlineLicenseHelper =
                            OfflineLicenseHelper.newWidevineInstance(
                                drmConfiguration,
                                dataSourceFactory!!,
                                DrmSessionEventListener.EventDispatcher()
                            )
                        try {
                            keySetId = offlineLicenseHelper.downloadLicense(format)
                        } catch (e: DrmSessionException) {
                            drmSessionException = e
                        } finally {
                            offlineLicenseHelper.release()
                            Handler(Looper.getMainLooper())
                                .post(
                                    Runnable {
                                        if (drmSessionException != null) {
                                            dialogHelper.onOfflineLicenseFetchedError(
                                                drmSessionException
                                            )
                                        } else {
                                            dialogHelper.onOfflineLicenseFetched(
                                                downloadHelper,
                                                Preconditions.checkNotNull<ByteArray?>(keySetId)
                                            )
                                        }
                                    })
                        }
                    })
        }
    }

    companion object {
        private const val TAG = "DownloadTracker"
    }
}
