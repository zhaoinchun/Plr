package androidx.media3.exoplayer.ima

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.GuardedBy
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.media3.common.AdOverlayInfo
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.AdViewProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.ConditionVariable
import androidx.media3.common.util.Log
import androidx.media3.common.util.Util
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ads.ServerSideAdInsertionMediaSource
import androidx.media3.exoplayer.source.ads.ServerSideAdInsertionUtil
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.google.ads.interactivemedia.v3.api.Ad
import com.google.ads.interactivemedia.v3.api.AdErrorEvent
import com.google.ads.interactivemedia.v3.api.AdEvent
import com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType.LOADED
import com.google.ads.interactivemedia.v3.api.AdPodInfo
import com.google.ads.interactivemedia.v3.api.AdSlot
import com.google.ads.interactivemedia.v3.api.AdsManagerLoadedEvent
import com.google.ads.interactivemedia.v3.api.AdsRenderingSettings
import com.google.ads.interactivemedia.v3.api.CompanionAdSlot
import com.google.ads.interactivemedia.v3.api.CuePoint
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory
import com.google.ads.interactivemedia.v3.api.ImaSdkSettings
import com.google.ads.interactivemedia.v3.api.StreamDisplayContainer
import com.google.ads.interactivemedia.v3.api.StreamManager
import com.google.ads.interactivemedia.v3.api.StreamRequest
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate
import com.google.ads.interactivemedia.v3.api.player.VideoStreamPlayer
import com.google.common.base.Preconditions
import com.google.common.base.Predicate
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.io.IOException
import kotlin.Any
import androidx.media3.extractor.metadata.emsg.EventMessage
import kotlin.Boolean
import kotlin.Float
import kotlin.Int
import kotlin.IntArray
import kotlin.Long
import kotlin.String
import kotlin.Throws
import kotlin.check
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.MutableCollection
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.count
import kotlin.collections.get
import kotlin.collections.indices
import kotlin.collections.minus
import kotlin.collections.plus
import kotlin.concurrent.Volatile
import kotlin.math.floor
import kotlin.minus
import kotlin.plus
import kotlin.sequences.count
import kotlin.sequences.minus
import kotlin.sequences.plus
import kotlin.text.compareTo
import kotlin.text.count
import kotlin.text.get
import kotlin.text.indices
import kotlin.text.replace
import com.google.ads.interactivemedia.v3.api.AdsLoader.AdsLoadedListener

