package androidx.media3.exoplayer.mediacodec

import android.content.Context
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.util.Pair
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.isAudio
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.CodecSpecificDataUtil.getHevcProfileAndLevel
import androidx.media3.common.util.CodecSpecificDataUtil.getMediaCodecProfileAndLevel
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.split
import androidx.media3.container.NalUnitUtil.getH265BaseLayerCodecsString
import com.google.common.base.Ascii
import com.google.errorprone.annotations.InlineMe

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.util.Collections
import kotlin.math.max

object MediaCodecUtil {
    private const val TAG = "MediaCodecUtil"

    @GuardedBy("MediaCodecUtil.class")
    private val decoderInfosCache: HashMap<CodecKey, List<MediaCodecInfo>> =
        HashMap<CodecKey, List<MediaCodecInfo>>()

    // Lazily initialized.
    private var maxH264DecodableFrameSize = -1

    fun warmDecoderInfoCache(mimeType: String, secure: Boolean, tunneling: Boolean) {
        try {
            getDecoderInfos(mimeType, secure, tunneling)
        } catch (e: DecoderQueryException) {
            // Codec warming is best effort, so we can swallow the exception.
            e(TAG, "Codec warming failed", e)
        }
    }

    @Synchronized
    fun clearDecoderInfoCache() {
        decoderInfosCache.clear()
    }

    @get:Throws(DecoderQueryException::class)
    val decryptOnlyDecoderInfo: MediaCodecInfo?
        get() = getDecoderInfo(MimeTypes.AUDIO_RAW, false, false)

    @Throws(DecoderQueryException::class)
    fun getDecoderInfo(mimeType: String, secure: Boolean, tunneling: Boolean): MediaCodecInfo? {
        val decoderInfos = getDecoderInfos(mimeType, secure, tunneling)
        return if (decoderInfos.isEmpty()) null else decoderInfos[0]
    }

    @Synchronized
    @Throws(DecoderQueryException::class)
    fun getDecoderInfos(
        mimeType: String, secure: Boolean, tunneling: Boolean
    ): List<MediaCodecInfo> {
        val key = CodecKey(mimeType, secure, tunneling)
        val cachedDecoderInfos = decoderInfosCache[key]
        if (cachedDecoderInfos != null) {
            return cachedDecoderInfos
        }

        // MV-HEVC is handled by a special codec in the media_codecs.xml file.  We need to get
        // ALL_CODECS list to include the special codec.
        val specialCodec = mimeType == MimeTypes.VIDEO_MV_HEVC
        var mediaCodecList: MediaCodecListCompat =
            MediaCodecListCompatV21(secure, tunneling, specialCodec)
        var decoderInfos = getDecoderInfosInternal(key, mediaCodecList)
        if (secure && decoderInfos.isEmpty() && Build.VERSION.SDK_INT == 23) {
            // Some devices don't list secure decoders on API level 21 [Internal: b/18678462]. Try the
            // legacy path. We also try this path on API levels 22 and 23 as a defensive measure.
            mediaCodecList = MediaCodecListCompatV16()
            decoderInfos = getDecoderInfosInternal(key, mediaCodecList)
            if (!decoderInfos.isEmpty()) {
                w(
                    TAG,
                    ("MediaCodecList API didn't list secure decoder for: "
                            + mimeType
                            + ". Assuming: "
                            + decoderInfos[0].name)
                )
            }
        }
        applyWorkarounds(mimeType, decoderInfos)
        val immutableDecoderInfos = decoderInfos.toList()
        decoderInfosCache[key] = immutableDecoderInfos
        return immutableDecoderInfos
    }

