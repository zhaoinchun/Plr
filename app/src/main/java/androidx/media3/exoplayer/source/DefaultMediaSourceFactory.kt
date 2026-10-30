package androidx.media3.exoplayer.source

import android.content.Context
import androidx.media3.common.AdViewProvider
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType
import androidx.media3.common.util.Util.msToUs
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ads.AdsLoader
import androidx.media3.exoplayer.source.ads.AdsMediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.heif.HeifExtractor
import androidx.media3.extractor.jpeg.JpegExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.base.Supplier
import com.google.common.primitives.Ints
import java.io.IOException

class DefaultMediaSourceFactory(
    private var dataSourceFactory: DataSource.Factory,
    extractorsFactory: ExtractorsFactory,
    private var subtitleParserFactory: SubtitleParser.Factory
) : MediaSourceFactory {
    @Deprecated("Use {@link AdsLoader.Provider} instead.")
    interface AdsLoaderProvider : AdsLoader.Provider

    private val delegateFactoryLoader: DelegateFactoryLoader =
        DelegateFactoryLoader(extractorsFactory, subtitleParserFactory)
    private var serverSideAdInsertionMediaSourceFactory: MediaSource.Factory? = null
    private var externalImageLoader: ExternalLoader? = null
    private var adsLoaderProvider: AdsLoader.Provider? = null
    private var adViewProvider: AdViewProvider? = null
    private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null
    private var liveTargetOffsetMs: Long = C.TIME_UNSET
    private var liveMinOffsetMs: Long = C.TIME_UNSET
    private var liveMaxOffsetMs: Long = C.TIME_UNSET
    private var liveMinSpeed: Float = C.RATE_UNSET
    private var liveMaxSpeed: Float = C.RATE_UNSET
    private var parseSubtitlesDuringExtraction: Boolean = true
    private var loadOnlySelectedTracks = false
    private var enableClippingInMediaPeriod = false

    constructor(context: Context) : this(DefaultDataSource.Factory(context))

    constructor(
        context: Context,
        extractorsFactory: ExtractorsFactory
    ) : this(DefaultDataSource.Factory(context), extractorsFactory)

    constructor(dataSourceFactory: DataSource.Factory) : this(
        dataSourceFactory,
        DefaultExtractorsFactory()
    )

    constructor(
        dataSourceFactory: DataSource.Factory,
        extractorsFactory: ExtractorsFactory
    ) : this(dataSourceFactory, extractorsFactory, DefaultSubtitleParserFactory())

    init {
        delegateFactoryLoader.setDataSourceFactory(dataSourceFactory)
    }

    @Deprecated("")
    override fun experimentalParseSubtitlesDuringExtraction(
        parseSubtitlesDuringExtraction: Boolean
    ): DefaultMediaSourceFactory {
        this.parseSubtitlesDuringExtraction = parseSubtitlesDuringExtraction
        delegateFactoryLoader.setParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
        return this
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): DefaultMediaSourceFactory {
        this.subtitleParserFactory = subtitleParserFactory
        delegateFactoryLoader.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies: @C.VideoCodecFlags Int
    ): DefaultMediaSourceFactory {
        delegateFactoryLoader.setCodecsToParseWithinGopSampleDependencies(
            codecsToParseWithinGopSampleDependencies
        )
        return this
    }

    fun setLocalAdInsertionComponents(
        adsLoaderProvider: AdsLoader.Provider, adViewProvider: AdViewProvider
    ): DefaultMediaSourceFactory {
        this.adsLoaderProvider = adsLoaderProvider
        this.adViewProvider = adViewProvider
        return this
    }

    fun clearLocalAdInsertionComponents(): DefaultMediaSourceFactory {
        this.adsLoaderProvider = null
        this.adViewProvider = null
        return this
    }

    fun setDataSourceFactory(dataSourceFactory: DataSource.Factory): DefaultMediaSourceFactory {
        this.dataSourceFactory = dataSourceFactory
        delegateFactoryLoader.setDataSourceFactory(dataSourceFactory)
        return this
    }

    fun setServerSideAdInsertionMediaSourceFactory(
        serverSideAdInsertionMediaSourceFactory: MediaSource.Factory?
    ): DefaultMediaSourceFactory {
        this.serverSideAdInsertionMediaSourceFactory = serverSideAdInsertionMediaSourceFactory
        return this
    }

    fun setExternalImageLoader(externalImageLoader: ExternalLoader?): DefaultMediaSourceFactory {
        this.externalImageLoader = externalImageLoader
        return this
    }

    fun setLiveTargetOffsetMs(liveTargetOffsetMs: Long): DefaultMediaSourceFactory {
        this.liveTargetOffsetMs = liveTargetOffsetMs
        return this
    }

    fun setLiveMinOffsetMs(liveMinOffsetMs: Long): DefaultMediaSourceFactory {
        this.liveMinOffsetMs = liveMinOffsetMs
        return this
    }

    fun setLiveMaxOffsetMs(liveMaxOffsetMs: Long): DefaultMediaSourceFactory {
        this.liveMaxOffsetMs = liveMaxOffsetMs
        return this
    }

    fun setLiveMinSpeed(minSpeed: Float): DefaultMediaSourceFactory {
        this.liveMinSpeed = minSpeed
        return this
    }

    fun setLiveMaxSpeed(maxSpeed: Float): DefaultMediaSourceFactory {
        this.liveMaxSpeed = maxSpeed
        return this
    }

    override fun setCmcdConfigurationFactory(cmcdConfigurationFactory: CmcdConfiguration.Factory): DefaultMediaSourceFactory {
        delegateFactoryLoader.setCmcdConfigurationFactory(cmcdConfigurationFactory)
        return this
    }

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider
    ): DefaultMediaSourceFactory {
        delegateFactoryLoader.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy
    ): DefaultMediaSourceFactory {
        this.loadErrorHandlingPolicy = loadErrorHandlingPolicy
        delegateFactoryLoader.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    fun setLoadOnlySelectedTracks(loadOnlySelectedTracks: Boolean): DefaultMediaSourceFactory {
        this.loadOnlySelectedTracks = loadOnlySelectedTracks
        delegateFactoryLoader.setLoadOnlySelectedTracks(loadOnlySelectedTracks)
        return this
    }

    fun setExperimentalEnableHagcPlayback(enableHagcPlayback: Boolean): DefaultMediaSourceFactory {
        delegateFactoryLoader.setExperimentalEnableHagcPlayback(enableHagcPlayback)
        return this
    }

    fun setEnableClippingInMediaPeriod(enableClippingInMediaPeriod: Boolean): DefaultMediaSourceFactory {
        this.enableClippingInMediaPeriod = enableClippingInMediaPeriod
        return this
    }

    override fun setDownloadExecutor(downloadExecutor: Supplier<ReleasableExecutor>): MediaSource.Factory {
        delegateFactoryLoader.setDownloadExecutor(downloadExecutor)
        return this
    }

    override val supportedTypes: IntArray
        get() = delegateFactoryLoader.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        var mediaItem = mediaItem
        val config = mediaItem.localConfiguration!!
        val scheme = config.uri.scheme
        if (scheme != null && scheme == C.SSAI_SCHEME) {
            return serverSideAdInsertionMediaSourceFactory!!.createMediaSource(mediaItem)
        }
        if (config.mimeType == MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE) {
            return ExternallyLoadedMediaSource.Factory(
                msToUs(config.imageDurationMs),
                externalImageLoader!!
            )
                .createMediaSource(mediaItem)
        }
        val type: @C.ContentType Int =
            inferContentTypeForUriAndMimeType(
                config.uri, mediaItem.localConfiguration.mimeType
            )
        if (config.imageDurationMs != C.TIME_UNSET) {
            delegateFactoryLoader.setJpegExtractorFlags(JpegExtractor.FLAG_READ_IMAGE)
            delegateFactoryLoader.setHeifExtractorFlags(HeifExtractor.FLAG_READ_IMAGE)
        }

        val mediaSourceFactory: MediaSource.Factory?
        try {
            mediaSourceFactory = delegateFactoryLoader.getMediaSourceFactory(type)
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(e)
        }
        val liveConfigurationBuilder =
            mediaItem.liveConfiguration.buildUpon()
        if (mediaItem.liveConfiguration.targetOffsetMs == C.TIME_UNSET) {
            liveConfigurationBuilder.setTargetOffsetMs(liveTargetOffsetMs)
        }
        if (mediaItem.liveConfiguration.minPlaybackSpeed == C.RATE_UNSET) {
            liveConfigurationBuilder.setMinPlaybackSpeed(liveMinSpeed)
        }
        if (mediaItem.liveConfiguration.maxPlaybackSpeed == C.RATE_UNSET) {
            liveConfigurationBuilder.setMaxPlaybackSpeed(liveMaxSpeed)
        }
        if (mediaItem.liveConfiguration.minOffsetMs == C.TIME_UNSET) {
            liveConfigurationBuilder.setMinOffsetMs(liveMinOffsetMs)
        }
        if (mediaItem.liveConfiguration.maxOffsetMs == C.TIME_UNSET) {
            liveConfigurationBuilder.setMaxOffsetMs(liveMaxOffsetMs)
        }
        val liveConfiguration = liveConfigurationBuilder.build()
        // Make sure to retain the very same media item instance, if no value needs to be overridden.
        if (liveConfiguration != mediaItem.liveConfiguration) {
            mediaItem = mediaItem.buildUpon().setLiveConfiguration(liveConfiguration).build()
        }

        var mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

        val subtitleConfigurations: List<MediaItem.SubtitleConfiguration> =
            config.subtitleConfigurations
        if (!subtitleConfigurations.isEmpty()) {
            val mediaSources = arrayOfNulls<MediaSource>(subtitleConfigurations.size + 1)
            mediaSources[0] = mediaSource
            for (i in subtitleConfigurations.indices) {
                if (parseSubtitlesDuringExtraction) {
                    val format =
                        Format.Builder()
                            .setSampleMimeType(subtitleConfigurations[i].mimeType)
                            .setLanguage(subtitleConfigurations[i].language)
                            .setSelectionFlags(subtitleConfigurations[i].selectionFlags)
                            .setRoleFlags(subtitleConfigurations[i].roleFlags)
                            .setLabel(subtitleConfigurations[i].label)
                            .setId(subtitleConfigurations[i].id)
                            .build()
                    val extractorsFactory =
                        ExtractorsFactory {
                            arrayOf(
                                if (subtitleParserFactory.supportsFormat(format))
                                    SubtitleExtractor(
                                        subtitleParserFactory.create(format)!!,  null
                                    )
                                else
                                    UnknownSubtitlesExtractor(format)
                            )
                        }
                    val progressiveMediaSourceFactory =
                        ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                            .enableLazyLoadingWithSingleTrack(
                                SubtitleExtractor.TRACK_ID,
                                if (subtitleParserFactory.supportsFormat(format))
                                    format
                                        .buildUpon()
                                        .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                                        .setCodecs(format.sampleMimeType)
                                        .setCueReplacementBehavior(
                                            subtitleParserFactory.getCueReplacementBehavior(format)
                                        )
                                        .build()
                                else
                                    format
                            )
                            .setLoadOnlySelectedTracks(loadOnlySelectedTracks)
                    loadErrorHandlingPolicy?.also {
                        progressiveMediaSourceFactory.setLoadErrorHandlingPolicy(it)
                    }
                    mediaSources[i + 1] =
                        progressiveMediaSourceFactory.createMediaSource(
                            MediaItem.fromUri(subtitleConfigurations[i].uri.toString())
                        )
                } else {
                    val singleSampleMediaSourceFactory =
                        SingleSampleMediaSource.Factory(dataSourceFactory)
                    if (loadErrorHandlingPolicy != null) {
                        singleSampleMediaSourceFactory.setLoadErrorHandlingPolicy(
                            loadErrorHandlingPolicy
                        )
                    }
                    mediaSources[i + 1] =
                        singleSampleMediaSourceFactory.createMediaSource(
                            subtitleConfigurations[i],  C.TIME_UNSET
                        )
                }
            }

            mediaSource = MergingMediaSource(mediaSources as Array<MediaSource>)
        }
        return maybeWrapWithAdsMediaSource(
            mediaItem, maybeClipMediaSource(
                mediaItem,
                mediaSource,
                enableClippingInMediaPeriod
            )
        )
    }

    private fun maybeWrapWithAdsMediaSource(
        mediaItem: MediaItem,
        mediaSource: MediaSource
    ): MediaSource {
        val adsConfiguration = mediaItem.localConfiguration!!.adsConfiguration ?: return mediaSource
        val adsLoaderProvider = this.adsLoaderProvider
        val adViewProvider = this.adViewProvider
        if (adsLoaderProvider == null || adViewProvider == null) {
            w(
                TAG,
                "Playing media without ads. Configure ad support by calling setAdsLoaderProvider and"
                        + " setAdViewProvider."
            )
            return mediaSource
        }
        val adsLoader = adsLoaderProvider.getAdsLoader(adsConfiguration)
        if (adsLoader == null) {
            w(TAG, "Playing media without ads, as no AdsLoader was provided.")
            return mediaSource
        }
        return AdsMediaSource(
            mediaSource,
            DataSpec(adsConfiguration.adTagUri),  
            adsConfiguration.adsId
                ?: listOf(
                    mediaItem.mediaId,
                    mediaItem.localConfiguration.uri,
                    adsConfiguration.adTagUri
                ),  
            this,
            adsLoader,
            adViewProvider,  
            true,  
            false
        )
    }

    private class DelegateFactoryLoader(
        private val extractorsFactory: ExtractorsFactory,
        private var subtitleParserFactory: SubtitleParser.Factory
    ) {
        private val mediaSourceFactorySuppliers: MutableMap<Int, Supplier<MediaSource.Factory>> =
            mutableMapOf()
        private val mediaSourceFactories: MutableMap<Int, MediaSource.Factory> = mutableMapOf()

        private var dataSourceFactory:DataSource.Factory? = null
        private var parseSubtitlesDuringExtraction = true
        private var codecsToParseWithinGopSampleDependencies: @C.VideoCodecFlags Int =
            C.VIDEO_CODEC_FLAG_H264 or C.VIDEO_CODEC_FLAG_H265
        private var loadOnlySelectedTracks = false
        private var experimentalEnableHagcPlayback = true
        private var cmcdConfigurationFactory: CmcdConfiguration.Factory? = null
        private var drmSessionManagerProvider: DrmSessionManagerProvider? = null
        private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null
        private var downloadExecutorSupplier: Supplier<ReleasableExecutor>? = null

        val supportedTypes: IntArray
            get() {
                ensureAllSuppliersAreLoaded()
                return Ints.toArray(mediaSourceFactorySuppliers.keys)
            }

        @Throws(ClassNotFoundException::class)
        fun getMediaSourceFactory(contentType: @C.ContentType Int): MediaSource.Factory {
            var mediaSourceFactory = mediaSourceFactories[contentType]
            if (mediaSourceFactory != null) {
                return mediaSourceFactory
            }
            val mediaSourceFactorySupplier = loadSupplier(contentType)

            mediaSourceFactory = mediaSourceFactorySupplier.get()!!
            cmcdConfigurationFactory?.also { mediaSourceFactory.setCmcdConfigurationFactory(it) }
            drmSessionManagerProvider?.also { mediaSourceFactory.setDrmSessionManagerProvider(it) }
            loadErrorHandlingPolicy?.also { mediaSourceFactory.setLoadErrorHandlingPolicy(it) }
            downloadExecutorSupplier?.also { mediaSourceFactory.setDownloadExecutor(it) }
            downloadExecutorSupplier?.also { mediaSourceFactory.setDownloadExecutor(it) }

            mediaSourceFactory.setSubtitleParserFactory(subtitleParserFactory)
            mediaSourceFactory.experimentalParseSubtitlesDuringExtraction(
                parseSubtitlesDuringExtraction
            )
            mediaSourceFactory.experimentalSetCodecsToParseWithinGopSampleDependencies(
                codecsToParseWithinGopSampleDependencies
            )
            mediaSourceFactories[contentType] = mediaSourceFactory
            return mediaSourceFactory
        }

        fun setDataSourceFactory(dataSourceFactory: DataSource.Factory) {
            if (dataSourceFactory !== this.dataSourceFactory) {
                this.dataSourceFactory = dataSourceFactory
                mediaSourceFactorySuppliers.clear()
                mediaSourceFactories.clear()
            }
        }

        fun setParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction: Boolean) {
            this.parseSubtitlesDuringExtraction = parseSubtitlesDuringExtraction
            extractorsFactory.experimentalSetTextTrackTranscodingEnabled(
                parseSubtitlesDuringExtraction
            )
            for (mediaSourceFactory in mediaSourceFactories.values) {
                mediaSourceFactory.experimentalParseSubtitlesDuringExtraction(
                    parseSubtitlesDuringExtraction
                )
            }
        }

        fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory) {
            this.subtitleParserFactory = subtitleParserFactory
            extractorsFactory.setSubtitleParserFactory(subtitleParserFactory)
            for (mediaSourceFactory in mediaSourceFactories.values) {
                mediaSourceFactory.setSubtitleParserFactory(subtitleParserFactory)
            }
        }

        fun setCodecsToParseWithinGopSampleDependencies(
            codecsToParseWithinGopSampleDependencies: @C.VideoCodecFlags Int
        ) {
            this.codecsToParseWithinGopSampleDependencies = codecsToParseWithinGopSampleDependencies
            extractorsFactory.experimentalSetCodecsToParseWithinGopSampleDependencies(
                codecsToParseWithinGopSampleDependencies
            )
        }

        fun setCmcdConfigurationFactory(cmcdConfigurationFactory: CmcdConfiguration.Factory) {
            this.cmcdConfigurationFactory = cmcdConfigurationFactory
            for (mediaSourceFactory in mediaSourceFactories.values) {
                mediaSourceFactory.setCmcdConfigurationFactory(cmcdConfigurationFactory)
            }
        }

        fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider) {
            this.drmSessionManagerProvider = drmSessionManagerProvider
            for (mediaSourceFactory in mediaSourceFactories.values) {
                mediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
            }
        }

        fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy) {
            this.loadErrorHandlingPolicy = loadErrorHandlingPolicy
            for (mediaSourceFactory in mediaSourceFactories.values) {
                mediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            }
        }

        fun setJpegExtractorFlags(flags: @JpegExtractor.Flags Int) {
            if (this.extractorsFactory is DefaultExtractorsFactory) {
                this.extractorsFactory.setJpegExtractorFlags(flags)
            }
        }

        fun setLoadOnlySelectedTracks(loadOnlySelectedTracks: Boolean) {
            this.loadOnlySelectedTracks = loadOnlySelectedTracks
        }

        fun setExperimentalEnableHagcPlayback(experimentalEnableHagcPlayback: Boolean) {
            this.experimentalEnableHagcPlayback = experimentalEnableHagcPlayback
        }

        fun setHeifExtractorFlags(flags: @HeifExtractor.Flags Int) {
            if (this.extractorsFactory is DefaultExtractorsFactory) {
                this.extractorsFactory.setHeifExtractorFlags(flags)
            }
        }

        fun setDownloadExecutor(downloadExecutor: Supplier<ReleasableExecutor>) {
            this.downloadExecutorSupplier = downloadExecutor
            for (mediaSourceFactory in mediaSourceFactories.values) {
                mediaSourceFactory.setDownloadExecutor(downloadExecutor)
            }
        }

        fun ensureAllSuppliersAreLoaded() {
            maybeLoadSupplier(C.CONTENT_TYPE_DASH)
            maybeLoadSupplier(C.CONTENT_TYPE_SS)
            maybeLoadSupplier(C.CONTENT_TYPE_HLS)
            maybeLoadSupplier(C.CONTENT_TYPE_RTSP)
            maybeLoadSupplier(C.CONTENT_TYPE_OTHER)
        }

        fun maybeLoadSupplier(contentType: @C.ContentType Int): Supplier<MediaSource.Factory>? {
            try {
                return loadSupplier(contentType)
            } catch (e: ClassNotFoundException) {
                // Expected if the app was built without the specific module
                return null
            }
        }

        @Throws(ClassNotFoundException::class)
        fun loadSupplier(contentType: @C.ContentType Int): Supplier<MediaSource.Factory> {
            var mediaSourceFactorySupplier = mediaSourceFactorySuppliers[contentType]
            if (mediaSourceFactorySupplier != null) {
                return mediaSourceFactorySupplier
            }

            // LINT.IfChange
            val dataSourceFactory =this.dataSourceFactory!!
            val clazz: Class<out MediaSource.Factory>?
            when (contentType) {
                C.CONTENT_TYPE_DASH -> {
                    clazz =
                        Class.forName("androidx.media3.exoplayer.dash.DashMediaSource\$Factory")
                            .asSubclass<MediaSource.Factory>(MediaSource.Factory::class.java)
                    mediaSourceFactorySupplier = Supplier { newInstance(clazz, dataSourceFactory) }
                }

                C.CONTENT_TYPE_SS -> {
                    clazz =
                        Class.forName("androidx.media3.exoplayer.smoothstreaming.SsMediaSource\$Factory")
                            .asSubclass<MediaSource.Factory>(MediaSource.Factory::class.java)
                    mediaSourceFactorySupplier = Supplier { newInstance(clazz, dataSourceFactory) }
                }

                C.CONTENT_TYPE_HLS -> {
                    clazz =
                        Class.forName("androidx.media3.exoplayer.hls.HlsMediaSource\$Factory")
                            .asSubclass<MediaSource.Factory>(MediaSource.Factory::class.java)
                    mediaSourceFactorySupplier = Supplier { newInstance(clazz, dataSourceFactory) }
                }

                C.CONTENT_TYPE_RTSP -> {
                    clazz =
                        Class.forName("androidx.media3.exoplayer.rtsp.RtspMediaSource\$Factory")
                            .asSubclass<MediaSource.Factory>(MediaSource.Factory::class.java)
                    mediaSourceFactorySupplier = Supplier { newInstance(clazz) }
                }

                C.CONTENT_TYPE_OTHER -> mediaSourceFactorySupplier =
                    Supplier {
                        ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                            .setLoadOnlySelectedTracks(loadOnlySelectedTracks)
                            .setExperimentalEnableHagcPlayback(experimentalEnableHagcPlayback)
                    }

                else -> throw IllegalArgumentException("Unrecognized contentType: $contentType")
            }
            // LINT.ThenChange(../../../../../proguard-rules.txt)
            mediaSourceFactorySuppliers[contentType] = mediaSourceFactorySupplier
            return mediaSourceFactorySupplier
        }
    }

    private class UnknownSubtitlesExtractor(private val format: Format) : Extractor {
        override fun sniff(input: ExtractorInput): Boolean {
            return true
        }

        override fun init(output: ExtractorOutput) {
            val trackOutput = output.track(SubtitleExtractor.TRACK_ID, C.TRACK_TYPE_TEXT)
            output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
            output.endTracks()
            trackOutput.format(
                format
                    .buildUpon()
                    .setSampleMimeType(MimeTypes.TEXT_UNKNOWN)
                    .setCodecs(format.sampleMimeType)
                    .build()
            )
        }

        @Throws(IOException::class)
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            val skipResult = input.skip(Int.MAX_VALUE)
            if (skipResult == C.RESULT_END_OF_INPUT) {
                return Extractor.RESULT_END_OF_INPUT
            }
            return Extractor.RESULT_CONTINUE
        }

        override fun seek(position: Long, timeUs: Long) {}

        override fun release() {}
    }

    companion object {
        private const val TAG = "DMediaSourceFactory"

        // internal methods
        private fun maybeClipMediaSource(
            mediaItem: MediaItem, mediaSource: MediaSource, enableClippingInMediaPeriod: Boolean
        ): MediaSource {
            if (mediaItem.clippingConfiguration.startPositionUs == 0L && mediaItem.clippingConfiguration.endPositionUs == C.TIME_END_OF_SOURCE && !mediaItem.clippingConfiguration.relativeToDefaultPosition) {
                return mediaSource
            }
            return ClippingMediaSource.Builder(mediaSource)
                .setClippingConfiguration(mediaItem.clippingConfiguration)
                .setEnableClippingInMediaPeriod(enableClippingInMediaPeriod)
                .build()
        }

        private fun newInstance(
            clazz: Class<out MediaSource.Factory>, dataSourceFactory: DataSource.Factory?
        ): MediaSource.Factory {
            try {
                return clazz.getConstructor(DataSource.Factory::class.java)
                    .newInstance(dataSourceFactory)
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }

        private fun newInstance(clazz: Class<out MediaSource.Factory>): MediaSource.Factory {
            try {
                return clazz.getConstructor().newInstance()
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }
    }
}
