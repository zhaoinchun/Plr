package androidx.media3.exoplayer.source

import android.os.Handler
import androidx.annotation.CallSuper
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util.createHandlerForCurrentLooper
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.KeyRequestInfo
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.MediaSource.MediaSourceCaller
import com.google.common.base.Preconditions
import java.io.IOException

abstract class CompositeMediaSource<T> protected constructor() : BaseMediaSource() {
    private val childSources = mutableMapOf<T?, MediaSourceAndListener<T>>()
    private var eventHandler: Handler? = null

    @CallSuper
    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        eventHandler = createHandlerForCurrentLooper()
    }

    @CallSuper
    @Throws(IOException::class)
    override fun maybeThrowSourceInfoRefreshError() {
        for (childSource in childSources.values) {
            childSource.mediaSource.maybeThrowSourceInfoRefreshError()
        }
    }

    @CallSuper
    override fun enableInternal() {
        for (childSource in childSources.values) {
            childSource.mediaSource.enable(childSource.caller)
        }
    }

    @CallSuper
    override fun disableInternal() {
        for (childSource in childSources.values) {
            childSource.mediaSource.disable(childSource.caller)
        }
    }

    @CallSuper
    override fun releaseSourceInternal() {
        for (childSource in childSources.values) {
            childSource.mediaSource.releaseSource(childSource.caller)
            childSource.mediaSource.removeEventListener(childSource.eventListener)
            childSource.mediaSource.removeDrmEventListener(childSource.eventListener)
        }
        childSources.clear()
    }

    protected abstract fun onChildSourceInfoRefreshed(
        childSourceId: T?, mediaSource: MediaSource, newTimeline: Timeline
    )

    protected fun prepareChildSource(id: T?, mediaSource: MediaSource) {
        Preconditions.checkArgument(!childSources.containsKey(id))
        val caller =
            MediaSourceCaller { source: MediaSource, timeline: Timeline ->
                onChildSourceInfoRefreshed(
                    id,
                    source,
                    timeline
                )
            }
        val eventListener = ForwardingEventListener(id)
        childSources[id] = MediaSourceAndListener(mediaSource, caller, eventListener)
        mediaSource.addEventListener(eventHandler!!, eventListener)
        mediaSource.addDrmEventListener(eventHandler!!, eventListener)
        mediaSource.prepareSource(caller, getPlayerId(), getBandwidthMeter())
        if (!isEnabled) {
            mediaSource.disable(caller)
        }
    }

    protected fun enableChildSource(id: T?) {
        val enabledChild = childSources[id]!!
        enabledChild.mediaSource.enable(enabledChild.caller)
    }

    protected fun disableChildSource(id: T?) {
        val disabledChild = childSources[id]!!
        disabledChild.mediaSource.disable(disabledChild.caller)
    }

    protected fun releaseChildSource(id: T?) {
        val removedChild = childSources.remove(id)!!
        removedChild.mediaSource.releaseSource(removedChild.caller)
        removedChild.mediaSource.removeEventListener(removedChild.eventListener)
        removedChild.mediaSource.removeDrmEventListener(removedChild.eventListener)
    }

    protected open fun getWindowIndexForChildWindowIndex(childSourceId: T?, windowIndex: Int): Int {
        return windowIndex
    }

    protected open fun getMediaPeriodIdForChildMediaPeriodId(
        childSourceId: T?,
        mediaPeriodId: MediaPeriodId
    ): MediaPeriodId? {
        return mediaPeriodId
    }

    protected open fun getMediaTimeForChildMediaTime(
        childSourceId: T?,
        mediaTimeMs: Long,
        mediaPeriodId: MediaPeriodId?
    ): Long {
        return mediaTimeMs
    }

    private class MediaSourceAndListener<T>(
        val mediaSource: MediaSource,
        val caller: MediaSourceCaller,
        val eventListener: CompositeMediaSource<T>.ForwardingEventListener
    ) {
    }

    private inner class ForwardingEventListener(private val id: T?) : MediaSourceEventListener,
        DrmSessionEventListener {
        private var mediaSourceEventDispatcher = createEventDispatcher(null)
        private var drmEventDispatcher = createDrmEventDispatcher(null)

        // MediaSourceEventListener implementation
        override fun onLoadStarted(
            windowIndex: Int,
            mediaPeriodId: MediaPeriodId?,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            retryCount: Int
        ) {
            if (maybeUpdateEventDispatcher(windowIndex, mediaPeriodId)) {
                mediaSourceEventDispatcher.loadStarted(
                    loadEventInfo,
                    maybeUpdateMediaLoadData(mediaLoadData, mediaPeriodId),
                    retryCount
                )
            }
        }

        override fun onLoadCompleted(
            windowIndex: Int,
            mediaPeriodId: MediaPeriodId?,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            if (maybeUpdateEventDispatcher(windowIndex, mediaPeriodId)) {
                mediaSourceEventDispatcher.loadCompleted(
                    loadEventInfo, maybeUpdateMediaLoadData(mediaLoadData, mediaPeriodId)
                )
            }
        }

        override fun onLoadCanceled(
            windowIndex: Int,
            mediaPeriodId: MediaPeriodId?,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            if (maybeUpdateEventDispatcher(windowIndex, mediaPeriodId)) {
                mediaSourceEventDispatcher.loadCanceled(
                    loadEventInfo, maybeUpdateMediaLoadData(mediaLoadData, mediaPeriodId)
                )
            }
        }

        override fun onLoadError(
            windowIndex: Int,
            mediaPeriodId: MediaPeriodId?,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean
        ) {
            if (maybeUpdateEventDispatcher(windowIndex, mediaPeriodId)) {
                mediaSourceEventDispatcher.loadError(
                    loadEventInfo,
                    maybeUpdateMediaLoadData(mediaLoadData, mediaPeriodId),
                    error,
                    wasCanceled
                )
            }
        }

        override fun onUpstreamDiscarded(
            windowIndex: Int, mediaPeriodId: MediaPeriodId?, mediaLoadData: MediaLoadData
        ) {
            if (maybeUpdateEventDispatcher(windowIndex, mediaPeriodId)) {
                mediaSourceEventDispatcher.upstreamDiscarded(
                    maybeUpdateMediaLoadData(mediaLoadData, mediaPeriodId)
                )
            }
        }

        override fun onDownstreamFormatChanged(
            windowIndex: Int, mediaPeriodId: MediaPeriodId?, mediaLoadData: MediaLoadData
        ) {
            if (maybeUpdateEventDispatcher(windowIndex, mediaPeriodId)) {
                mediaSourceEventDispatcher.downstreamFormatChanged(
                    maybeUpdateMediaLoadData(mediaLoadData, mediaPeriodId)
                )
            }
        }

        // DrmSessionEventListener implementation
        override fun onDrmSessionAcquired(
            windowIndex: Int,
            mediaPeriodId: MediaPeriodId?,
            state: @DrmSession.State Int
        ) {
            if (maybeUpdateEventDispatcher(windowIndex, mediaPeriodId)) {
                drmEventDispatcher.drmSessionAcquired(state)
            }
        }

        override fun onDrmKeysLoaded(
            windowIndex: Int, mediaPeriodId: MediaPeriodId?, keyRequestInfo: KeyRequestInfo
        ) {
            if (maybeUpdateEventDispatcher(windowIndex, mediaPeriodId)) {
                drmEventDispatcher.drmKeysLoaded(keyRequestInfo)
            }
        }

        override fun onDrmSessionManagerError(
            windowIndex: Int, mediaPeriodId: MediaPeriodId?, error: Exception
        ) {
            if (maybeUpdateEventDispatcher(windowIndex, mediaPeriodId)) {
                drmEventDispatcher.drmSessionManagerError(error)
            }
        }

        override fun onDrmKeysRestored(windowIndex: Int, mediaPeriodId: MediaPeriodId?) {
            if (maybeUpdateEventDispatcher(windowIndex, mediaPeriodId)) {
                drmEventDispatcher.drmKeysRestored()
            }
        }

        override fun onDrmKeysRemoved(windowIndex: Int, mediaPeriodId: MediaPeriodId?) {
            if (maybeUpdateEventDispatcher(windowIndex, mediaPeriodId)) {
                drmEventDispatcher.drmKeysRemoved()
            }
        }

        override fun onDrmSessionReleased(windowIndex: Int, mediaPeriodId: MediaPeriodId?) {
            if (maybeUpdateEventDispatcher(windowIndex, mediaPeriodId)) {
                drmEventDispatcher.drmSessionReleased()
            }
        }

        fun maybeUpdateEventDispatcher(
            childWindowIndex: Int, childMediaPeriodId: MediaPeriodId?
        ): Boolean {
            var mediaPeriodId: MediaPeriodId? = null
            if (childMediaPeriodId != null) {
                mediaPeriodId = getMediaPeriodIdForChildMediaPeriodId(id, childMediaPeriodId)
                if (mediaPeriodId == null) {
                    // Media period not found. Ignore event.
                    return false
                }
            }
            val windowIndex = getWindowIndexForChildWindowIndex(id, childWindowIndex)
            if (mediaSourceEventDispatcher.windowIndex != windowIndex
                || mediaSourceEventDispatcher.mediaPeriodId != mediaPeriodId
            ) {
                mediaSourceEventDispatcher = createEventDispatcher(windowIndex, mediaPeriodId)
            }
            if (drmEventDispatcher.windowIndex != windowIndex
                || drmEventDispatcher.mediaPeriodId != mediaPeriodId
            ) {
                drmEventDispatcher = createDrmEventDispatcher(windowIndex, mediaPeriodId)
            }
            return true
        }

        fun maybeUpdateMediaLoadData(
            mediaLoadData: MediaLoadData, childMediaPeriodId: MediaPeriodId?
        ): MediaLoadData {
            val mediaStartTimeMs =
                getMediaTimeForChildMediaTime(
                    id,
                    mediaLoadData.mediaStartTimeMs,
                    childMediaPeriodId
                )
            val mediaEndTimeMs =
                getMediaTimeForChildMediaTime(id, mediaLoadData.mediaEndTimeMs, childMediaPeriodId)
            if (mediaStartTimeMs == mediaLoadData.mediaStartTimeMs
                && mediaEndTimeMs == mediaLoadData.mediaEndTimeMs
            ) {
                return mediaLoadData
            }
            return MediaLoadData(
                mediaLoadData.dataType,
                mediaLoadData.trackType,
                mediaLoadData.trackFormat,
                mediaLoadData.trackSelectionReason,
                mediaLoadData.trackSelectionData,
                mediaStartTimeMs,
                mediaEndTimeMs
            )
        }
    }
}
