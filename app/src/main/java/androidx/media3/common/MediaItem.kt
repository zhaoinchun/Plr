package androidx.media3.common

import android.net.Uri
import android.os.Bundle
import androidx.annotation.IntRange
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.BundleCollectionUtil
import androidx.media3.common.util.Util
import com.google.common.base.Function
import com.google.common.base.Preconditions
import java.util.Collections
import java.util.UUID

class MediaItem private constructor(
    val mediaId: String,
    clippingConfiguration: ClippingConfiguration,
    val localConfiguration: LocalConfiguration?,
    liveConfiguration: LiveConfiguration,
    mediaMetadata: MediaMetadata,
    requestMetadata: RequestMetadata
) {
    @Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    class Builder() {
        private var mediaId: String? = null
        private var uri: Uri? = null
        private var mimeType: String? = null
        private var clippingConfiguration: ClippingConfiguration = ClippingConfiguration()
        private var drmConfiguration: DrmConfiguration = DrmConfiguration()
        private var streamKeys: List<StreamKey> = mutableListOf()
        private var customCacheKey: String? = null
        private var subtitleConfigurations: List<SubtitleConfiguration> = listOf()
        private var adsConfiguration: AdsConfiguration? = null
        private var tag: Any? = null
        private var imageDurationMs: Long = C.TIME_UNSET
        private var mediaMetadata: MediaMetadata? = null
        private var liveConfiguration: LiveConfiguration = LiveConfiguration()
        private var requestMetadata: RequestMetadata = RequestMetadata.EMPTY

        constructor(mediaItem: MediaItem) : this() {
            clippingConfiguration = mediaItem.clippingConfiguration.copy()
            mediaId = mediaItem.mediaId
            mediaMetadata = mediaItem.mediaMetadata
            liveConfiguration = mediaItem.liveConfiguration.copy()
            requestMetadata = mediaItem.requestMetadata
            val localConfiguration = mediaItem.localConfiguration
            if (localConfiguration != null) {
                customCacheKey = localConfiguration.customCacheKey
                mimeType = localConfiguration.mimeType
                uri = localConfiguration.uri
                streamKeys = localConfiguration.streamKeys
                subtitleConfigurations = localConfiguration.subtitleConfigurations
                tag = localConfiguration.tag
                drmConfiguration =
                    if (localConfiguration.drmConfiguration != null)
                        localConfiguration.drmConfiguration.copy()
                    else
                        DrmConfiguration()
                adsConfiguration = localConfiguration.adsConfiguration
                imageDurationMs = localConfiguration.imageDurationMs
            }
        }

        fun setMediaId(mediaId: String): Builder {
            this.mediaId = mediaId
            return this
        }

        fun setUri(uri: String?): Builder {
            return setUri(if (uri == null) null else Uri.parse(uri))
        }

        fun setUri(uri: Uri?): Builder {
            this.uri = uri
            return this
        }

        fun setMimeType(mimeType: String?): Builder {
            this.mimeType = mimeType
            return this
        }

        fun setClippingConfiguration(clippingConfiguration: ClippingConfiguration): Builder {
            this.clippingConfiguration = clippingConfiguration.copy()
            return this
        }

        fun setDrmConfiguration(drmConfiguration: DrmConfiguration?): Builder {
            this.drmConfiguration = drmConfiguration?.copy() ?: DrmConfiguration()
            return this
        }

        fun setStreamKeys(streamKeys: List<StreamKey>?): Builder {
            this.streamKeys =
                if (!streamKeys.isNullOrEmpty())
                    Collections.unmodifiableList(ArrayList(streamKeys))
                else listOf<StreamKey?>()
            return this
        }

        fun setCustomCacheKey(customCacheKey: String?): Builder {
            this.customCacheKey = customCacheKey
            return this
        }

        fun setLiveConfiguration(liveConfiguration: LiveConfiguration): Builder {
            this.liveConfiguration = liveConfiguration.copy()
            return this
        }

        fun setSubtitleConfigurations(subtitleConfigurations: List<SubtitleConfiguration>): Builder {
            this.subtitleConfigurations = subtitleConfigurations.toList()
            return this
        }

        fun setAdsConfiguration(adsConfiguration: AdsConfiguration?): Builder {
            this.adsConfiguration = adsConfiguration
            return this
        }

        fun setTag(tag: Any?): Builder {
            this.tag = tag
            return this
        }

        fun setImageDurationMs(imageDurationMs: Long): Builder {
            Preconditions.checkArgument(imageDurationMs > 0 || imageDurationMs == C.TIME_UNSET)
            this.imageDurationMs = imageDurationMs
            return this
        }

        fun setMediaMetadata(mediaMetadata: MediaMetadata?): Builder {
            this.mediaMetadata = mediaMetadata
            return this
        }

        fun setRequestMetadata(requestMetadata: RequestMetadata): Builder {
            this.requestMetadata = requestMetadata
            return this
        }

        fun build(): MediaItem {
            // TODO: remove this check once all the deprecated individual DRM setters are removed.
            Preconditions.checkState(drmConfiguration.licenseUri == null || drmConfiguration.scheme != null)
            var localConfiguration: LocalConfiguration? = null
            val uri = this.uri
            if (uri != null) {
                localConfiguration =
                    LocalConfiguration(
                        uri,
                        mimeType,
                        if (drmConfiguration.scheme != null) drmConfiguration else null,
                        adsConfiguration,
                        streamKeys,
                        customCacheKey,
                        subtitleConfigurations,
                        tag,
                        imageDurationMs
                    )
            }
            return MediaItem(
                (if (mediaId != null) mediaId else DEFAULT_MEDIA_ID)!!,
                clippingConfiguration.copy(),
                localConfiguration,
                liveConfiguration.copy(),
                (if (mediaMetadata != null) mediaMetadata else MediaMetadata.EMPTY)!!,
                requestMetadata
            )
        }
    }

    data class DrmConfiguration(
        var scheme: UUID? = null,
        val uuid: UUID? = scheme,
        var licenseUri: Uri? = null,
        var licenseRequestHeaders: Map<String, String> = mapOf<String, String>(),
        var multiSession: Boolean = false,
        var playClearContentWithoutKey: Boolean = true,
        var forceDefaultLicenseUri: Boolean = false,
        var forcedSessionTrackTypes: List<Int?> = listOf<Int?>(),
        val keySetId: ByteArray? = null,
    ) {
        fun setForceSessionsForAudioAndVideoTracks(
            forceSessionsForAudioAndVideoTracks: Boolean
        ) {
            this.forcedSessionTrackTypes =
                if (forceSessionsForAudioAndVideoTracks)
                    listOf<Int?>(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
                else
                    listOf<Int?>()
        }

        fun toBundle(): Bundle {
            val bundle = Bundle()
            bundle.putString(FIELD_SCHEME, scheme.toString())
            if (licenseUri != null) {
                bundle.putParcelable(FIELD_LICENSE_URI, licenseUri)
            }
            if (!licenseRequestHeaders.isEmpty()) {
                bundle.putBundle(
                    FIELD_LICENSE_REQUEST_HEADERS,
                    BundleCollectionUtil.stringMapToBundle(licenseRequestHeaders)
                )
            }
            if (multiSession) {
                bundle.putBoolean(FIELD_MULTI_SESSION, multiSession)
            }
            if (playClearContentWithoutKey) {
                bundle.putBoolean(FIELD_PLAY_CLEAR_CONTENT_WITHOUT_KEY, playClearContentWithoutKey)
            }
            if (forceDefaultLicenseUri) {
                bundle.putBoolean(FIELD_FORCE_DEFAULT_LICENSE_URI, forceDefaultLicenseUri)
            }
            if (!forcedSessionTrackTypes.isEmpty()) {
                bundle.putIntegerArrayList(
                    FIELD_FORCED_SESSION_TRACK_TYPES, ArrayList<Int?>(forcedSessionTrackTypes)
                )
            }
            if (keySetId != null) {
                bundle.putByteArray(FIELD_KEY_SET_ID, keySetId)
            }
            return bundle
        }

        companion object {
            private val FIELD_SCHEME: String = Util.intToStringMaxRadix(0)
            private val FIELD_LICENSE_URI: String = Util.intToStringMaxRadix(1)
            private val FIELD_LICENSE_REQUEST_HEADERS: String = Util.intToStringMaxRadix(2)
            private val FIELD_MULTI_SESSION: String = Util.intToStringMaxRadix(3)

            
            val FIELD_PLAY_CLEAR_CONTENT_WITHOUT_KEY: String = Util.intToStringMaxRadix(4)

            private val FIELD_FORCE_DEFAULT_LICENSE_URI: String = Util.intToStringMaxRadix(5)
            private val FIELD_FORCED_SESSION_TRACK_TYPES: String = Util.intToStringMaxRadix(6)
            private val FIELD_KEY_SET_ID: String = Util.intToStringMaxRadix(7)

            fun fromBundle(bundle: Bundle): DrmConfiguration {
                val scheme = UUID.fromString(
                    bundle.getString(FIELD_SCHEME)
                )
                val licenseUri = bundle.getParcelable<Uri?>(FIELD_LICENSE_URI)
                val licenseMapAsBundle =
                    BundleCollectionUtil.getBundleWithDefault(
                        bundle, FIELD_LICENSE_REQUEST_HEADERS, Bundle.EMPTY
                    )
                val licenseRequestHeaders =
                    BundleCollectionUtil.bundleToStringImmutableMap(licenseMapAsBundle)
                val multiSession = bundle.getBoolean(FIELD_MULTI_SESSION, false)
                val playClearContentWithoutKey =
                    bundle.getBoolean(FIELD_PLAY_CLEAR_CONTENT_WITHOUT_KEY, false)
                val forceDefaultLicenseUri =
                    bundle.getBoolean(FIELD_FORCE_DEFAULT_LICENSE_URI, false)
                val forcedSessionTrackTypesArray =
                    BundleCollectionUtil.getIntegerArrayListWithDefault(
                        bundle, FIELD_FORCED_SESSION_TRACK_TYPES, ArrayList<Int?>()
                    )
                val forcedSessionTrackTypes = forcedSessionTrackTypesArray.toList()
                val keySetId = bundle.getByteArray(FIELD_KEY_SET_ID)

                return DrmConfiguration(
                    licenseUri = licenseUri,
                    licenseRequestHeaders = licenseRequestHeaders,
                    multiSession = multiSession,
                    forceDefaultLicenseUri = forceDefaultLicenseUri,
                    playClearContentWithoutKey = playClearContentWithoutKey,
                    forcedSessionTrackTypes = forcedSessionTrackTypes,
                    keySetId = keySetId
                )
            }
        }
    }

    data class AdsConfiguration(
        var adTagUri: Uri,
        var adsId: Any? = null
    ) {

        fun toBundle(): Bundle {
            val bundle = Bundle()
            bundle.putParcelable(FIELD_AD_TAG_URI, adTagUri)
            return bundle
        }

        companion object {
            private val FIELD_AD_TAG_URI: String = Util.intToStringMaxRadix(0)

            fun fromBundle(bundle: Bundle): AdsConfiguration {
                val adTagUri = bundle.getParcelable<Uri?>(FIELD_AD_TAG_URI)
                return AdsConfiguration(adTagUri!!)
            }
        }
    }

    class LocalConfiguration(
        val uri: Uri,
        mimeType: String?,
        val drmConfiguration: DrmConfiguration?,
        val adsConfiguration: AdsConfiguration?,
        val streamKeys: List<StreamKey>,
        val customCacheKey: String?,
        val subtitleConfigurations: List<SubtitleConfiguration>,
        tag: Any?,
        imageDurationMs: Long
    ) {
        val mimeType: String?
        val tag: Any?
        val imageDurationMs: Long

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is LocalConfiguration) {
                return false
            }
            val other = obj

            return uri == other.uri
                    && mimeType == other.mimeType
                    && drmConfiguration == other.drmConfiguration
                    && adsConfiguration == other.adsConfiguration
                    && streamKeys == other.streamKeys
                    && customCacheKey == other.customCacheKey
                    && subtitleConfigurations == other.subtitleConfigurations
                    && tag == other.tag
                    && imageDurationMs == other.imageDurationMs
        }

        override fun hashCode(): Int {
            var result = uri.hashCode()
            result = 31 * result + (if (mimeType == null) 0 else mimeType.hashCode())
            result =
                31 * result + (if (drmConfiguration == null) 0 else drmConfiguration.hashCode())
            result =
                31 * result + (if (adsConfiguration == null) 0 else adsConfiguration.hashCode())
            result = 31 * result + streamKeys.hashCode()
            result = 31 * result + (if (customCacheKey == null) 0 else customCacheKey.hashCode())
            result = 31 * result + subtitleConfigurations.hashCode()
            result = 31 * result + (if (tag == null) 0 else tag.hashCode())
            result = (31L * result + imageDurationMs).toInt()
            return result
        }

        init {
            this.mimeType = MimeTypes.normalizeMimeType(mimeType)
            this.tag = tag
            this.imageDurationMs = imageDurationMs
        }

        fun toBundle(): Bundle {
            val bundle = Bundle()
            bundle.putParcelable(FIELD_URI, uri)
            if (mimeType != null) {
                bundle.putString(FIELD_MIME_TYPE, mimeType)
            }
            if (drmConfiguration != null) {
                bundle.putBundle(FIELD_DRM_CONFIGURATION, drmConfiguration.toBundle())
            }
            if (adsConfiguration != null) {
                bundle.putBundle(FIELD_ADS_CONFIGURATION, adsConfiguration.toBundle())
            }
            if (!streamKeys.isEmpty()) {
                bundle.putParcelableArrayList(
                    FIELD_STREAM_KEYS,
                    BundleCollectionUtil.toBundleArrayList<StreamKey>(
                        streamKeys,
                        Function { obj: StreamKey? -> obj!!.toBundle() })
                )
            }
            if (customCacheKey != null) {
                bundle.putString(FIELD_CUSTOM_CACHE_KEY, customCacheKey)
            }
            if (!subtitleConfigurations.isEmpty()) {
                bundle.putParcelableArrayList(
                    FIELD_SUBTITLE_CONFIGURATION,
                    BundleCollectionUtil.toBundleArrayList<SubtitleConfiguration>(
                        subtitleConfigurations,
                        Function { obj: SubtitleConfiguration? -> obj!!.toBundle() })
                )
            }
            if (imageDurationMs != C.TIME_UNSET) {
                bundle.putLong(FIELD_IMAGE_DURATION_MS, imageDurationMs)
            }
            return bundle
        }

        companion object {
            private val FIELD_URI: String = Util.intToStringMaxRadix(0)
            private val FIELD_MIME_TYPE: String = Util.intToStringMaxRadix(1)
            private val FIELD_DRM_CONFIGURATION: String = Util.intToStringMaxRadix(2)
            private val FIELD_ADS_CONFIGURATION: String = Util.intToStringMaxRadix(3)
            private val FIELD_STREAM_KEYS: String = Util.intToStringMaxRadix(4)
            private val FIELD_CUSTOM_CACHE_KEY: String = Util.intToStringMaxRadix(5)
            private val FIELD_SUBTITLE_CONFIGURATION: String = Util.intToStringMaxRadix(6)
            private val FIELD_IMAGE_DURATION_MS: String = Util.intToStringMaxRadix(7)

            fun fromBundle(bundle: Bundle): LocalConfiguration {
                val drmBundle = bundle.getBundle(FIELD_DRM_CONFIGURATION)
                val drmConfiguration =
                    if (drmBundle == null) null else DrmConfiguration.fromBundle(drmBundle)
                val adsBundle = bundle.getBundle(FIELD_ADS_CONFIGURATION)
                val adsConfiguration =
                    if (adsBundle == null) null else AdsConfiguration.fromBundle(adsBundle)
                val streamKeysBundles: MutableList<Bundle>? =
                    bundle.getParcelableArrayList<Bundle>(
                        FIELD_STREAM_KEYS
                    )
                val streamKeys: List<StreamKey> =
                    if (streamKeysBundles == null)
                        listOf()
                    else
                        BundleCollectionUtil.fromBundleList<StreamKey>(Function { bundle: Bundle ->
                            StreamKey.fromBundle(
                                bundle
                            )
                        }, streamKeysBundles)
                val subtitleBundles: MutableList<Bundle>? = bundle.getParcelableArrayList(
                    FIELD_SUBTITLE_CONFIGURATION
                )
                val subtitleConfiguration =
                    if (subtitleBundles == null)
                        listOf<SubtitleConfiguration>()
                    else
                        BundleCollectionUtil.fromBundleList<SubtitleConfiguration>(
                            Function { bundle: Bundle? ->
                                SubtitleConfiguration.fromBundle(
                                    bundle!!
                                )
                            }, subtitleBundles
                        )
                val imageDurationMs = bundle.getLong(FIELD_IMAGE_DURATION_MS, C.TIME_UNSET)

                return LocalConfiguration(
                    bundle.getParcelable(FIELD_URI)!!,
                    bundle.getString(FIELD_MIME_TYPE),
                    drmConfiguration,
                    adsConfiguration,
                    streamKeys,
                    bundle.getString(FIELD_CUSTOM_CACHE_KEY),
                    subtitleConfiguration,
                    null,
                    imageDurationMs
                )
            }
        }
    }

    data class LiveConfiguration(
        val targetOffsetMs: Long = C.TIME_UNSET,
        val minOffsetMs: Long = C.TIME_UNSET,
        val maxOffsetMs: Long = C.TIME_UNSET,
        val minPlaybackSpeed: Float = C.RATE_UNSET,
        val maxPlaybackSpeed: Float = C.RATE_UNSET
    ) {
        fun toBundle(): Bundle {
            val bundle = Bundle()
            if (targetOffsetMs != UNSET.targetOffsetMs) {
                bundle.putLong(FIELD_TARGET_OFFSET_MS, targetOffsetMs)
            }
            if (minOffsetMs != UNSET.minOffsetMs) {
                bundle.putLong(FIELD_MIN_OFFSET_MS, minOffsetMs)
            }
            if (maxOffsetMs != UNSET.maxOffsetMs) {
                bundle.putLong(FIELD_MAX_OFFSET_MS, maxOffsetMs)
            }
            if (minPlaybackSpeed != UNSET.minPlaybackSpeed) {
                bundle.putFloat(FIELD_MIN_PLAYBACK_SPEED, minPlaybackSpeed)
            }
            if (maxPlaybackSpeed != UNSET.maxPlaybackSpeed) {
                bundle.putFloat(FIELD_MAX_PLAYBACK_SPEED, maxPlaybackSpeed)
            }
            return bundle
        }

        companion object {
            val UNSET: LiveConfiguration = LiveConfiguration()
            private val FIELD_TARGET_OFFSET_MS: String = Util.intToStringMaxRadix(0)
            private val FIELD_MIN_OFFSET_MS: String = Util.intToStringMaxRadix(1)
            private val FIELD_MAX_OFFSET_MS: String = Util.intToStringMaxRadix(2)
            private val FIELD_MIN_PLAYBACK_SPEED: String = Util.intToStringMaxRadix(3)
            private val FIELD_MAX_PLAYBACK_SPEED: String = Util.intToStringMaxRadix(4)

            fun fromBundle(bundle: Bundle): LiveConfiguration {
                return LiveConfiguration(
                    targetOffsetMs = bundle.getLong(
                        FIELD_TARGET_OFFSET_MS,
                        UNSET.targetOffsetMs
                    ),
                    minOffsetMs = bundle.getLong(FIELD_MIN_OFFSET_MS, UNSET.minOffsetMs),
                    maxOffsetMs = bundle.getLong(FIELD_MAX_OFFSET_MS, UNSET.maxOffsetMs),
                    minPlaybackSpeed = bundle.getFloat(
                        FIELD_MIN_PLAYBACK_SPEED,
                        UNSET.minPlaybackSpeed
                    ),
                    maxPlaybackSpeed = bundle.getFloat(
                        FIELD_MAX_PLAYBACK_SPEED,
                        UNSET.maxPlaybackSpeed
                    )
                )
            }
        }
    }

    data class SubtitleConfiguration(
        var uri: Uri,
        var mimeType: String? = null,
        var language: String? = null,
        var selectionFlags: Int = 0,
        var roleFlags: Int = 0,
        var label: String? = null,
        var id: String? = null,
    ) {
        init {
            mimeType = MimeTypes.normalizeMimeType(mimeType)
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is SubtitleConfiguration) {
                return false
            }

            val other = obj

            return uri == other.uri
                    && mimeType == other.mimeType
                    && language == other.language
                    && selectionFlags == other.selectionFlags && roleFlags == other.roleFlags && label == other.label
                    && id == other.id
        }

        override fun hashCode(): Int {
            var result = uri.hashCode()
            result = 31 * result + (if (mimeType == null) 0 else mimeType.hashCode())
            result = 31 * result + (if (language == null) 0 else language.hashCode())
            result = 31 * result + selectionFlags
            result = 31 * result + roleFlags
            result = 31 * result + (if (label == null) 0 else label.hashCode())
            result = 31 * result + (if (id == null) 0 else id.hashCode())
            return result
        }

        fun toBundle(): Bundle {
            val bundle = Bundle()
            bundle.putParcelable(FIELD_URI, uri)
            if (mimeType != null) {
                bundle.putString(FIELD_MIME_TYPE, mimeType)
            }
            if (language != null) {
                bundle.putString(FIELD_LANGUAGE, language)
            }
            if (selectionFlags != 0) {
                bundle.putInt(FIELD_SELECTION_FLAGS, selectionFlags)
            }
            if (roleFlags != 0) {
                bundle.putInt(FIELD_ROLE_FLAGS, roleFlags)
            }
            if (label != null) {
                bundle.putString(FIELD_LABEL, label)
            }
            if (id != null) {
                bundle.putString(FIELD_ID, id)
            }
            return bundle
        }

        companion object {
            private val FIELD_URI: String = Util.intToStringMaxRadix(0)
            private val FIELD_MIME_TYPE: String = Util.intToStringMaxRadix(1)
            private val FIELD_LANGUAGE: String = Util.intToStringMaxRadix(2)
            private val FIELD_SELECTION_FLAGS: String = Util.intToStringMaxRadix(3)
            private val FIELD_ROLE_FLAGS: String = Util.intToStringMaxRadix(4)
            private val FIELD_LABEL: String = Util.intToStringMaxRadix(5)
            private val FIELD_ID: String = Util.intToStringMaxRadix(6)

            fun fromBundle(bundle: Bundle): SubtitleConfiguration {
                val uri = bundle.getParcelable<Uri?>(FIELD_URI)!!
                val mimeType = bundle.getString(FIELD_MIME_TYPE)
                val language = bundle.getString(FIELD_LANGUAGE)
                val selectionFlags: Int = bundle.getInt(
                    FIELD_SELECTION_FLAGS, 0
                )
                val roleFlags: Int = bundle.getInt(FIELD_ROLE_FLAGS, 0)
                val label = bundle.getString(FIELD_LABEL)
                val id = bundle.getString(FIELD_ID)

                return SubtitleConfiguration(
                    uri,
                    mimeType = mimeType,
                    language = language,
                    selectionFlags = selectionFlags,
                    roleFlags = roleFlags,
                    label = label,
                    id = id
                )
            }
        }
    }

    // TODO: Mark this final when ClippingProperties is deleted.
    data class ClippingConfiguration(
        var startPositionUs: Long = 0,
        var endPositionUs: Long = C.TIME_END_OF_SOURCE,
        var relativeToLiveWindow: Boolean = false,
        var relativeToDefaultPosition: Boolean = false,
        var startsAtKeyFrame: Boolean = false,
        var allowUnseekableMedia: Boolean = false,
    ) {
        val startPositionMs: Long = Util.usToMs(startPositionUs)
        val endPositionMs: Long = Util.usToMs(endPositionUs)

        fun toBundle(): Bundle {
            val bundle = Bundle()
            if (startPositionMs != UNSET.startPositionMs) {
                bundle.putLong(FIELD_START_POSITION_MS, startPositionMs)
            }
            if (endPositionMs != UNSET.endPositionMs) {
                bundle.putLong(FIELD_END_POSITION_MS, endPositionMs)
            }
            if (startPositionUs != UNSET.startPositionUs) {
                bundle.putLong(FIELD_START_POSITION_US, startPositionUs)
            }
            if (endPositionUs != UNSET.endPositionUs) {
                bundle.putLong(FIELD_END_POSITION_US, endPositionUs)
            }
            if (relativeToLiveWindow != UNSET.relativeToLiveWindow) {
                bundle.putBoolean(FIELD_RELATIVE_TO_LIVE_WINDOW, relativeToLiveWindow)
            }
            if (relativeToDefaultPosition != UNSET.relativeToDefaultPosition) {
                bundle.putBoolean(FIELD_RELATIVE_TO_DEFAULT_POSITION, relativeToDefaultPosition)
            }
            if (startsAtKeyFrame != UNSET.startsAtKeyFrame) {
                bundle.putBoolean(FIELD_STARTS_AT_KEY_FRAME, startsAtKeyFrame)
            }
            if (allowUnseekableMedia != UNSET.allowUnseekableMedia) {
                bundle.putBoolean(FIELD_ALLOW_UNSEEKABLE_MEDIA, allowUnseekableMedia)
            }
            return bundle
        }

        companion object {
            val UNSET: ClippingConfiguration = ClippingConfiguration()

            private val FIELD_START_POSITION_MS: String = Util.intToStringMaxRadix(0)
            private val FIELD_END_POSITION_MS: String = Util.intToStringMaxRadix(1)
            private val FIELD_RELATIVE_TO_LIVE_WINDOW: String = Util.intToStringMaxRadix(2)
            private val FIELD_RELATIVE_TO_DEFAULT_POSITION: String = Util.intToStringMaxRadix(3)
            private val FIELD_STARTS_AT_KEY_FRAME: String = Util.intToStringMaxRadix(4)

            
            val FIELD_START_POSITION_US: String = Util.intToStringMaxRadix(5)

            
            val FIELD_END_POSITION_US: String = Util.intToStringMaxRadix(6)
            private val FIELD_ALLOW_UNSEEKABLE_MEDIA: String = Util.intToStringMaxRadix(7)

            @Suppress("deprecation") // Building deprecated type for backwards compatibility
            fun fromBundle(bundle: Bundle): ClippingConfiguration {
                var startPositionUs =
                    bundle.getLong(
                        FIELD_START_POSITION_US,
                        UNSET.startPositionUs
                    )
                if (startPositionUs == UNSET.startPositionUs) {
                    startPositionUs = Util.msToUs(
                        bundle.getLong(
                            FIELD_START_POSITION_MS, UNSET.startPositionMs
                        )
                    )
                }
                var endPositionUs =
                    bundle.getLong(FIELD_END_POSITION_US, UNSET.endPositionUs)
                if (endPositionUs == UNSET.endPositionUs) {
                    endPositionUs = Util.msToUs(
                        bundle.getLong(
                            FIELD_END_POSITION_MS, UNSET.endPositionMs
                        )
                    )
                }
                return ClippingConfiguration(
                    startPositionUs = startPositionUs,
                    endPositionUs = endPositionUs,
                    relativeToLiveWindow = bundle.getBoolean(
                        FIELD_RELATIVE_TO_LIVE_WINDOW,
                        UNSET.relativeToLiveWindow
                    ),
                    relativeToDefaultPosition = bundle.getBoolean(
                        FIELD_RELATIVE_TO_DEFAULT_POSITION,
                        UNSET.relativeToDefaultPosition
                    ),
                    startsAtKeyFrame = bundle.getBoolean(
                        FIELD_STARTS_AT_KEY_FRAME,
                        UNSET.startsAtKeyFrame
                    ),
                    allowUnseekableMedia = bundle.getBoolean(
                        FIELD_ALLOW_UNSEEKABLE_MEDIA,
                        UNSET.allowUnseekableMedia
                    ),
                )
            }
        }
    }

    data class RequestMetadata(
        var mediaUri: Uri? = null,
        var searchQuery: String? = null,
        var extras: Bundle? = null,
    ) {
        fun toBundle(): Bundle {
            val bundle = Bundle()
            if (mediaUri != null) {
                bundle.putParcelable(FIELD_MEDIA_URI, mediaUri)
            }
            if (searchQuery != null) {
                bundle.putString(FIELD_SEARCH_QUERY, searchQuery)
            }
            if (extras != null) {
                bundle.putBundle(FIELD_EXTRAS, extras)
            }
            return bundle
        }

        companion object {
            val EMPTY: RequestMetadata = RequestMetadata()

            private val FIELD_MEDIA_URI: String = Util.intToStringMaxRadix(0)
            private val FIELD_SEARCH_QUERY: String = Util.intToStringMaxRadix(1)
            private val FIELD_EXTRAS: String = Util.intToStringMaxRadix(2)

            fun fromBundle(bundle: Bundle): RequestMetadata {
                return RequestMetadata(
                    mediaUri = bundle.getParcelable<Uri?>(FIELD_MEDIA_URI),
                    searchQuery = bundle.getString(FIELD_SEARCH_QUERY),
                    extras = Util.convertToNullIfInvalid(bundle.getBundle(FIELD_EXTRAS))
                )
            }
        }
    }

    var liveConfiguration: LiveConfiguration
    val mediaMetadata: MediaMetadata
    val clippingConfiguration: ClippingConfiguration
    val requestMetadata: RequestMetadata

    fun buildUpon(): Builder {
        return MediaItem.Builder(this)
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is MediaItem) {
            return false
        }

        val other = obj

        return mediaId == other.mediaId
                && clippingConfiguration == other.clippingConfiguration
                && localConfiguration == other.localConfiguration
                && liveConfiguration == other.liveConfiguration
                && mediaMetadata == other.mediaMetadata
                && requestMetadata == other.requestMetadata
    }

    override fun hashCode(): Int {
        var result = mediaId.hashCode()
        result =
            31 * result + (localConfiguration?.hashCode() ?: 0)
        result = 31 * result + liveConfiguration.hashCode()
        result = 31 * result + clippingConfiguration.hashCode()
        result = 31 * result + mediaMetadata.hashCode()
        result = 31 * result + requestMetadata.hashCode()
        return result
    }

    init {
        this.liveConfiguration = liveConfiguration
        this.mediaMetadata = mediaMetadata
        this.clippingConfiguration = clippingConfiguration
        this.requestMetadata = requestMetadata
    }

    private fun toBundle(includeLocalConfiguration: Boolean, interfaceVersion: Int): Bundle {
        val bundle = Bundle()
        if (mediaId != DEFAULT_MEDIA_ID) {
            bundle.putString(FIELD_MEDIA_ID, mediaId)
        }
        if (liveConfiguration != LiveConfiguration.UNSET) {
            bundle.putBundle(FIELD_LIVE_CONFIGURATION, liveConfiguration.toBundle())
        }
        if (mediaMetadata != MediaMetadata.EMPTY) {
            bundle.putBundle(FIELD_MEDIA_METADATA, mediaMetadata.toBundle(interfaceVersion))
        }
        if (clippingConfiguration != ClippingConfiguration.UNSET) {
            bundle.putBundle(FIELD_CLIPPING_PROPERTIES, clippingConfiguration.toBundle())
        }
        if (requestMetadata != RequestMetadata.EMPTY) {
            bundle.putBundle(FIELD_REQUEST_METADATA, requestMetadata.toBundle())
        }
        if (includeLocalConfiguration && localConfiguration != null) {
            bundle.putBundle(FIELD_LOCAL_CONFIGURATION, localConfiguration.toBundle())
        }
        return bundle
    }

    fun toBundle(interfaceVersion: Int): Bundle {
        return toBundle(false, interfaceVersion)
    }

    fun toBundleIncludeLocalConfiguration(interfaceVersion: Int): Bundle {
        return toBundle(true, interfaceVersion)
    }

    companion object {
        fun fromUri(uri: String?): MediaItem {
            return Builder().setUri(uri).build()
        }

        fun fromUri(uri: Uri?): MediaItem {
            return Builder().setUri(uri).build()
        }

        const val DEFAULT_MEDIA_ID: String = ""

        val EMPTY: MediaItem = Builder().build()
        private val FIELD_MEDIA_ID: String = Util.intToStringMaxRadix(0)
        private val FIELD_LIVE_CONFIGURATION: String = Util.intToStringMaxRadix(1)
        private val FIELD_MEDIA_METADATA: String = Util.intToStringMaxRadix(2)
        private val FIELD_CLIPPING_PROPERTIES: String = Util.intToStringMaxRadix(3)
        private val FIELD_REQUEST_METADATA: String = Util.intToStringMaxRadix(4)
        private val FIELD_LOCAL_CONFIGURATION: String = Util.intToStringMaxRadix(5)

        fun fromBundle(bundle: Bundle, interfaceVersion: Int): MediaItem {
            val mediaId = bundle.getString(FIELD_MEDIA_ID, DEFAULT_MEDIA_ID)
            val liveConfigurationBundle = bundle.getBundle(FIELD_LIVE_CONFIGURATION)
            val liveConfiguration = if (liveConfigurationBundle == null) {
                LiveConfiguration.UNSET
            } else {
                LiveConfiguration.fromBundle(liveConfigurationBundle)
            }
            val mediaMetadataBundle = bundle.getBundle(FIELD_MEDIA_METADATA)
            val mediaMetadata: MediaMetadata?
            if (mediaMetadataBundle == null) {
                mediaMetadata = MediaMetadata.EMPTY
            } else {
                mediaMetadata = MediaMetadata.fromBundle(mediaMetadataBundle, interfaceVersion)
            }
            val clippingConfigurationBundle = bundle.getBundle(FIELD_CLIPPING_PROPERTIES)
            val clippingConfiguration: ClippingConfiguration?
            if (clippingConfigurationBundle == null) {
                clippingConfiguration = ClippingConfiguration.UNSET
            } else {
                clippingConfiguration =
                    ClippingConfiguration.fromBundle(clippingConfigurationBundle)
            }
            val requestMetadataBundle = bundle.getBundle(FIELD_REQUEST_METADATA)
            val requestMetadata: RequestMetadata?
            if (requestMetadataBundle == null) {
                requestMetadata = RequestMetadata.EMPTY
            } else {
                requestMetadata = RequestMetadata.fromBundle(requestMetadataBundle)
            }
            val localConfigurationBundle = bundle.getBundle(FIELD_LOCAL_CONFIGURATION)
            val localConfiguration: LocalConfiguration?
            if (localConfigurationBundle == null) {
                localConfiguration = null
            } else {
                localConfiguration = LocalConfiguration.fromBundle(localConfigurationBundle)
            }
            return MediaItem(
                mediaId,
                clippingConfiguration,
                localConfiguration,
                liveConfiguration,
                mediaMetadata,
                requestMetadata
            )
        }
    }
}
