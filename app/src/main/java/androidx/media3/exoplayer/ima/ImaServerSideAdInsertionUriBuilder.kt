package androidx.media3.exoplayer.ima

import android.net.Uri
import android.text.TextUtils
import androidx.media3.common.C
import com.google.ads.interactivemedia.v3.api.CustomUiOptions
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory
import com.google.ads.interactivemedia.v3.api.StreamRequest
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableMap
import com.google.errorprone.annotations.CanIgnoreReturnValue
import kotlin.Boolean
import kotlin.IllegalArgumentException
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.require

class ImaServerSideAdInsertionUriBuilder {
    private var adsId: String? = null
    private var assetKey: String? = null
    private var apiKey: String? = null
    private var contentSourceId: String? = null
    private var videoId: String? = null
    private var networkCode: String? = null
    private var manifestSuffix: String? = null
    private var contentUrl: String? = null
    private var authToken: String? = null
    private var streamActivityMonitorId: String? = null
    private var adTagParameters: ImmutableMap<String?, String?>
    private var customUiOptions: CustomUiOptions? = null

    var format: Int
    private var loadVideoTimeoutMs: Int

    init {
        adTagParameters = ImmutableMap.of<String?, String?>()
        loadVideoTimeoutMs = DEFAULT_LOAD_VIDEO_TIMEOUT_MS
        format = C.CONTENT_TYPE_OTHER
    }

    fun setAdsId(adsId: String?): ImaServerSideAdInsertionUriBuilder {
        this.adsId = adsId
        return this
    }

    @CanIgnoreReturnValue
    fun setAssetKey(assetKey: String?): ImaServerSideAdInsertionUriBuilder {
        this.assetKey = assetKey
        return this
    }

    @CanIgnoreReturnValue
    fun setAuthToken(authToken: String?): ImaServerSideAdInsertionUriBuilder {
        this.authToken = authToken
        return this
    }

    @CanIgnoreReturnValue
    fun setContentSourceId(contentSourceId: String?): ImaServerSideAdInsertionUriBuilder {
        this.contentSourceId = contentSourceId
        return this
    }

    @CanIgnoreReturnValue
    fun setVideoId(videoId: String?): ImaServerSideAdInsertionUriBuilder {
        this.videoId = videoId
        return this
    }

    @CanIgnoreReturnValue
    fun setNetworkCode(networkCode: String?): ImaServerSideAdInsertionUriBuilder {
        this.networkCode = networkCode
        return this
    }

    @CanIgnoreReturnValue
    fun setFormat(format: Int): ImaServerSideAdInsertionUriBuilder {
        Preconditions.checkArgument(format == C.CONTENT_TYPE_DASH || format == C.CONTENT_TYPE_HLS)
        this.format = format
        return this
    }

    @CanIgnoreReturnValue
    fun setApiKey(apiKey: String?): ImaServerSideAdInsertionUriBuilder {
        this.apiKey = apiKey
        return this
    }

    @CanIgnoreReturnValue
    fun setStreamActivityMonitorId(
        streamActivityMonitorId: String?
    ): ImaServerSideAdInsertionUriBuilder {
        this.streamActivityMonitorId = streamActivityMonitorId
        return this
    }

    @CanIgnoreReturnValue
    fun setAdTagParameters(
        adTagParameters: MutableMap<String?, String?>
    ): ImaServerSideAdInsertionUriBuilder {
        this.adTagParameters = ImmutableMap.copyOf<String?, String?>(adTagParameters)
        return this
    }

    @CanIgnoreReturnValue
    fun setCustomUiOptions(
        customUiOptions: CustomUiOptions?
    ): ImaServerSideAdInsertionUriBuilder {
        this.customUiOptions = customUiOptions
        return this
    }

    @CanIgnoreReturnValue
    fun setManifestSuffix(manifestSuffix: String?): ImaServerSideAdInsertionUriBuilder {
        this.manifestSuffix = manifestSuffix
        return this
    }

    @CanIgnoreReturnValue
    fun setContentUrl(contentUrl: String?): ImaServerSideAdInsertionUriBuilder {
        this.contentUrl = contentUrl
        return this
    }

    @CanIgnoreReturnValue
    fun setLoadVideoTimeoutMs(loadVideoTimeoutMs: Int): ImaServerSideAdInsertionUriBuilder {
        this.loadVideoTimeoutMs = loadVideoTimeoutMs
        return this
    }

