package androidx.media3.exoplayer.source

import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.upstream.Allocator
import org.checkerframework.checker.nullness.qual.RequiresNonNull
import kotlin.math.max

class MaskingMediaSource(mediaSource: MediaSource, useLazyPreparation: Boolean) :
    WrappingMediaSource(mediaSource) {
    private val useLazyPreparation: Boolean = useLazyPreparation && mediaSource.isSingleWindow
    private val window: Timeline.Window = Timeline.Window()
    private val period: Timeline.Period = Timeline.Period()

    private var timeline: MaskingTimeline
    private var unpreparedMaskingMediaPeriod: MaskingMediaPeriod? = null
    private var hasStartedPreparing = false
    private var isPrepared = false
    private var hasRealTimeline = false

    init {
        val initialTimeline = mediaSource.initialTimeline
        if (initialTimeline != null) {
            timeline =
                MaskingTimeline.createWithRealTimeline(initialTimeline, null, null)
            hasRealTimeline = true
        } else {
            timeline = MaskingTimeline.createWithPlaceholderTimeline(mediaSource.mediaItem)
        }
    }

    fun getTimeline(): Timeline {
        return timeline
    }

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        return mediaSource.canUpdateMediaItem(mediaItem)
    }

    override fun updateMediaItem(mediaItem: MediaItem) {
        if (hasRealTimeline) {
            val tl = TimelineWithUpdatedMediaItem.create(timeline.timeline, mediaItem)
            timeline = timeline.cloneWithUpdatedTimeline(tl)
        } else {
            timeline = MaskingTimeline.createWithPlaceholderTimeline(mediaItem)
        }
        mediaSource.updateMediaItem(mediaItem)
    }

    override fun prepareSourceInternal() {
        if (!useLazyPreparation) {
            hasStartedPreparing = true
            prepareChildSource()
        }
    }

    override fun createPeriod(
        id: MediaPeriodId, allocator: Allocator, startPositionUs: Long
    ): MaskingMediaPeriod {
        val mediaPeriod = MaskingMediaPeriod(id, allocator, startPositionUs)
        mediaPeriod.setMediaSource(mediaSource)
        if (isPrepared) {
            val idInSource = id.copyWithPeriodUid(getInternalPeriodUid(id.periodUid))
            mediaPeriod.createPeriod(idInSource)
        } else {
            // We should have at most one media period while source is unprepared because the duration is
            // unset and we don't load beyond periods with unset duration. We need to figure out how to
            // handle the prepare positions of multiple deferred media periods, should that ever change.
            unpreparedMaskingMediaPeriod = mediaPeriod
            if (!hasStartedPreparing) {
                hasStartedPreparing = true
                prepareChildSource()
            }
        }
        return mediaPeriod
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        (mediaPeriod as MaskingMediaPeriod).releasePeriod()
        if (mediaPeriod === unpreparedMaskingMediaPeriod) {
            unpreparedMaskingMediaPeriod = null
        }
    }

    override fun releaseSourceInternal() {
        isPrepared = false
        hasStartedPreparing = false
        super.releaseSourceInternal()
    }

    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        var idForMaskingPeriodPreparation: MediaPeriodId? = null
        if (isPrepared) {
            timeline = timeline.cloneWithUpdatedTimeline(newTimeline)
            if (unpreparedMaskingMediaPeriod != null) {
                // Reset override in case the duration changed and we need to update our override.
                setPreparePositionOverrideToUnpreparedMaskingPeriod(
                    unpreparedMaskingMediaPeriod!!.preparePositionOverrideUs
                )
            }
        } else if (newTimeline.isEmpty) {
            timeline =
                if (hasRealTimeline)
                    timeline.cloneWithUpdatedTimeline(newTimeline)
                else
                    MaskingTimeline.createWithRealTimeline(
                        newTimeline,
                        Timeline.Window.SINGLE_WINDOW_UID,
                        MaskingTimeline.MASKING_EXTERNAL_PERIOD_UID
                    )
        } else {
            // Determine first period and the start position.
            // This will be:
            //  1. The default window start position if no deferred period has been created yet.
            //  2. The non-zero prepare position of the deferred period under the assumption that this is
            //     a non-zero initial seek position in the window.
            //  3. The default window start position if the deferred period has a prepare position of zero
            //     under the assumption that the prepare position of zero was used because it's the
            //     default position of the PlaceholderTimeline window. Note that this will override an
            //     intentional seek to zero for a window with a non-zero default position. This is
            //     unlikely to be a problem as a non-zero default position usually only occurs for live
            //     playbacks and seeking to zero in a live window would cause BehindLiveWindowExceptions
            //     anyway.
            newTimeline.getWindow( 0, window)
            var windowStartPositionUs = window.defaultPositionUs
            val windowUid = window.uid
            if (unpreparedMaskingMediaPeriod != null) {
                val periodPreparePositionUs = unpreparedMaskingMediaPeriod!!.preparePositionUs
                timeline.getPeriodByUid(unpreparedMaskingMediaPeriod!!.id.periodUid, period)
                val windowPreparePositionUs = period.positionInWindowUs + periodPreparePositionUs
                val oldWindowDefaultPositionUs =
                    timeline.getWindow( 0, window).defaultPositionUs
                if (windowPreparePositionUs != oldWindowDefaultPositionUs) {
                    windowStartPositionUs = windowPreparePositionUs
                }
            }
            val periodUidAndPositionUs =
                newTimeline.getPeriodPositionUs(
                    window, period,  0, windowStartPositionUs
                )
            val periodUid = periodUidAndPositionUs.first
            val periodPositionUs: Long = periodUidAndPositionUs.second!!
            timeline =
                if (hasRealTimeline)
                    timeline.cloneWithUpdatedTimeline(newTimeline)
                else
                    MaskingTimeline.createWithRealTimeline(newTimeline, windowUid, periodUid)
            if (unpreparedMaskingMediaPeriod != null) {
                val maskingPeriod = unpreparedMaskingMediaPeriod
                if (setPreparePositionOverrideToUnpreparedMaskingPeriod(periodPositionUs)) {
                    idForMaskingPeriodPreparation =
                        maskingPeriod!!.id.copyWithPeriodUid(getInternalPeriodUid(maskingPeriod.id.periodUid))
                }
            }
        }
        hasRealTimeline = true
        isPrepared = true
        refreshSourceInfo(this.timeline)
        if (idForMaskingPeriodPreparation != null) {
            unpreparedMaskingMediaPeriod!!.createPeriod(idForMaskingPeriodPreparation)
        }
    }

    override fun getMediaPeriodIdForChildMediaPeriodId(mediaPeriodId: MediaPeriodId): MediaPeriodId {
        return mediaPeriodId.copyWithPeriodUid(getExternalPeriodUid(mediaPeriodId.periodUid))
    }

    private fun getInternalPeriodUid(externalPeriodUid: Any): Any {
        return if (timeline.replacedInternalPeriodUid != null
            && externalPeriodUid == MaskingTimeline.MASKING_EXTERNAL_PERIOD_UID
        )
            timeline.replacedInternalPeriodUid!!
        else
            externalPeriodUid
    }

    private fun getExternalPeriodUid(internalPeriodUid: Any): Any {
        return if (timeline.replacedInternalPeriodUid != null
            && timeline.replacedInternalPeriodUid == internalPeriodUid
        )
            MaskingTimeline.MASKING_EXTERNAL_PERIOD_UID
        else
            internalPeriodUid
    }

    @RequiresNonNull("unpreparedMaskingMediaPeriod")
    private fun setPreparePositionOverrideToUnpreparedMaskingPeriod(
        preparePositionOverrideUs: Long
    ): Boolean {
        var preparePositionOverrideUs = preparePositionOverrideUs
        val maskingPeriod = unpreparedMaskingMediaPeriod
        val maskingPeriodIndex = timeline.getIndexOfPeriod(maskingPeriod!!.id.periodUid)
        if (maskingPeriodIndex == C.INDEX_UNSET) {
            // The new timeline doesn't contain this period anymore. This can happen if the media source
            // has multiple periods and removed the first period with a timeline update. Ignore the
            // update, as the non-existing period will be released anyway as soon as the player receives
            // this new timeline.
            return false
        }
        val periodDurationUs = timeline.getPeriod(maskingPeriodIndex, period).durationUs
        if (periodDurationUs != C.TIME_UNSET) {
            // Ensure the overridden position doesn't exceed the period duration.
            if (preparePositionOverrideUs >= periodDurationUs) {
                preparePositionOverrideUs = max(0, periodDurationUs - 1)
            }
        }
        maskingPeriod.overridePreparePositionUs(preparePositionOverrideUs)
        return true
    }

    private class MaskingTimeline(
        timeline: Timeline,
        val replacedInternalWindowUid: Any?,
        val replacedInternalPeriodUid: Any?
    ) : ForwardingTimeline(timeline) {
        fun cloneWithUpdatedTimeline(timeline: Timeline): MaskingTimeline {
            return MaskingTimeline(timeline, replacedInternalWindowUid, replacedInternalPeriodUid)
        }

        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long
        ): Window {
            timeline.getWindow(windowIndex, window, defaultPositionProjectionUs)
            if (window.uid == replacedInternalWindowUid) {
                window.uid = Window.SINGLE_WINDOW_UID
            }
            return window
        }

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            timeline.getPeriod(periodIndex, period, setIds)
            if (period.uid == replacedInternalPeriodUid && setIds) {
                period.uid = MASKING_EXTERNAL_PERIOD_UID
            }
            return period
        }

        override fun getIndexOfPeriod(uid: Any): Int {
            return timeline.getIndexOfPeriod(
                if (MASKING_EXTERNAL_PERIOD_UID == uid && replacedInternalPeriodUid != null)
                    replacedInternalPeriodUid
                else
                    uid
            )
        }

        override fun getUidOfPeriod(periodIndex: Int): Any {
            val uid = timeline.getUidOfPeriod(periodIndex)
            return if (uid == replacedInternalPeriodUid) MASKING_EXTERNAL_PERIOD_UID else uid
        }

        companion object {
            val MASKING_EXTERNAL_PERIOD_UID: Any = Any()

            fun createWithPlaceholderTimeline(mediaItem: MediaItem): MaskingTimeline {
                return MaskingTimeline(
                    PlaceholderTimeline(mediaItem),
                    Window.SINGLE_WINDOW_UID,
                    MASKING_EXTERNAL_PERIOD_UID
                )
            }

            fun createWithRealTimeline(
                timeline: Timeline, firstWindowUid: Any?, firstPeriodUid: Any?
            ): MaskingTimeline {
                return MaskingTimeline(timeline, firstWindowUid, firstPeriodUid)
            }
        }
    }

    class PlaceholderTimeline(private val mediaItem: MediaItem) : Timeline() {
        override val windowCount= 1

        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long
        ): Window {
            window.set(
                Window.SINGLE_WINDOW_UID,
                mediaItem,  
                null,  
                C.TIME_UNSET,  
                C.TIME_UNSET,  
                C.TIME_UNSET,  
                false,  // Dynamic window to indicate pending timeline updates.
                
                true,  
                null,  
                0,  
                C.TIME_UNSET,  
                0,  
                0,  
                0
            )
            window.isPlaceholder = true
            return window
        }

        override val periodCount= 1

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            period.set( 
                if (setIds) 0 else null,  
                if (setIds) MaskingTimeline.MASKING_EXTERNAL_PERIOD_UID else null,  
                0,  
                C.TIME_UNSET,  
                0,  
                AdPlaybackState.NONE,  
                true
            )
            return period
        }

        override fun getIndexOfPeriod(uid: Any): Int {
            return if (uid === MaskingTimeline.MASKING_EXTERNAL_PERIOD_UID) 0 else C.INDEX_UNSET
        }

        override fun getUidOfPeriod(periodIndex: Int): Any {
            return MaskingTimeline.MASKING_EXTERNAL_PERIOD_UID
        }
    }
}
