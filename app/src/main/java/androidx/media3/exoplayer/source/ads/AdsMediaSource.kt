package androidx.media3.exoplayer.source.ads

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.IntDef
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.AdViewProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline

import androidx.media3.common.util.Util.createHandlerForCurrentLooper
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.ClippingMediaPeriod
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MaskingMediaPeriod
import androidx.media3.exoplayer.source.MaskingMediaPeriod.PrepareListener
import androidx.media3.exoplayer.source.MaskingMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.upstream.Allocator
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.io.IOException
import java.util.Arrays
import kotlin.Array
import kotlin.concurrent.Volatile
import kotlin.math.max

class AdsMediaSource(
    contentMediaSource: MediaSource,
    private val adTagDataSpec: DataSpec,
    val adsId: Any,
    private val adMediaSourceFactory: MediaSource.Factory,
    private val adsLoader: AdsLoader,
    private val adViewProvider: AdViewProvider,
    private val useLazyContentSourcePreparation: Boolean = true,
    private val useAdMediaSourceClipping: Boolean = false
) : CompositeMediaSource<MediaPeriodId>() {
    class AdLoadException private constructor(
        val type: @Type Int, cause: Exception?
    ) : IOException(cause) {
        @MustBeDocumented
        @Retention(AnnotationRetention.SOURCE)
        @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
        @IntDef(value = [TYPE_AD, TYPE_AD_GROUP, TYPE_ALL_ADS, TYPE_UNEXPECTED])
        annotation class Type

        val runtimeExceptionForUnexpected: RuntimeException
            get() {
                Preconditions.checkState(type == TYPE_UNEXPECTED)
                return cause!! as java.lang.RuntimeException
            }

        companion object {
            const val TYPE_AD: Int = 0
            const val TYPE_AD_GROUP: Int = 1
            const val TYPE_ALL_ADS: Int = 2
            const val TYPE_UNEXPECTED: Int = 3
            fun createForAd(error: Exception?): AdLoadException {
                return AdLoadException(TYPE_AD, error)
            }

            fun createForAdGroup(error: Exception?, adGroupIndex: Int): AdLoadException {
                return AdLoadException(
                    TYPE_AD_GROUP, IOException("Failed to load ad group $adGroupIndex", error)
                )
            }

            fun createForAllAds(error: Exception?): AdLoadException {
                return AdLoadException(TYPE_ALL_ADS, error)
            }

            fun createForUnexpected(error: java.lang.RuntimeException?): AdLoadException {
                return AdLoadException(TYPE_UNEXPECTED, error)
            }
        }
    }

    private val contentMediaSource = MaskingMediaSource(
        contentMediaSource, useLazyContentSourcePreparation
    )
    val contentDrmConfiguration: MediaItem.DrmConfiguration? =
        contentMediaSource.mediaItem.localConfiguration!!.drmConfiguration

    private val mainHandler = Handler(Looper.getMainLooper())
    private val period = Timeline.Period()
    private val activeMediaSourceHolders = mutableListOf<AdMediaSourceHolder>()
    private val activeContentClippingMediaPeriods = mutableMapOf<ClippingMediaPeriod, Int>()

    // Accessed on the player thread.
    private var componentListener: ComponentListener? = null
    private var contentTimeline: Timeline? = null
    private var adPlaybackState: AdPlaybackState? = null
    private var adMediaSourceHolders: Array<Array<AdMediaSourceHolder?>> = arrayOf()
    private var playerHandler: Handler? = null

    init {
        adsLoader.setSupportedContentTypes(adMediaSourceFactory.supportedTypes)
    }

    override val mediaItem: MediaItem
        get() = contentMediaSource.mediaItem

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        return getAdsConfiguration(mediaItem) == getAdsConfiguration(mediaItem)
                && contentMediaSource.canUpdateMediaItem(mediaItem)
    }

    override fun updateMediaItem(mediaItem: MediaItem) {
        contentMediaSource.updateMediaItem(mediaItem)
    }

    protected override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        this.playerHandler = createHandlerForCurrentLooper()
        val componentListener = ComponentListener(playerHandler!!)
        this.componentListener = componentListener
        contentTimeline = contentMediaSource.getTimeline()
        prepareChildSource(CHILD_SOURCE_MEDIA_PERIOD_ID, contentMediaSource)
        mainHandler.post {
            adsLoader.start(
                this,
                adTagDataSpec,
                adsId,
                adViewProvider,
                componentListener
            )
        }
    }

    override fun createPeriod(
        id: MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        val adPlaybackState = this.adPlaybackState!!
        if (adPlaybackState.adGroupCount > 0 && id.isAd) {
            val adGroupIndex = id.adGroupIndex
            val adIndexInAdGroup = id.adIndexInAdGroup
            if (adMediaSourceHolders[adGroupIndex].size <= adIndexInAdGroup) {
                // todo
                adMediaSourceHolders[adGroupIndex] = adMediaSourceHolders[adGroupIndex].copyOf()
            }
            var adMediaSourceHolder = adMediaSourceHolders[adGroupIndex][adIndexInAdGroup]
            if (adMediaSourceHolder == null) {
                var endPositionUs = C.TIME_END_OF_SOURCE
                if (useAdMediaSourceClipping) {
                    val adGroup = adPlaybackState.getAdGroup(id.adGroupIndex)
                    if (adGroup.durationsUs.size > adIndexInAdGroup) {
                        val adDurationUs = adGroup.durationsUs[adIndexInAdGroup]
                        if (adDurationUs != C.TIME_UNSET) {
                            endPositionUs = adDurationUs
                        }
                    }
                }
                adMediaSourceHolder = AdMediaSourceHolder(id, endPositionUs)
                adMediaSourceHolders[adGroupIndex][adIndexInAdGroup] = adMediaSourceHolder
                activeMediaSourceHolders.add(adMediaSourceHolder)
                maybeUpdateAdMediaSources()
            }
            return adMediaSourceHolder.createMediaPeriod(
                id, allocator, startPositionUs, useAdMediaSourceClipping
            )
        } else {
            val contentMediaPeriodId = MediaPeriodId(id.periodUid, id.windowSequenceNumber)
            val maskingMediaPeriod =
                MaskingMediaPeriod(contentMediaPeriodId, allocator, startPositionUs)
            maskingMediaPeriod.setMediaSource(contentMediaSource)
            maskingMediaPeriod.createPeriod(contentMediaPeriodId)
            if (id.nextAdGroupIndex == C.INDEX_UNSET) {
                return maskingMediaPeriod
            }
            val nextAdGroupTimeUs = adPlaybackState.getAdGroup(id.nextAdGroupIndex).timeUs
            val clippingMediaPeriod =
                ClippingMediaPeriod(
                    maskingMediaPeriod,
                    true,
                    0,
                    nextAdGroupTimeUs
                )
            activeContentClippingMediaPeriods[clippingMediaPeriod] = id.nextAdGroupIndex
            return clippingMediaPeriod
        }
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val maskingMediaPeriod =
            (if (mediaPeriod is ClippingMediaPeriod)
                mediaPeriod.mediaPeriod
            else
                mediaPeriod) as MaskingMediaPeriod
        val id = maskingMediaPeriod.id
        if (id.isAd) {
            val adMediaSourceHolder: AdMediaSourceHolder =
                adMediaSourceHolders[id.adGroupIndex][id.adIndexInAdGroup]!!
            adMediaSourceHolder.releaseMediaPeriod(mediaPeriod)
            if (adMediaSourceHolder.isInactive) {
                adMediaSourceHolder.release()
                adMediaSourceHolders[id.adGroupIndex][id.adIndexInAdGroup] = null
                activeMediaSourceHolders.remove(adMediaSourceHolder)
            }
        } else {
            if (mediaPeriod is ClippingMediaPeriod) {
                activeContentClippingMediaPeriods.remove(mediaPeriod)
            }
            maskingMediaPeriod.releasePeriod()
        }
    }

    override fun releaseSourceInternal() {
        super.releaseSourceInternal()
        val componentListener = this.componentListener!!
        this.componentListener = null
        this.playerHandler = null
        componentListener.stop()
        contentTimeline = null
        adPlaybackState = null
        adMediaSourceHolders = arrayOf()
        mainHandler.post(Runnable { adsLoader.stop(this, componentListener) })
    }

    override fun onChildSourceInfoRefreshed(
        childSourceId: MediaPeriodId?, mediaSource: MediaSource, newTimeline: Timeline
    ) {
        if (childSourceId!!.isAd) {
            val adGroupIndex = childSourceId.adGroupIndex
            val adIndexInAdGroup = childSourceId.adIndexInAdGroup
            adMediaSourceHolders[adGroupIndex][adIndexInAdGroup]!!.handleSourceInfoRefresh(
                newTimeline
            )
            maybeUpdateSourceInfo()
        } else {
            Preconditions.checkArgument(newTimeline.periodCount == 1)
            contentTimeline = newTimeline
            mainHandler.post {
                val sourceInfoUpdated =
                    adsLoader.handleContentTimelineChanged(this, newTimeline)
                // The ad playback state must not be updated when lazy preparation is used.
                Preconditions.checkState(!sourceInfoUpdated || !useLazyContentSourcePreparation)
                // If the source isn't updated by the ads loader we do, if not already published.
                if (!sourceInfoUpdated && !useLazyContentSourcePreparation) {
                    playerHandler!!.post(Runnable { this.maybeUpdateSourceInfo() })
                }
            }
            if (useLazyContentSourcePreparation) {
                // If lazy preparation is used, the ads loader is not allowed to update the ad playback
                // state on timeline change. We can synchronously publish the timeline as early as possible.
                maybeUpdateSourceInfo()
            }
        }
    }

    override fun getMediaPeriodIdForChildMediaPeriodId(
        childSourceId: MediaPeriodId?, mediaPeriodId: MediaPeriodId
    ): MediaPeriodId? {
        // The child id for the content period is just CHILD_SOURCE_MEDIA_PERIOD_ID. That's why
        // we need to forward the reported mediaPeriodId in this case.
        return if (childSourceId!!.isAd) childSourceId else mediaPeriodId
    }

    // Internal methods.
    private fun onAdPlaybackState(adPlaybackState: AdPlaybackState) {
        if (this.adPlaybackState == null) {
            val playableAdGroupCount =
                (adPlaybackState.adGroupCount
                        - (if (adPlaybackState.endsWithLivePostrollPlaceHolder()) 1 else 0))
            val adMediaSourceHolders =
                arrayOfNulls<Array<AdMediaSourceHolder?>>(playableAdGroupCount)
            Arrays.fill(adMediaSourceHolders, arrayOf<AdMediaSourceHolder>())
            this.adMediaSourceHolders = adMediaSourceHolders as Array<Array<AdMediaSourceHolder?>>
        } else {
            val adGroupInsertionCount: Int =
                checkValidAdPlaybackStateUpdate(this.adPlaybackState!!, adPlaybackState)
            if (adGroupInsertionCount > 0) {
                adMediaSourceHolders =
                    growAdMediaSourceHolderGrid(adMediaSourceHolders, adGroupInsertionCount)
            }
            if (useAdMediaSourceClipping) {
                for (i in activeMediaSourceHolders.indices) {
                    val adMediaSourceHolder = activeMediaSourceHolders[i]
                    val id = adMediaSourceHolder.id
                    val adDurationUs =
                        adPlaybackState.getAdGroup(id.adGroupIndex).durationsUs[id.adIndexInAdGroup]
                    if (adDurationUs != C.TIME_UNSET) {
                        adMediaSourceHolder.endPositionUs = (adDurationUs)
                    }
                }
            }
            for (activeClippingPeriod in activeContentClippingMediaPeriods.entries) {
                val nextAdGroupIndex: Int = activeClippingPeriod.value
                val nextAdGroupTimeUs = adPlaybackState.getAdGroup(nextAdGroupIndex).timeUs
                activeClippingPeriod
                    .key
                    .updateClipping(0, nextAdGroupTimeUs)
            }
        }
        this.adPlaybackState = adPlaybackState
        maybeUpdateAdMediaSources()
        maybeUpdateSourceInfo()
    }

    private fun maybeUpdateAdMediaSources() {
        val adPlaybackState = this.adPlaybackState ?: return
        for (adGroupIndex in adMediaSourceHolders.indices) {
            for (adIndexInAdGroup in this.adMediaSourceHolders[adGroupIndex].indices) {
                val adMediaSourceHolder =
                    this.adMediaSourceHolders[adGroupIndex][adIndexInAdGroup]
                val adGroup = adPlaybackState.getAdGroup(adGroupIndex)
                if (adMediaSourceHolder != null && !adMediaSourceHolder.hasMediaSource() && adIndexInAdGroup < adGroup.mediaItems.size) {
                    var adMediaItem = adGroup.mediaItems[adIndexInAdGroup]
                    if (adMediaItem != null) {
                        // Propagate the content's DRM config into the ad media source.
                        if (contentDrmConfiguration != null) {
                            adMediaItem =
                                adMediaItem.buildUpon().setDrmConfiguration(contentDrmConfiguration)
                                    .build()
                        }
                        val adMediaSource = adMediaSourceFactory.createMediaSource(adMediaItem)
                        adMediaSourceHolder.initializeWithMediaSource(adMediaSource, adMediaItem)
                    }
                }
            }
        }
    }

    private fun maybeUpdateSourceInfo() {
        val contentTimeline = this.contentTimeline
        if (adPlaybackState != null && contentTimeline != null) {
            if (adPlaybackState!!.adGroupCount == 0) {
                refreshSourceInfo(contentTimeline)
            } else {
                adPlaybackState = adPlaybackState!!.withAdDurationsUs(this.adDurationsUs)
                refreshSourceInfo(SinglePeriodAdTimeline(contentTimeline, adPlaybackState!!))
            }
        }
    }

    @get:RequiresNonNull("adPlaybackState")
    private val adDurationsUs: Array<LongArray?>
        get() {
            val adPlaybackState = this.adPlaybackState!!
            val hasPostRollPlaceholder =
                adPlaybackState.endsWithLivePostrollPlaceHolder()
            val adGroupCount =
                adMediaSourceHolders.size + (if (hasPostRollPlaceholder) 1 else 0)
            val adDurationsUs =
                arrayOfNulls<LongArray>(adGroupCount)
            for (i in adMediaSourceHolders.indices) {
                val adCount =
                    if (useAdMediaSourceClipping) max(
                        adPlaybackState.getAdGroup(i).count,
                        0
                    ) else
                        adMediaSourceHolders[i].size
                adDurationsUs[i] = LongArray(adCount)
                for (j in 0..<adCount) {
                    val adDurationUs =
                        if (adPlaybackState.getAdGroup(i).durationsUs.size > j)
                            adPlaybackState.getAdGroup(i).durationsUs[j]
                        else
                            C.TIME_UNSET
                    if (adDurationUs != C.TIME_UNSET && useAdMediaSourceClipping) {
                        adDurationsUs[i]!![j] = adDurationUs
                    } else if (adMediaSourceHolders[i].size > j && adMediaSourceHolders[i][j] != null) {
                        adDurationsUs[i]!![j] = adMediaSourceHolders[i][j]!!.durationUs
                    } else {
                        adDurationsUs[i]!![j] = C.TIME_UNSET
                    }
                }
            }
            if (hasPostRollPlaceholder) {
                // Set the pseudo-durations of the placeholder that is not represented by the holders.
                adDurationsUs[adGroupCount - 1] = LongArray(0)
            }
            return adDurationsUs
        }

    private inner class ComponentListener(private val playerHandler: Handler) :
        AdsLoader.EventListener {
        @Volatile
        private var stopped = false

        fun stop() {
            stopped = true
            playerHandler.removeCallbacksAndMessages(null)
        }

        override fun onAdPlaybackState(adPlaybackState: AdPlaybackState) {
            if (stopped) {
                return
            }
            playerHandler.post(
                Runnable {
                    if (stopped) {
                        return@Runnable
                    }
                    this@AdsMediaSource.onAdPlaybackState(adPlaybackState)
                })
        }

        override fun onAdLoadError(error: AdLoadException, dataSpec: DataSpec) {
            if (stopped) {
                return
            }
            createEventDispatcher(null)
                .loadError(
                    LoadEventInfo.Builder(
                        LoadEventInfo.newId,
                        dataSpec,
                        SystemClock.elapsedRealtime()
                    )
                        .build(),
                    C.DATA_TYPE_AD,
                    error,
                    true
                )
        }
    }

    private inner class AdPrepareListener(private val adMediaItem: MediaItem) : PrepareListener {
        override fun onPrepareComplete(mediaPeriodId: MediaPeriodId) {
            mainHandler.post {
                adsLoader.handlePrepareComplete(
                    this@AdsMediaSource,
                    mediaPeriodId.adGroupIndex,
                    mediaPeriodId.adIndexInAdGroup
                )
            }
        }

        override fun onPrepareError(mediaPeriodId: MediaPeriodId, exception: IOException) {
            createEventDispatcher(mediaPeriodId)
                .loadError(
                    LoadEventInfo.Builder(
                        LoadEventInfo.newId,
                        DataSpec(
                            adMediaItem.localConfiguration!!.uri
                        ),
                        SystemClock.elapsedRealtime()
                    )
                        .build(),
                    C.DATA_TYPE_AD,
                    AdLoadException.createForAd(exception),
                    true
                )
            mainHandler.post(
                Runnable {
                    adsLoader.handlePrepareError(
                        this@AdsMediaSource,
                        mediaPeriodId.adGroupIndex,
                        mediaPeriodId.adIndexInAdGroup,
                        exception
                    )
                })
        }
    }

    private inner class AdMediaSourceHolder(
        val id: MediaPeriodId,
        endPositionUs: Long
    ) {
        val activeMediaPeriods = mutableListOf<MediaPeriod?>()
        var adMediaItem:MediaItem? = null
        var adMediaSource:MediaSource? = null
        var timeline:Timeline? = null
        var endPositionUs: Long = endPositionUs
            set(value) {
                if (!useAdMediaSourceClipping || field != C.TIME_END_OF_SOURCE || value == C.TIME_END_OF_SOURCE) {
                    return
                }
                field = value
                for (i in activeMediaPeriods.indices) {
                    if (activeMediaPeriods[i] is ClippingMediaPeriod) {
                        (activeMediaPeriods[i] as ClippingMediaPeriod)
                            .updateClipping(0, value)
                    }
                }
            }

        fun initializeWithMediaSource(adMediaSource: MediaSource, adMediaItem: MediaItem) {
            this.adMediaSource = adMediaSource
            this.adMediaItem = adMediaItem
            for (i in activeMediaPeriods.indices) {
                val maskingMediaPeriod = getActiveMaskingMediaPeriod(i)
                maskingMediaPeriod.setMediaSource(adMediaSource)
                maskingMediaPeriod.setPrepareListener(AdPrepareListener(adMediaItem))
            }
            prepareChildSource(id, adMediaSource)
        }

        fun createMediaPeriod(
            id: MediaPeriodId, allocator: Allocator, startPositionUs: Long, useClipping: Boolean
        ): MediaPeriod {
            val maskingMediaPeriod =
                MaskingMediaPeriod(id, allocator, startPositionUs)
            val mediaPeriod: MediaPeriod =
                if (useClipping)
                    ClippingMediaPeriod(
                        maskingMediaPeriod,
                        false,
                        startPositionUs,
                        endPositionUs
                    )
                else
                    maskingMediaPeriod
            activeMediaPeriods.add(mediaPeriod)
            if (adMediaSource != null) {
                maskingMediaPeriod.setMediaSource(adMediaSource!!)
                maskingMediaPeriod.setPrepareListener(
                    AdPrepareListener(adMediaItem!!)
                )
            }
            if (timeline != null) {
                val periodUid = timeline!!.getUidOfPeriod(0)
                val adSourceMediaPeriodId = MediaPeriodId(periodUid, id.windowSequenceNumber)
                maskingMediaPeriod.createPeriod(adSourceMediaPeriodId)
            }
            return mediaPeriod
        }

        fun handleSourceInfoRefresh(timeline: Timeline) {
            Preconditions.checkArgument(timeline.periodCount == 1)
            if (this.timeline == null) {
                val periodUid = timeline.getUidOfPeriod(0)
                for (i in activeMediaPeriods.indices) {
                    val maskingMediaPeriod = getActiveMaskingMediaPeriod(i)
                    val adSourceMediaPeriodId =
                        MediaPeriodId(periodUid, maskingMediaPeriod.id.windowSequenceNumber)
                    maskingMediaPeriod.createPeriod(adSourceMediaPeriodId)
                }
                endPositionUs = (endPositionUs)
            }
            this.timeline = timeline
        }

        val durationUs: Long
            get() = if (timeline == null)
                C.TIME_UNSET
            else
                timeline!!.getPeriod(0, period).durationUs

        fun releaseMediaPeriod(mediaPeriod: MediaPeriod?) {
            activeMediaPeriods.remove(mediaPeriod)
            val maskingMediaPeriod =
                (if (mediaPeriod is ClippingMediaPeriod)
                    mediaPeriod.mediaPeriod
                else
                    mediaPeriod) as MaskingMediaPeriod
            maskingMediaPeriod.releasePeriod()
        }

        fun release() {
            if (hasMediaSource()) {
                releaseChildSource(id)
            }
        }

        fun hasMediaSource(): Boolean {
            return adMediaSource != null
        }

        val isInactive: Boolean
            get() = activeMediaPeriods.isEmpty()

        fun getActiveMaskingMediaPeriod(activeMediaPeriodIndex: Int): MaskingMediaPeriod {
            val mediaPeriod = activeMediaPeriods[activeMediaPeriodIndex]
            return (if (mediaPeriod is ClippingMediaPeriod)
                mediaPeriod.mediaPeriod
            else
                mediaPeriod) as MaskingMediaPeriod
        }
    }

    companion object {
        // Used to identify the content "child" source for CompositeMediaSource.
        private val CHILD_SOURCE_MEDIA_PERIOD_ID = MediaPeriodId(Any())

        private fun checkValidAdPlaybackStateUpdate(
            oldAdPlaybackState: AdPlaybackState, newAdPlaybackState: AdPlaybackState
        ): Int {
            Preconditions.checkState(
                oldAdPlaybackState.endsWithLivePostrollPlaceHolder()
                        == newAdPlaybackState.endsWithLivePostrollPlaceHolder()
            )
            val insertionCount = newAdPlaybackState.adGroupCount - oldAdPlaybackState.adGroupCount
            Preconditions.checkState(insertionCount >= 0)
            for (i in newAdPlaybackState.removedAdGroupCount..<oldAdPlaybackState.adGroupCount) {
                val oldAdGroup = oldAdPlaybackState.getAdGroup(i)
                if (oldAdGroup.isLivePostrollPlaceholder) {
                    // Post-roll placeholder must be at the last index.
                    Preconditions.checkState(i == oldAdPlaybackState.adGroupCount - 1)
                    break
                }
                val newAdGroup = newAdPlaybackState.getAdGroup(i)
                Preconditions.checkState(oldAdGroup.timeUs == newAdGroup.timeUs)
                if (oldAdGroup.hasUnplayedAds()) {
                    Preconditions.checkState(oldAdGroup.count <= newAdGroup.count)
                    for (j in 0..<oldAdGroup.count) {
                        val oldMediaItem = oldAdGroup.mediaItems[j]
                        if (oldMediaItem != null && oldAdGroup.states[j] == AdPlaybackState.AD_STATE_AVAILABLE) {
                            Preconditions.checkState(oldMediaItem.equals(newAdGroup.mediaItems[j]))
                        }
                    }
                }
            }
            return insertionCount
        }

        private fun growAdMediaSourceHolderGrid(
            grid: Array<Array<AdMediaSourceHolder?>>, insertionCount: Int
        ): Array<Array<AdMediaSourceHolder?>> {
            val grownGrid = arrayOfNulls<Array<AdMediaSourceHolder?>>(grid.size + insertionCount)
            System.arraycopy(grid, 0, grownGrid, 0, grid.size)
            for (i in grid.size..<grownGrid.size) {
                grownGrid[i] = arrayOfNulls(0)
            }
            return grownGrid as Array<Array<AdMediaSourceHolder?>>
        }

        private fun getAdsConfiguration(mediaItem: MediaItem): MediaItem.AdsConfiguration? {
            return mediaItem.localConfiguration?.adsConfiguration
        }
    }
}