    fun build(): Uri? {
        Preconditions.checkState(
            (TextUtils.isEmpty(assetKey)
                    && !TextUtils.isEmpty(contentSourceId) && !TextUtils.isEmpty(videoId))
                    || (!TextUtils.isEmpty(assetKey) && TextUtils.isEmpty(contentSourceId)
                    && TextUtils.isEmpty(videoId))
        )
        Preconditions.checkState(format != C.CONTENT_TYPE_OTHER)
        var adsId = this.adsId
        if (adsId == null) {
            adsId = if (assetKey != null) assetKey else Preconditions.checkNotNull<String?>(videoId)
        }
        val dataUriBuilder = Uri.Builder()
        dataUriBuilder.scheme(C.SSAI_SCHEME)
        dataUriBuilder.authority(IMA_AUTHORITY)
        dataUriBuilder.appendQueryParameter(ADS_ID, adsId)
        if (loadVideoTimeoutMs != DEFAULT_LOAD_VIDEO_TIMEOUT_MS) {
            dataUriBuilder.appendQueryParameter(
                LOAD_VIDEO_TIMEOUT_MS, loadVideoTimeoutMs.toString()
            )
        }
        if (assetKey != null) {
            dataUriBuilder.appendQueryParameter(ASSET_KEY, assetKey)
        }
        if (apiKey != null) {
            dataUriBuilder.appendQueryParameter(API_KEY, apiKey)
        }
        if (contentSourceId != null) {
            dataUriBuilder.appendQueryParameter(CONTENT_SOURCE_ID, contentSourceId)
        }
        if (videoId != null) {
            dataUriBuilder.appendQueryParameter(VIDEO_ID, videoId)
        }
        if (networkCode != null) {
            dataUriBuilder.appendQueryParameter(NETWORK_CODE, networkCode)
        }
        if (manifestSuffix != null) {
            dataUriBuilder.appendQueryParameter(MANIFEST_SUFFIX, manifestSuffix)
        }
        if (contentUrl != null) {
            dataUriBuilder.appendQueryParameter(CONTENT_URL, contentUrl)
        }
        if (authToken != null) {
            dataUriBuilder.appendQueryParameter(AUTH_TOKEN, authToken)
        }
        if (streamActivityMonitorId != null) {
            dataUriBuilder.appendQueryParameter(STREAM_ACTIVITY_MONITOR_ID, streamActivityMonitorId)
        }
        if (!adTagParameters.isEmpty()) {
            val adTagParametersUriBuilder = Uri.Builder()
            for (entry in adTagParameters.entries) {
                adTagParametersUriBuilder.appendQueryParameter(entry.key, entry.value)
            }
            dataUriBuilder.appendQueryParameter(
                AD_TAG_PARAMETERS, adTagParametersUriBuilder.build().toString()
            )
        }
        dataUriBuilder.appendQueryParameter(FORMAT, format.toString())
        if (customUiOptions != null) {
            val customUiOptions = this.customUiOptions!!
            dataUriBuilder.appendQueryParameter(
                CUSTOM_UI_OPTIONS_SKIPPABLE_SUPPORT,
                customUiOptions.skippableSupport.toString()
            )
            dataUriBuilder.appendQueryParameter(
                CUSTOM_UI_OPTIONS_ABOUT_THIS_AD_SUPPORT,
                customUiOptions.aboutThisAdSupport.toString()
            )
        }
        return dataUriBuilder.build()
    }

