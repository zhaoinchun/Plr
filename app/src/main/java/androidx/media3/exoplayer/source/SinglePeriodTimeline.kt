package androidx.media3.exoplayer.source

import android.net.Uri
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import com.google.common.base.Preconditions

class SinglePeriodTimeline(
    private val presentationStartTimeMs: Long,
    private val windowStartTimeMs: Long,
    private val elapsedRealtimeEpochOffsetMs: Long,
    private val periodDurationUs: Long,
    private val windowDurationUs: Long,
    private val windowPositionInPeriodUs: Long,
    private val windowDefaultStartPositionUs: Long,
    private val isSeekable: Boolean,
    private val isDynamic: Boolean,
    private val suppressPositionProjection: Boolean,
    private val manifest: Any?,
    val mediaItem: MediaItem,
    private val liveConfiguration: MediaItem.LiveConfiguration?
) : Timeline() {

    constructor(
        durationUs: Long,
        isSeekable: Boolean,
        isDynamic: Boolean,
        useLiveConfiguration: Boolean,
        manifest: Any?,
        mediaItem: MediaItem
    ) : this(
        durationUs,
        durationUs,  
        0,  
        0,
        isSeekable,
        isDynamic,
        useLiveConfiguration,
        manifest,
        mediaItem
    )

    constructor(
        periodDurationUs: Long,
        windowDurationUs: Long,
        windowPositionInPeriodUs: Long,
        windowDefaultStartPositionUs: Long,
        isSeekable: Boolean,
        isDynamic: Boolean,
        useLiveConfiguration: Boolean,
        manifest: Any?,
        mediaItem: MediaItem
    ) : this( 
        C.TIME_UNSET,  
        C.TIME_UNSET,  
        C.TIME_UNSET,
        periodDurationUs,
        windowDurationUs,
        windowPositionInPeriodUs,
        windowDefaultStartPositionUs,
        isSeekable,
        isDynamic,  
        false,
        manifest,
        mediaItem,
        if (useLiveConfiguration) mediaItem.liveConfiguration else null
    )

    override val windowCount: Int
        get() = 1

    // Provide backwards compatibility.
    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long
    ): Window {
        Preconditions.checkElementIndex(windowIndex, 1)
        var windowDefaultStartPositionUs = this.windowDefaultStartPositionUs
        if (isDynamic && !suppressPositionProjection && defaultPositionProjectionUs != 0L) {
            if (windowDurationUs == C.TIME_UNSET) {
                // Don't allow projection into a window that has an unknown duration.
                windowDefaultStartPositionUs = C.TIME_UNSET
            } else {
                windowDefaultStartPositionUs += defaultPositionProjectionUs
                if (windowDefaultStartPositionUs > windowDurationUs) {
                    // The projection takes us beyond the end of the window.
                    windowDefaultStartPositionUs = C.TIME_UNSET
                }
            }
        }
        return window.set(
            Window.SINGLE_WINDOW_UID,
            mediaItem,
            manifest,
            presentationStartTimeMs,
            windowStartTimeMs,
            elapsedRealtimeEpochOffsetMs,
            isSeekable,
            isDynamic,
            liveConfiguration,
            windowDefaultStartPositionUs,
            windowDurationUs,  
            0,  
            0,
            windowPositionInPeriodUs
        )
    }

    override val periodCount: Int
        get() = 1

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        Preconditions.checkElementIndex(periodIndex, 1)
        val uid: Any? = if (setIds) UID else null
        return period.set( 
            null,
            uid,  
            0,
            periodDurationUs,  
            -windowPositionInPeriodUs,
            AdPlaybackState.NONE,  
            false
        )
    }

    override fun getIndexOfPeriod(uid: Any): Int {
        return if (UID == uid) 0 else C.INDEX_UNSET
    }

    override fun getUidOfPeriod(periodIndex: Int): Any {
        Preconditions.checkElementIndex(periodIndex, 1)
        return UID
    }

    companion object {
        private val UID = Any()
        private val MEDIA_ITEM =
            MediaItem.Builder().setMediaId("SinglePeriodTimeline").setUri(Uri.EMPTY).build()
    }
}
