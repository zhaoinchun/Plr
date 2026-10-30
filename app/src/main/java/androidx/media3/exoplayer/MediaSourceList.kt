
package androidx.media3.exoplayer

import android.util.Pair
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.createHandlerForCurrentOrMainLooper
import androidx.media3.exoplayer.analytics.AnalyticsCollector
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.KeyRequestInfo
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MaskingMediaPeriod
import androidx.media3.exoplayer.source.MaskingMediaSource
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.MediaSource.MediaSourceCaller
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.base.Preconditions
import java.io.IOException
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

internal class MediaSourceList(
    private val mediaSourceListInfoListener: MediaSourceListInfoRefreshListener,
    analyticsCollector: AnalyticsCollector,
    analyticsCollectorHandler: HandlerWrapper,
    private val playerId: PlayerId,
    private val bandwidthMeter: BandwidthMeter
) {
    interface MediaSourceListInfoRefreshListener {
        fun onPlaylistUpdateRequested()
    }

    private val mediaSourceHolders: MutableList<MediaSourceHolder>
    private val mediaSourceByMediaPeriod: IdentityHashMap<MediaPeriod, MediaSourceHolder>
    private val mediaSourceByUid: MutableMap<Any, MediaSourceHolder>
    private val childSources: HashMap<MediaSourceHolder, MediaSourceAndListener>
    private val enabledMediaSourceHolders: MutableSet<MediaSourceHolder>
    private val eventListener: AnalyticsCollector
    private val eventHandler: HandlerWrapper

    var shuffleOrder: ShuffleOrder
        private set
    var isPrepared: Boolean = false
        private set

    init {
        shuffleOrder = DefaultShuffleOrder(0)
        mediaSourceByMediaPeriod = IdentityHashMap<MediaPeriod, MediaSourceHolder>()
        mediaSourceByUid = HashMap<Any, MediaSourceHolder>()
        mediaSourceHolders = ArrayList<MediaSourceHolder>()
        eventListener = analyticsCollector
        eventHandler = analyticsCollectorHandler
        childSources = HashMap<MediaSourceHolder, MediaSourceAndListener>()
        enabledMediaSourceHolders = HashSet<MediaSourceHolder>()
    }

    fun setMediaSources(
        holders: List<MediaSourceHolder>,
        shuffleOrder: ShuffleOrder
    ): Timeline {
        removeMediaSourcesInternal( 0,  mediaSourceHolders.size)
        return addMediaSources( this.mediaSourceHolders.size, holders, shuffleOrder)
    }

    fun addMediaSources(
        index: Int, holders: List<MediaSourceHolder>, shuffleOrder: ShuffleOrder
    ): Timeline {
        if (!holders.isEmpty()) {
            this.shuffleOrder = shuffleOrder
            for (insertionIndex in index..<index + holders.size) {
                val holder = holders.get(insertionIndex - index)
                if (insertionIndex > 0) {
                    val previousHolder = mediaSourceHolders.get(insertionIndex - 1)
                    val previousTimeline = previousHolder.mediaSource.getTimeline()
                    holder.reset( 
                        previousHolder.firstWindowIndexInChild
                                + previousTimeline.windowCount
                    )
                } else {
                    holder.reset( 0)
                }
                val newTimeline = holder.mediaSource.getTimeline()
                correctOffsets( 
                    insertionIndex,  
                    newTimeline.windowCount
                )
                mediaSourceHolders.add(insertionIndex, holder)
                mediaSourceByUid.put(holder.uid, holder)
                if (isPrepared) {
                    prepareChildSource(holder)
                    if (mediaSourceByMediaPeriod.isEmpty()) {
                        enabledMediaSourceHolders.add(holder)
                    } else {
                        disableChildSource(holder)
                    }
                }
            }
        }
        return createTimeline()
    }

    fun removeMediaSourceRange(
        fromIndex: Int,
        toIndex: Int,
        shuffleOrder: ShuffleOrder
    ): Timeline {
        Preconditions.checkArgument(fromIndex >= 0 && fromIndex <= toIndex && toIndex <= this.size)
        this.shuffleOrder = shuffleOrder
        removeMediaSourcesInternal(fromIndex, toIndex)
        return createTimeline()
    }

    fun moveMediaSource(currentIndex: Int, newIndex: Int, shuffleOrder: ShuffleOrder): Timeline {
        return moveMediaSourceRange(currentIndex, currentIndex + 1, newIndex, shuffleOrder)
    }

    fun moveMediaSourceRange(
        fromIndex: Int, toIndex: Int, newFromIndex: Int, shuffleOrder: ShuffleOrder
    ): Timeline {
        Preconditions.checkArgument(
            fromIndex >= 0 && fromIndex <= toIndex && toIndex <= this.size && newFromIndex >= 0
        )
        this.shuffleOrder = shuffleOrder
        if (fromIndex == toIndex || fromIndex == newFromIndex) {
            return createTimeline()
        }
        val startIndex = min(fromIndex, newFromIndex)
        val newEndIndex = newFromIndex + (toIndex - fromIndex) - 1
        val endIndex = max(newEndIndex, toIndex - 1)
        var windowOffset = mediaSourceHolders.get(startIndex).firstWindowIndexInChild
        Util.moveItems<MediaSourceHolder>(mediaSourceHolders, fromIndex, toIndex, newFromIndex)
        for (i in startIndex..endIndex) {
            val holder = mediaSourceHolders[i]
            holder.firstWindowIndexInChild = windowOffset
            windowOffset += holder.mediaSource.getTimeline().windowCount
        }
        return createTimeline()
    }

    fun updateMediaSourcesWithMediaItems(
        fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>
    ): Timeline {
        Preconditions.checkArgument(fromIndex in 0..toIndex && toIndex <= this.size)
        Preconditions.checkArgument(mediaItems.size == toIndex - fromIndex)
        for (i in fromIndex..<toIndex) {
            mediaSourceHolders[i].mediaSource.updateMediaItem(mediaItems[i - fromIndex])
        }
        return createTimeline()
    }

    fun clear(shuffleOrder: ShuffleOrder?): Timeline {
        this.shuffleOrder = shuffleOrder ?: this.shuffleOrder.cloneAndClear()
        removeMediaSourcesInternal( 0,  this.size)
        return createTimeline()
    }

    val size: Int
        get() = mediaSourceHolders.size

    fun setShuffleOrder(shuffleOrder: ShuffleOrder): Timeline {
        var shuffleOrder = shuffleOrder
        val size = this.size
        if (shuffleOrder.length != size) {
            shuffleOrder =
                shuffleOrder
                    .cloneAndClear()
                    .cloneAndInsert( 0,  size)
        }
        this.shuffleOrder = shuffleOrder
        return createTimeline()
    }

    fun prepare() {
        Preconditions.checkState(!isPrepared)
        for (i in mediaSourceHolders.indices) {
            val mediaSourceHolder = mediaSourceHolders[i]
            prepareChildSource(mediaSourceHolder)
            enabledMediaSourceHolders.add(mediaSourceHolder)
        }
        isPrepared = true
    }

    fun createPeriod(
        id: MediaPeriodId, allocator: Allocator, startPositionUs: Long
    ): MediaPeriod {
        val mediaSourceHolderUid: Any? = getMediaSourceHolderUid(id.periodUid)
        val childMediaPeriodId =
            id.copyWithPeriodUid(getChildPeriodUid(id.periodUid)!!)
        val holder = mediaSourceByUid.get(mediaSourceHolderUid)!!
        enableMediaSource(holder)
        holder.activeMediaPeriodIds.add(childMediaPeriodId)
        val mediaPeriod: MediaPeriod =
            holder.mediaSource.createPeriod(childMediaPeriodId, allocator, startPositionUs)
        mediaSourceByMediaPeriod.put(mediaPeriod, holder)
        disableUnusedMediaSources()
        return mediaPeriod
    }

    fun releasePeriod(mediaPeriod: MediaPeriod) {
        val holder = mediaSourceByMediaPeriod.remove(mediaPeriod)!!
        holder.mediaSource.releasePeriod(mediaPeriod)
        holder.activeMediaPeriodIds.remove((mediaPeriod as MaskingMediaPeriod).id)
        if (!mediaSourceByMediaPeriod.isEmpty()) {
            disableUnusedMediaSources()
        }
        maybeReleaseChildSource(holder)
    }

    fun release() {
        for (childSource in childSources.values) {
            try {
                childSource.mediaSource.releaseSource(childSource.caller)
            } catch (e: RuntimeException) {
                // There's nothing we can do.
                e(TAG, "Failed to release child source.", e)
            }
            childSource.mediaSource.removeEventListener(childSource.eventListener)
            childSource.mediaSource.removeDrmEventListener(childSource.eventListener)
        }
        childSources.clear()
        enabledMediaSourceHolders.clear()
        isPrepared = false
    }

    fun createTimeline(): Timeline {
        if (mediaSourceHolders.isEmpty()) {
            return Timeline.EMPTY
        }
        var windowOffset = 0
        for (i in mediaSourceHolders.indices) {
            val mediaSourceHolder = mediaSourceHolders[i]
            mediaSourceHolder.firstWindowIndexInChild = windowOffset
            windowOffset += mediaSourceHolder.mediaSource.getTimeline().windowCount
        }
        return PlaylistTimeline(mediaSourceHolders, shuffleOrder)
    }

    private fun enableMediaSource(mediaSourceHolder: MediaSourceHolder) {
        enabledMediaSourceHolders.add(mediaSourceHolder)
        val enabledChild = childSources.get(mediaSourceHolder)
        if (enabledChild != null) {
            enabledChild.mediaSource.enable(enabledChild.caller)
        }
    }

    private fun disableUnusedMediaSources() {
        val iterator = enabledMediaSourceHolders.iterator()
        while (iterator.hasNext()) {
            val holder = iterator.next()
            if (holder.activeMediaPeriodIds.isEmpty()) {
                disableChildSource(holder)
                iterator.remove()
            }
        }
    }

    private fun disableChildSource(holder: MediaSourceHolder?) {
        val disabledChild = childSources.get(holder)
        if (disabledChild != null) {
            disabledChild.mediaSource.disable(disabledChild.caller)
        }
    }

    private fun removeMediaSourcesInternal(fromIndex: Int, toIndex: Int) {
        for (index in toIndex - 1 downTo fromIndex) {
            val holder = mediaSourceHolders.removeAt(index)
            mediaSourceByUid.remove(holder.uid)
            val oldTimeline = holder.mediaSource.getTimeline()
            correctOffsets( 
                index,  -oldTimeline.windowCount
            )
            holder.isRemoved = true
            if (isPrepared) {
                maybeReleaseChildSource(holder)
            }
        }
    }

    private fun correctOffsets(startIndex: Int, windowOffsetUpdate: Int) {
        for (i in startIndex..<mediaSourceHolders.size) {
            val mediaSourceHolder = mediaSourceHolders[i]
            mediaSourceHolder.firstWindowIndexInChild += windowOffsetUpdate
        }
    }

    private fun prepareChildSource(holder: MediaSourceHolder) {
        val mediaSource: MediaSource = holder.mediaSource
        val caller: MediaSourceCaller =
            MediaSourceCaller { source: MediaSource?, timeline: Timeline? -> mediaSourceListInfoListener.onPlaylistUpdateRequested() }
        val eventListener: ForwardingEventListener = ForwardingEventListener(holder)
        childSources.put(holder, MediaSourceAndListener(mediaSource, caller, eventListener))
        mediaSource.addEventListener(createHandlerForCurrentOrMainLooper(), eventListener)
        mediaSource.addDrmEventListener(createHandlerForCurrentOrMainLooper(), eventListener)
        mediaSource.prepareSource(caller, playerId, bandwidthMeter)
    }

    private fun maybeReleaseChildSource(mediaSourceHolder: MediaSourceHolder) {
        // Release if the source has been removed from the playlist and no periods are still active.
        if (mediaSourceHolder.isRemoved && mediaSourceHolder.activeMediaPeriodIds.isEmpty()) {
            val removedChild = childSources.remove(mediaSourceHolder)!!
            removedChild.mediaSource.releaseSource(removedChild.caller)
            removedChild.mediaSource.removeEventListener(removedChild.eventListener)
            removedChild.mediaSource.removeDrmEventListener(removedChild.eventListener)
            enabledMediaSourceHolders.remove(mediaSourceHolder)
        }
    }

    internal class MediaSourceHolder(mediaSource: MediaSource, useLazyPreparation: Boolean) :
        MediaSourceInfoHolder {
        val mediaSource: MaskingMediaSource
        override val uid: Any
        val activeMediaPeriodIds: MutableList<MediaPeriodId?>

        var firstWindowIndexInChild: Int = 0
        var isRemoved: Boolean = false

        init {
            this.mediaSource = MaskingMediaSource(mediaSource, useLazyPreparation)
            this.activeMediaPeriodIds = ArrayList<MediaPeriodId?>()
            this.uid = Any()
        }

        fun reset(firstWindowIndexInChild: Int) {
            this.firstWindowIndexInChild = firstWindowIndexInChild
            this.isRemoved = false
            this.activeMediaPeriodIds.clear()
        }

        override val timeline: Timeline
            get() = mediaSource.getTimeline()
    }

    private class MediaSourceAndListener(
        val mediaSource: MediaSource,
        val caller: MediaSourceCaller,
        val eventListener: ForwardingEventListener
    )

    private inner class ForwardingEventListener
        (private val id: MediaSourceHolder) : MediaSourceEventListener, DrmSessionEventListener {
        // MediaSourceEventListener implementation
        override fun onLoadStarted(
            windowIndex: Int,
            mediaPeriodId: MediaPeriodId?,
            loadEventData: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            retryCount: Int
        ) {
            val eventParameters =
                getEventParameters(windowIndex, mediaPeriodId)
            if (eventParameters != null) {
                eventHandler.post(
                    Runnable {
                        eventListener.onLoadStarted(
                            eventParameters.first!!,
                            eventParameters.second,
                            loadEventData,
                            mediaLoadData,
                            retryCount
                        )
                    })
            }
        }

        override fun onLoadCompleted(
            windowIndex: Int,
            mediaPeriodId: MediaPeriodId?,
            loadEventData: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            val eventParameters =
                getEventParameters(windowIndex, mediaPeriodId)
            if (eventParameters != null) {
                eventHandler.post(
                    Runnable {
                        eventListener.onLoadCompleted(
                            eventParameters.first!!,
                            eventParameters.second,
                            loadEventData,
                            mediaLoadData
                        )
                    })
            }
        }

        override fun onLoadCanceled(
            windowIndex: Int,
            mediaPeriodId: MediaPeriodId?,
            loadEventData: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            val eventParameters =
                getEventParameters(windowIndex, mediaPeriodId)
            if (eventParameters != null) {
                eventHandler.post(
                    Runnable {
                        eventListener.onLoadCanceled(
                            eventParameters.first!!,
                            eventParameters.second,
                            loadEventData,
                            mediaLoadData
                        )
                    })
            }
        }

        override fun onLoadError(
            windowIndex: Int,
            mediaPeriodId: MediaPeriodId?,
            loadEventData: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean
        ) {
            val eventParameters =
                getEventParameters(windowIndex, mediaPeriodId)
            if (eventParameters != null) {
                eventHandler.post(
                    Runnable {
                        eventListener.onLoadError(
                            eventParameters.first!!,
                            eventParameters.second,
                            loadEventData,
                            mediaLoadData,
                            error,
                            wasCanceled
                        )
                    })
            }
        }

        override fun onUpstreamDiscarded(
            windowIndex: Int,
            mediaPeriodId: MediaPeriodId?,
            mediaLoadData: MediaLoadData
        ) {
            val eventParameters =
                getEventParameters(windowIndex, mediaPeriodId)
            if (eventParameters != null) {
                eventHandler.post(
                    Runnable {
                        eventListener.onUpstreamDiscarded(
                            eventParameters.first!!,
                            eventParameters.second!!,
                            mediaLoadData
                        )
                    })
            }
        }

        override fun onDownstreamFormatChanged(
            windowIndex: Int,
            mediaPeriodId: MediaPeriodId?,
            mediaLoadData: MediaLoadData
        ) {
            val eventParameters =
                getEventParameters(windowIndex, mediaPeriodId)
            if (eventParameters != null) {
                eventHandler.post(
                    Runnable {
                        eventListener.onDownstreamFormatChanged(
                            eventParameters.first!!, eventParameters.second, mediaLoadData
                        )
                    })
            }
        }

        // DrmSessionEventListener implementation
        override fun onDrmSessionAcquired(
            windowIndex: Int,
            mediaPeriodId: MediaPeriodId?,
            @DrmSession.State state: @DrmSession.State Int
        ) {
            val eventParameters =
                getEventParameters(windowIndex, mediaPeriodId)
            if (eventParameters != null) {
                eventHandler.post(
                    Runnable {
                        eventListener.onDrmSessionAcquired(
                            eventParameters.first!!, eventParameters.second, state
                        )
                    })
            }
        }

        override fun onDrmKeysLoaded(
            windowIndex: Int,
            mediaPeriodId: MediaPeriodId?,
            keyRequestInfo: KeyRequestInfo
        ) {
            val eventParameters =
                getEventParameters(windowIndex, mediaPeriodId)
            if (eventParameters != null) {
                eventHandler.post(
                    Runnable {
                        eventListener.onDrmKeysLoaded(
                            eventParameters.first!!, eventParameters.second, keyRequestInfo
                        )
                    })
            }
        }

        override fun onDrmSessionManagerError(
            windowIndex: Int, mediaPeriodId: MediaPeriodId?, error: Exception
        ) {
            val eventParameters =
                getEventParameters(windowIndex, mediaPeriodId)
            if (eventParameters != null) {
                eventHandler.post(
                    Runnable {
                        eventListener.onDrmSessionManagerError(
                            eventParameters.first!!, eventParameters.second, error
                        )
                    })
            }
        }

        override fun onDrmKeysRestored(
            windowIndex: Int, mediaPeriodId: MediaPeriodId?
        ) {
            val eventParameters =
                getEventParameters(windowIndex, mediaPeriodId)
            if (eventParameters != null) {
                eventHandler.post(
                    Runnable {
                        eventListener.onDrmKeysRestored(
                            eventParameters.first!!,
                            eventParameters.second
                        )
                    })
            }
        }

        override fun onDrmKeysRemoved(
            windowIndex: Int, mediaPeriodId: MediaPeriodId?
        ) {
            val eventParameters =
                getEventParameters(windowIndex, mediaPeriodId)
            if (eventParameters != null) {
                eventHandler.post(
                    Runnable {
                        eventListener.onDrmKeysRemoved(
                            eventParameters.first!!,
                            eventParameters.second
                        )
                    })
            }
        }

        override fun onDrmSessionReleased(
            windowIndex: Int, mediaPeriodId: MediaPeriodId?
        ) {
            val eventParameters =
                getEventParameters(windowIndex, mediaPeriodId)
            if (eventParameters != null) {
                eventHandler.post(
                    Runnable {
                        eventListener.onDrmSessionReleased(
                            eventParameters.first!!,
                            eventParameters.second
                        )
                    })
            }
        }

        fun getEventParameters(
            childWindowIndex: Int, childMediaPeriodId: MediaPeriodId?
        ): Pair<Int?, MediaPeriodId?>? {
            var mediaPeriodId: MediaPeriodId? = null
            if (childMediaPeriodId != null) {
                mediaPeriodId = getMediaPeriodIdForChildMediaPeriodId(id, childMediaPeriodId)
                if (mediaPeriodId == null) {
                    // Media period not found. Ignore event.
                    return null
                }
            }
            val windowIndex: Int = getWindowIndexForChildWindowIndex(id, childWindowIndex)
            return Pair.create<Int?, MediaPeriodId?>(windowIndex, mediaPeriodId)
        }
    }

    companion object {
        private const val TAG = "MediaSourceList"

        // Internal methods to manage child sources.
        private fun getMediaPeriodIdForChildMediaPeriodId(
            mediaSourceHolder: MediaSourceHolder, mediaPeriodId: MediaPeriodId
        ): MediaPeriodId? {
            for (i in mediaSourceHolder.activeMediaPeriodIds.indices) {
                // Ensure the reported media period id has the same window sequence number as the one created
                // by this media source. Otherwise it does not belong to this child source.
                if (mediaSourceHolder.activeMediaPeriodIds[i]!!.windowSequenceNumber
                    == mediaPeriodId.windowSequenceNumber
                ) {
                    val periodUid: Any = getPeriodUid(mediaSourceHolder, mediaPeriodId.periodUid)
                    return mediaPeriodId.copyWithPeriodUid(periodUid)
                }
            }
            return null
        }

        private fun getWindowIndexForChildWindowIndex(
            mediaSourceHolder: MediaSourceHolder, windowIndex: Int
        ): Int {
            return windowIndex + mediaSourceHolder.firstWindowIndexInChild
        }

        private fun getMediaSourceHolderUid(periodUid: Any): Any? {
            return AbstractConcatenatedTimeline.getChildTimelineUidFromConcatenatedUid(periodUid)
        }

        private fun getChildPeriodUid(periodUid: Any): Any? {
            return AbstractConcatenatedTimeline.getChildPeriodUidFromConcatenatedUid(periodUid)
        }

        private fun getPeriodUid(holder: MediaSourceHolder, childPeriodUid: Any?): Any {
            return AbstractConcatenatedTimeline.getConcatenatedUid(holder.uid, childPeriodUid)
        }
    }
}