    companion object {

        const val DEFAULT_LOAD_VIDEO_TIMEOUT_MS: Int = 10000

        /* package */
        const val IMA_AUTHORITY: kotlin.String = "dai.google.com"
        private const val ADS_ID = "adsId"
        private const val ASSET_KEY = "assetKey"
        private const val API_KEY = "apiKey"
        private const val CONTENT_SOURCE_ID = "contentSourceId"
        private const val VIDEO_ID = "videoId"
        private const val NETWORK_CODE = "networkCode"
        private const val AD_TAG_PARAMETERS = "adTagParameters"
        private const val MANIFEST_SUFFIX = "manifestSuffix"
        private const val CONTENT_URL = "contentUrl"
        private const val AUTH_TOKEN = "authToken"
        private const val STREAM_ACTIVITY_MONITOR_ID = "streamActivityMonitorId"
        private const val FORMAT = "format"
        private const val LOAD_VIDEO_TIMEOUT_MS = "loadVideoTimeoutMs"
        private const val CUSTOM_UI_OPTIONS_SKIPPABLE_SUPPORT = "customUiOptionsSkippableSupport"
        private const val CUSTOM_UI_OPTIONS_ABOUT_THIS_AD_SUPPORT =
            "customUiOptionsAboutThisAdSupport"

        /* package */
        fun isLiveStream(uri: Uri): Boolean {
            return !TextUtils.isEmpty(uri.getQueryParameter(ASSET_KEY))
        }

        /* package */
        fun getAdsId(uri: Uri): kotlin.String {
            return Preconditions.checkNotNull<kotlin.String>(uri.getQueryParameter(ADS_ID))
        }

        /* package */
        fun getLoadVideoTimeoutMs(uri: Uri): Int {
            val adsLoaderTimeoutUs = uri.getQueryParameter(LOAD_VIDEO_TIMEOUT_MS)
            return if (TextUtils.isEmpty(adsLoaderTimeoutUs))
                DEFAULT_LOAD_VIDEO_TIMEOUT_MS
            else adsLoaderTimeoutUs!!.toInt()
        }

        fun createStreamRequest(uri: Uri): StreamRequest {
            require(!(C.SSAI_SCHEME != uri.getScheme() || IMA_AUTHORITY != uri.getAuthority())) { "Invalid URI scheme or authority." }
            val streamRequest: StreamRequest
            // Required params.
            val assetKey = uri.getQueryParameter(ASSET_KEY)
            val apiKey = uri.getQueryParameter(API_KEY)
            val contentSourceId = uri.getQueryParameter(CONTENT_SOURCE_ID)
            val videoId = uri.getQueryParameter(VIDEO_ID)
            val networkCode = uri.getQueryParameter(NETWORK_CODE)
            if (!TextUtils.isEmpty(assetKey)) {
                streamRequest =
                    ImaSdkFactory.getInstance()
                        .createLiveStreamRequest(assetKey!!, apiKey, networkCode)
            } else {
                streamRequest =
                    ImaSdkFactory.getInstance()
                        .createVodStreamRequest(
                            contentSourceId!!,
                            videoId!!,
                            apiKey,
                            networkCode
                        )
            }
            val format = uri.getQueryParameter(FORMAT)!!.toInt()
            if (format == C.CONTENT_TYPE_DASH) {
                streamRequest.setFormat(StreamRequest.StreamFormat.DASH)
            } else if (format == C.CONTENT_TYPE_HLS) {
                streamRequest.setFormat(StreamRequest.StreamFormat.HLS)
            } else {
                throw IllegalArgumentException("Unsupported stream format:" + format)
            }
            // Optional params.
            val adTagParametersValue = uri.getQueryParameter(AD_TAG_PARAMETERS)
            if (!TextUtils.isEmpty(adTagParametersValue)) {
                val adTagParameters: MutableMap<kotlin.String?, kotlin.String?> =
                    HashMap<kotlin.String?, kotlin.String?>()
                val adTagParametersUri = Uri.parse(adTagParametersValue)
                for (paramName in adTagParametersUri.getQueryParameterNames()) {
                    val singleAdTagParameterValue = adTagParametersUri.getQueryParameter(paramName)
                    if (!TextUtils.isEmpty(singleAdTagParameterValue)) {
                        adTagParameters.put(paramName, singleAdTagParameterValue)
                    }
                }
                streamRequest.setAdTagParameters(adTagParameters)
            }
            val manifestSuffix = uri.getQueryParameter(MANIFEST_SUFFIX)
            if (manifestSuffix != null) {
                streamRequest.setManifestSuffix(manifestSuffix)
            }
            val contentUrl = uri.getQueryParameter(CONTENT_URL)
            if (contentUrl != null) {
                streamRequest.setContentUrl(contentUrl)
            }
            val authToken = uri.getQueryParameter(AUTH_TOKEN)
            if (authToken != null) {
                streamRequest.setAuthToken(authToken)
            }
            val streamActivityMonitorId = uri.getQueryParameter(STREAM_ACTIVITY_MONITOR_ID)
            if (streamActivityMonitorId != null) {
                streamRequest.setStreamActivityMonitorId(streamActivityMonitorId)
            }

            val customUiOptionsSkippableSupport =
                uri.getQueryParameter(CUSTOM_UI_OPTIONS_SKIPPABLE_SUPPORT)
            val customUiOptionsAboutThisAdSupport =
                uri.getQueryParameter(CUSTOM_UI_OPTIONS_ABOUT_THIS_AD_SUPPORT)
            if (customUiOptionsSkippableSupport != null || customUiOptionsAboutThisAdSupport != null) {
                val customUiOptions: CustomUiOptions = ImaSdkFactory.createCustomUiOptions()
                if (customUiOptionsSkippableSupport != null) {
                    customUiOptions.setSkippableSupport(customUiOptionsSkippableSupport.toBoolean())
                }
                if (customUiOptionsAboutThisAdSupport != null) {
                    customUiOptions.setAboutThisAdSupport(
                        customUiOptionsAboutThisAdSupport.toBoolean()
                    )
                }
                streamRequest.setCustomUiOptions(customUiOptions)
            }
            return streamRequest
        }
    }
}
