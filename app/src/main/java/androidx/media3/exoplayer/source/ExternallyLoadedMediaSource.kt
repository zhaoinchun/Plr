package androidx.media3.exoplayer.source

import androidx.annotation.GuardedBy
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util.msToUs
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.google.common.base.Preconditions

class ExternallyLoadedMediaSource private constructor(
    @field:GuardedBy("this") override var mediaItem: MediaItem,
    private val timelineDurationUs: Long,
    private val externalLoader: ExternalLoader
) : BaseMediaSource() {
    class Factory(
        private val timelineDurationUs: Long,
        private val externalLoader: ExternalLoader
    ) :
        MediaSource.Factory {
        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: DrmSessionManagerProvider
        ): MediaSource.Factory {
            return this
        }

        override fun setLoadErrorHandlingPolicy(
            loadErrorHandlingPolicy: LoadErrorHandlingPolicy
        ): MediaSource.Factory {
            return this
        }

        override val supportedTypes = intArrayOf(C.CONTENT_TYPE_OTHER)

        override fun createMediaSource(mediaItem: MediaItem): ExternallyLoadedMediaSource {
            return ExternallyLoadedMediaSource(mediaItem, timelineDurationUs, externalLoader)
        }
    }

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        val timeline: Timeline =
            SinglePeriodTimeline(
                timelineDurationUs,  
                true,  
                false,  
                false,  
                null,
                mediaItem
            )
        refreshSourceInfo(timeline)
    }

    override fun releaseSourceInternal() {
        // Do nothing.
    }

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        val newConfiguration = mediaItem.localConfiguration
        val oldConfiguration = this.mediaItem.localConfiguration!!
        return newConfiguration != null && newConfiguration.uri == oldConfiguration.uri
                && newConfiguration.mimeType == oldConfiguration.mimeType
                && (newConfiguration.imageDurationMs == C.TIME_UNSET
                || msToUs(newConfiguration.imageDurationMs) == timelineDurationUs)
    }

    @Synchronized
    override fun updateMediaItem(mediaItem: MediaItem) {
        this.mediaItem = mediaItem
    }

    override fun maybeThrowSourceInfoRefreshError() {
        // Do nothing.
    }

    override fun createPeriod(
        id: MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        val mediaItem = mediaItem
        val config = mediaItem.localConfiguration!!
        Preconditions.checkNotNull<String>(
            config.mimeType,
            "Externally loaded mediaItems require a MIME type."
        )
        return ExternallyLoadedMediaPeriod(config.uri, config.mimeType, externalLoader)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        (mediaPeriod as ExternallyLoadedMediaPeriod).releasePeriod()
    }
}
