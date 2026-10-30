package androidx.media3.common

import android.net.Uri
import android.os.Bundle
import androidx.annotation.IntRange
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C.RoleFlags
import androidx.media3.common.C.SelectionFlags
import androidx.media3.common.util.BundleCollectionUtil
import androidx.media3.common.util.Util
import com.google.common.base.Function
import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.Collections
import java.util.UUID
import kotlin.Any
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Deprecated
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress

class MediaItem private constructor(
    val mediaId: String,
    clippingConfiguration: ClippingProperties,
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

        // TODO: Change this to ClippingProperties once all the deprecated individual setters are
        // removed.
        private var clippingConfiguration: ClippingConfiguration.Builder

        // TODO: Change this to @Nullable DrmConfiguration once all the deprecated individual setters
        // are removed.
        private var drmConfiguration: DrmConfiguration.Builder
        private var streamKeys: List<StreamKey>
        private var customCacheKey: String? = null
        private var subtitleConfigurations: List<SubtitleConfiguration>
        private var adsConfiguration: AdsConfiguration? = null
        private var tag: Any? = null
        private var imageDurationMs: Long
        private var mediaMetadata: MediaMetadata? = null

        // TODO: Change this to LiveConfiguration once all the deprecated individual setters
        // are removed.
        private var liveConfiguration: LiveConfiguration.Builder
        private var requestMetadata: RequestMetadata

        init {
            clippingConfiguration = ClippingConfiguration.Builder()
            drmConfiguration = DrmConfiguration.Builder()
            streamKeys = mutableListOf()
            subtitleConfigurations = listOf()
            liveConfiguration = LiveConfiguration.Builder()
            requestMetadata = RequestMetadata.EMPTY
            imageDurationMs = C.TIME_UNSET
        }

        internal constructor(mediaItem: MediaItem) : this() {
            clippingConfiguration = mediaItem.clippingConfiguration.buildUpon()
            mediaId = mediaItem.mediaId
            mediaMetadata = mediaItem.mediaMetadata
            liveConfiguration = mediaItem.liveConfiguration.buildUpon()
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
                        localConfiguration.drmConfiguration.buildUpon()
                    else
                        DrmConfiguration.Builder()
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
            this.clippingConfiguration = clippingConfiguration.buildUpon()
            return this
        }

        fun setDrmConfiguration(drmConfiguration: DrmConfiguration?): Builder {
            this.drmConfiguration =
                drmConfiguration?.buildUpon() ?: DrmConfiguration.Builder()
            return this
        }

        fun setStreamKeys(streamKeys: MutableList<StreamKey>?): Builder {
            this.streamKeys =
                if (!streamKeys.isNullOrEmpty())
                    Collections.unmodifiableList(ArrayList(streamKeys))
                else mutableListOf<StreamKey?>()
            return this
        }

        fun setCustomCacheKey(customCacheKey: String?): Builder {
            this.customCacheKey = customCacheKey
            return this
        }

        fun setLiveConfiguration(liveConfiguration: LiveConfiguration): Builder {
            this.liveConfiguration = liveConfiguration.buildUpon()
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
                        if (drmConfiguration.scheme != null) drmConfiguration.build() else null,
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
                clippingConfiguration.buildClippingProperties(),
                localConfiguration,
                liveConfiguration.build(),
                (if (mediaMetadata != null) mediaMetadata else MediaMetadata.EMPTY)!!,
                requestMetadata
            )
        }
    }

    class DrmConfiguration
    internal constructor(builder: Builder) {
        class Builder {
            // TODO remove @Nullable annotation when the deprecated zero-arg constructor is removed.
            internal var scheme: UUID? = null
            internal var licenseUri: Uri? = null
            internal var licenseRequestHeaders = mapOf<String, String>()
            internal var multiSession = false
            internal var playClearContentWithoutKey: Boolean
            internal var forceDefaultLicenseUri = false
            internal var forcedSessionTrackTypes: List<Int?>
            internal var keySetId: ByteArray? = null

            @Deprecated("")
            internal constructor() {
                this.playClearContentWithoutKey = true
                this.forcedSessionTrackTypes = listOf<@C.TrackType Int?>()
            }

            constructor(scheme: UUID?) : this() {
                this.scheme = scheme
            }

            internal constructor(drmConfiguration: DrmConfiguration) {
                this.scheme = drmConfiguration.scheme
                this.licenseUri = drmConfiguration.licenseUri
                this.licenseRequestHeaders = drmConfiguration.licenseRequestHeaders
                this.multiSession = drmConfiguration.multiSession
                this.playClearContentWithoutKey = drmConfiguration.playClearContentWithoutKey
                this.forceDefaultLicenseUri = drmConfiguration.forceDefaultLicenseUri
                this.forcedSessionTrackTypes = drmConfiguration.forcedSessionTrackTypes
                this.keySetId = drmConfiguration.keySetId
            }

            fun setScheme(scheme: UUID?): Builder {
                this.scheme = scheme
                return this
            }

            fun setLicenseUri(licenseUri: Uri?): Builder {
                this.licenseUri = licenseUri
                return this
            }

            fun setLicenseUri(licenseUri: String?): Builder {
                this.licenseUri = if (licenseUri == null) null else Uri.parse(licenseUri)
                return this
            }

            fun setLicenseRequestHeaders(licenseRequestHeaders: Map<String, String>): Builder {
                this.licenseRequestHeaders = licenseRequestHeaders.toMap()
                return this
            }

            fun setMultiSession(multiSession: Boolean): Builder {
                this.multiSession = multiSession
                return this
            }

            fun setForceDefaultLicenseUri(forceDefaultLicenseUri: Boolean): Builder {
                this.forceDefaultLicenseUri = forceDefaultLicenseUri
                return this
            }

            fun setPlayClearContentWithoutKey(playClearContentWithoutKey: Boolean): Builder {
                this.playClearContentWithoutKey = playClearContentWithoutKey
                return this
            }

            fun setForceSessionsForAudioAndVideoTracks(
                forceSessionsForAudioAndVideoTracks: Boolean
            ): Builder {
                this.setForcedSessionTrackTypes(
                    if (forceSessionsForAudioAndVideoTracks)
                        listOf<@C.TrackType Int?>(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
                    else
                       listOf<@C.TrackType Int?>()
                )
                return this
            }

            fun setForcedSessionTrackTypes(
                forcedSessionTrackTypes: List<Int?>
            ): Builder {
                this.forcedSessionTrackTypes =forcedSessionTrackTypes.toList()
                return this
            }

            fun setKeySetId(keySetId: ByteArray?): Builder {
                this.keySetId = if (keySetId != null) keySetId.copyOf(keySetId.size) else null
                return this
            }

            fun build(): DrmConfiguration {
                return DrmConfiguration(this)
            }
        }

        val scheme: UUID

        @Deprecated("")
        val uuid: UUID
        val licenseUri: Uri?

        @Deprecated("")
        val requestHeaders = builder.licenseRequestHeaders
        val licenseRequestHeaders = builder.licenseRequestHeaders
        val multiSession: Boolean
        val playClearContentWithoutKey: Boolean
        val forceDefaultLicenseUri: Boolean

        @Deprecated("")
        val sessionForClearTypes: List<Int?>?
        val forcedSessionTrackTypes: List<Int?>
        private val keySetId: ByteArray?

        fun getKeySetId(): ByteArray? {
            return keySetId?.copyOf(keySetId.size)
        }

        fun buildUpon(): Builder {
            return DrmConfiguration.Builder(this)
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is DrmConfiguration) {
                return false
            }
            val other = obj
            return scheme == other.scheme
                    && licenseUri == other.licenseUri
                    && licenseRequestHeaders == other.licenseRequestHeaders
                    && multiSession == other.multiSession && forceDefaultLicenseUri == other.forceDefaultLicenseUri && playClearContentWithoutKey == other.playClearContentWithoutKey && forcedSessionTrackTypes == other.forcedSessionTrackTypes
                    && keySetId.contentEquals(other.keySetId)
        }

        override fun hashCode(): Int {
            var result = scheme.hashCode()
            result = 31 * result + (licenseUri?.hashCode() ?: 0)
            result = 31 * result + licenseRequestHeaders.hashCode()
            result = 31 * result + (if (multiSession) 1 else 0)
            result = 31 * result + (if (forceDefaultLicenseUri) 1 else 0)
            result = 31 * result + (if (playClearContentWithoutKey) 1 else 0)
            result = 31 * result + forcedSessionTrackTypes.hashCode()
            result = 31 * result + keySetId.contentHashCode()
            return result
        }

        init {
            Preconditions.checkState(!(builder.forceDefaultLicenseUri && builder.licenseUri == null))
            this.scheme = builder.scheme!!
            this.uuid = scheme
            this.licenseUri = builder.licenseUri
            this.multiSession = builder.multiSession
            this.forceDefaultLicenseUri = builder.forceDefaultLicenseUri
            this.playClearContentWithoutKey = builder.playClearContentWithoutKey
            this.sessionForClearTypes = builder.forcedSessionTrackTypes
            this.forcedSessionTrackTypes = builder.forcedSessionTrackTypes
            this.keySetId =
                if (builder.keySetId != null) builder.keySetId!!.copyOf(builder.keySetId!!.size) else
                    null
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

            @VisibleForTesting
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
                val forcedSessionTrackTypes =forcedSessionTrackTypesArray.toList()
                val keySetId = bundle.getByteArray(FIELD_KEY_SET_ID)

                val builder = Builder(scheme)
                return builder
                    .setLicenseUri(licenseUri)
                    .setLicenseRequestHeaders(licenseRequestHeaders)
                    .setMultiSession(multiSession)
                    .setForceDefaultLicenseUri(forceDefaultLicenseUri)
                    .setPlayClearContentWithoutKey(playClearContentWithoutKey)
                    .setForcedSessionTrackTypes(forcedSessionTrackTypes)
                    .setKeySetId(keySetId)
                    .build()
            }
        }
    }

    class AdsConfiguration private constructor(builder: Builder) {
        class Builder(internal var adTagUri: Uri) {
            internal var adsId: Any? = null

            fun setAdTagUri(adTagUri: Uri): Builder {
                this.adTagUri = adTagUri
                return this
            }

            fun setAdsId(adsId: Any?): Builder {
                this.adsId = adsId
                return this
            }

            fun build(): AdsConfiguration {
                return AdsConfiguration(this)
            }
        }

        val adTagUri: Uri
        val adsId: Any?

        fun buildUpon(): Builder {
            return Builder(adTagUri).setAdsId(adsId)
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is AdsConfiguration) {
                return false
            }
            val other = obj
            return adTagUri == other.adTagUri && adsId == other.adsId
        }

        override fun hashCode(): Int {
            var result = adTagUri.hashCode()
            result = 31 * result + (if (adsId != null) adsId.hashCode() else 0)
            return result
        }

        init {
            this.adTagUri = builder.adTagUri
            this.adsId = builder.adsId
        }

        fun toBundle(): Bundle {
            val bundle = Bundle()
            bundle.putParcelable(FIELD_AD_TAG_URI, adTagUri)
            return bundle
        }

        companion object {
            private val FIELD_AD_TAG_URI: String = Util.intToStringMaxRadix(0)

            fun fromBundle(bundle: Bundle): AdsConfiguration {
                val adTagUri = bundle.getParcelable<Uri?>(FIELD_AD_TAG_URI)
                return Builder(adTagUri!!).build()
            }
        }
    }

    class LocalConfiguration internal constructor(
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

        @Deprecated("")
        val subtitles: List<Subtitle?>
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
            val subtitles = mutableListOf<Subtitle?>()
            for (i in subtitleConfigurations.indices) {
                subtitles.add(subtitleConfigurations[i]!!.buildUpon().buildSubtitle())
            }
            this.subtitles = subtitles.toList()
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

    class LiveConfiguration @Deprecated("") constructor(
        val targetOffsetMs: Long,
        val minOffsetMs: Long,
        val maxOffsetMs: Long,
        val minPlaybackSpeed: Float,
        val maxPlaybackSpeed: Float
    ) {
        class Builder {
            internal var targetOffsetMs: Long
            internal var minOffsetMs: Long
            internal var maxOffsetMs: Long
            internal var minPlaybackSpeed: Float
            internal var maxPlaybackSpeed: Float

            constructor() {
                this.targetOffsetMs = C.TIME_UNSET
                this.minOffsetMs = C.TIME_UNSET
                this.maxOffsetMs = C.TIME_UNSET
                this.minPlaybackSpeed = C.RATE_UNSET
                this.maxPlaybackSpeed = C.RATE_UNSET
            }

            internal constructor(liveConfiguration: LiveConfiguration) {
                this.targetOffsetMs = liveConfiguration.targetOffsetMs
                this.minOffsetMs = liveConfiguration.minOffsetMs
                this.maxOffsetMs = liveConfiguration.maxOffsetMs
                this.minPlaybackSpeed = liveConfiguration.minPlaybackSpeed
                this.maxPlaybackSpeed = liveConfiguration.maxPlaybackSpeed
            }

            fun setTargetOffsetMs(targetOffsetMs: Long): Builder {
                this.targetOffsetMs = targetOffsetMs
                return this
            }

            fun setMinOffsetMs(minOffsetMs: Long): Builder {
                this.minOffsetMs = minOffsetMs
                return this
            }

            fun setMaxOffsetMs(maxOffsetMs: Long): Builder {
                this.maxOffsetMs = maxOffsetMs
                return this
            }

            fun setMinPlaybackSpeed(minPlaybackSpeed: Float): Builder {
                this.minPlaybackSpeed = minPlaybackSpeed
                return this
            }

            fun setMaxPlaybackSpeed(maxPlaybackSpeed: Float): Builder {
                this.maxPlaybackSpeed = maxPlaybackSpeed
                return this
            }

            fun build(): LiveConfiguration {
                return LiveConfiguration(this)
            }
        }

        // Using the deprecated constructor while it exists.
        private constructor(builder: Builder) : this(
            builder.targetOffsetMs,
            builder.minOffsetMs,
            builder.maxOffsetMs,
            builder.minPlaybackSpeed,
            builder.maxPlaybackSpeed
        )

        fun buildUpon(): Builder {
            return LiveConfiguration.Builder(this)
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is LiveConfiguration) {
                return false
            }
            val other = obj

            return targetOffsetMs == other.targetOffsetMs && minOffsetMs == other.minOffsetMs && maxOffsetMs == other.maxOffsetMs && minPlaybackSpeed == other.minPlaybackSpeed && maxPlaybackSpeed == other.maxPlaybackSpeed
        }

        override fun hashCode(): Int {
            var result = (targetOffsetMs xor (targetOffsetMs ushr 32)).toInt()
            result = 31 * result + (minOffsetMs xor (minOffsetMs ushr 32)).toInt()
            result = 31 * result + (maxOffsetMs xor (maxOffsetMs ushr 32)).toInt()
            result =
                31 * result + (if (minPlaybackSpeed != 0f) minPlaybackSpeed.toBits() else 0)
            result =
                31 * result + (if (maxPlaybackSpeed != 0f) maxPlaybackSpeed.toBits() else 0)
            return result
        }

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
            val UNSET: LiveConfiguration = Builder().build()
            private val FIELD_TARGET_OFFSET_MS: String = Util.intToStringMaxRadix(0)
            private val FIELD_MIN_OFFSET_MS: String = Util.intToStringMaxRadix(1)
            private val FIELD_MAX_OFFSET_MS: String = Util.intToStringMaxRadix(2)
            private val FIELD_MIN_PLAYBACK_SPEED: String = Util.intToStringMaxRadix(3)
            private val FIELD_MAX_PLAYBACK_SPEED: String = Util.intToStringMaxRadix(4)

                    fun fromBundle(bundle: Bundle): LiveConfiguration {
                return Builder()
                    .setTargetOffsetMs(
                        bundle.getLong(
                            FIELD_TARGET_OFFSET_MS,  
                            UNSET.targetOffsetMs
                        )
                    )
                    .setMinOffsetMs(
                        bundle.getLong(FIELD_MIN_OFFSET_MS,  UNSET.minOffsetMs)
                    )
                    .setMaxOffsetMs(
                        bundle.getLong(FIELD_MAX_OFFSET_MS,  UNSET.maxOffsetMs)
                    )
                    .setMinPlaybackSpeed(
                        bundle.getFloat(
                            FIELD_MIN_PLAYBACK_SPEED,  
                            UNSET.minPlaybackSpeed
                        )
                    )
                    .setMaxPlaybackSpeed(
                        bundle.getFloat(
                            FIELD_MAX_PLAYBACK_SPEED,  
                            UNSET.maxPlaybackSpeed
                        )
                    )
                    .build()
            }
        }
    }

    open class SubtitleConfiguration {
        class Builder {
            internal var uri: Uri
            internal var mimeType: String? = null
            internal var language: String? = null

            @SelectionFlags
            internal var selectionFlags: @SelectionFlags Int = 0

            @RoleFlags
            internal var roleFlags: @RoleFlags Int = 0
            internal var label: String? = null
            internal var id: String? = null

            constructor(uri: Uri) {
                this.uri = uri
            }

            internal constructor(subtitleConfiguration: SubtitleConfiguration) {
                this.uri = subtitleConfiguration.uri
                this.mimeType = subtitleConfiguration.mimeType
                this.language = subtitleConfiguration.language
                this.selectionFlags = subtitleConfiguration.selectionFlags
                this.roleFlags = subtitleConfiguration.roleFlags
                this.label = subtitleConfiguration.label
                this.id = subtitleConfiguration.id
            }

            
            fun setUri(uri: Uri): Builder {
                this.uri = uri
                return this
            }

            fun setMimeType(mimeType: String?): Builder {
                this.mimeType = MimeTypes.normalizeMimeType(mimeType)
                return this
            }

            fun setLanguage(language: String?): Builder {
                this.language = language
                return this
            }

            fun setSelectionFlags(@SelectionFlags selectionFlags: @SelectionFlags Int): Builder {
                this.selectionFlags = selectionFlags
                return this
            }

            fun setRoleFlags(@RoleFlags roleFlags: @RoleFlags Int): Builder {
                this.roleFlags = roleFlags
                return this
            }

            fun setLabel(label: String?): Builder {
                this.label = label
                return this
            }

            fun setId(id: String?): Builder {
                this.id = id
                return this
            }

            fun build(): SubtitleConfiguration {
                return SubtitleConfiguration(this)
            }

            // Building deprecated type to support deprecated builder
            internal fun buildSubtitle(): Subtitle {
                return Subtitle(this)
            }
        }

        val uri: Uri
        val mimeType: String?
        val language: String?

        @SelectionFlags
        val selectionFlags: @SelectionFlags Int

        @RoleFlags
        val roleFlags: @RoleFlags Int
        val label: String?
        val id: String?

        internal constructor(
            uri: Uri,
            mimeType: String?,
            language: String?,
            selectionFlags: Int,
            roleFlags: Int,
            label: String?,
            id: String?
        ) {
            this.uri = uri
            this.mimeType = MimeTypes.normalizeMimeType(mimeType)
            this.language = language
            this.selectionFlags = selectionFlags
            this.roleFlags = roleFlags
            this.label = label
            this.id = id
        }

        internal constructor(builder: Builder) {
            this.uri = builder.uri
            this.mimeType = builder.mimeType
            this.language = builder.language
            this.selectionFlags = builder.selectionFlags
            this.roleFlags = builder.roleFlags
            this.label = builder.label
            this.id = builder.id
        }

        fun buildUpon(): Builder {
            return SubtitleConfiguration.Builder(this)
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
                @SelectionFlags val selectionFlags: @SelectionFlags Int = bundle.getInt(
                    FIELD_SELECTION_FLAGS, 0
                )
                @RoleFlags val roleFlags: @RoleFlags Int = bundle.getInt(FIELD_ROLE_FLAGS, 0)
                val label = bundle.getString(FIELD_LABEL)
                val id = bundle.getString(FIELD_ID)

                val builder = Builder(uri)
                return builder
                    .setMimeType(mimeType)
                    .setLanguage(language)
                    .setSelectionFlags(selectionFlags)
                    .setRoleFlags(roleFlags)
                    .setLabel(label)
                    .setId(id)
                    .build()
            }
        }
    }

    @Deprecated("")
    class Subtitle : SubtitleConfiguration {
        @Deprecated("")
        constructor(uri: Uri, mimeType: String?, language: String?) : this(
            uri,
            mimeType,
            language,  
            0
        )

        @Deprecated("")
        constructor(
            uri: Uri,
            mimeType: String?,
            language: String?,
            @SelectionFlags selectionFlags: @SelectionFlags Int
        ) : this(uri, mimeType, language, selectionFlags,  0,  null)

        @Deprecated("")
        constructor(
            uri: Uri,
            mimeType: String?,
            language: String?,
            @SelectionFlags selectionFlags: @SelectionFlags Int,
            @RoleFlags roleFlags: @RoleFlags Int,
            label: String?
        ) : super(uri, mimeType, language, selectionFlags, roleFlags, label,  null)

        internal constructor(builder: SubtitleConfiguration.Builder) : super(builder)
    }

    // TODO: Mark this final when ClippingProperties is deleted.
    open class ClippingConfiguration internal constructor(builder: Builder) {
        class Builder {
            internal var startPositionUs: Long = 0
            internal var endPositionUs: Long
            internal var relativeToLiveWindow = false
            internal var relativeToDefaultPosition = false
            internal var startsAtKeyFrame = false
            internal var allowUnseekableMedia = false

            constructor() {
                endPositionUs = C.TIME_END_OF_SOURCE
            }

            constructor(clippingConfiguration: ClippingConfiguration) {
                startPositionUs = clippingConfiguration.startPositionUs
                endPositionUs = clippingConfiguration.endPositionUs
                relativeToLiveWindow = clippingConfiguration.relativeToLiveWindow
                relativeToDefaultPosition = clippingConfiguration.relativeToDefaultPosition
                startsAtKeyFrame = clippingConfiguration.startsAtKeyFrame
                allowUnseekableMedia = clippingConfiguration.allowUnseekableMedia
            }

            fun setStartPositionMs(@IntRange(from = 0) startPositionMs: Long): Builder {
                return setStartPositionUs(Util.msToUs(startPositionMs))
            }

            fun setStartPositionUs(@IntRange(from = 0) startPositionUs: Long): Builder {
                Preconditions.checkArgument(startPositionUs >= 0)
                this.startPositionUs = startPositionUs
                return this
            }

            fun setEndPositionMs(endPositionMs: Long): Builder {
                return setEndPositionUs(Util.msToUs(endPositionMs))
            }

            fun setEndPositionUs(endPositionUs: Long): Builder {
                Preconditions.checkArgument(endPositionUs == C.TIME_END_OF_SOURCE || endPositionUs >= 0)
                this.endPositionUs = endPositionUs
                return this
            }

            fun setRelativeToLiveWindow(relativeToLiveWindow: Boolean): Builder {
                this.relativeToLiveWindow = relativeToLiveWindow
                return this
            }

            fun setRelativeToDefaultPosition(relativeToDefaultPosition: Boolean): Builder {
                this.relativeToDefaultPosition = relativeToDefaultPosition
                return this
            }

            fun setStartsAtKeyFrame(startsAtKeyFrame: Boolean): Builder {
                this.startsAtKeyFrame = startsAtKeyFrame
                return this
            }

            fun setAllowUnseekableMedia(allowUnseekableMedia: Boolean): Builder {
                this.allowUnseekableMedia = allowUnseekableMedia
                return this
            }

            fun build(): ClippingConfiguration {
                return ClippingConfiguration(this)
            }

            @Deprecated("")
            fun buildClippingProperties(): ClippingProperties {
                return ClippingProperties(this)
            }
        }

        @IntRange(from = 0)
        val startPositionMs: Long = Util.usToMs(builder.startPositionUs)

        @IntRange(from = 0)
        val startPositionUs: Long
        val endPositionMs: Long = Util.usToMs(builder.endPositionUs)
        val endPositionUs: Long
        val relativeToLiveWindow: Boolean
        val relativeToDefaultPosition: Boolean
        val startsAtKeyFrame: Boolean
        val allowUnseekableMedia: Boolean

        fun buildUpon(): Builder {
            return ClippingConfiguration.Builder(this)
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is ClippingConfiguration) {
                return false
            }

            val other = obj

            return startPositionUs == other.startPositionUs && endPositionUs == other.endPositionUs && relativeToLiveWindow == other.relativeToLiveWindow && relativeToDefaultPosition == other.relativeToDefaultPosition && startsAtKeyFrame == other.startsAtKeyFrame && allowUnseekableMedia == other.allowUnseekableMedia
        }

        override fun hashCode(): Int {
            var result = (startPositionUs xor (startPositionUs ushr 32)).toInt()
            result = 31 * result + (endPositionUs xor (endPositionUs ushr 32)).toInt()
            result = 31 * result + (if (relativeToLiveWindow) 1 else 0)
            result = 31 * result + (if (relativeToDefaultPosition) 1 else 0)
            result = 31 * result + (if (startsAtKeyFrame) 1 else 0)
            result = 31 * result + (if (allowUnseekableMedia) 1 else 0)
            return result
        }

        init {
            this.startPositionUs = builder.startPositionUs
            this.endPositionUs = builder.endPositionUs
            this.relativeToLiveWindow = builder.relativeToLiveWindow
            this.relativeToDefaultPosition = builder.relativeToDefaultPosition
            this.startsAtKeyFrame = builder.startsAtKeyFrame
            this.allowUnseekableMedia = builder.allowUnseekableMedia
        }

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
            val UNSET: ClippingConfiguration = Builder().build()

            private val FIELD_START_POSITION_MS: String = Util.intToStringMaxRadix(0)
            private val FIELD_END_POSITION_MS: String = Util.intToStringMaxRadix(1)
            private val FIELD_RELATIVE_TO_LIVE_WINDOW: String = Util.intToStringMaxRadix(2)
            private val FIELD_RELATIVE_TO_DEFAULT_POSITION: String = Util.intToStringMaxRadix(3)
            private val FIELD_STARTS_AT_KEY_FRAME: String = Util.intToStringMaxRadix(4)

            @VisibleForTesting
            val FIELD_START_POSITION_US: String = Util.intToStringMaxRadix(5)

            @VisibleForTesting
            val FIELD_END_POSITION_US: String = Util.intToStringMaxRadix(6)
            private val FIELD_ALLOW_UNSEEKABLE_MEDIA: String = Util.intToStringMaxRadix(7)

            @Suppress("deprecation") // Building deprecated type for backwards compatibility
            fun fromBundle(bundle: Bundle): ClippingProperties {
                val clippingConfiguration =
                    Builder()
                        .setStartPositionMs(
                            bundle.getLong(
                                FIELD_START_POSITION_MS,  UNSET.startPositionMs
                            )
                        )
                        .setEndPositionMs(
                            bundle.getLong(
                                FIELD_END_POSITION_MS,  
                                UNSET.endPositionMs
                            )
                        )
                        .setRelativeToLiveWindow(
                            bundle.getBoolean(
                                FIELD_RELATIVE_TO_LIVE_WINDOW,  
                                UNSET.relativeToLiveWindow
                            )
                        )
                        .setRelativeToDefaultPosition(
                            bundle.getBoolean(
                                FIELD_RELATIVE_TO_DEFAULT_POSITION,  
                                UNSET.relativeToDefaultPosition
                            )
                        )
                        .setStartsAtKeyFrame(
                            bundle.getBoolean(
                                FIELD_STARTS_AT_KEY_FRAME,  
                                UNSET.startsAtKeyFrame
                            )
                        )
                        .setAllowUnseekableMedia(
                            bundle.getBoolean(
                                FIELD_ALLOW_UNSEEKABLE_MEDIA,  
                                UNSET.allowUnseekableMedia
                            )
                        )
                val startPositionUs =
                    bundle.getLong(
                        FIELD_START_POSITION_US,  
                        UNSET.startPositionUs
                    )
                if (startPositionUs != UNSET.startPositionUs) {
                    clippingConfiguration.setStartPositionUs(startPositionUs)
                }
                val endPositionUs =
                    bundle.getLong(FIELD_END_POSITION_US,  UNSET.endPositionUs)
                if (endPositionUs != UNSET.endPositionUs) {
                    clippingConfiguration.setEndPositionUs(endPositionUs)
                }
                return clippingConfiguration.buildClippingProperties()
            }
        }
    }

    @Deprecated("")
    class ClippingProperties constructor(builder: ClippingConfiguration.Builder) :
        ClippingConfiguration(builder) {
        companion object {
            val UNSET: ClippingProperties =
                ClippingConfiguration.Builder().buildClippingProperties()
        }
    }

    class RequestMetadata private constructor(builder: Builder) {
        class Builder {
            internal var mediaUri: Uri? = null
            internal var searchQuery: String? = null
            internal var extras: Bundle? = null

            constructor()

            internal constructor(requestMetadata: RequestMetadata) {
                this.mediaUri = requestMetadata.mediaUri
                this.searchQuery = requestMetadata.searchQuery
                this.extras = requestMetadata.extras
            }

            fun setMediaUri(mediaUri: Uri?): Builder {
                this.mediaUri = mediaUri
                return this
            }

            fun setSearchQuery(searchQuery: String?): Builder {
                this.searchQuery = searchQuery
                return this
            }

            fun setExtras(extras: Bundle?): Builder {
                this.extras = extras
                return this
            }

            fun build(): RequestMetadata {
                return RequestMetadata(this)
            }
        }

        val mediaUri: Uri?

        val searchQuery: String?

        val extras: Bundle?

        fun buildUpon(): Builder {
            return RequestMetadata.Builder(this)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is RequestMetadata) {
                return false
            }
            val that = o
            return mediaUri == that.mediaUri
                    && searchQuery == that.searchQuery
                    && ((extras == null) == (that.extras == null))
        }

        override fun hashCode(): Int {
            var result = if (mediaUri == null) 0 else mediaUri.hashCode()
            result = 31 * result + (if (searchQuery == null) 0 else searchQuery.hashCode())
            result = 31 * result + (if (extras == null) 0 else 1)
            return result
        }

        init {
            this.mediaUri = builder.mediaUri
            this.searchQuery = builder.searchQuery
            this.extras = builder.extras
        }

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
            val EMPTY: RequestMetadata = Builder().build()

            private val FIELD_MEDIA_URI: String = Util.intToStringMaxRadix(0)
            private val FIELD_SEARCH_QUERY: String = Util.intToStringMaxRadix(1)
            private val FIELD_EXTRAS: String = Util.intToStringMaxRadix(2)

            fun fromBundle(bundle: Bundle): RequestMetadata {
                return Builder()
                    .setMediaUri(bundle.getParcelable<Uri?>(FIELD_MEDIA_URI))
                    .setSearchQuery(bundle.getString(FIELD_SEARCH_QUERY))
                    .setExtras(Util.convertToNullIfInvalid(bundle.getBundle(FIELD_EXTRAS)))
                    .build()
            }
        }
    }

    @Deprecated("")
    val playbackProperties: LocalConfiguration? = localConfiguration
    var liveConfiguration: LiveConfiguration
    val mediaMetadata: MediaMetadata
    val clippingConfiguration: ClippingConfiguration

    @Deprecated("")
    val clippingProperties: ClippingProperties?
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
        this.clippingProperties = clippingConfiguration
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

    @Deprecated("")
    fun toBundle(): Bundle {
        return toBundle(MediaLibraryInfo.INTERFACE_VERSION)
    }

    fun toBundle(interfaceVersion: Int): Bundle {
        return toBundle( false, interfaceVersion)
    }

    @Deprecated("")
    fun toBundleIncludeLocalConfiguration(): Bundle {
        return toBundleIncludeLocalConfiguration(MediaLibraryInfo.INTERFACE_VERSION)
    }

    fun toBundleIncludeLocalConfiguration(interfaceVersion: Int): Bundle {
        return toBundle( true, interfaceVersion)
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

        @Deprecated("Use {@link #fromBundle(Bundle, int)} instead.")
        fun fromBundle(bundle: Bundle): MediaItem {
            return fromBundle(bundle, MediaLibraryInfo.INTERFACE_VERSION)
        }

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
            val clippingConfiguration: ClippingProperties?
            if (clippingConfigurationBundle == null) {
                clippingConfiguration = ClippingProperties.UNSET
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
