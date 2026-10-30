package androidx.media3.exoplayer.upstream.contentsteering

import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.util.Clock
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.common.util.Log.w

import androidx.media3.common.util.UriUtil
import androidx.media3.common.util.Util.currentOrMainLooper
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.exoplayer.upstream.Loader.LoadErrorAction
import androidx.media3.exoplayer.upstream.ParsingLoadable
import androidx.media3.exoplayer.util.ReleasableExecutor
import com.google.common.base.Preconditions
import com.google.common.base.Supplier
import java.io.IOException
import java.util.TreeMap

class SteeringManifestTracker internal constructor(
    private val dataSourceFactory: DataSource.Factory,
    private val downloadExecutorSupplier: Supplier<ReleasableExecutor?>?,
    private val clock: Clock
) {
    interface Callback {
        val steeringQueryParameters: Map<String?, String?>
        fun onSteeringManifestUpdated(steeringManifest: SteeringManifest?)
        fun onSteeringManifestLoadError(error: IOException?, canceled: Boolean)
    }

    private val steeringManifestLoaderCallback: SteeringManifestLoaderCallback
    private val handler: HandlerWrapper?

    private var steeringManifestUrl: Uri? = null
    private var callback: Callback? = null
    private var eventDispatcher: MediaSourceEventListener.EventDispatcher? = null
    private var steeringManifest: SteeringManifest? = null
    private var steeringManifestReloadHandler: HandlerWrapper? = null
    private var steeringManifestLoader: Loader? = null
    private var hasStarted = false

    constructor(
        dataSourceFactory: DataSource.Factory,
        downloadExecutorSupplier: Supplier<ReleasableExecutor?>?
    ) : this(dataSourceFactory, downloadExecutorSupplier, Clock.DEFAULT)

    init {
        this.steeringManifestLoaderCallback = SteeringManifestLoaderCallback()
        this.handler = clock.createHandler(currentOrMainLooper!!,  null)
    }

    fun start(
        initialSteeringManifestUrl: Uri?,
        callback: Callback?,
        eventDispatcher: MediaSourceEventListener.EventDispatcher?
    ) {
        this.steeringManifestUrl = initialSteeringManifestUrl
        this.callback = callback
        this.eventDispatcher = eventDispatcher
        this.steeringManifestReloadHandler =
            clock.createHandler(currentOrMainLooper!!,  null)
        this.steeringManifestLoader =
            if (downloadExecutorSupplier != null)
                Loader(downloadExecutorSupplier.get()!!)
            else
                Loader("SteeringManifestTracker")
        this.hasStarted = true
        loadSteeringManifestImmediately()
    }

    fun stop() {
        if (steeringManifestLoader != null) {
            steeringManifestLoader!!.release()
            steeringManifestLoader = null
        }
        if (steeringManifestReloadHandler != null) {
            steeringManifestReloadHandler!!.removeCallbacksAndMessages( null)
            steeringManifestReloadHandler = null
        }
        callback = null
        steeringManifest = null
        eventDispatcher = null
        hasStarted = false
    }

    private fun loadSteeringManifestImmediately() {
        Preconditions.checkState(hasStarted)
        val steeringManifestUrlBuilder =steeringManifestUrl!!.buildUpon()
        val steeringQueryParameters =callback!!.steeringQueryParameters
        val sortedQueryParameters = TreeMap<String?, String?>(steeringQueryParameters)
        for (entry in sortedQueryParameters.entries) {
            steeringManifestUrlBuilder.appendQueryParameter(entry.key, entry.value)
        }
        val dataSpec =
            DataSpec.Builder()
                .setUri(steeringManifestUrlBuilder.build()!!)
                .build()
        val steeringManifestLoadable =
            ParsingLoadable<SteeringManifest>(
                dataSourceFactory.createDataSource(),
                dataSpec,
                C.DATA_TYPE_STEERING_MANIFEST,
                SteeringManifestParser()
            )
        steeringManifestLoader!!.startLoading<ParsingLoadable<SteeringManifest>>(
                steeringManifestLoadable,  
                steeringManifestLoaderCallback,  
                0
            )
    }

    private inner class SteeringManifestLoaderCallback : Loader.Callback<ParsingLoadable<SteeringManifest>> {
        override fun onLoadStarted(
            loadable: ParsingLoadable<SteeringManifest>,
            elapsedRealtimeMs: Long,
            loadDurationMs: Long,
            retryCount: Int
        ) {
            if (!hasStarted) {
                return
            }
            val loadEventInfo: LoadEventInfo =
                buildLoadEventInfo(loadable, elapsedRealtimeMs, loadDurationMs)
            eventDispatcher!!.loadStarted(loadEventInfo, C.DATA_TYPE_STEERING_MANIFEST, retryCount)
        }

        override fun onLoadCompleted(
            loadable: ParsingLoadable<SteeringManifest>,
            elapsedRealtimeMs: Long,
            loadDurationMs: Long
        ) {
            if (!hasStarted) {
                return
            }
            val newSteeringManifest = loadable.result!!
            steeringManifest = newSteeringManifest
            handler!!.post(Runnable {
                callback!!.onSteeringManifestUpdated(newSteeringManifest)
            })
            steeringManifestUrl =
                getSteeringManifestUrl(
                    steeringManifestUrl!!,
                    newSteeringManifest.reloadUri
                )
            val delayUntilNextLoadMs =
                if (newSteeringManifest.timeToLiveMs != C.TIME_UNSET)
                    newSteeringManifest.timeToLiveMs
                else
                    FALLBACK_DELAY_UNTIL_NEXT_LOAD_MS
            steeringManifestReloadHandler!!.postDelayed(
                    Runnable { this@SteeringManifestTracker.loadSteeringManifestImmediately() },
                    delayUntilNextLoadMs
                )
            val loadEventInfo: LoadEventInfo =
                buildLoadEventInfo(loadable, elapsedRealtimeMs, loadDurationMs)
            eventDispatcher!!.loadCompleted(loadEventInfo, C.DATA_TYPE_STEERING_MANIFEST)
        }

        override fun onLoadCanceled(
            loadable: ParsingLoadable<SteeringManifest>,
            elapsedRealtimeMs: Long,
            loadDurationMs: Long,
            released: Boolean
        ) {
            if (!hasStarted) {
                return
            }
            val loadEventInfo: LoadEventInfo =
                buildLoadEventInfo(loadable, elapsedRealtimeMs, loadDurationMs)
            eventDispatcher!!.loadCanceled(loadEventInfo, C.DATA_TYPE_STEERING_MANIFEST)
        }

        override fun onLoadError(
            loadable: ParsingLoadable<SteeringManifest>,
            elapsedRealtimeMs: Long,
            loadDurationMs: Long,
            error: IOException,
            errorCount: Int
        ): LoadErrorAction {
            if (!hasStarted) {
                return Loader.DONT_RETRY
            }
            var responseCode = Int.MAX_VALUE
            if (error is InvalidResponseCodeException) {
                responseCode = error.responseCode
            }
            // See https://datatracker.ietf.org/doc/html/draft-pantos-content-steering-01#section-7
            // (sub-term 7).
            var delayUntilNextLoadMs: Long =
                FALLBACK_DELAY_UNTIL_NEXT_LOAD_MS // Use fallback TTL unless the below cases.
            if (responseCode == 410) {
                // If HTTP 410 Gone is in response, we will not reload for the remainder of the session.
                delayUntilNextLoadMs = C.TIME_UNSET
                steeringManifestLoader!!.release()
                steeringManifestReloadHandler!!.removeCallbacksAndMessages( null)
            } else if (responseCode == 429) {
                // If HTTP 429 Too Many Requests with a Retry-After header is in response, we will wait
                // for the specified time until reload.
                val retryAfter = loadable.responseHeaders!!.get(RETRY_AFTER_HEADER)
                if (retryAfter != null) {
                    try {
                        delayUntilNextLoadMs = retryAfter[0]!!.toLong() * 1000
                    } catch (e: NumberFormatException) {
                        w(TAG, "Retry-After header string doesn't contain a parsable long")
                    }
                }
            } else if (steeringManifest != null && steeringManifest!!.timeToLiveMs != C.TIME_UNSET) {
                // If there has been a steeringManifest with a TTL, we will wait for that previously
                // specified TTL until reload.
                delayUntilNextLoadMs = steeringManifest!!.timeToLiveMs
            }
            if (delayUntilNextLoadMs != C.TIME_UNSET) {
                steeringManifestReloadHandler!!.postDelayed(
                        Runnable { this@SteeringManifestTracker.loadSteeringManifestImmediately() },
                        delayUntilNextLoadMs
                    )
            }
            val canceled = delayUntilNextLoadMs == C.TIME_UNSET
            handler!!.post(Runnable {
                callback!!.onSteeringManifestLoadError(error, canceled)
            })
            val loadEventInfo: LoadEventInfo =
                buildLoadEventInfo(loadable, elapsedRealtimeMs, loadDurationMs)
            eventDispatcher!!.loadError(loadEventInfo, C.DATA_TYPE_STEERING_MANIFEST, error, canceled)
            // We do not retry here but will still reload with the latest parameters after the delay.
            return Loader.DONT_RETRY
        }
    }

    companion object {
        @VisibleForTesting
        
        const val FALLBACK_DELAY_UNTIL_NEXT_LOAD_MS: Long = 300000 // 5 mins.

        private const val TAG = "SteeringManifestTracker"
        private const val RETRY_AFTER_HEADER = "Retry-After"

        private fun getSteeringManifestUrl(
            previousSteeringManifestUrl: Uri, reloadUri: Uri?
        ): Uri? {
            if (reloadUri == null) {
                // The reloadUri is null, then we continue using the previousSteeringManifestUrl.
                return previousSteeringManifestUrl
            }
            if (UriUtil.isAbsolute(reloadUri.toString())) {
                // The reloadUri is absolute, then we use it directly.
                return reloadUri
            }
            // The reloadUri is relative, then we use the relative resolution of it with respect to the
            // previousSteeringManifestUrl.
            return UriUtil.resolveToUri(
                previousSteeringManifestUrl.toString(),
                reloadUri.toString()
            )
        }

        private fun buildLoadEventInfo(
            loadable: ParsingLoadable<SteeringManifest>,
            elapsedRealtimeMs: Long,
            loadDurationMs: Long
        ): LoadEventInfo {
            return LoadEventInfo.Builder(loadable.loadTaskId, loadable.dataSpec, elapsedRealtimeMs)
                .setUri(loadable.uri)
                .setResponseHeaders(loadable.responseHeaders)
                .setLoadDurationMs(loadDurationMs)
                .setBytesLoaded(loadable.bytesLoaded())
                .build()
        }
    }
}
