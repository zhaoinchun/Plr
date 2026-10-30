package androidx.media3.exoplayer.source.ads

import androidx.media3.common.AdPlaybackState
import androidx.media3.common.AdViewProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.ads.AdsMediaSource.AdLoadException
import java.io.IOException

interface AdsLoader {
    interface Provider {
        fun getAdsLoader(adsConfiguration: MediaItem.AdsConfiguration): AdsLoader?
    }

    interface EventListener {
        fun onAdPlaybackState(adPlaybackState: AdPlaybackState) {}
        fun onAdLoadError(error: AdLoadException, dataSpec: DataSpec) {}
        fun onAdClicked() {}
        fun onAdTapped() {}
    }

    fun setPlayer(player: Player?)
    fun release()
    fun setSupportedContentTypes(@C.ContentType contentTypes: IntArray)

    fun start(
        adsMediaSource: AdsMediaSource,
        adTagDataSpec: DataSpec,
        adsId: Any,
        adViewProvider: AdViewProvider,
        eventListener: EventListener
    )

    fun stop(adsMediaSource: AdsMediaSource, eventListener: EventListener)

    fun handleContentTimelineChanged(
        adsMediaSource: AdsMediaSource, timeline: Timeline
    ): Boolean {
        return false
    }

    fun handlePrepareComplete(
        adsMediaSource: AdsMediaSource,
        adGroupIndex: Int,
        adIndexInAdGroup: Int
    )

    fun handlePrepareError(
        adsMediaSource: AdsMediaSource,
        adGroupIndex: Int,
        adIndexInAdGroup: Int,
        exception: IOException
    )
}
