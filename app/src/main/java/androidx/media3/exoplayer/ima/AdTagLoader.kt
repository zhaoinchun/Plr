package androidx.media3.exoplayer.ima

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.view.ViewGroup
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.AdViewProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Log
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.ads.AdsLoader.EventListener
import androidx.media3.exoplayer.source.ads.AdsMediaSource.AdLoadException
import com.google.ads.interactivemedia.v3.api.AdDisplayContainer
import com.google.ads.interactivemedia.v3.api.AdError
import com.google.ads.interactivemedia.v3.api.AdErrorEvent
import com.google.ads.interactivemedia.v3.api.AdEvent
import com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType
import com.google.ads.interactivemedia.v3.api.AdPodInfo
import com.google.ads.interactivemedia.v3.api.AdsLoader
import com.google.ads.interactivemedia.v3.api.AdsLoader.AdsLoadedListener
import com.google.ads.interactivemedia.v3.api.AdsManager
import com.google.ads.interactivemedia.v3.api.AdsManagerLoadedEvent
import com.google.ads.interactivemedia.v3.api.AdsRenderingSettings
import com.google.ads.interactivemedia.v3.api.AdsRequest
import com.google.ads.interactivemedia.v3.api.ImaSdkSettings
import com.google.ads.interactivemedia.v3.api.player.AdMediaInfo
import com.google.ads.interactivemedia.v3.api.player.ContentProgressProvider
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate
import com.google.common.collect.BiMap
import com.google.common.collect.HashBiMap
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max

