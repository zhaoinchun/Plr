package androidx.media3.exoplayer.ima

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IntRange
import androidx.annotation.VisibleForTesting
import androidx.media3.common.AdViewProvider
import androidx.media3.common.C
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.ads.AdsLoader
import androidx.media3.exoplayer.source.ads.AdsMediaSource
import com.google.ads.interactivemedia.v3.api.AdDisplayContainer
import com.google.ads.interactivemedia.v3.api.AdErrorEvent
import com.google.ads.interactivemedia.v3.api.AdEvent
import com.google.ads.interactivemedia.v3.api.AdsRenderingSettings
import com.google.ads.interactivemedia.v3.api.AdsRequest
import com.google.ads.interactivemedia.v3.api.CompanionAdSlot
import com.google.ads.interactivemedia.v3.api.FriendlyObstruction
import com.google.ads.interactivemedia.v3.api.FriendlyObstructionPurpose
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory
import com.google.ads.interactivemedia.v3.api.ImaSdkSettings
import com.google.ads.interactivemedia.v3.api.UiElement
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.io.IOException
import java.util.Arrays
import java.util.Collections

class ImaAdsLoader(
    context: Context,
    private val configuration: ImaUtil.Configuration,
    imaFactory: ImaUtil.ImaFactory
) : AdsLoader {
    class Builder(context: Context?) {
        private val context: Context

        private var imaSdkSettings: ImaSdkSettings? = null
        private var adErrorListener: AdErrorEvent.AdErrorListener? = null
        private var adEventListener: AdEvent.AdEventListener? = null
        private var videoAdPlayerCallback: VideoAdPlayer.VideoAdPlayerCallback? = null
        private var adMediaMimeTypes: MutableList<String?>? = null
        private var adUiElements: MutableSet<UiElement?>? = null
        private var companionAdSlots: MutableCollection<CompanionAdSlot?>? = null
        private var enableContinuousPlayback: Boolean? = null
        private var adPreloadTimeoutMs: Long
        private var vastLoadTimeoutMs: Int
        private var mediaLoadTimeoutMs: Int
        private var mediaBitrate: Int
        private var focusSkipButtonWhenAvailable = true
        private var playAdBeforeStartPosition = true
        private var debugModeEnabled = false
        private var enableCustomTabs = false
        private var imaFactory: ImaUtil.ImaFactory

        init {
            this.context = Preconditions.checkNotNull<Context?>(context).getApplicationContext()
            adPreloadTimeoutMs = DEFAULT_AD_PRELOAD_TIMEOUT_MS
            vastLoadTimeoutMs = ImaUtil.TIMEOUT_UNSET
            mediaLoadTimeoutMs = ImaUtil.TIMEOUT_UNSET
            mediaBitrate = ImaUtil.BITRATE_UNSET
            imaFactory = DefaultImaFactory()
        }

        fun setImaSdkSettings(imaSdkSettings: ImaSdkSettings?): Builder {
            this.imaSdkSettings = Preconditions.checkNotNull<ImaSdkSettings?>(imaSdkSettings)
            return this
        }

        fun setAdErrorListener(adErrorListener: AdErrorEvent.AdErrorListener?): Builder {
            this.adErrorListener =
                Preconditions.checkNotNull<AdErrorEvent.AdErrorListener?>(adErrorListener)
            return this
        }

        fun setAdEventListener(adEventListener: AdEvent.AdEventListener?): Builder {
            this.adEventListener =
                Preconditions.checkNotNull<AdEvent.AdEventListener?>(adEventListener)
            return this
        }

        fun setVideoAdPlayerCallback(
            videoAdPlayerCallback: VideoAdPlayer.VideoAdPlayerCallback?
        ): Builder {
            this.videoAdPlayerCallback =
                Preconditions.checkNotNull<VideoAdPlayer.VideoAdPlayerCallback?>(
                    videoAdPlayerCallback
                )
            return this
        }

        fun setAdUiElements(adUiElements: MutableSet<UiElement?>?): Builder {
            this.adUiElements = ImmutableSet.copyOf<UiElement?>(
                Preconditions.checkNotNull<MutableSet<UiElement?>?>(adUiElements)
            )
            return this
        }

        fun setCompanionAdSlots(companionAdSlots: MutableCollection<CompanionAdSlot?>?): Builder {
            this.companionAdSlots = ImmutableList.copyOf<CompanionAdSlot?>(
                Preconditions.checkNotNull<MutableCollection<CompanionAdSlot?>?>(companionAdSlots)
            )
            return this
        }

        fun setAdMediaMimeTypes(adMediaMimeTypes: MutableList<String?>?): Builder {
            this.adMediaMimeTypes = ImmutableList.copyOf<String?>(
                Preconditions.checkNotNull<MutableList<String?>?>(adMediaMimeTypes)
            )
            return this
        }

        fun setEnableContinuousPlayback(enableContinuousPlayback: Boolean): Builder {
            this.enableContinuousPlayback = enableContinuousPlayback
            return this
        }

        fun setAdPreloadTimeoutMs(adPreloadTimeoutMs: Long): Builder {
            Preconditions.checkArgument(adPreloadTimeoutMs == C.TIME_UNSET || adPreloadTimeoutMs > 0)
            this.adPreloadTimeoutMs = adPreloadTimeoutMs
            return this
        }

        fun setVastLoadTimeoutMs(@IntRange(from = 1) vastLoadTimeoutMs: Int): Builder {
            Preconditions.checkArgument(vastLoadTimeoutMs > 0)
            this.vastLoadTimeoutMs = vastLoadTimeoutMs
            return this
        }

        fun setMediaLoadTimeoutMs(@IntRange(from = 1) mediaLoadTimeoutMs: Int): Builder {
            Preconditions.checkArgument(mediaLoadTimeoutMs > 0)
            this.mediaLoadTimeoutMs = mediaLoadTimeoutMs
            return this
        }

        fun setEnableCustomTabs(enableCustomTabs: Boolean): Builder {
            this.enableCustomTabs = enableCustomTabs
            return this
        }

        fun setMaxMediaBitrate(@IntRange(from = 1) bitrate: Int): Builder {
            Preconditions.checkArgument(bitrate > 0)
            this.mediaBitrate = bitrate
            return this
        }

        fun setFocusSkipButtonWhenAvailable(focusSkipButtonWhenAvailable: Boolean): Builder {
            this.focusSkipButtonWhenAvailable = focusSkipButtonWhenAvailable
            return this
        }

        fun setPlayAdBeforeStartPosition(playAdBeforeStartPosition: Boolean): Builder {
            this.playAdBeforeStartPosition = playAdBeforeStartPosition
            return this
        }

        fun setDebugModeEnabled(debugModeEnabled: Boolean): Builder {
            this.debugModeEnabled = debugModeEnabled
            return this
        }

        @VisibleForTesting
        fun setImaFactory(imaFactory: ImaUtil.ImaFactory?): Builder {
            this.imaFactory = Preconditions.checkNotNull<ImaUtil.ImaFactory?>(imaFactory)
            return this
        }

        fun build(): ImaAdsLoader {
            if (vastLoadTimeoutMs != ImaUtil.TIMEOUT_UNSET && adPreloadTimeoutMs < vastLoadTimeoutMs) {
                adPreloadTimeoutMs = vastLoadTimeoutMs.toLong()
            }
            return ImaAdsLoader(
                context,
                ImaUtil.Configuration(
                    adPreloadTimeoutMs,
                    vastLoadTimeoutMs,
                    mediaLoadTimeoutMs,
                    focusSkipButtonWhenAvailable,
                    playAdBeforeStartPosition,
                    enableCustomTabs,
                    mediaBitrate,
                    enableContinuousPlayback,
                    adMediaMimeTypes,
                    adUiElements,
                    companionAdSlots,
                    adErrorListener,
                    adEventListener,
                    videoAdPlayerCallback,
                    imaSdkSettings,
                    debugModeEnabled
                ),
                imaFactory
            )
        }

        companion object {

            val DEFAULT_AD_PRELOAD_TIMEOUT_MS: Long = 10 * C.MILLIS_PER_SECOND
        }
    }

    private val context: Context
    private val imaFactory: ImaUtil.ImaFactory
    private val playerListener: PlayerListenerImpl
    private val adTagLoaderByAdsId: HashMap<Any?, AdTagLoader>
    private val adTagLoaderByAdsMediaSource: HashMap<AdsMediaSource?, AdTagLoader>
    private val period: Timeline.Period
    private val window: Timeline.Window

    private var wasSetPlayerCalled = false
    private var nextPlayer: Player? = null
    private var supportedMimeTypes: MutableList<String>
    private var player: Player? = null
    private var currentAdTagLoader: AdTagLoader? = null

    init {
        this.context = context.getApplicationContext()
        this.imaFactory = imaFactory
        playerListener = PlayerListenerImpl()
        supportedMimeTypes = ImmutableList.of<String?>()
        adTagLoaderByAdsId = HashMap<Any?, AdTagLoader>()
        adTagLoaderByAdsMediaSource = HashMap<AdsMediaSource?, AdTagLoader>()
        period = Timeline.Period()
        window = Timeline.Window()
    }

    val adsLoader: com.google.ads.interactivemedia.v3.api.AdsLoader?
        get() = if (currentAdTagLoader != null) currentAdTagLoader!!.getAdsLoader() else null

    val adDisplayContainer: AdDisplayContainer?
        get() = if (currentAdTagLoader != null) currentAdTagLoader!!.getAdDisplayContainer() else null

    fun requestAds(adTagDataSpec: DataSpec, adsId: Any?, adViewGroup: ViewGroup?) {
        if (!adTagLoaderByAdsId.containsKey(adsId)) {
            val adTagLoader =
                AdTagLoader(
                    context,
                    configuration,
                    imaFactory,
                    supportedMimeTypes,
                    adTagDataSpec,
                    adsId,
                    adViewGroup
                )
            adTagLoaderByAdsId.put(adsId, adTagLoader)
        }
    }

    fun skipAd() {
        if (currentAdTagLoader != null) {
            currentAdTagLoader!!.skipAd()
        }
    }

    fun focusSkipButton() {
        if (currentAdTagLoader != null) {
            currentAdTagLoader!!.focusSkipButton()
        }
    }

    // AdsLoader implementation.
    override fun setPlayer(player: Player?) {
        Preconditions.checkState(Looper.myLooper() == ImaUtil.imaLooper)
        Preconditions.checkState(player == null || player.applicationLooper == ImaUtil.imaLooper)
        nextPlayer = player
        wasSetPlayerCalled = true
    }

    override fun setSupportedContentTypes(vararg contentTypes: Int) {
        val supportedMimeTypes: MutableList<String?> = ArrayList<String?>()
        for (contentType in contentTypes) {
            // IMA does not support Smooth Streaming ad media.
            if (contentType == C.CONTENT_TYPE_DASH) {
                supportedMimeTypes.add(MimeTypes.APPLICATION_MPD)
            } else if (contentType == C.CONTENT_TYPE_HLS) {
                supportedMimeTypes.add(MimeTypes.APPLICATION_M3U8)
            } else if (contentType == C.CONTENT_TYPE_OTHER) {
                supportedMimeTypes.addAll(
                    Arrays.asList<String>(
                        MimeTypes.VIDEO_MP4,
                        MimeTypes.VIDEO_WEBM,
                        MimeTypes.VIDEO_H263,
                        MimeTypes.AUDIO_MP4,
                        MimeTypes.AUDIO_MPEG
                    )
                )
            }
        }
        this.supportedMimeTypes = Collections.unmodifiableList<String?>(supportedMimeTypes)
    }

    override fun start(
        adsMediaSource: AdsMediaSource,
        adTagDataSpec: DataSpec,
        adsId: Any,
        adViewProvider: AdViewProvider,
        eventListener: AdsLoader.EventListener
    ) {
        Preconditions.checkState(
            wasSetPlayerCalled, "Set player using adsLoader.setPlayer before preparing the player."
        )
        if (adTagLoaderByAdsMediaSource.isEmpty()) {
            player = nextPlayer
            val player: Player = this.player ?: return
            player.addListener(playerListener)
        }

        var adTagLoader = adTagLoaderByAdsId.get(adsId)
        if (adTagLoader == null) {
            requestAds(adTagDataSpec, adsId, adViewProvider.adViewGroup)
            adTagLoader = adTagLoaderByAdsId.get(adsId)
        }
        adTagLoaderByAdsMediaSource.put(
            adsMediaSource,
            Preconditions.checkNotNull<AdTagLoader?>(adTagLoader)
        )
        adTagLoader!!.addListenerWithAdView(eventListener, adViewProvider)
        maybeUpdateCurrentAdTagLoader()
    }

    override fun stop(adsMediaSource: AdsMediaSource, eventListener: AdsLoader.EventListener) {
        val removedAdTagLoader = adTagLoaderByAdsMediaSource.remove(adsMediaSource)
        maybeUpdateCurrentAdTagLoader()
        if (removedAdTagLoader != null) {
            removedAdTagLoader.removeListener(eventListener)
        }

        val player = player
        if (player != null && adTagLoaderByAdsMediaSource.isEmpty()) {
            player.removeListener(playerListener)
            this.player = null
        }
    }

    override fun release() {
        val player = player
        if (player != null) {
            player.removeListener(playerListener)
            this.player = null
            maybeUpdateCurrentAdTagLoader()
        }
        nextPlayer = null

        for (adTagLoader in adTagLoaderByAdsMediaSource.values) {
            adTagLoader.release()
        }
        adTagLoaderByAdsMediaSource.clear()

        for (adTagLoader in adTagLoaderByAdsId.values) {
            adTagLoader.release()
        }
        adTagLoaderByAdsId.clear()
    }

    override fun handlePrepareComplete(
        adsMediaSource: AdsMediaSource, adGroupIndex: Int, adIndexInAdGroup: Int
    ) {
        if (player == null) {
            return
        }
        adTagLoaderByAdsMediaSource.get(adsMediaSource)!!
            .handlePrepareComplete(adGroupIndex, adIndexInAdGroup)
    }

    override fun handlePrepareError(
        adsMediaSource: AdsMediaSource,
        adGroupIndex: Int,
        adIndexInAdGroup: Int,
        exception: IOException
    ) {
        if (player == null) {
            return
        }
        adTagLoaderByAdsMediaSource.get(adsMediaSource)!!
            .handlePrepareError(adGroupIndex, adIndexInAdGroup, exception)
    }

    // Internal methods.
    private fun maybeUpdateCurrentAdTagLoader() {
        val oldAdTagLoader = currentAdTagLoader
        val newAdTagLoader = getCurrentAdTagLoader()
        if (oldAdTagLoader != newAdTagLoader) {
            if (oldAdTagLoader != null) {
                oldAdTagLoader.deactivate()
            }
            currentAdTagLoader = newAdTagLoader
            if (newAdTagLoader != null) {
                newAdTagLoader.activate(Preconditions.checkNotNull<Player?>(player))
            }
        }
    }

    private fun getCurrentAdTagLoader(): AdTagLoader? {
        val player: Player? = this.player
        if (player == null) {
            return null
        }
        val timeline: Timeline = player.currentTimeline
        if (timeline.isEmpty) {
            return null
        }
        val periodIndex: Int = player.currentPeriodIndex
        val adsId: Any? = timeline.getPeriod(periodIndex, period).adsId
        if (adsId == null) {
            return null
        }
        val adTagLoader = adTagLoaderByAdsId.get(adsId)
        if (adTagLoader == null || !adTagLoaderByAdsMediaSource.containsValue(adTagLoader)) {
            return null
        }
        return adTagLoader
    }

    private fun maybePreloadNextPeriodAds() {
        val player: Player? = this@ImaAdsLoader.player
        if (player == null) {
            return
        }
        val timeline: Timeline = player.currentTimeline
        if (timeline.isEmpty) {
            return
        }
        val nextPeriodIndex: Int =
            timeline.getNextPeriodIndex(
                player.currentPeriodIndex,
                period,
                window,
                player.repeatMode,
                player.shuffleModeEnabled
            )
        if (nextPeriodIndex == C.INDEX_UNSET) {
            return
        }
        timeline.getPeriod(nextPeriodIndex, period)
        val nextAdsId = period.adsId
        if (nextAdsId == null) {
            return
        }
        val nextAdTagLoader = adTagLoaderByAdsId.get(nextAdsId)
        if (nextAdTagLoader == null || nextAdTagLoader == currentAdTagLoader) {
            return
        }
        val periodPositionUs: Long =
            timeline.getPeriodPositionUs(
                window, period, period.windowIndex,  /* windowPositionUs= */C.TIME_UNSET
            )
                .second
        nextAdTagLoader.maybePreloadAds(
            Util.usToMs(periodPositionUs),
            Util.usToMs(period.durationUs)
        )
    }

    private inner class PlayerListenerImpl : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (timeline.isEmpty) {
                // The player is being reset or contains no media.
                return
            }
            maybeUpdateCurrentAdTagLoader()
            maybePreloadNextPeriodAds()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            maybeUpdateCurrentAdTagLoader()
            maybePreloadNextPeriodAds()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            maybePreloadNextPeriodAds()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            maybePreloadNextPeriodAds()
        }
    }

    private class DefaultImaFactory : ImaUtil.ImaFactory {
        override fun createImaSdkSettings(): ImaSdkSettings {
            val settings: ImaSdkSettings = ImaSdkFactory.getInstance().createImaSdkSettings()
            settings.setLanguage(Util.systemLanguageCodes[0])
            return settings
        }

        override fun createAdsRenderingSettings(): AdsRenderingSettings {
            return ImaSdkFactory.getInstance().createAdsRenderingSettings()
        }

        override fun createAdDisplayContainer(
            container: ViewGroup,
            player: VideoAdPlayer
        ): AdDisplayContainer {
            return ImaSdkFactory.createAdDisplayContainer(container, player)
        }

        override fun createAudioAdDisplayContainer(
            context: Context,
            player: VideoAdPlayer
        ): AdDisplayContainer {
            return ImaSdkFactory.createAudioAdDisplayContainer(context, player)
        }

        // The reasonDetail parameter to createFriendlyObstruction is annotated @Nullable but the
        // annotation is not kept in the obfuscated dependency.
        override fun createFriendlyObstruction(
            view: View,
            friendlyObstructionPurpose: FriendlyObstructionPurpose,
            reasonDetail: String?
        ): FriendlyObstruction {
            return ImaSdkFactory.getInstance()
                .createFriendlyObstruction(view, friendlyObstructionPurpose, reasonDetail)
        }

        override fun createAdsRequest(): AdsRequest {
            return ImaSdkFactory.getInstance().createAdsRequest()
        }

        override fun createAdsLoader(
            context: Context,
            imaSdkSettings: ImaSdkSettings,
            adDisplayContainer: AdDisplayContainer
        ): com.google.ads.interactivemedia.v3.api.AdsLoader {
            return ImaSdkFactory.getInstance()
                .createAdsLoader(context, imaSdkSettings, adDisplayContainer)
        }
    }

    companion object {
        init {
            MediaLibraryInfo.registerModule("media3.exoplayer.ima")
        }
    }
}
