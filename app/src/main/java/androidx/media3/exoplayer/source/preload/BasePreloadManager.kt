package androidx.media3.exoplayer.source.preload

import android.os.Handler
import android.os.Looper
import androidx.annotation.CallSuper
import androidx.annotation.GuardedBy
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ListenerSet
import androidx.media3.common.util.Util.createHandlerForCurrentOrMainLooper
import androidx.media3.common.util.Util.postOrRun
import androidx.media3.exoplayer.source.MediaSource
import com.google.common.base.Preconditions
import com.google.common.base.Predicate
import java.util.Collections

abstract class BasePreloadManager<T, PreloadStatusT> protected constructor(
    protected val rankingDataComparator: RankingDataComparator<T>,
    private val targetPreloadStatusControl: TargetPreloadStatusControl<T, PreloadStatusT>,
    private val mediaSourceFactory: MediaSource.Factory
) {
    abstract class BuilderBase<T, PreloadStatusT>(
        var rankingDataComparator: RankingDataComparator<T>,
        val targetPreloadStatusControl: TargetPreloadStatusControl<T, PreloadStatusT>,
        var mediaSourceFactorySupplier: MediaSourceFactorySupplier
    ) {
        abstract fun build(): BasePreloadManager<T, PreloadStatusT>
    }

    private val lock = Any()
    private val mediaSourceHolderMap = MediaSourceHolderMap()
    private val applicationHandler: Handler = createHandlerForCurrentOrMainLooper()
    private val listeners = ListenerSet<PreloadManagerListener>(applicationHandler.looper)

    @GuardedBy("lock")
    private val sourceHolderPriorityList = mutableListOf<MediaSourceHolder>()

    @GuardedBy("lock")
    private var indexForSourceHolderToPreload = 0

    @GuardedBy("lock")
    private var indexForSourceHolderToClear = 0

    @GuardedBy("lock")
    private var targetPreloadStatusOfCurrentPreloadingSource: PreloadStatusT? = null

    // It's safe to use "this" because the invalidationListener of rankingDataComparator shouldn't be
    // triggered before exiting this (or subclass's) constructor.
    init {
        rankingDataComparator.setInvalidationListener { this.invalidate() }
    }

    fun addListener(listener: PreloadManagerListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: PreloadManagerListener) {
        verifyApplicationThread()
        listeners.remove(listener)
    }

    fun clearListeners() {
        verifyApplicationThread()
        listeners.clear()
    }

    val sourceCount: Int
        get() = mediaSourceHolderMap.size()

    fun addMediaItems(mediaItems: List<MediaItem>, rankingDataList: List<T>) {
        Preconditions.checkArgument(mediaItems.size == rankingDataList.size)
        for (i in mediaItems.indices) {
            add(mediaItems[i], rankingDataList[i])
        }
        invalidate()
    }

    fun add(mediaItem: MediaItem, rankingData: T) {
        add(mediaSourceFactory.createMediaSource(mediaItem), rankingData)
    }

    fun addMediaSources(mediaSources: List<MediaSource>, rankingDataList: List<T>) {
        Preconditions.checkArgument(mediaSources.size == rankingDataList.size)
        for (i in mediaSources.indices) {
            add(mediaSources[i], rankingDataList[i])
        }
        invalidate()
    }

    fun add(mediaSource: MediaSource, rankingData: T) {
        val mediaHolder: MediaSourceHolder =
            createMediaSourceHolder(mediaSource.mediaItem, mediaSource, rankingData)
        mediaSourceHolderMap.put(mediaHolder.mediaItem, mediaHolder.mediaSource, mediaHolder)
    }

    fun invalidate() {
        synchronized(lock) {
            resetSourceHolderPriorityList()
            while (indexForSourceHolderToPreload < sourceHolderPriorityList.size
                && !maybeStartPreloadingNextSourceHolder()
            ) {
                indexForSourceHolderToPreload++
            }
        }
    }

    @GuardedBy("lock")
    private fun resetSourceHolderPriorityList() {
        sourceHolderPriorityList.clear()
        sourceHolderPriorityList.addAll(mediaSourceHolderMap.values())
        Collections.sort(sourceHolderPriorityList)
        indexForSourceHolderToPreload = 0
        indexForSourceHolderToClear = sourceHolderPriorityList.size - 1
    }

    fun getMediaSource(mediaItem: MediaItem?): MediaSource? {
        if (!mediaSourceHolderMap.containsKey(mediaItem)) {
            return null
        }
        return mediaSourceHolderMap.get(mediaItem)!!.mediaSource
    }

    fun remove(mediaItem: MediaItem?): Boolean {
        val mediaSourceHolder: MediaSourceHolder? = mediaSourceHolderMap.get(mediaItem)
        if (mediaSourceHolder != null) {
            releaseMediaSourceHolderInternal(mediaSourceHolder)
            mediaSourceHolderMap.remove(mediaItem)
            if (isCurrentlyPreloading(mediaSourceHolder)) {
                maybeAdvanceToNextMediaSourceHolder()
            }
            return true
        }
        return false
    }

    fun removeMediaItems(mediaItems: List<MediaItem?>) {
        for (mediaItem in mediaItems) {
            val mediaSourceHolder: MediaSourceHolder? = mediaSourceHolderMap.get(mediaItem)
            if (mediaSourceHolder != null) {
                releaseMediaSourceHolderInternal(mediaSourceHolder)
                mediaSourceHolderMap.remove(mediaItem)
            }
        }
        val currentMediaSourceHolder: MediaSourceHolder?
        synchronized(lock) {
            currentMediaSourceHolder =
                this.currentlyPreloadingMediaSourceHolder
        }
        if (currentMediaSourceHolder != null && currentMediaSourceHolder.isReleased) {
            maybeAdvanceToNextMediaSourceHolder()
        }
    }

    fun remove(mediaSource: MediaSource?): Boolean {
        val mediaSourceHolder: MediaSourceHolder? = mediaSourceHolderMap.get(mediaSource)
        if (mediaSourceHolder != null) {
            releaseMediaSourceHolderInternal(mediaSourceHolder)
            mediaSourceHolderMap.remove(mediaSource)
            if (isCurrentlyPreloading(mediaSourceHolder)) {
                maybeAdvanceToNextMediaSourceHolder()
            }
            return true
        }
        return false
    }

    fun removeMediaSources(mediaSources: List<MediaSource?>) {
        for (mediaSource in mediaSources) {
            val mediaSourceHolder: MediaSourceHolder? = mediaSourceHolderMap.get(mediaSource)
            if (mediaSourceHolder != null) {
                releaseMediaSourceHolderInternal(mediaSourceHolder)
                mediaSourceHolderMap.remove(mediaSource)
            }
        }
        val currentMediaSourceHolder: MediaSourceHolder?
        synchronized(lock) {
            currentMediaSourceHolder =
                this.currentlyPreloadingMediaSourceHolder
        }
        if (currentMediaSourceHolder != null && currentMediaSourceHolder.isReleased) {
            maybeAdvanceToNextMediaSourceHolder()
        }
    }

    fun reset() {
        for (mediaHolder in mediaSourceHolderMap.values()) {
            releaseMediaSourceHolderInternal(mediaHolder)
        }
        mediaSourceHolderMap.clear()
        synchronized(lock) {
            resetSourceHolderPriorityList()
            targetPreloadStatusOfCurrentPreloadingSource = null
        }
    }

    fun release() {
        reset()
        releaseInternal()
        clearListeners()
    }

    protected fun onCompleted(
        mediaSource: MediaSource?,
        shouldNotifyListenerAndAdvancePredicate: Predicate<PreloadStatusT?>
    ) {
        applicationHandler.post(
            Runnable {
                val targetPreloadStatus =
                    getTargetPreloadStatusIfCurrentlyPreloading(mediaSource) ?: return@Runnable

                val mediaSourceHolder: MediaSourceHolder = mediaSourceHolderMap.get(mediaSource)!!
                if (shouldNotifyListenerAndAdvancePredicate.apply(targetPreloadStatus)) {
                    listeners.sendEvent { listener: PreloadManagerListener ->
                        listener.onCompleted(mediaSourceHolder.mediaItem)
                    }
                    maybeAdvanceToNextMediaSourceHolder()
                }
            })
    }

    protected fun onCompleted(
        mediaItem: MediaItem, shouldNotifyListenerAndAdvancePredicate: Predicate<PreloadStatusT?>
    ) {
        applicationHandler.post(
            Runnable {
                val targetPreloadStatus =
                    getTargetPreloadStatusIfCurrentlyPreloading(mediaItem) ?: return@Runnable

                val mediaSourceHolder: MediaSourceHolder = mediaSourceHolderMap.get(mediaItem)!!
                if (shouldNotifyListenerAndAdvancePredicate.apply(targetPreloadStatus)) {
                    listeners.sendEvent { listener: PreloadManagerListener ->
                        listener.onCompleted(mediaSourceHolder.mediaItem)
                    }
                    maybeAdvanceToNextMediaSourceHolder()
                }
            })
    }

    protected fun onError(
        error: PreloadException,
        mediaSource: MediaSource?,
        shouldNotifyListenerAndAdvancePredicate: Predicate<PreloadStatusT?>
    ) {
        applicationHandler.post(
            Runnable {
                val targetPreloadStatus =
                    getTargetPreloadStatusIfCurrentlyPreloading(mediaSource) ?: return@Runnable
                if (shouldNotifyListenerAndAdvancePredicate.apply(targetPreloadStatus)) {
                    listeners.sendEvent { listener: PreloadManagerListener ->
                        listener.onError(error)
                    }
                    maybeAdvanceToNextMediaSourceHolder()
                }
            })
    }

    protected fun onError(
        error: PreloadException,
        mediaItem: MediaItem,
        shouldNotifyListenerAndAdvancePredicate: Predicate<PreloadStatusT?>
    ) {
        applicationHandler.post(
            Runnable {
                val targetPreloadStatus =
                    getTargetPreloadStatusIfCurrentlyPreloading(mediaItem) ?: return@Runnable
                if (shouldNotifyListenerAndAdvancePredicate.apply(targetPreloadStatus)) {
                    listeners.sendEvent(ListenerSet.Event { listener: PreloadManagerListener ->
                        listener.onError(error)
                    } as (PreloadManagerListener) -> Unit)
                    maybeAdvanceToNextMediaSourceHolder()
                }
            })
    }

    protected fun onSkipped(
        mediaSource: MediaSource?, shouldAdvancePredicate: Predicate<PreloadStatusT?>
    ) {
        postOrRun(applicationHandler) {
            val targetPreloadStatus =
                getTargetPreloadStatusIfCurrentlyPreloading(mediaSource) ?: return@postOrRun
            if (shouldAdvancePredicate.apply(targetPreloadStatus)) {
                maybeAdvanceToNextMediaSourceHolder()
            }
        }
    }

    protected fun onSourceCleared() {
        synchronized(lock) {
            indexForSourceHolderToClear--
        }
    }

    protected fun onMediaSourceUpdated(mediaItem: MediaItem, updatedMediaSource: MediaSource) {
        postOrRun(applicationHandler) {
            val targetPreloadStatus =
                getTargetPreloadStatusIfCurrentlyPreloading(mediaItem) ?: return@postOrRun
            val sourceHolder: MediaSourceHolder = mediaSourceHolderMap.get(mediaItem)!!
            mediaSourceHolderMap.remove(mediaItem)
            sourceHolder.mediaSource = (updatedMediaSource)
            mediaSourceHolderMap.put(mediaItem, updatedMediaSource, sourceHolder)
        }
    }

    private fun maybeAdvanceToNextMediaSourceHolder() {
        synchronized(lock) {
            do {
                indexForSourceHolderToPreload++
            } while (indexForSourceHolderToPreload < sourceHolderPriorityList.size
                && !maybeStartPreloadingNextSourceHolder()
            )
        }
    }

    @get:GuardedBy("lock")
    private val currentlyPreloadingMediaSourceHolder: MediaSourceHolder?
        get() {
            if (indexForSourceHolderToPreload >= sourceHolderPriorityList.size) {
                return null
            }
            return sourceHolderPriorityList[indexForSourceHolderToPreload]
        }

    protected val mediaSourceHolderToClear: MediaSourceHolder?
        get() {
            synchronized(lock) {
                if (indexForSourceHolderToPreload >= indexForSourceHolderToClear) {
                    return null
                }
                return sourceHolderPriorityList[indexForSourceHolderToClear]
            }
        }

    protected fun getTargetPreloadStatusIfCurrentlyPreloading(
        mediaSource: MediaSource?
    ): PreloadStatusT? {
        synchronized(lock) {
            val currentMediaHolder: MediaSourceHolder? =
                this.currentlyPreloadingMediaSourceHolder
            if (currentMediaHolder == null || mediaSource !== currentMediaHolder.mediaSource) {
                return null
            }
            return targetPreloadStatusOfCurrentPreloadingSource
        }
    }

    protected fun getTargetPreloadStatusIfCurrentlyPreloading(mediaItem: MediaItem): PreloadStatusT? {
        synchronized(lock) {
            val currentMediaHolder: MediaSourceHolder? =
                this.currentlyPreloadingMediaSourceHolder
            if (currentMediaHolder == null || mediaItem != currentMediaHolder.mediaItem) {
                return null
            }
            return targetPreloadStatusOfCurrentPreloadingSource
        }
    }

    private fun isCurrentlyPreloading(mediaSourceHolder: MediaSourceHolder?): Boolean {
        synchronized(lock) {
            return mediaSourceHolder === this.currentlyPreloadingMediaSourceHolder
        }
    }

    protected fun shouldStartPreloadingNextSource(): Boolean {
        return true
    }

    protected abstract fun createMediaSourceHolder(
        mediaItem: MediaItem, mediaSource: MediaSource?, rankingData: T
    ): MediaSourceHolder

    protected abstract fun preloadMediaSourceHolderInternal(
        mediaSourceHolder: MediaSourceHolder, targetPreloadStatus: PreloadStatusT
    )

    @CallSuper
    protected open fun releaseMediaSourceHolderInternal(mediaSourceHolder: MediaSourceHolder) {
        mediaSourceHolder.release()
    }

    protected open fun releaseInternal() {}

    @GuardedBy("lock")
    private fun maybeStartPreloadingNextSourceHolder(): Boolean {
        if (shouldStartPreloadingNextSource()) {
            val preloadingHolder: MediaSourceHolder =
                sourceHolderPriorityList[indexForSourceHolderToPreload]
            if (preloadingHolder.isReleased) {
                return false
            }
            this.targetPreloadStatusOfCurrentPreloadingSource =
                targetPreloadStatusControl.getTargetPreloadStatus(preloadingHolder.rankingData)
            preloadMediaSourceHolderInternal(
                preloadingHolder, targetPreloadStatusOfCurrentPreloadingSource!!
            )
            return true
        }
        return false
    }

    private fun verifyApplicationThread() {
        check(Looper.myLooper() == applicationHandler.looper) { "Preload manager is accessed on the wrong thread." }
    }

    protected open inner class MediaSourceHolder(
        val mediaItem: MediaItem,
        val rankingData: T,
        open @set:Synchronized @get:Synchronized var mediaSource: MediaSource
    ) : Comparable<MediaSourceHolder> {
        var isReleased: Boolean = false
            private set

        fun release() {
            this.isReleased = true
        }

        override fun compareTo(o: MediaSourceHolder): Int {
            return rankingDataComparator.compare(this.rankingData, o.rankingData)
        }
    }

    private inner class MediaSourceHolderMap {
        @GuardedBy("this")
        private val mediaItemToMediaSourceHolder = HashMap<MediaItem, MediaSourceHolder>()

        @GuardedBy("this")
        private val mediaSourceToMediaItem = HashMap<MediaSource, MediaItem>()

        @Synchronized
        fun put(
            mediaItem: MediaItem, mediaSource: MediaSource, mediaSourceHolder: MediaSourceHolder
        ) {
            mediaItemToMediaSourceHolder[mediaItem] = mediaSourceHolder
            mediaSourceToMediaItem[mediaSource] = mediaItem
        }

        @Synchronized
        fun values(): MutableCollection<MediaSourceHolder> {
            return mediaItemToMediaSourceHolder.values
        }

        @Synchronized
        fun size(): Int {
            return mediaItemToMediaSourceHolder.size
        }

        @Synchronized
        fun containsKey(mediaItem: MediaItem?): Boolean {
            return mediaItemToMediaSourceHolder.containsKey(mediaItem)
        }

        @Synchronized
        fun get(mediaItem: MediaItem?): MediaSourceHolder? {
            return mediaItemToMediaSourceHolder[mediaItem]
        }

        @Synchronized
        fun get(mediaSource: MediaSource?): MediaSourceHolder? {
            val mediaItem = mediaSourceToMediaItem[mediaSource]
            if (mediaItem != null) {
                return mediaItemToMediaSourceHolder[mediaItem]
            }
            return null
        }

        @Synchronized
        fun remove(mediaItem: MediaItem?): Boolean {
            val mediaSourceHolder: BasePreloadManager<T, PreloadStatusT>.MediaSourceHolder =
                mediaItemToMediaSourceHolder.remove(mediaItem) ?: return false
            mediaSourceToMediaItem.remove(mediaSourceHolder.mediaSource)
            return true
        }

        @Synchronized
        fun remove(mediaSource: MediaSource?): Boolean {
            val mediaItem = mediaSourceToMediaItem.remove(mediaSource) ?: return false
            mediaItemToMediaSourceHolder.remove(mediaItem)
            return true
        }

        @Synchronized
        fun clear() {
            mediaItemToMediaSourceHolder.clear()
            mediaSourceToMediaItem.clear()
        }
    }
}