internal class AdTagLoader(
    context: Context,
    private val configuration: ImaUtil.Configuration,
    imaFactory: ImaUtil.ImaFactory,
    supportedMimeTypes: MutableList<String>,
    adTagDataSpec: DataSpec,
    adsId: Any?,
    adViewGroup: ViewGroup?
) : Player.Listener {
    private val imaFactory: ImaUtil.ImaFactory
    private val supportedMimeTypes: MutableList<String>
    private val adTagDataSpec: DataSpec
    private val adsId: Any?
    private val period: Timeline.Period
    private val handler: Handler
    private val componentListener: ComponentListener
    private val contentPlaybackAdapter: ContentPlaybackAdapter
    private val eventListeners: MutableList<EventListener?>
    private val adCallbacks: MutableList<VideoAdPlayer.VideoAdPlayerCallback>
    private val updateAdProgressRunnable: Runnable
    private val adInfoByAdMediaInfo: BiMap<AdMediaInfo?, AdInfo?>
    private val adDisplayContainer: AdDisplayContainer
    private val adsLoader: AdsLoader
    private val adLoadTimeoutRunnable: Runnable

    private var pendingAdRequestContext: Any? = null
    private var player: Player? = null
    private var lastContentProgress: VideoProgressUpdate
    private var lastAdProgress: VideoProgressUpdate
    private var lastVolumePercent = 0

    private var adsManager: AdsManager? = null
    private var isAdsManagerInitialized = false
    private var pendingAdLoadError: AdLoadException? = null
    private var timeline: Timeline
    private var contentDurationMs: Long
    private var adPlaybackState: AdPlaybackState

    private var released = false

    // Fields tracking IMA's state.
    /** Whether IMA has sent an ad event to pause content since the last resume content event.  */
    private var imaPausedContent = false

    /** The current ad playback state.  */
    private var imaAdState: Int = 0

    /** The current ad media info, or `null` if in state [.IMA_AD_STATE_NONE].  */
    private var imaAdMediaInfo: AdMediaInfo? = null

    /** The current ad info, or `null` if in state [.IMA_AD_STATE_NONE].  */
    private var imaAdInfo: AdInfo? = null

    /** Whether IMA has been notified that playback of content has finished.  */
    private var sentContentComplete = false

    // Fields tracking the player/loader state.
    /** Whether the player is playing an ad.  */
    private var playingAd = false

    /** Whether the player is buffering an ad.  */
    private var bufferingAd = false

    /**
     * If the player is playing an ad, stores the ad index in its ad group. [C.INDEX_UNSET]
     * otherwise.
     */
    private var playingAdIndexInAdGroup = 0

    /**
     * The ad info for a pending ad for which the media failed preparation, or `null` if no
     * pending ads have failed to prepare.
     */
    private var pendingAdPrepareErrorAdInfo: AdInfo? = null

    /**
     * If a content period has finished but IMA has not yet called [ ][ComponentListener.playAd], stores the value of [ ][SystemClock.elapsedRealtime] when the content stopped playing. This can be used to determine
     * a fake, increasing content position. [C.TIME_UNSET] otherwise.
     */
    private var fakeContentProgressElapsedRealtimeMs: Long

    /**
     * If [.fakeContentProgressElapsedRealtimeMs] is set, stores the offset from which the
     * content progress should increase. [C.TIME_UNSET] otherwise.
     */
    private var fakeContentProgressOffsetMs: Long

    /** Stores the pending content position when a seek operation was intercepted to play an ad.  */
    private var pendingContentPositionMs: Long

    /**
     * Whether [ComponentListener.getContentProgress] has sent [ ][.pendingContentPositionMs] to IMA.
     */
    private var sentPendingContentPositionMs = false

    /**
     * Stores the real time in milliseconds at which the player started buffering, possibly due to not
     * having preloaded an ad, or [C.TIME_UNSET] if not applicable.
     */
    private var waitingForPreloadElapsedRealtimeMs: Long

    /** Creates a new ad tag loader, starting the ad request if the ad tag is valid.  */
    init {
        this.imaFactory = imaFactory
        var imaSdkSettings: ImaSdkSettings? = configuration.imaSdkSettings
        if (imaSdkSettings == null) {
            imaSdkSettings = imaFactory.createImaSdkSettings()
            if (configuration.debugModeEnabled) {
                imaSdkSettings!!.setDebugMode(true)
            }
        }
        imaSdkSettings!!.setPlayerType(IMA_SDK_SETTINGS_PLAYER_TYPE)
        imaSdkSettings.setPlayerVersion(IMA_SDK_SETTINGS_PLAYER_VERSION)
        this.supportedMimeTypes = supportedMimeTypes
        this.adTagDataSpec = adTagDataSpec
        this.adsId = adsId
        period = Timeline.Period()
        handler = Util.createHandler(ImaUtil.imaLooper,  /* callback= */null)
        componentListener = ComponentListener()
        contentPlaybackAdapter = ContentPlaybackAdapter()
        eventListeners = ArrayList<EventListener?>()
        adCallbacks = ArrayList<VideoAdPlayer.VideoAdPlayerCallback>( /* initialCapacity= */1)
        if (configuration.applicationVideoAdPlayerCallback != null) {
            adCallbacks.add(configuration.applicationVideoAdPlayerCallback)
        }
        updateAdProgressRunnable = Runnable { this.updateAdProgress() }
        adInfoByAdMediaInfo = HashBiMap.create<AdMediaInfo?, AdInfo?>()
        lastContentProgress = VideoProgressUpdate.VIDEO_TIME_NOT_READY
        lastAdProgress = VideoProgressUpdate.VIDEO_TIME_NOT_READY
        fakeContentProgressElapsedRealtimeMs = C.TIME_UNSET
        fakeContentProgressOffsetMs = C.TIME_UNSET
        pendingContentPositionMs = C.TIME_UNSET
        waitingForPreloadElapsedRealtimeMs = C.TIME_UNSET
        contentDurationMs = C.TIME_UNSET
        timeline = Timeline.EMPTY
        adPlaybackState = AdPlaybackState.NONE
        adLoadTimeoutRunnable = Runnable { this.handleAdLoadTimeout() }
        val videoAdPlayerImpl = VideoAdPlayerImpl()
        if (adViewGroup != null) {
            adDisplayContainer =
                imaFactory.createAdDisplayContainer(adViewGroup,  /* player= */videoAdPlayerImpl)
        } else {
            adDisplayContainer =
                imaFactory.createAudioAdDisplayContainer(context,  /* player= */videoAdPlayerImpl)
        }
        if (configuration.companionAdSlots != null) {
            adDisplayContainer.setCompanionSlots(configuration.companionAdSlots)
        }
        adsLoader = requestAds(context, imaSdkSettings, adDisplayContainer)
    }

    /** Returns the underlying IMA SDK ads loader.  */
    fun getAdsLoader(): AdsLoader {
        return adsLoader
    }

    /** Returns the IMA SDK ad display container.  */
    fun getAdDisplayContainer(): AdDisplayContainer {
        return adDisplayContainer
    }

    /** Skips the current skippable ad, if there is one.  */
    fun skipAd() {
        adsManager?.skip()
    }

    /**
     * Moves UI focus to the skip button (or other interactive elements), if currently shown. See
     * [AdsManager.focus].
     */
    fun focusSkipButton() {
        adsManager?.focus()
    }

    /**
     * Starts passing events from this instance (including any pending ad playback state) and
     * registers obstructions.
     */
    fun addListenerWithAdView(
        eventListener: EventListener,
        adViewProvider: AdViewProvider
    ) {
        val isStarted = !eventListeners.isEmpty()
        eventListeners.add(eventListener)
        if (isStarted) {
            if (AdPlaybackState.NONE != adPlaybackState) {
                // Pass the existing ad playback state to the new listener.
                eventListener.onAdPlaybackState(adPlaybackState)
            }
            return
        }
        lastVolumePercent = 0
        lastAdProgress = VideoProgressUpdate.VIDEO_TIME_NOT_READY
        lastContentProgress = VideoProgressUpdate.VIDEO_TIME_NOT_READY
        maybeNotifyPendingAdLoadError()
        if (AdPlaybackState.NONE != adPlaybackState) {
            // Pass the ad playback state to the player, and resume ads if necessary.
            eventListener.onAdPlaybackState(adPlaybackState)
        } else if (adsManager != null) {
            adPlaybackState =
                AdPlaybackState(
                    adsId,
                    ImaUtil.getAdGroupTimesUsForCuePoints(adsManager!!.getAdCuePoints()).map {
                        AdPlaybackState.AdGroup((it))
                    }
                )
            updateAdPlaybackState()
        }
        for (overlayInfo in adViewProvider.getAdOverlayInfos()) {
            adDisplayContainer.registerFriendlyObstruction(
                imaFactory.createFriendlyObstruction(
                    overlayInfo.view,
                    ImaUtil.getFriendlyObstructionPurpose(overlayInfo.purpose),
                    overlayInfo.reasonDetail
                )
            )
        }
    }

    /**
     * Populates the ad playback state with loaded cue points, if available. Any preroll will be
     * paused immediately while waiting for this instance to be [activated][.activate].
     */
    fun maybePreloadAds(contentPositionMs: Long, contentDurationMs: Long) {
        maybeInitializeAdsManager(contentPositionMs, contentDurationMs)
    }

    /** Activates playback.  */
    fun activate(player: Player) {
        this.player = player
        player.addListener(this)

        val playWhenReady: Boolean = player.playWhenReady
        onTimelineChanged(player.currentTimeline, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        val adsManager: AdsManager? = this.adsManager
        if ((AdPlaybackState.NONE != adPlaybackState) && adsManager != null && imaPausedContent) {
            // Check whether the current ad break matches the expected ad break based on the current
            // position. If not, discard the current ad break so that the correct ad break can load.
            val contentPositionMs: Long = getContentPeriodPositionMs(player, timeline, period)
            val adGroupForPositionIndex =
                adPlaybackState.getAdGroupIndexForPositionUs(
                    Util.msToUs(contentPositionMs), Util.msToUs(contentDurationMs)
                )
            if (adGroupForPositionIndex != C.INDEX_UNSET && imaAdInfo != null && imaAdInfo!!.adGroupIndex != adGroupForPositionIndex) {
                if (configuration.debugModeEnabled) {
                    Log.d(TAG, "Discarding preloaded ad " + imaAdInfo)
                }
                adsManager.discardAdBreak()
            }
            if (playWhenReady) {
                adsManager.resume()
            }
        }
    }

    /** Deactivates playback.  */
    fun deactivate() {
        val player: Player = player!!
        // Post deactivation behind any already queued Player.Listener events to ensure that
        // any pending events are processed before the listener is removed and the ads manager paused.
        handler.post(Runnable { deactivateInternal(player) })
    }

    /**
     * Deactivates playback internally, after the Listener.onEvents() cycle completes so the complete
     * state change picture is clear. For example, if an error caused the deactivation, the error
     * callback can be handled first.
     */
    private fun deactivateInternal(player: Player) {
        if ((adPlaybackState != AdPlaybackState.NONE) && imaPausedContent
            && player.playerError == null
        ) {
            // Only need to pause and store resume position if not in error state.
            adsManager?.pause()
            adPlaybackState =
                adPlaybackState.withAdResumePositionUs(
                    if (playingAd) Util.msToUs(player.currentPosition) else 0
                )
        }
        lastVolumePercent = this.playerVolumePercent
        lastAdProgress = this.adVideoProgressUpdate
        lastContentProgress = this.contentVideoProgressUpdate
        player.removeListener(this)
        this.player = null
    }

    /** Stops passing of events from this instance and unregisters obstructions.  */
    fun removeListener(eventListener: EventListener?) {
        eventListeners.remove(eventListener)
        if (eventListeners.isEmpty()) {
            adDisplayContainer.unregisterAllFriendlyObstructions()
        }
    }

    /** Releases all resources used by the ad tag loader.  */
    fun release() {
        if (released) {
            return
        }
        released = true
        pendingAdRequestContext = null
        destroyAdsManager()
        adsLoader.removeAdsLoadedListener(componentListener)
        adsLoader.removeAdErrorListener(componentListener)
        if (configuration.applicationAdErrorListener != null) {
            adsLoader.removeAdErrorListener(configuration.applicationAdErrorListener)
        }
        adsLoader.release()
        imaPausedContent = false
        imaAdState = IMA_AD_STATE_NONE
        imaAdMediaInfo = null
        stopUpdatingAdProgress()
        imaAdInfo = null
        pendingAdLoadError = null
        // No more ads will play once the loader is released, so mark all ad groups as skipped.
        for (i in 0..<adPlaybackState.adGroupCount) {
            adPlaybackState = adPlaybackState.withSkippedAdGroup(i)
        }
        updateAdPlaybackState()
    }

    /** Notifies the IMA SDK that the specified ad has been prepared for playback.  */
    fun handlePrepareComplete(adGroupIndex: Int, adIndexInAdGroup: Int) {
        val adInfo = AdInfo(adGroupIndex, adIndexInAdGroup)
        if (configuration.debugModeEnabled) {
            Log.d(TAG, "Prepared ad " + adInfo)
        }
        val adMediaInfo: AdMediaInfo? = adInfoByAdMediaInfo.inverse().get(adInfo)
        if (adMediaInfo != null) {
            for (i in adCallbacks.indices) {
                adCallbacks.get(i).onLoaded(adMediaInfo)
            }
        } else {
            Log.w(TAG, "Unexpected prepared ad " + adInfo)
        }
    }

    /** Notifies the IMA SDK that the specified ad has failed to prepare for playback.  */
    fun handlePrepareError(adGroupIndex: Int, adIndexInAdGroup: Int, exception: IOException?) {
        if (player == null) {
            return
        }
        try {
            handleAdPrepareError(adGroupIndex, adIndexInAdGroup, exception)
        } catch (e: RuntimeException) {
            maybeNotifyInternalError("handlePrepareError", e)
        }
    }

    // Player.Listener implementation.
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        val player: Player? = this.player
        if (timeline.isEmpty || player == null) {
            // The player is being reset or contains no media.
            return
        }
        this.timeline = timeline
        val contentDurationUs: Long =
            timeline.getPeriod(player.currentPeriodIndex, period).durationUs
        contentDurationMs = Util.usToMs(contentDurationUs)
        if (contentDurationUs != adPlaybackState.contentDurationUs) {
            adPlaybackState = adPlaybackState.withContentDurationUs(contentDurationUs)
            updateAdPlaybackState()
        }
        val contentPositionMs: Long = getContentPeriodPositionMs(player, timeline, period)
        maybeInitializeAdsManager(contentPositionMs, contentDurationMs)
        handleTimelineOrPositionChanged()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        handleTimelineOrPositionChanged()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val player: Player? = this.player
        if (adsManager == null || player == null) {
            return
        }

        if (playbackState == Player.STATE_BUFFERING && !player.isPlayingAd && this.isWaitingForFirstAdToPreload) {
            waitingForPreloadElapsedRealtimeMs = SystemClock.elapsedRealtime()
        } else if (playbackState == Player.STATE_READY) {
            waitingForPreloadElapsedRealtimeMs = C.TIME_UNSET
        }

        handlePlayerStateChanged(player.playWhenReady, playbackState)
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean, reason: Int
    ) {
        val adsManager = adsManager
        val player = player
        if (adsManager == null || player == null) {
            return
        }

        if (imaAdState == IMA_AD_STATE_PLAYING && !playWhenReady) {
            adsManager.pause()
            return
        }

        if (imaAdState == IMA_AD_STATE_PAUSED && playWhenReady) {
            adsManager.resume()
            return
        }
        handlePlayerStateChanged(playWhenReady, player.playbackState)
    }

    override fun onPlayerError(error: PlaybackException) {
        if (imaAdState != IMA_AD_STATE_NONE && player!!.isPlayingAd) {
            val adMediaInfo = imaAdMediaInfo!!
            for (i in adCallbacks.indices) {
                adCallbacks.get(i).onError(adMediaInfo)
            }
        }
    }

    // Internal methods.
    private fun requestAds(
        context: Context, imaSdkSettings: ImaSdkSettings, adDisplayContainer: AdDisplayContainer
    ): AdsLoader {
        val adsLoader: AdsLoader =
            imaFactory.createAdsLoader(context, imaSdkSettings, adDisplayContainer)
        adsLoader.addAdErrorListener(componentListener)
        if (configuration.applicationAdErrorListener != null) {
            adsLoader.addAdErrorListener(configuration.applicationAdErrorListener)
        }
        adsLoader.addAdsLoadedListener(componentListener)
        val request: AdsRequest
        if (adTagDataSpec.uri.getScheme() == C.CSAI_SCHEME
            && adTagDataSpec.uri.getAuthority() == ImaAdTagUriBuilder.Companion.IMA_AUTHORITY
        ) {
            request = ImaAdTagUriBuilder.Companion.createAdsRequest(imaFactory, adTagDataSpec.uri)
        } else {
            try {
                request = ImaUtil.getAdsRequestForAdTagDataSpec(imaFactory, adTagDataSpec)
            } catch (e: IOException) {
                adPlaybackState = AdPlaybackState(adsId)
                updateAdPlaybackState()
                pendingAdLoadError = AdLoadException.createForAllAds(e)
                maybeNotifyPendingAdLoadError()
                return adsLoader
            }
        }
        pendingAdRequestContext = Any()
        request.setUserRequestContext(pendingAdRequestContext!!)
        if (configuration.enableContinuousPlayback != null) {
            request.setContinuousPlayback(configuration.enableContinuousPlayback)
        }
        if (configuration.vastLoadTimeoutMs != ImaUtil.TIMEOUT_UNSET) {
            request.setVastLoadTimeout(configuration.vastLoadTimeoutMs.toFloat())
        }
        request.setContentProgressProvider(contentPlaybackAdapter)
        adsLoader.requestAds(request)
        return adsLoader
    }

    private fun maybeInitializeAdsManager(contentPositionMs: Long, contentDurationMs: Long) {
        val adsManager: AdsManager? = this.adsManager
        if (!isAdsManagerInitialized && adsManager != null) {
            isAdsManagerInitialized = true
            val adsRenderingSettings: AdsRenderingSettings? =
                setupAdsRendering(contentPositionMs, contentDurationMs)
            if (adsRenderingSettings == null) {
                // There are no ads to play.
                destroyAdsManager()
            } else {
                adsManager.init(adsRenderingSettings)
                adsManager.start()
                if (configuration.debugModeEnabled) {
                    Log.d(TAG, "Initialized with ads rendering settings: " + adsRenderingSettings)
                }
            }
            updateAdPlaybackState()
        }
    }

    /**
     * Configures ads rendering for starting playback, returning the settings for the IMA SDK or
     * `null` if no ads should play.
     */
    private fun setupAdsRendering(
        contentPositionMs: Long,
        contentDurationMs: Long
    ): AdsRenderingSettings? {
        val adsRenderingSettings: AdsRenderingSettings = imaFactory.createAdsRenderingSettings()
        adsRenderingSettings.setEnablePreloading(true)
        adsRenderingSettings.setEnableCustomTabs(configuration.enableCustomTabs)
        adsRenderingSettings.setMimeTypes(
            if (configuration.adMediaMimeTypes != null)
                configuration.adMediaMimeTypes
            else
                supportedMimeTypes
        )
        if (configuration.mediaLoadTimeoutMs != ImaUtil.TIMEOUT_UNSET) {
            adsRenderingSettings.setLoadVideoTimeout(configuration.mediaLoadTimeoutMs)
        }
        if (configuration.mediaBitrate != ImaUtil.BITRATE_UNSET) {
            adsRenderingSettings.setBitrateKbps(configuration.mediaBitrate / 1000)
        }
        adsRenderingSettings.setFocusSkipButtonWhenAvailable(
            configuration.focusSkipButtonWhenAvailable
        )
        if (configuration.adUiElements != null) {
            adsRenderingSettings.setUiElements(configuration.adUiElements)
        }

        // Skip ads based on the start position as required.
        var adGroupForPositionIndex =
            adPlaybackState.getAdGroupIndexForPositionUs(
                Util.msToUs(contentPositionMs), Util.msToUs(contentDurationMs)
            )
        if (adGroupForPositionIndex != C.INDEX_UNSET) {
            val playAdWhenStartingPlayback =
                adPlaybackState.getAdGroup(adGroupForPositionIndex).timeUs == Util.msToUs(
                    contentPositionMs
                )
                        || configuration.playAdBeforeStartPosition
            if (!playAdWhenStartingPlayback) {
                adGroupForPositionIndex++
            } else if (hasMidrollAdGroups(adPlaybackState)) {
                // Provide the player's initial position to trigger loading and playing the ad. If there are
                // no midrolls, we are playing a preroll and any pending content position wouldn't be
                // cleared.
                pendingContentPositionMs = contentPositionMs
            }
            if (adGroupForPositionIndex > 0) {
                for (i in 0..<adGroupForPositionIndex) {
                    adPlaybackState = adPlaybackState.withSkippedAdGroup(i)
                }
                if (adGroupForPositionIndex == adPlaybackState.adGroupCount) {
                    // We don't need to play any ads. Because setPlayAdsAfterTime does not discard non-VMAP
                    // ads, we signal that no ads will render so the caller can destroy the ads manager.
                    return null
                }
                val adGroupForPositionTimeUs =
                    adPlaybackState.getAdGroup(adGroupForPositionIndex).timeUs
                val adGroupBeforePositionTimeUs =
                    adPlaybackState.getAdGroup(adGroupForPositionIndex - 1).timeUs
                if (adGroupForPositionTimeUs == C.TIME_END_OF_SOURCE) {
                    // Play the postroll by offsetting the start position just past the last non-postroll ad.
                    adsRenderingSettings.setPlayAdsAfterTime(
                        adGroupBeforePositionTimeUs.toDouble() / C.MICROS_PER_SECOND + 1.0
                    )
                } else {
                    // Play ads after the midpoint between the ad to play and the one before it, to avoid
                    // issues with rounding one of the two ad times.
                    val midpointTimeUs =
                        (adGroupForPositionTimeUs + adGroupBeforePositionTimeUs) / 2.0
                    adsRenderingSettings.setPlayAdsAfterTime(midpointTimeUs / C.MICROS_PER_SECOND)
                }
            }
        }
        return adsRenderingSettings
    }

    private val contentVideoProgressUpdate: VideoProgressUpdate
        get() {
            val player = player
            val hasContentDuration =
                contentDurationMs != C.TIME_UNSET
            val contentPositionMs: Long
            if (pendingContentPositionMs != C.TIME_UNSET) {
                sentPendingContentPositionMs = true
                contentPositionMs = pendingContentPositionMs
            } else if (player == null) {
                return lastContentProgress
            } else if (fakeContentProgressElapsedRealtimeMs != C.TIME_UNSET) {
                val elapsedSinceEndMs =
                    SystemClock.elapsedRealtime() - fakeContentProgressElapsedRealtimeMs
                contentPositionMs = fakeContentProgressOffsetMs + elapsedSinceEndMs
            } else if (imaAdState == IMA_AD_STATE_NONE && !playingAd && hasContentDuration) {
                contentPositionMs =
                    getContentPeriodPositionMs(player, timeline, period)
            } else {
                return VideoProgressUpdate.VIDEO_TIME_NOT_READY
            }
            val contentDurationMs =
                if (hasContentDuration) this.contentDurationMs else IMA_DURATION_UNSET
            return VideoProgressUpdate(contentPositionMs, contentDurationMs)
        }

    private val adVideoProgressUpdate: VideoProgressUpdate
        get() {
            val player = player
            if (player == null) {
                return lastAdProgress
            } else if (imaAdState != IMA_AD_STATE_NONE && playingAd) {
                val playerAdIndex = player.currentAdIndexInAdGroup
                val adPosition = player.currentPosition
                val imaAdInfo = this.imaAdInfo
                if (player.isPlayingAd
                    && imaAdInfo != null && player.currentAdGroupIndex == imaAdInfo.adGroupIndex && playerAdIndex == imaAdInfo.adIndexInAdGroup
                ) {
                    val adDuration = player.duration
                    return if (adDuration == C.TIME_UNSET)
                        VideoProgressUpdate.VIDEO_TIME_NOT_READY
                    else
                        VideoProgressUpdate(adPosition, adDuration)
                } else {
                    if (configuration.debugModeEnabled) {
                        Log.d(
                            TAG,
                            ("getAdVideoProgressUpdate: player not at expected ad (group="
                                    + player.currentAdGroupIndex
                                    + ", index="
                                    + playerAdIndex
                                    + "), returning NOT_READY")
                        )
                    }
                    return VideoProgressUpdate.VIDEO_TIME_NOT_READY
                }
            } else {
                return VideoProgressUpdate.VIDEO_TIME_NOT_READY
            }
        }

    private fun updateAdProgress() {
        val videoProgressUpdate =adVideoProgressUpdate
        if (configuration.debugModeEnabled) {
            Log.d(
                TAG,
                "Ad progress: " + ImaUtil.getStringForVideoProgressUpdate(videoProgressUpdate)
            )
        }

        val adMediaInfo = imaAdMediaInfo!!
        for (i in adCallbacks.indices) {
            adCallbacks.get(i).onAdProgress(adMediaInfo, videoProgressUpdate)
        }
        handler.removeCallbacks(updateAdProgressRunnable)
        handler.postDelayed(updateAdProgressRunnable, AD_PROGRESS_UPDATE_INTERVAL_MS.toLong())
    }

    private fun stopUpdatingAdProgress() {
        handler.removeCallbacks(updateAdProgressRunnable)
    }

    private val playerVolumePercent: Int
        get() {
            val player: Player? = this.player
            if (player == null) {
                return lastVolumePercent
            }

            if (player.isCommandAvailable(Player.Companion.COMMAND_GET_VOLUME)) {
                return (player.volume * 100).toInt()
            }

            // Check for a selected track using an audio renderer.
            return if (player.currentTracks!!.isTypeSelected(C.TRACK_TYPE_AUDIO)) 100 else 0
        }

    private fun handleAdEvent(adEvent: AdEvent) {
        if (adsManager == null) {
            // Drop events after release.
            return
        }
        when (adEvent.getType()) {
            AdEventType.AD_BREAK_FETCH_ERROR -> {
                val adGroupTimeSecondsString: String =adEvent.getAdData().get("adBreakTime")!!
                if (configuration.debugModeEnabled) {
                    Log.d(TAG, "Fetch error for ad at " + adGroupTimeSecondsString + " seconds")
                }
                val adGroupTimeSeconds = adGroupTimeSecondsString.toDouble()
                val adGroupIndex =
                    if (adGroupTimeSeconds == -1.0)
                        adPlaybackState.adGroupCount - 1
                    else
                        getAdGroupIndexForCuePointTimeSeconds(adGroupTimeSeconds)
                markAdGroupInErrorStateAndClearPendingContentPosition(adGroupIndex)
            }

            AdEventType.CONTENT_PAUSE_REQUESTED -> {
                // After CONTENT_PAUSE_REQUESTED, IMA will playAd/pauseAd/stopAd to show one or more ads
                // before sending CONTENT_RESUME_REQUESTED.
                imaPausedContent = true
                pauseContentInternal()
            }

            AdEventType.TAPPED -> {
                var i = 0
                while (i < eventListeners.size) {
                    eventListeners.get(i)!!.onAdTapped()
                    i++
                }
            }

            AdEventType.CLICKED -> {
                var i = 0
                while (i < eventListeners.size) {
                    eventListeners.get(i)!!.onAdClicked()
                    i++
                }
            }

            AdEventType. CONTENT_RESUME_REQUESTED -> {
                imaPausedContent = false
                resumeContentInternal()
            }

            AdEventType. LOG -> {
                val adData: MutableMap<String?, String?>? = adEvent.getAdData()
                val message = "AdEvent: " + adData
                Log.i(TAG, message)
            }

            else -> {}
        }
    }

    private fun pauseContentInternal() {
        imaAdState = IMA_AD_STATE_NONE
        if (sentPendingContentPositionMs) {
            pendingContentPositionMs = C.TIME_UNSET
            sentPendingContentPositionMs = false
        }
    }

    private fun resumeContentInternal() {
        if (imaAdInfo != null) {
            // Mark current ad group as skipped if it hasn't finished yet. This could for example happen
            // after a load timeout where we receive CONTENT_RESUME_REQUESTED instead of loadAd.
            // See [Internal: b/330750756].
            adPlaybackState =
                adPlaybackState.withSkippedAdGroup(imaAdInfo!!.adGroupIndex)
            updateAdPlaybackState()
        }
    }

    private val isWaitingForFirstAdToPreload: Boolean
        /**
         * Returns whether this instance is expecting the first ad in an the upcoming ad group to load
         * within the [preload timeout][ImaUtil.Configuration.adPreloadTimeoutMs].
         */
        get() {
            val player: Player? = this.player
            if (player == null) {
                return false
            }
            val adGroupIndex = this.loadingAdGroupIndex
            if (adGroupIndex == C.INDEX_UNSET) {
                return false
            }
            val adGroup: AdPlaybackState.AdGroup = adPlaybackState.getAdGroup(adGroupIndex)
            if (adGroup.count != C.LENGTH_UNSET && adGroup.count != 0 && adGroup.states[0] != AdPlaybackState.AD_STATE_UNAVAILABLE) {
                // An ad is available already.
                return false
            }
            val adGroupTimeMs =
                Util.usToMs(adGroup.timeUs)
            val contentPositionMs: Long =
                getContentPeriodPositionMs(player, timeline, period)
            val timeUntilAdMs = adGroupTimeMs - contentPositionMs
            return timeUntilAdMs < configuration.adPreloadTimeoutMs
        }

    private val isWaitingForCurrentAdToLoad: Boolean
        get() {
            val player: Player? = this.player
            if (player == null) {
                return false
            }
            val adGroupIndex: Int = player.currentAdGroupIndex
            if (adGroupIndex == C.INDEX_UNSET) {
                return false
            }
            if (adGroupIndex >= adPlaybackState.adGroupCount) {
                return true
            }
            val adGroup = adPlaybackState.getAdGroup(adGroupIndex)
            val adIndexInAdGroup: Int = player.currentAdIndexInAdGroup
            if (adGroup.count == C.LENGTH_UNSET || adGroup.count <= adIndexInAdGroup) {
                return true
            }
            return adGroup.states[adIndexInAdGroup] == AdPlaybackState.AD_STATE_UNAVAILABLE
        }

    private fun handlePlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
        if (playingAd && imaAdState == IMA_AD_STATE_PLAYING) {
            if (!bufferingAd && playbackState == Player.STATE_BUFFERING) {
                bufferingAd = true
                val adMediaInfo: AdMediaInfo =imaAdMediaInfo!!
                for (i in adCallbacks.indices) {
                    adCallbacks.get(i).onBuffering(adMediaInfo)
                }
                stopUpdatingAdProgress()
            } else if (bufferingAd && playbackState == Player.STATE_READY) {
                bufferingAd = false
                updateAdProgress()
            }
        }

        if (imaAdState == IMA_AD_STATE_NONE && (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_ENDED)
            && playWhenReady
        ) {
            ensureSentContentCompleteIfAtEndOfStream()
        } else if (imaAdState != IMA_AD_STATE_NONE && playbackState == Player.STATE_ENDED) {
            val adMediaInfo: AdMediaInfo? = imaAdMediaInfo
            if (adMediaInfo == null) {
                Log.w(TAG, "onEnded without ad media info")
            } else {
                for (i in adCallbacks.indices) {
                    adCallbacks.get(i).onEnded(adMediaInfo)
                }
            }
            if (configuration.debugModeEnabled) {
                Log.d(TAG, "VideoAdPlayerCallback.onEnded in onPlaybackStateChanged")
            }
        }
    }

    private fun handleTimelineOrPositionChanged() {
        val player: Player? = this.player
        if (adsManager == null || player == null) {
            return
        }
        if (!playingAd && !player.isPlayingAd) {
            ensureSentContentCompleteIfAtEndOfStream()
            if (!sentContentComplete && !timeline.isEmpty) {
                val positionMs: Long = getContentPeriodPositionMs(player, timeline, period)
                timeline.getPeriod(player.currentPeriodIndex, period)
                val newAdGroupIndex = period.getAdGroupIndexForPositionUs(Util.msToUs(positionMs))
                if (newAdGroupIndex != C.INDEX_UNSET) {
                    sentPendingContentPositionMs = false
                    pendingContentPositionMs = positionMs
                }
            }
        }

        val wasPlayingAd = playingAd
        val oldPlayingAdIndexInAdGroup = playingAdIndexInAdGroup
        playingAd = player.isPlayingAd
        playingAdIndexInAdGroup = if (playingAd) player.currentAdIndexInAdGroup else C.INDEX_UNSET
        val adFinished = wasPlayingAd && playingAdIndexInAdGroup != oldPlayingAdIndexInAdGroup
        if (adFinished) {
            // IMA is waiting for the ad playback to finish so invoke the callback now.
            // Either CONTENT_RESUME_REQUESTED will be passed next, or playAd will be called again.
            val adMediaInfo: AdMediaInfo? = imaAdMediaInfo
            if (adMediaInfo == null) {
                Log.w(TAG, "onEnded without ad media info")
            } else {
                val adInfo = adInfoByAdMediaInfo.get(adMediaInfo)
                if (playingAdIndexInAdGroup == C.INDEX_UNSET
                    || (adInfo != null && adInfo.adIndexInAdGroup < playingAdIndexInAdGroup)
                ) {
                    for (i in adCallbacks.indices) {
                        adCallbacks.get(i).onEnded(adMediaInfo)
                    }
                    if (configuration.debugModeEnabled) {
                        Log.d(
                            TAG,
                            "VideoAdPlayerCallback.onEnded in onTimelineChanged/onPositionDiscontinuity"
                        )
                    }
                }
            }
        }
        if (!sentContentComplete && !wasPlayingAd && playingAd && imaAdState == IMA_AD_STATE_NONE) {
            val adGroup: AdPlaybackState.AdGroup =
                adPlaybackState.getAdGroup(player.currentAdGroupIndex)
            if (adGroup.timeUs == C.TIME_END_OF_SOURCE) {
                sendContentComplete()
            } else {
                // IMA hasn't called playAd yet, so fake the content position.
                fakeContentProgressElapsedRealtimeMs = SystemClock.elapsedRealtime()
                fakeContentProgressOffsetMs = Util.usToMs(adGroup.timeUs)
                if (fakeContentProgressOffsetMs == C.TIME_END_OF_SOURCE) {
                    fakeContentProgressOffsetMs = contentDurationMs
                }
            }
        }
        if (this.isWaitingForCurrentAdToLoad) {
            handler.removeCallbacks(adLoadTimeoutRunnable)
            handler.postDelayed(adLoadTimeoutRunnable, configuration.adPreloadTimeoutMs)
        }
    }

    private fun loadAdInternal(adMediaInfo: AdMediaInfo, adPodInfo: AdPodInfo) {
        if (adsManager == null) {
            // Drop events after release.
            if (configuration.debugModeEnabled) {
                Log.d(
                    TAG,
                    "loadAd after release " + getAdMediaInfoString(adMediaInfo) + ", ad pod " + adPodInfo
                )
            }
            return
        }

        val adGroupIndex = getAdGroupIndexForAdPod(adPodInfo)
        val adIndexInAdGroup: Int = adPodInfo.getAdPosition() - 1
        val adInfo = AdInfo(adGroupIndex, adIndexInAdGroup)
        // The ad URI may already be known, so force put to update it if needed.
        adInfoByAdMediaInfo.forcePut(adMediaInfo, adInfo)
        if (configuration.debugModeEnabled) {
            Log.d(TAG, "loadAd " + getAdMediaInfoString(adMediaInfo))
        }
        if (adPlaybackState.isAdInErrorState(adGroupIndex, adIndexInAdGroup)) {
            // We have already marked this ad as having failed to load, so ignore the request. IMA will
            // timeout after its media load timeout.
            return
        }

        // The ad count may increase on successive loads of ads in the same ad pod, for example, due to
        // separate requests for ad tags with multiple ads within the ad pod completing after an earlier
        // ad has loaded. See also https://github.com/google/ExoPlayer/issues/7477.
        var adGroup: AdPlaybackState.AdGroup = adPlaybackState.getAdGroup(adInfo.adGroupIndex)
        adPlaybackState =
            adPlaybackState.withAdCount(
                adInfo.adGroupIndex, max(adPodInfo.getTotalAds(), adGroup.states.size)
            )
        adGroup = adPlaybackState.getAdGroup(adInfo.adGroupIndex)
        for (i in 0..<adIndexInAdGroup) {
            // Any preceding ads that haven't loaded are not going to load.
            if (adGroup.states[i] == AdPlaybackState.AD_STATE_UNAVAILABLE) {
                adPlaybackState =
                    adPlaybackState.withAdLoadError(adGroupIndex,  /* adIndexInAdGroup= */i)
            }
        }

        val adMediaItem: MediaItem.Builder = MediaItem.Builder().setUri(adMediaInfo.getUrl())
        // Use the video MIME type if it is provided.
        // Demuxed streams may contain an audio MIME type, however it should only be used to set the
        // audio MIME type or compose the audio codec string, when/if ExoPlayer introduces support for
        // demuxed streams functionality. Even audio-only streams should only use the video MIME type as
        // they are not demuxed. It is possible that the video MIME type is not provided, in which case,
        // we do not set the MIME type of the MediaItem. However, if an audio MIME type is provided, it
        // is most likely that the video MIME type is also provided (though not the other way around).
        val videoMimeType: String? = adMediaInfo.getVideoMimeType()
        if (videoMimeType != null) {
            adMediaItem.setMimeType(videoMimeType)
        }

        adPlaybackState =
            adPlaybackState.withAvailableAdMediaItem(
                adInfo.adGroupIndex, adInfo.adIndexInAdGroup, adMediaItem.build()
            )
        updateAdPlaybackState()
    }

    private fun playAdInternal(adMediaInfo: AdMediaInfo) {
        if (configuration.debugModeEnabled) {
            Log.d(TAG, "playAd " + getAdMediaInfoString(adMediaInfo))
        }
        if (adsManager == null) {
            // Drop events after release.
            return
        }

        if (imaAdState == IMA_AD_STATE_PLAYING) {
            // IMA does not always call stopAd before resuming content.
            // See [Internal: b/38354028].
            Log.w(TAG, "Unexpected playAd without stopAd")
        }

        if (imaAdState == IMA_AD_STATE_NONE) {
            // IMA is requesting to play the ad, so stop faking the content position.
            fakeContentProgressElapsedRealtimeMs = C.TIME_UNSET
            fakeContentProgressOffsetMs = C.TIME_UNSET
            imaAdState = IMA_AD_STATE_PLAYING
            imaAdMediaInfo = adMediaInfo
            imaAdInfo = adInfoByAdMediaInfo.get(adMediaInfo)!!
            for (i in adCallbacks.indices) {
                adCallbacks.get(i).onPlay(adMediaInfo)
            }
            if (pendingAdPrepareErrorAdInfo != null && pendingAdPrepareErrorAdInfo == imaAdInfo) {
                pendingAdPrepareErrorAdInfo = null
                for (i in adCallbacks.indices) {
                    adCallbacks.get(i).onError(adMediaInfo)
                }
            }
            updateAdProgress()
        } else {
            imaAdState = IMA_AD_STATE_PLAYING
            for (i in adCallbacks.indices) {
                adCallbacks.get(i).onResume(adMediaInfo)
            }
        }
        if (player?.playWhenReady==false) {
            // Either this loader hasn't been activated yet, or the player is paused now.
            adsManager!!.pause()
        }
    }

    private fun pauseAdInternal(adMediaInfo: AdMediaInfo) {
        if (configuration.debugModeEnabled) {
            Log.d(TAG, "pauseAd " + getAdMediaInfoString(adMediaInfo))
        }
        if (adsManager == null) {
            // Drop event after release.
            return
        }
        if (imaAdState == IMA_AD_STATE_NONE) {
            // This method is called if loadAd has been called but the loaded ad won't play due to a seek
            // to a different position, so drop the event. See also [Internal: b/159111848].
            return
        }
        if (configuration.debugModeEnabled && !adMediaInfo.equals(imaAdMediaInfo)) {
            Log.w(
                TAG,
                ("Unexpected pauseAd for "
                        + getAdMediaInfoString(adMediaInfo)
                        + ", expected "
                        + getAdMediaInfoString(imaAdMediaInfo))
            )
        }
        imaAdState = IMA_AD_STATE_PAUSED
        for (i in adCallbacks.indices) {
            adCallbacks.get(i).onPause(adMediaInfo)
        }
    }

    private fun stopAdInternal(adMediaInfo: AdMediaInfo?) {
        if (configuration.debugModeEnabled) {
            Log.d(TAG, "stopAd " + getAdMediaInfoString(adMediaInfo))
        }
        if (adsManager == null) {
            // Drop event after release.
            return
        }
        if (imaAdState == IMA_AD_STATE_NONE) {
            // This method is called if loadAd has been called but the preloaded ad won't play due to a
            // seek to a different position, so drop the event and discard the ad. See also [Internal:
            // b/159111848].
            val adInfo = adInfoByAdMediaInfo.get(adMediaInfo)
            if (adInfo != null) {
                adPlaybackState =
                    adPlaybackState.withSkippedAd(adInfo.adGroupIndex, adInfo.adIndexInAdGroup)
                updateAdPlaybackState()
            }
            return
        }
        imaAdState = IMA_AD_STATE_NONE
        stopUpdatingAdProgress()
        // TODO: Handle the skipped event so the ad can be marked as skipped rather than played.
        val imaAdInfo = imaAdInfo!!
        val adGroupIndex = imaAdInfo!!.adGroupIndex
        val adIndexInAdGroup = imaAdInfo!!.adIndexInAdGroup
        if (adPlaybackState.isAdInErrorState(adGroupIndex, adIndexInAdGroup)) {
            // We have already marked this ad as having failed to load, so ignore the request.
            return
        }
        adPlaybackState =
            adPlaybackState.withPlayedAd(adGroupIndex, adIndexInAdGroup).withAdResumePositionUs(0)
        updateAdPlaybackState()
        if (!playingAd) {
            imaAdMediaInfo = null
            this.imaAdInfo = null
        }
    }

    private fun handleAdGroupLoadError(error: Exception?) {
        val adGroupIndex = this.loadingAdGroupIndex
        if (adGroupIndex == C.INDEX_UNSET) {
            Log.w(TAG, "Unable to determine ad group index for ad group load error", error)
            return
        }
        markAdGroupInErrorStateAndClearPendingContentPosition(adGroupIndex)
        if (pendingAdLoadError == null) {
            pendingAdLoadError = AdLoadException.createForAdGroup(error, adGroupIndex)
        }
    }

    private fun handleAdLoadTimeout() {
        // We started the timeout when we were first waiting for the current ad to load. Check if we are
        // still waiting after the timeout before triggering the error event.
        if (!this.isWaitingForCurrentAdToLoad) {
            return
        }
        // IMA got stuck and didn't load an ad in time, so skip the entire group.
        handleAdGroupLoadError(IOException("Ad loading timed out"))
        maybeNotifyPendingAdLoadError()
    }

    private fun markAdGroupInErrorStateAndClearPendingContentPosition(adGroupIndex: Int) {
        // Update the ad playback state so all ads in the ad group are in the error state.
        var adGroup = adPlaybackState.getAdGroup(adGroupIndex)
        if (adGroup.count == C.LENGTH_UNSET) {
            adPlaybackState = adPlaybackState.withAdCount(adGroupIndex, max(1, adGroup.states.size))
            adGroup = adPlaybackState.getAdGroup(adGroupIndex)
        }
        for (i in 0..<adGroup.count) {
            if (adGroup.states[i] == AdPlaybackState.AD_STATE_UNAVAILABLE) {
                if (configuration.debugModeEnabled) {
                    Log.d(TAG, "Removing ad " + i + " in ad group " + adGroupIndex)
                }
                adPlaybackState = adPlaybackState.withAdLoadError(adGroupIndex, i)
            }
        }
        updateAdPlaybackState()
        // Clear any pending content position that triggered attempting to load the ad group.
        pendingContentPositionMs = C.TIME_UNSET
        fakeContentProgressElapsedRealtimeMs = C.TIME_UNSET
    }

    private fun handleAdPrepareError(
        adGroupIndex: Int,
        adIndexInAdGroup: Int,
        exception: Exception?
    ) {
        if (configuration.debugModeEnabled) {
            Log.d(
                TAG,
                "Prepare error for ad " + adIndexInAdGroup + " in group " + adGroupIndex,
                exception
            )
        }
        if (adsManager == null) {
            Log.w(TAG, "Ignoring ad prepare error after release")
            return
        }
        if (imaAdState == IMA_AD_STATE_NONE) {
            // Send IMA a content position at the ad group so that it will try to play it, at which point
            // we can notify that it failed to load.
            fakeContentProgressElapsedRealtimeMs = SystemClock.elapsedRealtime()
            fakeContentProgressOffsetMs =
                Util.usToMs(adPlaybackState.getAdGroup(adGroupIndex).timeUs)
            if (fakeContentProgressOffsetMs == C.TIME_END_OF_SOURCE) {
                fakeContentProgressOffsetMs = contentDurationMs
            }
            pendingAdPrepareErrorAdInfo = AdInfo(adGroupIndex, adIndexInAdGroup)
        } else {
            val adMediaInfo: AdMediaInfo = imaAdMediaInfo!!
            // We're already playing an ad.
            if (adIndexInAdGroup > playingAdIndexInAdGroup) {
                // Mark the playing ad as ended so we can notify the error on the next ad and remove it,
                // which means that the ad after will load (if any).
                for (i in adCallbacks.indices) {
                    adCallbacks.get(i).onEnded(adMediaInfo)
                }
            }
            playingAdIndexInAdGroup = adPlaybackState.getAdGroup(adGroupIndex).firstAdIndexToPlay
            for (i in adCallbacks.indices) {
                adCallbacks.get(i).onError(adMediaInfo)
            }
        }
        adPlaybackState = adPlaybackState.withAdLoadError(adGroupIndex, adIndexInAdGroup)
        updateAdPlaybackState()
    }

    private fun ensureSentContentCompleteIfAtEndOfStream() {
        if (sentContentComplete
            || contentDurationMs == C.TIME_UNSET || pendingContentPositionMs != C.TIME_UNSET
        ) {
            return
        }
        val contentPeriodPositionMs: Long =
            getContentPeriodPositionMs(
                player!!,
                timeline,
                period
            )
        if (contentPeriodPositionMs + THRESHOLD_END_OF_CONTENT_MS < contentDurationMs) {
            return
        }
        val pendingAdGroupIndex =
            adPlaybackState.getAdGroupIndexForPositionUs(
                Util.msToUs(contentPeriodPositionMs), Util.msToUs(contentDurationMs)
            )
        if (pendingAdGroupIndex != C.INDEX_UNSET && adPlaybackState.getAdGroup(pendingAdGroupIndex).timeUs != C.TIME_END_OF_SOURCE && adPlaybackState.getAdGroup(
                pendingAdGroupIndex
            ).shouldPlayAdGroup()
        ) {
            // Pending mid-roll ad that needs to be played before marking the content complete.
            return
        }
        sendContentComplete()
    }

    private fun sendContentComplete() {
        for (i in adCallbacks.indices) {
            adCallbacks.get(i).onContentComplete()
        }
        sentContentComplete = true
        if (configuration.debugModeEnabled) {
            Log.d(TAG, "adsLoader.contentComplete")
        }
        for (i in 0..<adPlaybackState.adGroupCount) {
            if (adPlaybackState.getAdGroup(i).timeUs != C.TIME_END_OF_SOURCE) {
                adPlaybackState = adPlaybackState.withSkippedAdGroup( /* adGroupIndex= */i)
            }
        }
        updateAdPlaybackState()
    }

    private fun updateAdPlaybackState() {
        for (i in eventListeners.indices) {
            eventListeners.get(i)!!.onAdPlaybackState(adPlaybackState)
        }
    }

    private fun maybeNotifyPendingAdLoadError() {
        if (pendingAdLoadError != null) {
            for (i in eventListeners.indices) {
                eventListeners.get(i)!!.onAdLoadError(pendingAdLoadError!!, adTagDataSpec)
            }
            pendingAdLoadError = null
        }
    }

    private fun maybeNotifyInternalError(name: String, cause: Exception?) {
        val message = "Internal error in " + name
        Log.e(TAG, message, cause)
        // We can't recover from an unexpected error in general, so skip all remaining ads.
        for (i in 0..<adPlaybackState.adGroupCount) {
            adPlaybackState = adPlaybackState.withSkippedAdGroup(i)
        }
        updateAdPlaybackState()
        for (i in eventListeners.indices) {
            eventListeners
                .get(i)!!
                .onAdLoadError(
                    AdLoadException.createForUnexpected(RuntimeException(message, cause)),
                    adTagDataSpec
                )
        }
    }

    private fun getAdGroupIndexForAdPod(adPodInfo: AdPodInfo): Int {
        if (adPodInfo.getPodIndex() === -1) {
            // This is a postroll ad.
            return adPlaybackState.adGroupCount - 1
        }

        // adPodInfo.podIndex may be 0-based or 1-based, so for now look up the cue point instead.
        return getAdGroupIndexForCuePointTimeSeconds(adPodInfo.getTimeOffset())
    }

    private val loadingAdGroupIndex: Int
        /**
         * Returns the index of the ad group that will preload next, or [C.INDEX_UNSET] if there is
         * no such ad group.
         */
        get() {
            if (player == null) {
                return C.INDEX_UNSET
            }
            val playerPositionUs: Long =
                Util.msToUs(getContentPeriodPositionMs(player!!, timeline, period))
            var adGroupIndex =
                adPlaybackState.getAdGroupIndexForPositionUs(
                    playerPositionUs,
                    Util.msToUs(contentDurationMs)
                )
            if (adGroupIndex == C.INDEX_UNSET) {
                adGroupIndex =
                    adPlaybackState.getAdGroupIndexAfterPositionUs(
                        playerPositionUs, Util.msToUs(contentDurationMs)
                    )
            }
            return adGroupIndex
        }

    private fun getAdGroupIndexForCuePointTimeSeconds(cuePointTimeSeconds: Double): Int {
        // We receive initial cue points from IMA SDK as floats. This code replicates the same
        // calculation used to populate adGroupTimesUs (having truncated input back to float, to avoid
        // failures if the behavior of the IMA SDK changes to provide greater precision).
        val cuePointTimeSecondsFloat = cuePointTimeSeconds.toFloat()
        val adPodTimeUs = Math.round(cuePointTimeSecondsFloat.toDouble() * C.MICROS_PER_SECOND)
        for (adGroupIndex in 0..<adPlaybackState.adGroupCount) {
            val adGroupTimeUs = adPlaybackState.getAdGroup(adGroupIndex).timeUs
            if (adGroupTimeUs != C.TIME_END_OF_SOURCE
                && abs(adGroupTimeUs - adPodTimeUs) < THRESHOLD_AD_MATCH_US
            ) {
                return adGroupIndex
            }
        }
        throw IllegalStateException("Failed to find cue point")
    }

    private fun getAdMediaInfoString(adMediaInfo: AdMediaInfo?): String {
        val adInfo = adInfoByAdMediaInfo.get(adMediaInfo)
        return ("AdMediaInfo["
                + (if (adMediaInfo == null) "null" else adMediaInfo.getUrl())
                + ", "
                + adInfo
                + "]")
    }

    private fun destroyAdsManager() {
        val adsManager = adsManager
        if (adsManager != null) {
            adsManager.removeAdErrorListener(componentListener)
            if (configuration.applicationAdErrorListener != null) {
                adsManager.removeAdErrorListener(configuration.applicationAdErrorListener)
            }
            adsManager.removeAdEventListener(componentListener)
            if (configuration.applicationAdEventListener != null) {
                adsManager.removeAdEventListener(configuration.applicationAdEventListener)
            }
            adsManager.destroy()
            this.adsManager = null
        }
    }

    private inner class ContentPlaybackAdapter : ContentProgressProvider {
        override fun getContentProgress(): VideoProgressUpdate {
            val videoProgressUpdate = contentVideoProgressUpdate
            if (configuration.debugModeEnabled) {
                Log.d(
                    TAG,
                    "Content progress: " + ImaUtil.getStringForVideoProgressUpdate(
                        videoProgressUpdate
                    )
                )
            }

            if (waitingForPreloadElapsedRealtimeMs != C.TIME_UNSET) {
                // IMA is polling the player position but we are buffering for an ad to preload, so playback
                // may be stuck. Detect this case and signal an error if applicable.
                val stuckElapsedRealtimeMs =
                    SystemClock.elapsedRealtime() - waitingForPreloadElapsedRealtimeMs
                if (stuckElapsedRealtimeMs >= configuration.adPreloadTimeoutMs) {
                    waitingForPreloadElapsedRealtimeMs = C.TIME_UNSET
                    handleAdGroupLoadError(IOException("Ad preloading timed out"))
                    maybeNotifyPendingAdLoadError()
                }
            } else if (pendingContentPositionMs != C.TIME_UNSET && player != null && player!!.playbackState == Player.STATE_BUFFERING && isWaitingForFirstAdToPreload) {
                // Prepare to timeout the load of an ad for the pending seek operation.
                waitingForPreloadElapsedRealtimeMs = SystemClock.elapsedRealtime()
            }

            return videoProgressUpdate
        }
    }

    private inner class ComponentListener
        : AdsLoadedListener, AdEvent.AdEventListener, AdErrorEvent.AdErrorListener {
        // AdsLoader.AdsLoadedListener implementation.
        public override fun onAdsManagerLoaded(adsManagerLoadedEvent: AdsManagerLoadedEvent) {
            val adsManager: AdsManager? = adsManagerLoadedEvent.getAdsManager()
            if (adsManager == null) {
                // The same AdsLoader may be used for both Client-side ads and SSAI ads at the same time.
                // In this scenario, it may emit an `AdsManagerLoadedEvent` which should be handled by the
                // `ImaServerSideAdInsertionMediaSource` instead of the `AdTagLoader`. It's safe to ignore
                // that event.
                return
            }
            if (pendingAdRequestContext != adsManagerLoadedEvent.getUserRequestContext()) {
                adsManager.destroy()
                return
            }
            pendingAdRequestContext = null
            this@AdTagLoader.adsManager = adsManager
            adsManager.addAdErrorListener(this)
            if (configuration.applicationAdErrorListener != null) {
                adsManager.addAdErrorListener(configuration.applicationAdErrorListener)
            }
            adsManager.addAdEventListener(this)
            if (configuration.applicationAdEventListener != null) {
                adsManager.addAdEventListener(configuration.applicationAdEventListener)
            }
            try {
                adPlaybackState =
                    AdPlaybackState(
                        adsId,
                        ImaUtil.getAdGroupTimesUsForCuePoints(adsManager.getAdCuePoints()).map {
                            AdPlaybackState.AdGroup(it)
                        }
                    )
                updateAdPlaybackState()
            } catch (e: RuntimeException) {
                maybeNotifyInternalError("onAdsManagerLoaded", e)
            }
        }

        // AdEvent.AdEventListener implementation.
        public override fun onAdEvent(adEvent: AdEvent) {
            val adEventType: AdEvent.AdEventType? = adEvent.getType()
            if (configuration.debugModeEnabled && adEventType !== AdEvent.AdEventType.AD_PROGRESS) {
                Log.d(TAG, "onAdEvent: " + adEventType)
            }
            try {
                handleAdEvent(adEvent)
            } catch (e: RuntimeException) {
                maybeNotifyInternalError("onAdEvent", e)
            }
        }

        // AdErrorEvent.AdErrorListener implementation.
        public override fun onAdError(adErrorEvent: AdErrorEvent) {
            val error: AdError = adErrorEvent.getError()
            if (configuration.debugModeEnabled) {
                Log.d(TAG, "onAdError", error)
            }
            if (adsManager == null) {
                // No ads were loaded, so allow playback to start without any ads.
                pendingAdRequestContext = null
                adPlaybackState = AdPlaybackState(adsId)
                updateAdPlaybackState()
            } else if (ImaUtil.isAdGroupLoadError(error)) {
                try {
                    handleAdGroupLoadError(error)
                } catch (e: RuntimeException) {
                    maybeNotifyInternalError("onAdError", e)
                }
            }
            if (pendingAdLoadError == null) {
                pendingAdLoadError = AdLoadException.createForAllAds(error)
            }
            maybeNotifyPendingAdLoadError()
        }
    }

    internal inner class VideoAdPlayerImpl : VideoAdPlayer {
        public override fun addCallback(videoAdPlayerCallback: VideoAdPlayer.VideoAdPlayerCallback) {
            adCallbacks.add(videoAdPlayerCallback)
        }

        public override fun removeCallback(videoAdPlayerCallback: VideoAdPlayer.VideoAdPlayerCallback) {
            adCallbacks.remove(videoAdPlayerCallback)
        }

        public override fun loadAd(adMediaInfo: AdMediaInfo, adPodInfo: AdPodInfo) {
            try {
                loadAdInternal(adMediaInfo, adPodInfo)
            } catch (e: RuntimeException) {
                maybeNotifyInternalError("loadAd", e)
            }
        }

        public override fun playAd(adMediaInfo: AdMediaInfo) {
            try {
                playAdInternal(adMediaInfo)
            } catch (e: RuntimeException) {
                maybeNotifyInternalError("playAd", e)
            }
        }

        public override fun pauseAd(adMediaInfo: AdMediaInfo) {
            try {
                pauseAdInternal(adMediaInfo)
            } catch (e: RuntimeException) {
                maybeNotifyInternalError("pauseAd", e)
            }
        }

        public override fun stopAd(adMediaInfo: AdMediaInfo) {
            try {
                stopAdInternal(adMediaInfo)
            } catch (e: RuntimeException) {
                maybeNotifyInternalError("stopAd", e)
            }
        }

        public override fun release() {
            // Do nothing.
        }

        override fun getAdProgress(): VideoProgressUpdate {
            throw java.lang.IllegalStateException("Unexpected call to getAdProgress when using preloading")
        }

        override fun getVolume(): Int {
            return playerVolumePercent
        }
    }

    // TODO: Consider moving this into AdPlaybackState.
    private class AdInfo(val adGroupIndex: Int, val adIndexInAdGroup: Int) {
        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || javaClass != o.javaClass) {
                return false
            }
            val adInfo = o as AdInfo
            if (adGroupIndex != adInfo.adGroupIndex) {
                return false
            }
            return adIndexInAdGroup == adInfo.adIndexInAdGroup
        }

        override fun hashCode(): Int {
            var result = adGroupIndex
            result = 31 * result + adIndexInAdGroup
            return result
        }

        override fun toString(): String {
            return "(" + adGroupIndex + ", " + adIndexInAdGroup + ')'
        }
    }

    companion object {
        private const val TAG = "AdTagLoader"

        private const val IMA_SDK_SETTINGS_PLAYER_TYPE = "google/exo.ext.ima"
        private val IMA_SDK_SETTINGS_PLAYER_VERSION: String = MediaLibraryInfo.VERSION

        /**
         * Interval at which ad progress updates are provided to the IMA SDK, in milliseconds. 200 ms is
         * the interval recommended by the Media Rating Council (MRC) for minimum polling of viewable
         * video impressions.
         * http://www.mediaratingcouncil.org/063014%20Viewable%20Ad%20Impression%20Guideline_Final.pdf.
         * 
         * @see VideoAdPlayer.VideoAdPlayerCallback
         */
        private const val AD_PROGRESS_UPDATE_INTERVAL_MS = 200

        /** The value used in [VideoProgressUpdate]s to indicate an unset duration.  */
        private val IMA_DURATION_UNSET = -1L

        /**
         * Threshold before the end of content at which IMA is notified that content is complete if the
         * player buffers, in milliseconds.
         */
        private const val THRESHOLD_END_OF_CONTENT_MS: Long = 5000

        /** The threshold below which ad cue points are treated as matching, in microseconds.  */
        private const val THRESHOLD_AD_MATCH_US: Long = 1000

        /** The ad playback state when IMA is not playing an ad.  */
        private const val IMA_AD_STATE_NONE = 0

        private const val IMA_AD_STATE_PLAYING = 1

        /**
         * The ad playback state when IMA has called [ComponentListener.pauseAd] while
         * playing an ad.
         */
        private const val IMA_AD_STATE_PAUSED = 2

        private fun getContentPeriodPositionMs(
            player: Player, timeline: Timeline, period: Timeline.Period
        ): Long {
            val contentWindowPositionMs: Long = player.contentPosition
            if (timeline.isEmpty) {
                return contentWindowPositionMs
            } else {
                return (contentWindowPositionMs
                        - timeline.getPeriod(player.currentPeriodIndex, period).positionInWindowMs)
            }
        }

        private fun hasMidrollAdGroups(adPlaybackState: AdPlaybackState): Boolean {
            val count: Int = adPlaybackState.adGroupCount
            if (count == 1) {
                val adGroupTimeUs: Long = adPlaybackState.getAdGroup(0).timeUs
                return adGroupTimeUs != 0L && adGroupTimeUs != C.TIME_END_OF_SOURCE
            } else if (count == 2) {
                return adPlaybackState.getAdGroup(0).timeUs != 0L
                        || adPlaybackState.getAdGroup(1).timeUs != C.TIME_END_OF_SOURCE
            } else {
                // There's at least one midroll ad group, as adPlaybackState is never empty.
                return true
            }
        }
    }
}
