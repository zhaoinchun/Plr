package androidx.media3.exoplayer.source

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline

import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.base.Supplier
import java.io.IOException

interface MediaSource {
    interface Factory {
        fun setCmcdConfigurationFactory(cmcdConfigurationFactory: CmcdConfiguration.Factory): Factory {
            // do nothing
            return this
        }

        fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): Factory

        fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): Factory

        @Deprecated(
            """This method (and all support for 'legacy' subtitle decoding during rendering)
          will be removed in a future release.
      """
        )
        fun experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction: Boolean): Factory {
            return this
        }

        fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): Factory {
            return this
        }

        fun experimentalSetCodecsToParseWithinGopSampleDependencies(
            codecsToParseWithinGopSampleDependencies: @C.VideoCodecFlags Int
        ): Factory {
            return this
        }

        fun setDownloadExecutor(downloadExecutor: Supplier<ReleasableExecutor>): Factory {
            return this
        }

        @get:C.ContentType
        val supportedTypes: IntArray
        fun createMediaSource(mediaItem: MediaItem): MediaSource
    }

    fun interface MediaSourceCaller {
        fun onSourceInfoRefreshed(source: MediaSource, timeline: Timeline)
    }

    data class MediaPeriodId(
        val periodUid: Any,
        val adGroupIndex: Int,
        val adIndexInAdGroup: Int,
        val windowSequenceNumber: Long,
        val nextAdGroupIndex: Int = C.INDEX_UNSET
    ) {
        constructor(periodUid: Any, windowSequenceNumber: Long = C.INDEX_UNSET.toLong()) : this(
            periodUid,
            C.INDEX_UNSET,
            C.INDEX_UNSET,
            windowSequenceNumber,
            C.INDEX_UNSET
        )

        constructor(periodUid: Any, windowSequenceNumber: Long, nextAdGroupIndex: Int) : this(
            periodUid,
            C.INDEX_UNSET,
            C.INDEX_UNSET,
            windowSequenceNumber,
            nextAdGroupIndex
        )

        fun copyWithPeriodUid(newPeriodUid: Any): MediaPeriodId {
            return if (periodUid == newPeriodUid)
                this
            else copy(periodUid = newPeriodUid)
        }

        fun copyWithWindowSequenceNumber(windowSequenceNumber: Long): MediaPeriodId {
            return if (this.windowSequenceNumber == windowSequenceNumber)
                this
            else copy(windowSequenceNumber = windowSequenceNumber)
        }

        fun equalsExceptNextAdGroupIndex(mediaPeriodId: MediaPeriodId?): Boolean {
            mediaPeriodId ?: return false
            if (this === mediaPeriodId) {
                return true
            }
            return periodUid == mediaPeriodId.periodUid
                    && adGroupIndex == mediaPeriodId.adGroupIndex
                    && adIndexInAdGroup == mediaPeriodId.adIndexInAdGroup
                    && windowSequenceNumber == mediaPeriodId.windowSequenceNumber
        }

        val isAd: Boolean
            get() = adGroupIndex != C.INDEX_UNSET
    }

    fun addEventListener(handler: Handler, eventListener: MediaSourceEventListener)
    fun removeEventListener(eventListener: MediaSourceEventListener)
    fun addDrmEventListener(handler: Handler, eventListener: DrmSessionEventListener)
    fun removeDrmEventListener(eventListener: DrmSessionEventListener)

    val initialTimeline: Timeline?
        get() = null
    val isSingleWindow: Boolean
        get() = true
    val mediaItem: MediaItem

    fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        return false
    }

    fun updateMediaItem(mediaItem: MediaItem) {
    }

    @Deprecated("Use {@link #prepareSource(MediaSourceCaller, PlayerId, BandwidthMeter)} instead.")
    fun prepareSource(
        caller: MediaSourceCaller,
        mediaTransferListener: TransferListener,
        playerId: PlayerId
    ) {
        throw IllegalStateException(
            "prepareSource(MediaSourceCaller, TransferListener, PlayerId) not implemented"
        )
    }

    fun prepareSource(
        caller: MediaSourceCaller, playerId: PlayerId, bandwidthMeter: BandwidthMeter
    ) {
        prepareSource(caller, bandwidthMeter.transferListener!!, playerId)
    }

    @Throws(IOException::class)
    fun maybeThrowSourceInfoRefreshError()

    fun enable(caller: MediaSourceCaller)
    fun createPeriod(id: MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod
    fun releasePeriod(mediaPeriod: MediaPeriod)
    fun disable(caller: MediaSourceCaller)
    fun releaseSource(caller: MediaSourceCaller)
}