class ImaServerSideAdInsertionMediaSource(
    imaSdkFactory: ImaSdkFactory,
    player: Player,
    mediaItem: MediaItem,
    streamRequest: StreamRequest,
    adsLoader: AdsLoader,
    sdkAdsLoader: com.google.ads.interactivemedia.v3.api.AdsLoader,
    streamPlayer: StreamPlayer,
    contentMediaSourceFactory: MediaSource.Factory
) : CompositeMediaSource<Void?>() {

    fun interface StreamEventListener {
        fun onStreamIdChanged(mediaItem: MediaItem?, streamId: String?)
    }

    class Factory(
        private val adsLoader: AdsLoader,
        private val contentMediaSourceFactory: MediaSource.Factory
    ) : MediaSource.Factory {
        private var imaSdkFactory: ImaSdkFactory

        init {
            imaSdkFactory = ImaSdkFactory.getInstance()
        }

        @CanIgnoreReturnValue
        override fun setLoadErrorHandlingPolicy(
            loadErrorHandlingPolicy: LoadErrorHandlingPolicy
        ): MediaSource.Factory {
            contentMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            return this
        }

        @CanIgnoreReturnValue
        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: DrmSessionManagerProvider
        ): MediaSource.Factory {
            contentMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
            return this
        }

        override val supportedTypes: IntArray
            get() = contentMediaSourceFactory.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            Preconditions.checkNotNull<MediaItem.LocalConfiguration?>(mediaItem.localConfiguration)
            val player: Player = adsLoader.player!!
            val streamRequestUri: Uri =
                Preconditions.checkNotNull<MediaItem.LocalConfiguration?>(mediaItem.localConfiguration).uri
            val streamRequest: StreamRequest =
                ImaServerSideAdInsertionUriBuilder.Companion.createStreamRequest(streamRequestUri)
            val streamPlayer = StreamPlayer(player, mediaItem, streamRequest)
            val streamDisplayContainer: StreamDisplayContainer =
                createStreamDisplayContainer(imaSdkFactory, adsLoader.configuration, streamPlayer)
            val imaAdsLoader: com.google.ads.interactivemedia.v3.api.AdsLoader =
                imaSdkFactory.createAdsLoader(
                    adsLoader.context,
                    adsLoader.configuration.imaSdkSettings,
                    streamDisplayContainer
                )
            val mediaSource =
                ImaServerSideAdInsertionMediaSource(
                    imaSdkFactory,
                    player,
                    mediaItem,
                    streamRequest,
                    adsLoader,
                    imaAdsLoader,
                    streamPlayer,
                    contentMediaSourceFactory
                )
            adsLoader.addMediaSourceResources(mediaSource, streamPlayer, imaAdsLoader)
            return mediaSource
        }

        @VisibleForTesting
        fun setImaSdkFactory(imaSdkFactory: ImaSdkFactory) {
            this.imaSdkFactory = imaSdkFactory
        }
    }

    class AdsLoader(
        context: Context,
        configuration: ImaUtil.ServerSideAdInsertionConfiguration,
        state: State
    ) {

        class Builder(private val context: Context, adViewProvider: AdViewProvider?) {
            private val adViewProvider: AdViewProvider?

            private var imaSdkSettings: ImaSdkSettings? = null
            private var streamEventListener: StreamEventListener?
            private var adEventListener: AdEvent.AdEventListener? = null
            private var adErrorListener: AdErrorEvent.AdErrorListener? = null
            private var state: State
            private var companionAdSlots: ImmutableList<CompanionAdSlot?>?
            private var pauseAdSlot: AdSlot? = null
            private var focusSkipButtonWhenAvailable: Boolean
            private var enableCustomTabs: Boolean

            init {
                this.adViewProvider = adViewProvider
                companionAdSlots = ImmutableList.of<CompanionAdSlot?>()
                state = State(ImmutableMap.of<String?, AdPlaybackState?>())
                focusSkipButtonWhenAvailable = true
                enableCustomTabs = false
                streamEventListener =
                    StreamEventListener { mediaItem: MediaItem?, streamId: String? -> }
            }

            @CanIgnoreReturnValue
            fun setImaSdkSettings(imaSdkSettings: ImaSdkSettings?): Builder {
                this.imaSdkSettings = imaSdkSettings
                return this
            }

            @CanIgnoreReturnValue
            fun setStreamEventListener(streamEventListener: StreamEventListener?): Builder {
                this.streamEventListener = streamEventListener
                return this
            }

            @CanIgnoreReturnValue
            fun setAdEventListener(adEventListener: AdEvent.AdEventListener?): Builder {
                this.adEventListener = adEventListener
                return this
            }

            @CanIgnoreReturnValue
            fun setAdErrorListener(adErrorListener: AdErrorEvent.AdErrorListener?): Builder {
                this.adErrorListener = adErrorListener
                return this
            }

            @CanIgnoreReturnValue
            fun setCompanionAdSlots(companionAdSlots: MutableCollection<CompanionAdSlot?>): Builder {
                this.companionAdSlots = ImmutableList.copyOf<CompanionAdSlot?>(companionAdSlots)
                return this
            }

            @CanIgnoreReturnValue
            fun setPauseAdSlot(pauseAdSlot: AdSlot?): Builder {
                this.pauseAdSlot = Preconditions.checkNotNull<AdSlot?>(pauseAdSlot)
                return this
            }

            @CanIgnoreReturnValue
            fun setAdsLoaderState(state: State): Builder {
                this.state = state
                return this
            }

            @CanIgnoreReturnValue
            fun setFocusSkipButtonWhenAvailable(
                focusSkipButtonWhenAvailable: Boolean
            ): Builder {
                this.focusSkipButtonWhenAvailable = focusSkipButtonWhenAvailable
                return this
            }

            @CanIgnoreReturnValue
            fun setEnableCustomTabs(enableCustomTabs: Boolean): Builder {
                this.enableCustomTabs = enableCustomTabs
                return this
            }

            fun build(): AdsLoader {
                var imaSdkSettings: ImaSdkSettings? = this.imaSdkSettings
                if (imaSdkSettings == null) {
                    imaSdkSettings = ImaSdkFactory.getInstance().createImaSdkSettings()
                    imaSdkSettings.setLanguage(Util.systemLanguageCodes[0])
                }
                val configuration: ImaUtil.ServerSideAdInsertionConfiguration =
                    ImaUtil.ServerSideAdInsertionConfiguration(
                        adViewProvider!!,
                        imaSdkSettings,
                        streamEventListener!!,
                        adEventListener,
                        adErrorListener,
                        companionAdSlots!!,
                        pauseAdSlot,
                        focusSkipButtonWhenAvailable,
                        enableCustomTabs,
                        imaSdkSettings.isDebugMode()
                    )
                return AdsLoader(context, configuration, state)
            }
        }

        class State @VisibleForTesting internal constructor(adPlaybackStates: ImmutableMap<String, AdPlaybackState>) {
            val adPlaybackStates: ImmutableMap<String, AdPlaybackState>

            override fun equals(o: Any?): Boolean {
                if (this === o) {
                    return true
                }
                if (o !is State) {
                    return false
                }
                val state = o
                return adPlaybackStates == state.adPlaybackStates
            }

            override fun hashCode(): Int {
                return adPlaybackStates.hashCode()
            }

            init {
                this.adPlaybackStates = adPlaybackStates
            }

            fun toBundle(): Bundle {
                val bundle = Bundle()
                val adPlaybackStatesBundle = Bundle()
                for (entry in adPlaybackStates.entries) {
                    adPlaybackStatesBundle.putBundle(
                        entry.key, entry.value.toBundle(MediaLibraryInfo.INTERFACE_VERSION)
                    )
                }
                bundle.putBundle(FIELD_AD_PLAYBACK_STATES, adPlaybackStatesBundle)
                return bundle
            }

            companion object {
                private val FIELD_AD_PLAYBACK_STATES = Util.intToStringMaxRadix(1)

                @JvmStatic
                fun fromBundle(bundle: Bundle): State {
                    val adPlaybackStateMap: ImmutableMap.Builder<String, AdPlaybackState> =
                        ImmutableMap.Builder<String, AdPlaybackState>()
                    val adPlaybackStateBundle = Preconditions.checkNotNull<Bundle>(
                        bundle.getBundle(
                            FIELD_AD_PLAYBACK_STATES
                        )
                    )
                    for (key in adPlaybackStateBundle.keySet()) {
                        val adPlaybackState =
                            AdPlaybackState.fromBundle(
                                adPlaybackStateBundle.getBundle(key)!!,
                                MediaLibraryInfo.INTERFACE_VERSION
                            )
                        adPlaybackStateMap.put(
                            key,
                            AdPlaybackState.fromAdPlaybackState( /* adsId= */key, adPlaybackState)
                        )
                    }
                    return State(adPlaybackStateMap.buildOrThrow())
                }
            }
        }

        val configuration: ImaUtil.ServerSideAdInsertionConfiguration
        val context: Context
        val mediaSourceResources: MutableMap<String?, MediaSourceResourceHolder>
        val adPlaybackStateMap: MutableMap<String?, AdPlaybackState?>
        var player: Player? = null

        init {
            this.context = context.getApplicationContext()
            this.configuration = configuration
            mediaSourceResources = HashMap<String?, MediaSourceResourceHolder>()
            adPlaybackStateMap = HashMap<String?, AdPlaybackState?>()
            adPlaybackStateMap.putAll(state.adPlaybackStates)
        }

        fun replaceAdTagParameters(adTagParameters: MutableMap<String, String>) {
            val player = player
            if (player == null) {
                return
            }
            if (player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED && player.mediaItemCount > 0) {
                val currentPeriodIndex = player.currentPeriodIndex
                val adsId =
                    player
                        .currentTimeline
                        .getPeriod(currentPeriodIndex, Timeline.Period())
                        .adsId
                if (adsId is String) {
                    val mediaSourceResourceHolder = mediaSourceResources.get(adsId)
                    if (mediaSourceResourceHolder != null
                        && (mediaSourceResourceHolder.imaServerSideAdInsertionMediaSource.streamManager
                                != null)
                    ) {
                        mediaSourceResourceHolder.imaServerSideAdInsertionMediaSource.streamManager!!
                            .replaceAdTagParameters(adTagParameters)
                    }
                }
            }
        }

        fun focusSkipButton() {
            val player = player ?: return
            if (player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED && player.mediaItemCount > 0) {
                val currentPeriodIndex = player.currentPeriodIndex
                val adsId =
                    player
                        .currentTimeline
                        .getPeriod(currentPeriodIndex, Timeline.Period())
                        .adsId
                if (adsId is String) {
                    val mediaSourceResourceHolder = mediaSourceResources.get(adsId)
                    if (mediaSourceResourceHolder != null
                        && (mediaSourceResourceHolder.imaServerSideAdInsertionMediaSource.streamManager
                                != null)
                    ) {
                        mediaSourceResourceHolder.imaServerSideAdInsertionMediaSource.streamManager!!.focus()
                    }
                }
            }
        }

        fun release(): State {
            for (resourceHolder in mediaSourceResources.values) {
                resourceHolder.streamPlayer.release()
                resourceHolder.imaServerSideAdInsertionMediaSource.setStreamManager( /* streamManager= */
                    null
                )
                resourceHolder.adsLoader.release()
            }
            val state = State(ImmutableMap.copyOf<String?, AdPlaybackState?>(adPlaybackStateMap))
            adPlaybackStateMap.clear()
            mediaSourceResources.clear()
            player = null
            return state
        }

        // Internal methods.
        fun addMediaSourceResources(
            mediaSource: ImaServerSideAdInsertionMediaSource,
            streamPlayer: StreamPlayer,
            adsLoader: com.google.ads.interactivemedia.v3.api.AdsLoader
        ) {
            mediaSourceResources.put(
                mediaSource.adsId, MediaSourceResourceHolder(mediaSource, streamPlayer, adsLoader)
            )
        }

        fun getAdPlaybackState(adsId: String?): AdPlaybackState {
            val adPlaybackState: AdPlaybackState? = adPlaybackStateMap.get(adsId)
            return if (adPlaybackState != null) adPlaybackState else AdPlaybackState.NONE
        }

        fun setAdPlaybackState(adsId: String?, adPlaybackState: AdPlaybackState?) {
            this.adPlaybackStateMap.put(adsId, adPlaybackState)
        }

        class MediaSourceResourceHolder(
            val imaServerSideAdInsertionMediaSource: ImaServerSideAdInsertionMediaSource,
            val streamPlayer: StreamPlayer,
            adsLoader: com.google.ads.interactivemedia.v3.api.AdsLoader
        ) {
            val adsLoader: com.google.ads.interactivemedia.v3.api.AdsLoader

            init {
                this.adsLoader = adsLoader
            }
        }
    }

    private val player: Player
    private val contentMediaSourceFactory: MediaSource.Factory
    private val adsLoader: AdsLoader
    private val sdkAdsLoader: com.google.ads.interactivemedia.v3.api.AdsLoader
    private val streamEventListener: StreamEventListener
    private val applicationAdEventListener: AdEvent.AdEventListener?
    private val applicationAdErrorListener: AdErrorEvent.AdErrorListener?
    private val isLiveStream: Boolean
    private val adsId: String
    private val streamRequest: StreamRequest
    private val loadVideoTimeoutMs: Int
    private val streamPlayer: StreamPlayer
    private val mainHandler: Handler
    private val componentListener: ComponentListener
    private val imaSdkFactory: ImaSdkFactory

    private var loader: Loader? = null
    private var streamManager: StreamManager? = null
    private var streamId: String? = null
    private var serverSideAdInsertionMediaSource: ServerSideAdInsertionMediaSource? = null
    private var loadError: IOException? = null
    private var contentTimeline: Timeline? = null
    private var adPlaybackState: AdPlaybackState

    @get:Synchronized
    @GuardedBy("this")
    override var mediaItem: MediaItem
        private set

    init {
        var streamRequest: StreamRequest = streamRequest
        this.imaSdkFactory = imaSdkFactory
        this.player = player
        this.mediaItem = mediaItem
        this.streamRequest = streamRequest
        this.adsLoader = adsLoader
        this.sdkAdsLoader = sdkAdsLoader
        this.streamPlayer = streamPlayer
        this.contentMediaSourceFactory = contentMediaSourceFactory
        this.streamEventListener = adsLoader.configuration.streamEventListener
        this.applicationAdEventListener = adsLoader.configuration.applicationAdEventListener
        this.applicationAdErrorListener = adsLoader.configuration.applicationAdErrorListener
        Preconditions.checkArgument(player.applicationLooper == Looper.getMainLooper())
        mainHandler = Handler(Looper.getMainLooper())
        val streamRequestUri: Uri =
            Preconditions.checkNotNull<MediaItem.LocalConfiguration?>(mediaItem.localConfiguration).uri
        isLiveStream = ImaServerSideAdInsertionUriBuilder.Companion.isLiveStream(streamRequestUri)
        adsId = ImaServerSideAdInsertionUriBuilder.Companion.getAdsId(streamRequestUri)
        loadVideoTimeoutMs =
            ImaServerSideAdInsertionUriBuilder.Companion.getLoadVideoTimeoutMs(streamRequestUri)
        streamRequest =
            ImaServerSideAdInsertionUriBuilder.Companion.createStreamRequest(streamRequestUri)
        val isDashStream = streamRequest.getFormat() == StreamRequest.StreamFormat.DASH
        componentListener = ComponentListener(
            if (isLiveStream)
                (if (isDashStream)
                    MultiPeriodLiveAdEventListener()
                else
                    SinglePeriodLiveAdEventListener())
            else
                VodAdEventListener()
        )
        adPlaybackState = adsLoader.getAdPlaybackState(adsId)
    }

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        val existingMediaItem = mediaItem
        val existingConfiguration = existingMediaItem.localConfiguration!!
        val newConfiguration = mediaItem.localConfiguration
        return newConfiguration != null && newConfiguration.uri == existingConfiguration.uri
                && newConfiguration.streamKeys == existingConfiguration.streamKeys
                && newConfiguration.customCacheKey == existingConfiguration.customCacheKey
                && newConfiguration.drmConfiguration == existingConfiguration.drmConfiguration
                && existingMediaItem.liveConfiguration == mediaItem.liveConfiguration
    }

    @Synchronized
    override fun updateMediaItem(mediaItem: MediaItem) {
        this.mediaItem = mediaItem
    }

    public override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        mainHandler.post(Runnable {
            assertSingleInstanceInPlaylist(
                Preconditions.checkNotNull<Player?>(
                    player
                )
            )
        })
        super.prepareSourceInternal(mediaTransferListener)
        if (loader == null) {
            val loader = Loader("ImaServerSideAdInsertionMediaSource")
            player.addListener(componentListener)
            val streamManagerLoadable =
                StreamManagerLoadable(
                    sdkAdsLoader,  /* imaServerSideAdInsertionMediaSource= */
                    this,
                    streamRequest,
                    streamPlayer,
                    applicationAdErrorListener
                )
            loader.startLoading<StreamManagerLoadable>(
                streamManagerLoadable,
                StreamManagerLoadableCallback(),  /* defaultMinRetryCount= */
                0
            )
            this.loader = loader
        }
    }

    override fun onChildSourceInfoRefreshed(
        childSourceId: Void?, mediaSource: MediaSource, newTimeline: Timeline
    ) {
        val mediaItem = mediaItem
        refreshSourceInfo(
            object : ForwardingTimeline(newTimeline) {
                public override fun getWindow(
                    windowIndex: Int, window: Timeline.Window, defaultPositionProjectionUs: Long
                ): Timeline.Window {
                    newTimeline.getWindow(windowIndex, window, defaultPositionProjectionUs)
                    window.mediaItem = mediaItem
                    return window
                }
            })
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        return Preconditions.checkNotNull<ServerSideAdInsertionMediaSource?>(
            serverSideAdInsertionMediaSource
        )
            .createPeriod(id, allocator, startPositionUs)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        Preconditions.checkNotNull<ServerSideAdInsertionMediaSource?>(
            serverSideAdInsertionMediaSource
        ).releasePeriod(mediaPeriod)
    }

    @Throws(IOException::class)
    public override fun maybeThrowSourceInfoRefreshError() {
        super.maybeThrowSourceInfoRefreshError()
        val loadError = this.loadError
        if (loadError != null) {
            this.loadError = null
            throw loadError
        }
    }

    protected override fun releaseSourceInternal() {
        super.releaseSourceInternal()
        if (loader != null) {
            loader!!.release()
            mainHandler.post(
                Runnable {
                    player.removeListener(componentListener)
                    setStreamManager( /* streamManager= */null)
                })
            loader = null
        }
        contentTimeline = null
        serverSideAdInsertionMediaSource = null
    }

    // Internal methods (called on the main thread).
    @MainThread
    private fun setStreamManager(streamManager: StreamManager?) {
        if (this.streamManager === streamManager) {
            return
        }
        val streamManager = this.streamManager
        if (streamManager != null) {
            if (applicationAdEventListener != null) {
                streamManager.removeAdEventListener(applicationAdEventListener)
            }
            if (applicationAdErrorListener != null) {
                streamManager.removeAdErrorListener(applicationAdErrorListener)
            }
            streamManager.removeAdEventListener(componentListener)
            streamManager.destroy()
            streamId = null
        }
        this.streamManager = streamManager
        if (streamManager != null) {
            val newStreamId: String? = streamManager.getStreamId()
            if (streamId != newStreamId) {
                streamId = newStreamId
                streamEventListener.onStreamIdChanged(mediaItem, newStreamId)
            }
            streamManager.addAdEventListener(componentListener)
            if (applicationAdEventListener != null) {
                streamManager.addAdEventListener(applicationAdEventListener)
            }
            if (applicationAdErrorListener != null) {
                streamManager.addAdErrorListener(applicationAdErrorListener)
            }
            val adsRenderingSettings: AdsRenderingSettings =
                imaSdkFactory.createAdsRenderingSettings()
            adsRenderingSettings.setLoadVideoTimeout(loadVideoTimeoutMs)
            adsRenderingSettings.setFocusSkipButtonWhenAvailable(
                adsLoader.configuration.focusSkipButtonWhenAvailable
            )
            adsRenderingSettings.setEnableCustomTabs(adsLoader.configuration.enableCustomTabs)
            streamManager.init(adsRenderingSettings)
        }
    }

    @MainThread
    private fun setAdPlaybackState(adPlaybackState: AdPlaybackState) {
        if (adPlaybackState == this.adPlaybackState) {
            return
        }
        this.adPlaybackState = adPlaybackState
        invalidateServerSideAdInsertionAdPlaybackState()
    }

    @MainThread
    private fun setContentTimeline(contentTimeline: Timeline) {
        if (contentTimeline.equals(this.contentTimeline)) {
            return
        }
        if (isLiveStream && streamRequest.getFormat() == StreamRequest.StreamFormat.DASH) {
            // If the ad started playing while the corresponding period in the timeline had an unknown
            // duration, the ad duration is estimated and needs to be corrected when the actual duration
            // is reported.
            adPlaybackState =
                ImaUtil.maybeCorrectPreviouslyUnknownAdDurations(contentTimeline, adPlaybackState)
        }
        this.contentTimeline = contentTimeline
        invalidateServerSideAdInsertionAdPlaybackState()
    }

    @MainThread
    private fun invalidateServerSideAdInsertionAdPlaybackState() {
        if ((adPlaybackState != AdPlaybackState.NONE) && contentTimeline != null && serverSideAdInsertionMediaSource != null) {
            val contentTimeline: Timeline =
                Preconditions.checkNotNull<Timeline>(this.contentTimeline)
            val splitAdPlaybackStates: ImmutableMap<Any?, AdPlaybackState?>?
            if (streamRequest.getFormat() == StreamRequest.StreamFormat.DASH) {
                // DASH ad groups are always split by period.
                splitAdPlaybackStates =
                    ImaUtil.splitAdPlaybackStateForPeriods(adPlaybackState, contentTimeline)
            } else {
                // The HLS single period timeline for VOD and live must not be split.
                val firstPeriodIndex: Int =
                    contentTimeline.getWindow( /* windowIndex= */0,
                        Timeline.Window()
                    ).firstPeriodIndex
                val periodUid =
                    Preconditions.checkNotNull<Any?>(
                        contentTimeline.getPeriod(
                            firstPeriodIndex, Timeline.Period(),  /* setIds= */true
                        )
                            .uid
                    )
                splitAdPlaybackStates =
                    ImmutableMap.of<Any?, AdPlaybackState?>(periodUid, adPlaybackState)
            }
            streamPlayer.setAdPlaybackStates(adsId, splitAdPlaybackStates, contentTimeline)
            Preconditions.checkNotNull<ServerSideAdInsertionMediaSource?>(
                serverSideAdInsertionMediaSource
            )
                .setAdPlaybackStates(splitAdPlaybackStates, contentTimeline)
            if (!isLiveStream) {
                adsLoader.setAdPlaybackState(adsId, adPlaybackState)
            }
        }
    }

    // Internal methods (called on the playback thread).
    private fun setContentUri(contentUri: Uri?) {
        if (serverSideAdInsertionMediaSource == null) {
            val mediaItem = mediaItem
            val contentMediaItem =
                MediaItem.Builder()
                    .setUri(contentUri)
                    .setDrmConfiguration(
                        Preconditions.checkNotNull<MediaItem.LocalConfiguration?>(
                            mediaItem.localConfiguration
                        ).drmConfiguration
                    )
                    .setLiveConfiguration(mediaItem.liveConfiguration)
                    .setCustomCacheKey(mediaItem.localConfiguration!!.customCacheKey)
                    .setStreamKeys(mediaItem.localConfiguration.streamKeys)
                    .build()
            val serverSideAdInsertionMediaSource: ServerSideAdInsertionMediaSource =
                ServerSideAdInsertionMediaSource(
                    contentMediaSourceFactory.createMediaSource(contentMediaItem), componentListener
                )
            this.serverSideAdInsertionMediaSource = serverSideAdInsertionMediaSource
            if (isLiveStream) {
                mainHandler.post(
                    Runnable {
                        setAdPlaybackState(
                            AdPlaybackState(adsId)
                                .withLivePostrollPlaceholderAppended( /* isServerSideInserted= */
                                    true
                                )
                        )
                    })
            }
            prepareChildSource( /* id= */null, serverSideAdInsertionMediaSource)
        }
    }

    private inner class ComponentListener
        (adEventListener: AdEvent.AdEventListener) : AdEvent.AdEventListener, Player.Listener,
        ServerSideAdInsertionMediaSource.AdPlaybackStateUpdater {
        private val adEventListener: AdEvent.AdEventListener

        init {
            this.adEventListener = adEventListener
        }

        // Implement Player.Listener.
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (!(reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                        || (isLiveStream && reason == Player.DISCONTINUITY_REASON_REMOVE))
            ) {
                // Only auto transitions and removals of an ad period in live streams need to be handled.
                return
            }

            val mediaItem = mediaItem
            if (mediaItem.equals(oldPosition.mediaItem) && !mediaItem.equals(newPosition.mediaItem)) {
                // Playback automatically transitioned to the next media item. Notify the SDK.
                streamPlayer.onContentCompleted()
            }

            if (!mediaItem.equals(oldPosition.mediaItem) || !mediaItem.equals(newPosition.mediaItem) || (adsId != player
                    .currentTimeline
                    .getPeriodByUid(
                        Preconditions.checkNotNull<Any?>(newPosition.periodUid),
                        Timeline.Period()
                    )
                    .adsId)
            ) {
                // Discontinuity not within this ad media source.
                return
            }

            if (oldPosition.adGroupIndex != C.INDEX_UNSET) {
                var adGroupIndex = oldPosition.adGroupIndex
                var adIndexInAdGroup = oldPosition.adIndexInAdGroup
                val timeline: Timeline = player.currentTimeline
                val window: Timeline.Window =
                    timeline.getWindow(oldPosition.mediaItemIndex, Timeline.Window())
                if (window.lastPeriodIndex > window.firstPeriodIndex) {
                    if (reason == Player.DISCONTINUITY_REASON_REMOVE) {
                        setAdPlaybackState(
                            ImaUtil.handleAdPeriodRemovedFromTimeline(
                                player.currentPeriodIndex, timeline, adPlaybackState
                            )
                        )
                        return
                    }
                    // Map adGroupIndex and adIndexInAdGroup to multi-period window.
                    val periodIndexInContentTimeline =
                        oldPosition.periodIndex - window.firstPeriodIndex
                    val adGroupIndexAndAdIndexInAdGroup =
                        if (window.isLive)
                            ImaUtil.getAdGroupAndIndexInLiveMultiPeriodTimeline(
                                periodIndexInContentTimeline,
                                adPlaybackState,
                                Preconditions.checkNotNull<Timeline?>(contentTimeline)
                            )
                        else
                            ImaUtil.getAdGroupAndIndexInVodMultiPeriodTimeline(
                                periodIndexInContentTimeline,
                                adPlaybackState,
                                Preconditions.checkNotNull<Timeline?>(contentTimeline)
                            )
                    adGroupIndex = adGroupIndexAndAdIndexInAdGroup.first!!
                    adIndexInAdGroup = adGroupIndexAndAdIndexInAdGroup.second!!
                }

                var adGroup: AdPlaybackState.AdGroup = adPlaybackState.getAdGroup(adGroupIndex)
                val adState: Int = adGroup.states[adIndexInAdGroup]
                if (adState == AdPlaybackState.Companion.AD_STATE_AVAILABLE || adState == AdPlaybackState.Companion.AD_STATE_UNAVAILABLE) {
                    var newAdPlaybackState: AdPlaybackState =
                        adPlaybackState.withPlayedAd(
                            adGroupIndex,  /* adIndexInAdGroup= */
                            adIndexInAdGroup
                        )
                    adGroup = newAdPlaybackState.getAdGroup(adGroupIndex)
                    if (isLiveStream
                        && newPosition.adGroupIndex == C.INDEX_UNSET && adIndexInAdGroup < adGroup.states.size - 1 && adGroup.states[adIndexInAdGroup + 1] == AdPlaybackState.Companion.AD_STATE_AVAILABLE
                    ) {
                        // There is an available ad after the ad period that just ended being played!
                        Log.w(
                            TAG,
                            "Detected late ad event. Regrouping trailing ads into separate ad group."
                        )
                        newAdPlaybackState =
                            ImaUtil.splitAdGroup(
                                adGroup,
                                adGroupIndex,  /* splitIndexExclusive= */
                                adIndexInAdGroup + 1,
                                newAdPlaybackState
                            )
                    }
                    setAdPlaybackState(newAdPlaybackState)
                }
            }
        }

        override fun onMetadata(metadata: Metadata) {
            if (!isCurrentlyPlayingMediaPeriodFromThisSource(player, mediaItem, adsId)) {
                return
            }
            for (textFrame in metadata.getMatchingEntries<TextInformationFrame>(
                TextInformationFrame::class.java,
                Predicate { textFrame: TextInformationFrame -> textFrame.id == "TXXX" })) {
                streamPlayer.triggerUserTextReceived(textFrame.values.get(0))
            }
            for (eventMessage in metadata.getEntriesOfType<EventMessage>(EventMessage::class.java)) {
                streamPlayer.triggerUserTextReceived(kotlin.text.String(eventMessage.messageData!!))
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED
                && isCurrentlyPlayingMediaPeriodFromThisSource(player, mediaItem, adsId)
            ) {
                streamPlayer.onContentCompleted()
            }
        }

        override fun onVolumeChanged(volume: Float) {
            if (!isCurrentlyPlayingMediaPeriodFromThisSource(player, mediaItem, adsId)) {
                return
            }
            val volumePct = floor((volume * 100).toDouble()).toInt()
            streamPlayer.onContentVolumeChanged(volumePct)
        }

        // Implement AdEvent.AdEventListener.
        @MainThread
        public override fun onAdEvent(event: AdEvent) {
            adEventListener.onAdEvent(event)
        }

        // Implement AdPlaybackStateUpdater (called on the playback thread).
        override fun onAdPlaybackStateUpdateRequested(contentTimeline: Timeline): Boolean {
            mainHandler.post(Runnable { setContentTimeline(contentTimeline) })
            // Defer source refresh to ad playback state update for VOD (wait for potential ad cue points)
            // or DASH (split manifest).
            return !isLiveStream || streamRequest.getFormat() == StreamRequest.StreamFormat.DASH
        }
    }

    private inner class StreamManagerLoadableCallback

        : Loader.Callback<StreamManagerLoadable> {
        override fun onLoadCompleted(
            loadable: StreamManagerLoadable, elapsedRealtimeMs: Long, loadDurationMs: Long
        ) {
            setContentUri(
                Preconditions.checkNotNull<Uri?>(
                    loadable.contentUri
                )
            )
        }

        override fun onLoadCanceled(
            loadable: StreamManagerLoadable,
            elapsedRealtimeMs: Long,
            loadDurationMs: Long,
            released: Boolean
        ) {
            // We only cancel when the loader is released.
            Preconditions.checkState(released)
        }

        override fun onLoadError(
            loadable: StreamManagerLoadable,
            elapsedRealtimeMs: Long,
            loadDurationMs: Long,
            error: IOException,
            errorCount: Int
        ): Loader.LoadErrorAction {
            loadError = error
            return Loader.DONT_RETRY
        }
    }

    private class StreamManagerLoadable
        (
        adsLoader: com.google.ads.interactivemedia.v3.api.AdsLoader,
        imaServerSideAdInsertionMediaSource: ImaServerSideAdInsertionMediaSource,
        request: StreamRequest?,
        streamPlayer: StreamPlayer,
        adErrorListener: AdErrorEvent.AdErrorListener?
    ) : Loader.Loadable, AdsLoadedListener, AdErrorEvent.AdErrorListener {
        private val adsLoader: com.google.ads.interactivemedia.v3.api.AdsLoader
        private val imaServerSideAdInsertionMediaSource: ImaServerSideAdInsertionMediaSource
        private val request: StreamRequest?
        private val streamPlayer: StreamPlayer
        private val adErrorListener: AdErrorEvent.AdErrorListener?
        private val conditionVariable: ConditionVariable

        @Volatile
        var contentUri: Uri? = null
            private set

        @Volatile
        private var cancelled = false

        @Volatile
        private var error = false

        @Volatile
        private var errorMessage: String? = null

        @Volatile
        private var errorCode: Int

        init {
            this.adsLoader = adsLoader
            this.imaServerSideAdInsertionMediaSource = imaServerSideAdInsertionMediaSource
            this.request = request
            this.streamPlayer = streamPlayer
            this.adErrorListener = adErrorListener
            conditionVariable = ConditionVariable()
            errorCode = -1
        }

        // Implement Loadable.
        @Throws(IOException::class)
        override fun load() {
            try {
                // SDK will call loadUrl on stream player for SDK once manifest uri is available.
                streamPlayer.setStreamLoadListener(
                    StreamPlayer.StreamLoadListener { streamUri: String?, subtitles: MutableList<HashMap<String, String>>? ->
                        contentUri = Uri.parse(streamUri)
                        conditionVariable.open()
                    })
                if (adErrorListener != null) {
                    adsLoader.addAdErrorListener(adErrorListener)
                }
                adsLoader.addAdsLoadedListener(this)
                adsLoader.addAdErrorListener(this)
                adsLoader.requestStream(request!!)
                while (contentUri == null && !cancelled && !error) {
                    try {
                        conditionVariable.block()
                    } catch (e: InterruptedException) {
                        /* Do nothing. */
                    }
                }
                if (error && contentUri == null) {
                    throw IOException(errorMessage + " [errorCode: " + errorCode + "]")
                }
            } finally {
                adsLoader.removeAdsLoadedListener(this)
                adsLoader.removeAdErrorListener(this)
                if (adErrorListener != null) {
                    adsLoader.removeAdErrorListener(adErrorListener)
                }
            }
        }

        override fun cancelLoad() {
            cancelled = true
        }

        // AdsLoader.AdsLoadedListener implementation.
        @MainThread
        public override fun onAdsManagerLoaded(event: AdsManagerLoadedEvent) {
            val streamManager: StreamManager? = event.getStreamManager()
            if (streamManager == null) {
                error = true
                errorMessage = "streamManager is null after ads manager has been loaded"
                conditionVariable.open()
                return
            }
            imaServerSideAdInsertionMediaSource.setStreamManager(streamManager)
        }

        // AdErrorEvent.AdErrorListener implementation.
        @MainThread
        public override fun onAdError(adErrorEvent: AdErrorEvent) {
            error = true
            if (adErrorEvent.getError() != null) {
                val errorMessage: String? = adErrorEvent.getError().message
                if (errorMessage != null) {
                    this.errorMessage = errorMessage.replace('\n', ' ')
                }
                errorCode = adErrorEvent.getError().getErrorCodeNumber()
            }
            conditionVariable.open()
        }
    }

    class StreamPlayer(player: Player, mediaItem: MediaItem, streamRequest: StreamRequest) :
        VideoStreamPlayer {

        fun interface StreamLoadListener {

            fun onLoadStream(
                streamUri: String?,
                subtitles: MutableList<HashMap<String, String>>?
            )
        }

        private val callbacks: MutableList<VideoStreamPlayer.VideoStreamPlayerCallback>
        private val player: Player
        private val mediaItem: MediaItem
        private val window: Timeline.Window
        private val period: Timeline.Period
        private val isDashStream: Boolean

        private var adPlaybackStates: ImmutableMap<Any?, AdPlaybackState?>
        private var contentTimeline: Timeline? = null
        private var adsId: Any? = null
        private var streamLoadListener: StreamLoadListener? = null

        init {
            this.player = player
            this.mediaItem = mediaItem
            this.isDashStream = streamRequest.getFormat() === StreamRequest.StreamFormat.DASH
            callbacks =
                ArrayList<VideoStreamPlayer.VideoStreamPlayerCallback>( /* initialCapacity= */1)
            adPlaybackStates = ImmutableMap.of<Any?, AdPlaybackState?>()
            window = Timeline.Window()
            period = Timeline.Period()
        }

        fun setAdPlaybackStates(
            adsId: Any?,
            adPlaybackStates: ImmutableMap<Any?, AdPlaybackState?>,
            contentTimeline: Timeline?
        ) {
            this.adsId = adsId
            this.adPlaybackStates = adPlaybackStates
            this.contentTimeline = contentTimeline
        }

        fun setStreamLoadListener(listener: StreamLoadListener?) {
            streamLoadListener = Preconditions.checkNotNull<StreamLoadListener?>(listener)
        }

        fun onContentCompleted() {
            for (callback in callbacks) {
                callback.onContentComplete()
            }
        }

        fun onContentVolumeChanged(volumePct: Int) {
            for (callback in callbacks) {
                callback.onVolumeChanged(volumePct)
            }
        }

        fun release() {
            callbacks.clear()
            adsId = null
            adPlaybackStates = ImmutableMap.of<Any?, AdPlaybackState?>()
            contentTimeline = null
            streamLoadListener = null
        }

        override fun getVolume(): Int {
            return floor((player.volume * 100).toDouble()).toInt()
        }

        override fun getContentProgress(): VideoProgressUpdate {
            if (!isCurrentlyPlayingMediaPeriodFromThisSource(
                    player,
                    mediaItem,
                    adsId
                )
            ) {
                return VideoProgressUpdate.VIDEO_TIME_NOT_READY
            } else if (adPlaybackStates.isEmpty()) {
                return VideoProgressUpdate( /* currentTimeMs= */0,  /* durationMs= */
                    C.TIME_UNSET
                )
            }

            val timeline: Timeline = player.currentTimeline
            val currentPeriodIndex = player.currentPeriodIndex
            timeline.getPeriod(currentPeriodIndex, period,  /* setIds= */true)
            timeline.getWindow(player.currentMediaItemIndex, window)
            var streamPositionMs: Long
            if (isDashStream && window.isLive) {
                // In multi-period live streams, we can't assume to find the same period in both timelines
                // with a given period index. Calculate stream position from the period structure instead.
                streamPositionMs =
                    if (player.isPlayingAd)
                        (window.windowStartTimeMs
                                + Util.msToUs(period.positionInWindowUs)
                                + player.currentPosition)
                    else
                        window.windowStartTimeMs + player.contentPosition
            } else {
                // The map of ad playback states is keyed with the period UID of the content timeline. In
                // timelines that do not change the periods (VOD and single period live), we can use the
                // period index in both timelines.
                val contentPeriod: Timeline.Period =
                    Preconditions.checkNotNull<Timeline?>(
                        contentTimeline
                    )
                        .getPeriod(
                            currentPeriodIndex - window.firstPeriodIndex,
                            Timeline.Period(),  /* setIds= */
                            true
                        )
                val adPlaybackState: AdPlaybackState =
                    Preconditions.checkNotNull<AdPlaybackState>(
                        adPlaybackStates.get(contentPeriod.uid)
                    )
                // Calculate the stream position from the current position and the playback state.
                streamPositionMs =
                    Util.msToUs(
                        ServerSideAdInsertionUtil.getStreamPositionUs(
                            player,
                            Preconditions.checkNotNull<Any?>(
                                adPlaybackState.adsId
                            )
                        )
                    )
                if (window.windowStartTimeMs != C.TIME_UNSET) {
                    // Add the time since epoch at start of the window for live streams.
                    streamPositionMs += window.windowStartTimeMs + period.positionInWindowMs
                } else if (currentPeriodIndex > window.firstPeriodIndex) {
                    // Add the end position of the previous period in the underlying stream.
                    Preconditions.checkNotNull<Timeline?>(
                        contentTimeline
                    )
                        .getPeriod(
                            currentPeriodIndex - window.firstPeriodIndex - 1,
                            contentPeriod,  /* setIds= */
                            true
                        )
                    streamPositionMs += Util.msToUs(contentPeriod.positionInWindowUs + contentPeriod.durationUs)
                }
            }
            return VideoProgressUpdate(
                streamPositionMs,
                Preconditions.checkNotNull<Timeline?>(contentTimeline)
                    .getWindow( /* windowIndex= */0, window).durationMs
            )
        }

        // Implement VideoStreamPlayer.
        public override fun loadUrl(
            url: String,
            subtitles: MutableList<HashMap<String, String>>
        ) {
            if (streamLoadListener != null) {
                // SDK provided manifest url, notify the listener.
                streamLoadListener!!.onLoadStream(url, subtitles)
            }
        }

        public override fun addCallback(callback: VideoStreamPlayer.VideoStreamPlayerCallback) {
            callbacks.add(callback)
        }

        public override fun removeCallback(callback: VideoStreamPlayer.VideoStreamPlayerCallback) {
            callbacks.remove(callback)
        }

        public override fun onAdBreakStarted() {
            // Do nothing.
        }

        public override fun onAdBreakEnded() {
            // Do nothing.
        }

        public override fun onAdPeriodStarted() {
            // Do nothing.
        }

        public override fun onAdPeriodEnded() {
            // Do nothing.
        }

        public override fun pause() {
            // Do nothing.
        }

        public override fun resume() {
            // Do nothing.
        }

        public override fun seek(timeMs: Long) {
            // Do nothing.
        }

        // Internal methods.
        fun triggerUserTextReceived(userText: String) {
            for (callback in callbacks) {
                callback.onUserTextReceived(userText)
            }
        }
    }

    private inner class VodAdEventListener : AdEvent.AdEventListener {
        public override fun onAdEvent(event: AdEvent) {
            var newAdPlaybackState: AdPlaybackState = adPlaybackState
            val ad: Ad? = event.getAd()
            when (event.getType()) {
                AdEvent.AdEventType.CUEPOINTS_CHANGED -> if (newAdPlaybackState == AdPlaybackState.NONE) {
                    newAdPlaybackState =
                        setVodAdGroupPlaceholders(
                            streamManager!!.getCuePoints(),
                            AdPlaybackState(adsId)
                        )
                }

                AdEvent.AdEventType.LOADED -> if (ad != null) {
                    newAdPlaybackState = setVodAdInPlaceholder(ad, newAdPlaybackState)
                }

                AdEvent.AdEventType.SKIPPED -> if (ad != null) {
                    newAdPlaybackState = skipAd(ad, newAdPlaybackState)
                }

                else -> {}
            }
            setAdPlaybackState(newAdPlaybackState)
        }
    }

    private inner class SinglePeriodLiveAdEventListener : AdEvent.AdEventListener {
        public override fun onAdEvent(event: AdEvent) {
            val ad: Ad? = event.getAd()
            if ((event.getType() != LOADED) || !isCurrentlyPlayingMediaPeriodFromThisSource(
                    player,
                    mediaItem,
                    adsId
                ) || ad == null
            ) {
                return
            }
            var newAdPlaybackState: AdPlaybackState = adPlaybackState
            val timeline: Timeline = player.currentTimeline
            val currentPeriod = Timeline.Period()
            val positionInWindowUs: Long =
                timeline.getPeriod(player.currentPeriodIndex, currentPeriod).positionInWindowUs
            val contentPositionUs =
                if (player.isPlayingAd)
                    currentPeriod.getAdGroupTimeUs(player.currentAdGroupIndex)
                else
                    Util.msToUs(player.contentPosition)
            val adPodInfo: AdPodInfo = ad.getAdPodInfo()
            newAdPlaybackState =
                ImaUtil.addLiveAdBreak( /* currentContentPeriodPositionUs= */
                    contentPositionUs - positionInWindowUs,  /* adDurationUs= */
                    ImaUtil.secToUsRounded(ad.getDuration()),  /* adPositionInAdPod= */
                    adPodInfo.getAdPosition(),  /* totalAdDurationUs= */
                    ImaUtil.secToUsRounded(adPodInfo.getMaxDuration()),  /* totalAdsInAdPod= */
                    adPodInfo.getTotalAds(),  /* adPlaybackState= */
                    if (newAdPlaybackState == AdPlaybackState.NONE)
                        AdPlaybackState(adsId)
                    else
                        newAdPlaybackState
                )
            setAdPlaybackState(newAdPlaybackState)
        }
    }

    private inner class MultiPeriodLiveAdEventListener : AdEvent.AdEventListener {
        public override fun onAdEvent(event: AdEvent) {
            val ad: Ad? = event.getAd()
            if ((event.getType() != LOADED) || !isCurrentlyPlayingMediaPeriodFromThisSource(
                    player,
                    mediaItem,
                    adsId
                ) || ad == null
            ) {
                return
            }
            val adPodInfo: AdPodInfo = ad.getAdPodInfo()
            val timeline: Timeline = player.currentTimeline
            val window = Timeline.Window()
            val adPeriod = Timeline.Period()
            // In case all periods are in the live window, we need to correct the ad group duration when
            // inserting the first ad. Try calculate ad group duration from media structure.
            val totalAdDurationUs =
                ImaUtil.getAdGroupDurationUsForLiveAdPeriodIndex(
                    timeline,
                    adPodInfo,  /* adPeriodIndex= */
                    player.currentPeriodIndex,
                    window,
                    adPeriod
                )
            val adPeriodStartTimeUs =
                (ImaUtil.getWindowStartTimeUs(
                    window.windowStartTimeMs,
                    window.positionInFirstPeriodUs
                )
                        + adPeriod.positionInWindowUs)
            val adDurationUs =
                if (adPeriod.durationUs != C.TIME_UNSET)
                    adPeriod.durationUs
                else
                    ImaUtil.secToUsRounded(ad.getDuration())
            setAdPlaybackState(
                ImaUtil.addLiveAdBreak( /* currentContentPeriodPositionUs= */
                    adPeriodStartTimeUs,
                    adDurationUs,
                    adPodInfo.getAdPosition(),
                    totalAdDurationUs,
                    adPodInfo.getTotalAds(),
                    adPlaybackState
                )
            )
        }
    }

    companion object {
        private const val TAG = "ImaSSAIMediaSource"

        // Static methods.
        private fun setVodAdGroupPlaceholders(
            cuePoints: MutableList<CuePoint>, adPlaybackState: AdPlaybackState
        ): AdPlaybackState {
            var adPlaybackState: AdPlaybackState = adPlaybackState
            for (i in cuePoints.indices) {
                val cuePoint: CuePoint = cuePoints.get(i)
                val fromPositionUs: Long = Util.msToUs(cuePoint.getStartTimeMs())
                adPlaybackState =
                    ServerSideAdInsertionUtil.addAdGroupToAdPlaybackState(
                        adPlaybackState,  /* fromPositionUs= */
                        fromPositionUs,  /* contentResumeOffsetUs= */
                        0,  /* adDurationsUs...= */
                        longArrayOf(Util.msToUs(cuePoint.getEndTimeMs() - cuePoint.getStartTimeMs()))
                    )
            }
            return adPlaybackState
        }

        private fun setVodAdInPlaceholder(
            ad: Ad,
            adPlaybackState: AdPlaybackState
        ): AdPlaybackState {
            var adPlaybackState: AdPlaybackState = adPlaybackState
            val adPodInfo: AdPodInfo = ad.getAdPodInfo()
            val adGroupIndex: Int = getAdGroupIndexFromAdPodInfo(adPodInfo, adPlaybackState)
            val adGroup: AdPlaybackState.AdGroup = adPlaybackState.getAdGroup(adGroupIndex)
            val adIndexInAdGroup: Int = adPodInfo.getAdPosition() - 1
            if (adGroup.count < adPodInfo.getTotalAds()) {
                adPlaybackState =
                    ImaUtil.expandAdGroupPlaceholder(
                        adGroupIndex,  /* adGroupDurationUs= */
                        Util.msToUs(ImaUtil.secToMsRounded(adPodInfo.getMaxDuration())),
                        adIndexInAdGroup,  /* adDurationUs= */
                        Util.msToUs(ImaUtil.secToMsRounded(ad.getDuration())),  /* adsInAdGroupCount= */
                        adPodInfo.getTotalAds(),
                        adPlaybackState
                    )
            } else if (adIndexInAdGroup < adGroup.count - 1) {
                adPlaybackState =
                    ImaUtil.updateAdDurationInAdGroup(
                        adGroupIndex,
                        adIndexInAdGroup,  /* adDurationUs= */
                        Util.msToUs(ImaUtil.secToMsRounded(ad.getDuration())),
                        adPlaybackState
                    )
            }
            return adPlaybackState
        }

        private fun skipAd(ad: Ad, adPlaybackState: AdPlaybackState): AdPlaybackState {
            val adPodInfo: AdPodInfo = ad.getAdPodInfo()
            val adGroupIndex: Int = getAdGroupIndexFromAdPodInfo(adPodInfo, adPlaybackState)
            // IMA SDK always returns index starting at 1.
            val adIndexInAdGroup: Int = adPodInfo.getAdPosition() - 1
            return adPlaybackState.withSkippedAd(adGroupIndex, adIndexInAdGroup)
        }

        private fun getAdGroupIndexFromAdPodInfo(
            adPodInfo: AdPodInfo, adPlaybackState: AdPlaybackState
        ): Int {
            val adPodIndex: Int = adPodInfo.getPodIndex()
            if (adPodIndex == -1) {
                // Post-roll
                return adPlaybackState.adGroupCount - 1
            }
            if (adPlaybackState.getAdGroup(0).timeUs == 0L) {
                // When a pre-roll exists, the index starts at zero.
                return adPodIndex
            }
            // Mid-rolls always start at 1.
            return adPodIndex - 1
        }

        private fun isCurrentlyPlayingMediaPeriodFromThisSource(
            player: Player, mediaItem: MediaItem, adsId: Any?
        ): Boolean {
            if (player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0) {
                return false
            }
            val period = Timeline.Period()
            player.currentTimeline.getPeriod(player.currentPeriodIndex, period)
            return (period.isPlaceholder && mediaItem.equals(player.currentMediaItem))
                    || (adsId != null && adsId == period.adsId)
        }

        private fun createStreamDisplayContainer(
            imaSdkFactory: ImaSdkFactory,
            config: ImaUtil.ServerSideAdInsertionConfiguration,
            streamPlayer: StreamPlayer
        ): StreamDisplayContainer {
            val container: StreamDisplayContainer =
                ImaSdkFactory.createStreamDisplayContainer(
                    config.adViewProvider!!.adViewGroup!!, streamPlayer
                )
            container.setCompanionSlots(config.companionAdSlots)
            if (config.pauseAdSlot != null) {
                container.setPauseAdSlot(config.pauseAdSlot)
            }
            registerFriendlyObstructions(imaSdkFactory, container, config.adViewProvider)
            return container
        }

        private fun registerFriendlyObstructions(
            imaSdkFactory: ImaSdkFactory,
            container: StreamDisplayContainer,
            adViewProvider: AdViewProvider
        ) {
            for (i in adViewProvider.getAdOverlayInfos().indices) {
                val overlayInfo: AdOverlayInfo = adViewProvider.getAdOverlayInfos().get(i)
                container.registerFriendlyObstruction(
                    imaSdkFactory.createFriendlyObstruction(
                        overlayInfo.view,
                        ImaUtil.getFriendlyObstructionPurpose(overlayInfo.purpose),
                        if (overlayInfo.reasonDetail != null) overlayInfo.reasonDetail else "Unknown reason"
                    )
                )
            }
        }

        private fun assertSingleInstanceInPlaylist(player: Player) {
            var counter = 0
            for (i in 0..<player.mediaItemCount) {
                val mediaItem: MediaItem? = player.getMediaItemAt(i)
                if (mediaItem!!.localConfiguration != null && C.SSAI_SCHEME == mediaItem.localConfiguration.uri.getScheme()
                    && ImaServerSideAdInsertionUriBuilder.Companion.IMA_AUTHORITY == mediaItem.localConfiguration.uri.getAuthority()
                ) {
                    check(++counter <= 1) { "Multiple IMA server side ad insertion sources not supported." }
                }
            }
        }
    }
}