        @RequiresNonNull("#2.sampleMimeType")
    @Throws(DecoderQueryException::class)
    fun getDecoderInfosSoftMatch(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo> {
        val decoderInfos =
            mediaCodecSelector.getDecoderInfos(
                format.sampleMimeType!!, requiresSecureDecoder, requiresTunnelingDecoder
            )
        val alternativeDecoderInfos =
            getAlternativeDecoderInfos(
                mediaCodecSelector, format, requiresSecureDecoder, requiresTunnelingDecoder
            )
        return mutableListOf<MediaCodecInfo>().apply {
            addAll(decoderInfos)
            addAll(alternativeDecoderInfos)
        }
    }

        @Throws(DecoderQueryException::class)
    fun getAlternativeDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo> {
        val alternativeMimeType = getAlternativeCodecMimeType(format) ?: return listOf()
        return mediaCodecSelector.getDecoderInfos(
            alternativeMimeType, requiresSecureDecoder, requiresTunnelingDecoder
        )
    }

    fun getDecoderInfosSortedByFormatSupport(
        context: Context, decoderInfos: List<MediaCodecInfo>, format: Format
    ): List<MediaCodecInfo> {
        var decoderInfos = decoderInfos
        decoderInfos = ArrayList<MediaCodecInfo>(decoderInfos)
        sortByScore(decoderInfos) { decoderInfo: MediaCodecInfo? ->
            if (decoderInfo!!.isFormatFunctionallySupported(context, format)) 1 else 0
        }
        return decoderInfos
    }

    fun getDecoderInfosSortedByFullFormatSupport(
        context: Context, decoderInfos: List<MediaCodecInfo>, format: Format
    ): List<MediaCodecInfo> {
        var decoderInfos = decoderInfos
        decoderInfos = ArrayList(decoderInfos)
        sortByScore(
            decoderInfos
        ) { decoderInfo: MediaCodecInfo? ->
            if (decoderInfo!!.isFormatSupported(context, format)) 1 else 0
        }
        return decoderInfos
    }

    fun getDecoderInfosSortedBySoftwareOnly(
        decoderInfos: List<MediaCodecInfo>
    ): List<MediaCodecInfo> {
        var decoderInfos = decoderInfos
        decoderInfos = ArrayList<MediaCodecInfo>(decoderInfos)
        sortByScore(decoderInfos) { decoderInfo: MediaCodecInfo -> (if (decoderInfo.softwareOnly) 2 else 0) + (if (decoderInfo.vendor) 0 else 1) }
        return decoderInfos.toList()
    }

    @Throws(DecoderQueryException::class)
    fun maxH264DecodableFrameSize(): Int {
        if (maxH264DecodableFrameSize == -1) {
            var result = 0
            val decoderInfo =
                getDecoderInfo(MimeTypes.VIDEO_H264, false, false)
            if (decoderInfo != null) {
                for (profileLevel in decoderInfo.profileLevels) {
                    result = max(avcLevelToMaxFrameSize(profileLevel.level), result)
                }
                // We assume support for at least 480p, which is the level mandated by the Android CDD.
                result = max(result, 720 * 480)
            }
            maxH264DecodableFrameSize = result
        }
        return maxH264DecodableFrameSize
    }

    fun createCodecProfileLevel(profile: Int, level: Int): CodecProfileLevel {
        val profileLevel = CodecProfileLevel()
        profileLevel.profile = profile
        profileLevel.level = level
        return profileLevel
    }

    @InlineMe(
        replacement = "CodecSpecificDataUtil.getCodecProfileAndLevel(format)",
        imports = ["androidx.media3.common.util.CodecSpecificDataUtil"]
    )
    @Deprecated("Use {@link CodecSpecificDataUtil#getCodecProfileAndLevel(Format)}.")
    fun getCodecProfileAndLevel(format: Format): Pair<Int?, Int?>? {
        return CodecSpecificDataUtil.getCodecProfileAndLevel(format)
    }

    fun getHevcBaseLayerCodecProfileAndLevel(format: Format): CodecSpecificDataUtil.MediaCodecProfileAndLevel? {
        val codecs = getH265BaseLayerCodecsString(format.initializationData) ?: return null
        val parts: Array<String> = split(codecs.trim { it <= ' ' }, "\\.")
        return getHevcProfileAndLevel(codecs, parts, format.colorInfo)
    }

        fun getAlternativeCodecMimeType(format: Format): String? {
        if (MimeTypes.AUDIO_E_AC3_JOC == format.sampleMimeType) {
            // E-AC3 decoders can decode JOC streams, but in 2-D rather than 3-D.
            // Some devices (e.g. Pixel) integrate an EAC3 decoder that does not support EAC3-JOC
            // stream decoding.
            return if (supportsEac3JocFallbackDecoding()) MimeTypes.AUDIO_E_AC3 else null
        }
        if (MimeTypes.AUDIO_DTS_HD == format.sampleMimeType
            || MimeTypes.AUDIO_DTS_UHD_P2 == format.sampleMimeType
        ) {
            // DTS decoders support DTS-HD streams (but decode only the core layer).
            return MimeTypes.AUDIO_DTS
        }
        if (MimeTypes.VIDEO_DOLBY_VISION == format.sampleMimeType) {
            // H.264/AVC, H.265/HEVC or AV1 decoders can decode the base layer of some DV profiles.
            // This can't be done for profile CodecProfileLevel.DolbyVisionProfileDvheStn and profile
            // CodecProfileLevel.DolbyVisionProfileDvheDtb because the first one is not backward
            // compatible and the second one is deprecated and is not always backward compatible.
            val codecProfileAndLevel =
                getMediaCodecProfileAndLevel(format)
            if (codecProfileAndLevel != null && codecProfileAndLevel.isSupportableByMediaCodec) {
                val profile = codecProfileAndLevel.getProfile()
                if (profile == CodecProfileLevel.DolbyVisionProfileDvheDtr
                    || profile == CodecProfileLevel.DolbyVisionProfileDvheSt
                ) {
                    return MimeTypes.VIDEO_H265
                } else if (profile == CodecProfileLevel.DolbyVisionProfileDvavSe) {
                    return MimeTypes.VIDEO_H264
                } else if (profile == CodecProfileLevel.DolbyVisionProfileDvav110) {
                    if (format.colorInfo != null && format.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084 && format.colorInfo.colorRange == C.COLOR_RANGE_FULL) {
                        return null
                    }
                    return MimeTypes.VIDEO_AV1
                }
            }
        }
        if (MimeTypes.VIDEO_MV_HEVC == format.sampleMimeType) {
            // Single-layer HEVC decoders can decode the base layer of MV-HEVC streams.
            return MimeTypes.VIDEO_H265
        }
        return null
    }

    // Internal methods.

    private fun supportsEac3JocFallbackDecoding(): Boolean {
        return Build.MANUFACTURER != "Google"
    }

    @Throws(DecoderQueryException::class)
    private fun getDecoderInfosInternal(
        key: CodecKey, mediaCodecList: MediaCodecListCompat
    ): MutableList<MediaCodecInfo> {
        try {
            val decoderInfos = ArrayList<MediaCodecInfo>()
            val mimeType = key.mimeType
            val numberOfCodecs = mediaCodecList.codecCount
            val secureDecodersExplicit = mediaCodecList.secureDecodersExplicit()
            // Note: MediaCodecList is sorted by the framework such that the best decoders come first.
            for (i in 0..<numberOfCodecs) {
                val codecInfo = mediaCodecList.getCodecInfoAt(i)
                if (isAlias(codecInfo)) {
                    // Skip aliases of other codecs, since they will also be listed under their canonical
                    // names.
                    continue
                }
                val name = codecInfo.name
                if (!isCodecUsableDecoder(codecInfo, name, secureDecodersExplicit, mimeType)) {
                    continue
                }
                val codecMimeType = getCodecMimeType(codecInfo, name, mimeType) ?: continue
                try {
                    val capabilities = codecInfo.getCapabilitiesForType(codecMimeType)
                    val tunnelingSupported =
                        mediaCodecList.isFeatureSupported(
                            CodecCapabilities.FEATURE_TunneledPlayback, codecMimeType, capabilities
                        )
                    val tunnelingRequired =
                        mediaCodecList.isFeatureRequired(
                            CodecCapabilities.FEATURE_TunneledPlayback, codecMimeType, capabilities
                        )
                    if ((!key.tunneling && tunnelingRequired) || (key.tunneling && !tunnelingSupported)) {
                        continue
                    }
                    val secureSupported =
                        mediaCodecList.isFeatureSupported(
                            CodecCapabilities.FEATURE_SecurePlayback, codecMimeType, capabilities
                        )
                    val secureRequired =
                        mediaCodecList.isFeatureRequired(
                            CodecCapabilities.FEATURE_SecurePlayback, codecMimeType, capabilities
                        )
                    if ((!key.secure && secureRequired) || (key.secure && !secureSupported)) {
                        continue
                    }
                    val hardwareAccelerated = isHardwareAccelerated(codecInfo, mimeType)
                    val softwareOnly = isSoftwareOnly(codecInfo, mimeType)
                    val vendor = isVendor(codecInfo)
                    if ((secureDecodersExplicit && key.secure == secureSupported)
                        || (!secureDecodersExplicit && !key.secure)
                    ) {
                        decoderInfos.add(
                            MediaCodecInfo.newInstance(
                                name,
                                mimeType,
                                codecMimeType,
                                capabilities,
                                hardwareAccelerated,
                                softwareOnly,
                                vendor,
                                false,
                                false
                            )
                        )
                    } else if (!secureDecodersExplicit && secureSupported) {
                        decoderInfos.add(
                            MediaCodecInfo.newInstance(
                                "$name.secure",
                                mimeType,
                                codecMimeType,
                                capabilities,
                                hardwareAccelerated,
                                softwareOnly,
                                vendor,
                                false,
                                true
                            )
                        )
                        // It only makes sense to have one synthesized secure decoder, return immediately.
                        return decoderInfos
                    }
                } catch (e: Exception) {
                    if (Build.VERSION.SDK_INT == 23 && !decoderInfos.isEmpty()) {
                        // Suppress error querying secondary codec capabilities up to API level 23.
                        e(TAG, "Skipping codec $name (failed to query capabilities)")
                    } else {
                        // Rethrow error querying primary codec capabilities, or secondary codec
                        // capabilities if API level is greater than 23.
                        e(TAG, "Failed to query codec $name ($codecMimeType)")
                        throw e
                    }
                }
            }
            return decoderInfos
        } catch (e: Exception) {
            // If the underlying mediaserver is in a bad state, we may catch an IllegalStateException
            // or an IllegalArgumentException here.
            throw DecoderQueryException(e)
        }
    }

    private fun getCodecMimeType(
        info: android.media.MediaCodecInfo, name: String?, mimeType: String
    ): String? {
        val supportedTypes = info.getSupportedTypes()
        for (supportedType in supportedTypes) {
            if (supportedType.equals(mimeType, ignoreCase = true)) {
                return supportedType
            }
        }

        if (mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
            // Handle decoders that declare support for DV via MIME types that aren't
            // video/dolby-vision.
            if ("OMX.MS.HEVCDV.Decoder" == name) {
                return "video/hevcdv"
            } else if ("OMX.RTK.video.decoder" == name
                || "OMX.realtek.video.decoder.tunneled" == name
            ) {
                return "video/dv_hevc"
            }
        } else if (mimeType == MimeTypes.VIDEO_MV_HEVC) {
            // Handle decoders that declare support for MV-HEVC via MIME types that aren't video/mv-hevc.
            if ("c2.qti.mvhevc.decoder" == name || "c2.qti.mvhevc.decoder.secure" == name) {
                return "video/x-mvhevc"
            }
        } else if (mimeType == MimeTypes.AUDIO_ALAC && "OMX.lge.alac.decoder" == name) {
            return "audio/x-lg-alac"
        } else if (mimeType == MimeTypes.AUDIO_FLAC && "OMX.lge.flac.decoder" == name) {
            return "audio/x-lg-flac"
        } else if (mimeType == MimeTypes.AUDIO_AC3 && "OMX.lge.ac3.decoder" == name) {
            return "audio/lg-ac3"
        }

        return null
    }

    private fun isCodecUsableDecoder(
        info: android.media.MediaCodecInfo,
        name: String,
        secureDecodersExplicit: Boolean,
        mimeType: String?
    ): Boolean {
        if (info.isEncoder || (!secureDecodersExplicit && name.endsWith(".secure"))) {
            return false
        }
        // Work around https://github.com/google/ExoPlayer/issues/3249.
        if (Build.VERSION.SDK_INT < 24 && ("OMX.SEC.aac.dec" == name || "OMX.Exynos.AAC.Decoder" == name)
            && "samsung" == Build.MANUFACTURER
            && (Build.DEVICE.startsWith("zeroflte") // Galaxy S6
                    || Build.DEVICE.startsWith("zerolte") // Galaxy S6 Edge
                    || Build.DEVICE.startsWith("zenlte") // Galaxy S6 Edge+
                    || "SC-05G" == Build.DEVICE // Galaxy S6
                    || "marinelteatt" == Build.DEVICE // Galaxy S6 Active
                    || "404SC" == Build.DEVICE // Galaxy S6 Edge
                    || "SC-04G" == Build.DEVICE
                    || "SCV31" == Build.DEVICE)
        ) {
            return false
        }

        // MTK AC3 decoder doesn't support decoding JOC streams in 2-D. See [Internal: b/69400041].
        if (Build.VERSION.SDK_INT == 23 && MimeTypes.AUDIO_E_AC3_JOC == mimeType
            && "OMX.MTK.AUDIO.DECODER.DSPAC3" == name
        ) {
            return false
        }

        return true
    }

    private fun applyWorkarounds(mimeType: String?, decoderInfos: MutableList<MediaCodecInfo>) {
        if (MimeTypes.AUDIO_RAW == mimeType) {
            if (Build.VERSION.SDK_INT < 26 && Build.DEVICE == "R9"
                && decoderInfos.size == 1 && decoderInfos[0].name == "OMX.MTK.AUDIO.DECODER.RAW"
            ) {
                // This device does not list a generic raw audio decoder, yet it can be instantiated by
                // name. See <a href="https://github.com/google/ExoPlayer/issues/5782">Issue #5782</a>.
                decoderInfos.add(
                    MediaCodecInfo.newInstance( 
                        "OMX.google.raw.decoder",  
                        MimeTypes.AUDIO_RAW,  
                        MimeTypes.AUDIO_RAW,  
                        null,  
                        false,  
                        true,  
                        false,
                        false,
                        false
                    )
                )
            }
            // Work around inconsistent raw audio decoding behavior across different devices.
            sortByScore<MediaCodecInfo>(
                decoderInfos,
                ScoreProvider { decoderInfo: MediaCodecInfo? ->
                    val name = decoderInfo!!.name
                    if (name.startsWith("OMX.google") || name.startsWith("c2.android")) {
                        // Prefer generic decoders over ones provided by the device.
                        return@ScoreProvider 1
                    }
                    if (Build.VERSION.SDK_INT < 26 && name == "OMX.MTK.AUDIO.DECODER.RAW") {
                        // This decoder may modify the audio, so any other compatible decoders take
                        // precedence. See [Internal: b/62337687].
                        return@ScoreProvider -1
                    }
                    0
                })
        }

        if (Build.VERSION.SDK_INT < 32 && decoderInfos.size > 1) {
            val firstCodecName = decoderInfos[0].name
            // Prefer anything other than OMX.qti.audio.decoder.flac on older devices. See [Internal
            // ref: b/199124812].
            if ("OMX.qti.audio.decoder.flac" == firstCodecName) {
                decoderInfos.add(decoderInfos.removeAt(0))
            }
        }
    }

    private fun isAlias(info: android.media.MediaCodecInfo): Boolean {
        return Build.VERSION.SDK_INT >= 29 && isAliasV29(info)
    }

    @RequiresApi(29)
    private fun isAliasV29(info: android.media.MediaCodecInfo): Boolean {
        return info.isAlias
    }

    private fun isHardwareAccelerated(
        codecInfo: android.media.MediaCodecInfo, mimeType: String?
    ): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            return isHardwareAcceleratedV29(codecInfo)
        }
        // codecInfo.isHardwareAccelerated() != codecInfo.isSoftwareOnly() is not necessarily true.
        // However, we assume this to be true as an approximation.
        return !isSoftwareOnly(codecInfo, mimeType)
    }

    @RequiresApi(29)
    private fun isHardwareAcceleratedV29(codecInfo: android.media.MediaCodecInfo): Boolean {
        return codecInfo.isHardwareAccelerated
    }

    private fun isSoftwareOnly(
        codecInfo: android.media.MediaCodecInfo,
        mimeType: String?
    ): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            return isSoftwareOnlyV29(codecInfo)
        }
        if (isAudio(mimeType)) {
            // Assume audio decoders are software only.
            return true
        }
        val codecName = Ascii.toLowerCase(codecInfo.name)
        if (codecName.startsWith("arc.")) {
            // App Runtime for Chrome (ARC) codecs
            return false
        }
        return codecName.startsWith("omx.google.")
                || codecName.startsWith("omx.ffmpeg.")
                || (codecName.startsWith("omx.sec.") && codecName.contains(".sw."))
                || codecName == "omx.qcom.video.decoder.hevcswvdec"
                || codecName.startsWith("c2.android.")
                || codecName.startsWith("c2.google.")
                || (!codecName.startsWith("omx.") && !codecName.startsWith("c2."))
    }

    @RequiresApi(29)
    private fun isSoftwareOnlyV29(codecInfo: android.media.MediaCodecInfo): Boolean {
        return codecInfo.isSoftwareOnly
    }

    private fun isVendor(codecInfo: android.media.MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            return isVendorV29(codecInfo)
        }
        val codecName = Ascii.toLowerCase(codecInfo.name)
        return !codecName.startsWith("omx.google.") && !codecName.startsWith("c2.android.") && !codecName.startsWith(
            "c2.google."
        )
    }

    @RequiresApi(29)
    private fun isVendorV29(codecInfo: android.media.MediaCodecInfo): Boolean {
        return codecInfo.isVendor
    }

    private fun avcLevelToMaxFrameSize(avcLevel: Int): Int {
        return when (avcLevel) {
            CodecProfileLevel.AVCLevel1, CodecProfileLevel.AVCLevel1b -> 99 * 16 * 16
            CodecProfileLevel.AVCLevel12, CodecProfileLevel.AVCLevel13, CodecProfileLevel.AVCLevel2 -> 396 * 16 * 16
            CodecProfileLevel.AVCLevel21 -> 792 * 16 * 16
            CodecProfileLevel.AVCLevel22, CodecProfileLevel.AVCLevel3 -> 1620 * 16 * 16
            CodecProfileLevel.AVCLevel31 -> 3600 * 16 * 16
            CodecProfileLevel.AVCLevel32 -> 5120 * 16 * 16
            CodecProfileLevel.AVCLevel4, CodecProfileLevel.AVCLevel41 -> 8192 * 16 * 16
            CodecProfileLevel.AVCLevel42 -> 8704 * 16 * 16
            CodecProfileLevel.AVCLevel5 -> 22080 * 16 * 16
            CodecProfileLevel.AVCLevel51, CodecProfileLevel.AVCLevel52 -> 36864 * 16 * 16
            CodecProfileLevel.AVCLevel6, CodecProfileLevel.AVCLevel61, CodecProfileLevel.AVCLevel62 -> 139264 * 16 * 16
            else -> -1
        }
    }

    private fun <T> sortByScore(list: MutableList<T>, scoreProvider: ScoreProvider<T>) {
        Collections.sort<T>(
            list,
            Comparator { a: T, b: T -> scoreProvider.getScore(b) - scoreProvider.getScore(a) })
    }

    class DecoderQueryException(cause: Throwable?) :
        Exception("Failed to query underlying media codecs", cause)

    private fun interface ScoreProvider<T> {
        fun getScore(t: T): Int
    }

    private interface MediaCodecListCompat {
        val codecCount: Int
        fun getCodecInfoAt(index: Int): android.media.MediaCodecInfo
        fun secureDecodersExplicit(): Boolean
        fun isFeatureSupported(
            feature: String,
            mimeType: String,
            capabilities: CodecCapabilities
        ): Boolean

        fun isFeatureRequired(
            feature: String,
            mimeType: String,
            capabilities: CodecCapabilities
        ): Boolean
    }

    private class MediaCodecListCompatV21(
        includeSecure: Boolean,
        includeTunneling: Boolean,
        includeSpecialCodec: Boolean
    ) : MediaCodecListCompat {
        private val codecKind: Int = if (includeSecure || includeTunneling || includeSpecialCodec)
            MediaCodecList.ALL_CODECS
        else
            MediaCodecList.REGULAR_CODECS
        private var mediaCodecInfos: Array<android.media.MediaCodecInfo>? = null

        override val codecCount: Int
            get() {
                ensureMediaCodecInfosInitialized()
                return mediaCodecInfos!!.size
            }

        override fun getCodecInfoAt(index: Int): android.media.MediaCodecInfo {
            ensureMediaCodecInfosInitialized()
            return mediaCodecInfos!![index]
        }

        override fun secureDecodersExplicit(): Boolean {
            return true
        }

        override fun isFeatureSupported(
            feature: String, mimeType: String, capabilities: CodecCapabilities
        ): Boolean {
            return capabilities.isFeatureSupported(feature)
        }

        override fun isFeatureRequired(
            feature: String, mimeType: String, capabilities: CodecCapabilities
        ): Boolean {
            return capabilities.isFeatureRequired(feature)
        }

        fun ensureMediaCodecInfosInitialized() {
            if (mediaCodecInfos == null) {
                mediaCodecInfos = MediaCodecList(codecKind).codecInfos
            }
        }
    }

    private class MediaCodecListCompatV16 : MediaCodecListCompat {
        override val codecCount: Int
            get() = MediaCodecList.getCodecCount()

        override fun getCodecInfoAt(index: Int): android.media.MediaCodecInfo {
            return MediaCodecList.getCodecInfoAt(index)
        }

        override fun secureDecodersExplicit(): Boolean {
            return false
        }

        override fun isFeatureSupported(
            feature: String, mimeType: String, capabilities: CodecCapabilities
        ): Boolean {
            // Secure decoders weren't explicitly listed prior to API level 21. We assume that a secure
            // H264 decoder exists.
            return CodecCapabilities.FEATURE_SecurePlayback == feature
                    && MimeTypes.VIDEO_H264 == mimeType
        }

        override fun isFeatureRequired(
            feature: String, mimeType: String, capabilities: CodecCapabilities
        ): Boolean {
            return false
        }
    }

    private data class CodecKey(val mimeType: String, val secure: Boolean, val tunneling: Boolean)
}
