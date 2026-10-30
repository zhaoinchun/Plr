package androidx.media3.exoplayer.mediacodec

import android.content.Context
import android.graphics.Point
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.VideoCapabilities
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaLibraryInfo.enableWorkarounds
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.isVideo
import androidx.media3.common.MimeTypes.normalizeMimeType
import androidx.media3.common.util.CodecSpecificDataUtil.getCodecProfileAndLevel
import androidx.media3.common.util.CodecSpecificDataUtil.getMediaCodecProfileAndLevel
import androidx.media3.common.util.Log.d
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.ceilDivide
import androidx.media3.common.util.Util.getSystemProperty
import androidx.media3.common.util.Util.isAutomotive
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DecoderReuseEvaluation.DecoderDiscardReasons
import androidx.media3.exoplayer.mediacodec.MediaCodecPerformancePointCoverageProvider.PerformancePointCoverageResult
import com.google.common.base.Strings
import com.google.common.base.Supplier
import com.google.common.base.Suppliers
import com.google.common.primitives.Ints
import com.google.errorprone.annotations.InlineMe
import kotlin.math.abs
import kotlin.math.floor

class MediaCodecInfo private constructor(
    val name: String,
    val mimeType: String,
    val codecMimeType: String?,
    val capabilities: CodecCapabilities?,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val vendor: Boolean,
    val adaptive: Boolean,
    val tunneling: Boolean,
    val secure: Boolean,
    val detachedSurfaceSupported: Boolean
) {
    private val isVideo = isVideo(mimeType)

    // Memoization for getMaxSupportedFrameRate
    private var maxFrameRateWidth= C.LENGTH_UNSET
    private var maxFrameRateHeight= C.LENGTH_UNSET
    private var maxFrameRate = C.RATE_UNSET

    override fun toString(): String {
        return name
    }

    val profileLevels: Array<out CodecProfileLevel> = if (capabilities == null || capabilities.profileLevels == null)
        arrayOf()
    else
        capabilities.profileLevels

    val maxSupportedInstances: Int
        get() {
            if (capabilities == null) {
                return MAX_SUPPORTED_INSTANCES_UNKNOWN
            }
            return capabilities.maxSupportedInstances
        }

    fun isFormatSupported(context: Context, format: Format): Boolean {
        if (!isSampleMimeTypeSupported(format)) {
            return false
        }

        if (!isCodecProfileAndLevelSupported(context, format,true)) {
            return false
        }
        if (!isCompressedAudioBitDepthSupported(format)) {
            return false
        }
        if (isVideo) {
            if (format.width <= 0 || format.height <= 0) {
                return true
            }
            return isVideoSizeAndRateSupported(
                format.width,
                format.height,
                format.frameRate.toDouble()
            )
        } else { // Audio
            return (format.sampleRate == Format.NO_VALUE || isAudioSampleRateSupported(format.sampleRate))
                    && (format.channelCount == Format.NO_VALUE
                    || isAudioChannelCountSupported(format.channelCount))
        }
    }

    fun isFormatFunctionallySupported(context: Context, format: Format): Boolean {
        return isSampleMimeTypeSupported(format)
                && isCodecProfileAndLevelSupported(context, format, false)
                && isCompressedAudioBitDepthSupported(format)
    }

    private fun isSampleMimeTypeSupported(format: Format): Boolean {
        return mimeType == format.sampleMimeType
                || mimeType == MediaCodecUtil.getAlternativeCodecMimeType(format)
    }

    private fun isCodecProfileAndLevelSupported(
        context: Context, format: Format, checkPerformanceCapabilities: Boolean
    ): Boolean {
        var codecProfileAndLevel =
            getMediaCodecProfileAndLevel(format)
        if (format.sampleMimeType != null && format.sampleMimeType == MimeTypes.VIDEO_MV_HEVC) {
            val normalizedCodecMimeType = normalizeMimeType(codecMimeType)
            if (normalizedCodecMimeType == MimeTypes.VIDEO_MV_HEVC) {
                // Currently as there is no formal support for MV-HEVC within Android framework, the profile
                // is not correctly specified by the underlying codec; just assume the profile obtained from
                // the MV-HEVC sample is supported.
                return true
            } else if (normalizedCodecMimeType == MimeTypes.VIDEO_H265) {
                // Falling back to single-layer HEVC from MV-HEVC.  Get base layer profile and level.
                codecProfileAndLevel = MediaCodecUtil.getHevcBaseLayerCodecProfileAndLevel(format)
            }
        }

        if (codecProfileAndLevel == null) {
            // If we don't know any better, we assume that the profile and level are supported.
            return true
        }
        if (!codecProfileAndLevel.isSupportableByMediaCodec) {
            return false
        }
        var profile = codecProfileAndLevel.getProfile()
        var level = codecProfileAndLevel.getLevel()
        if (MimeTypes.VIDEO_DOLBY_VISION == format.sampleMimeType) {
            // If this codec is H.264, H.265 or AV1, we only support the Dolby Vision base layer and need
            // to map the Dolby Vision profile to the corresponding base layer profile. Also assume all
            // levels of this base layer profile are supported.
            when (mimeType) {
                MimeTypes.VIDEO_H264 -> {
                    profile = CodecProfileLevel.AVCProfileHigh
                    level = 0
                }

                MimeTypes.VIDEO_H265 -> {
                    profile = CodecProfileLevel.HEVCProfileMain10
                    level = 0
                }

                MimeTypes.VIDEO_AV1 -> {
                    profile = CodecProfileLevel.AV1ProfileMain10
                    level = 0
                }

                else -> {}
            }
        }

        if (!isVideo && (mimeType != MimeTypes.AUDIO_AC4) && profile != CodecProfileLevel.AACObjectXHE) {
            // AC-4 decoders report profile levels or audio capabilities that determine whether the input
            // format is supported or not.
            // With other encodings some devices/builds underreport audio capabilities, so assume support
            // except for xHE-AAC which may not be widely supported. See
            // https://github.com/google/ExoPlayer/issues/5145.
            return true
        }

        var profileLevels = this.profileLevels
        if (mimeType == MimeTypes.AUDIO_AC4 && profileLevels.isEmpty()) {
            // Some older devices don't report profile levels for AC-4. Estimate them using other data
            // in the codec capabilities.
            profileLevels = estimateLegacyAc4ProfileLevels(context, capabilities)
        }
        if (Build.VERSION.SDK_INT == 23 && MimeTypes.VIDEO_VP9 == mimeType && profileLevels.isEmpty()) {
            // Some older devices don't report profile levels for VP9. Estimate them using other data in
            // the codec capabilities.
            profileLevels = estimateLegacyVp9ProfileLevels(capabilities)
        }

        for (profileLevel in profileLevels) {
            if (profileLevel.profile == profile && (profileLevel.level >= level || !checkPerformanceCapabilities)
                && !needsProfileExcludedWorkaround(mimeType, profile)
            ) {
                return true
            }
        }
        logNoSupport("codec.profileLevel, " + format.codecs + ", " + codecMimeType)
        return false
    }

    private fun isCompressedAudioBitDepthSupported(format: Format): Boolean {
        // MediaCodec doesn't have a way to query FLAC decoder bit-depth support.
        // c2.android.flac.decoder is known not to support 32-bit until API 34. We optimistically assume
        // that another (unrecognized) FLAC decoder does support 32-bit on all API levels where it
        // exists.
        return (format.sampleMimeType != MimeTypes.AUDIO_FLAC) || format.pcmEncoding != C.ENCODING_PCM_32BIT || Build.VERSION.SDK_INT >= 34 || (name != "c2.android.flac.decoder")
    }

    val isHdr10PlusOutOfBandMetadataSupported: Boolean
        get() {
            if (Build.VERSION.SDK_INT >= 29 && MimeTypes.VIDEO_VP9 == mimeType) {
                for (capabilities in this.profileLevels) {
                    if (capabilities.profile == CodecProfileLevel.VP9Profile2HDR10Plus) {
                        return true
                    }
                }
            }
            return false
        }

    fun isSeamlessAdaptationSupported(format: Format): Boolean {
        if (isVideo) {
            return adaptive
        } else {
            val profileLevel =
                getMediaCodecProfileAndLevel(format)
            return profileLevel != null && profileLevel.isSupportableByMediaCodec
                    && profileLevel.getProfile() == CodecProfileLevel.AACObjectXHE
        }
    }

    fun canReuseCodec(oldFormat: Format, newFormat: Format): DecoderReuseEvaluation {
        var discardReasons: @DecoderDiscardReasons Int = 0
        if (oldFormat.sampleMimeType != newFormat.sampleMimeType) {
            discardReasons =
                discardReasons or DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED
        }

        if (isVideo) {
            if (oldFormat.rotationDegrees != newFormat.rotationDegrees) {
                discardReasons =
                    discardReasons or DecoderReuseEvaluation.DISCARD_REASON_VIDEO_ROTATION_CHANGED
            }
            val resolutionChanged =
                oldFormat.width != newFormat.width || oldFormat.height != newFormat.height
            if (!adaptive && resolutionChanged) {
                discardReasons =
                    discardReasons or DecoderReuseEvaluation.DISCARD_REASON_VIDEO_RESOLUTION_CHANGED
            }
            if ((!ColorInfo.isEquivalentToAssumedSdrDefault(oldFormat.colorInfo)
                        || !ColorInfo.isEquivalentToAssumedSdrDefault(newFormat.colorInfo))
                && oldFormat.colorInfo != newFormat.colorInfo
            ) {
                // Don't perform detailed checks if both ColorInfos fall within the default SDR assumption.
                discardReasons =
                    discardReasons or DecoderReuseEvaluation.DISCARD_REASON_VIDEO_COLOR_INFO_CHANGED
            }
            if (needsAdaptationReconfigureWorkaround(name)
                && !oldFormat.initializationDataEquals(newFormat)
            ) {
                discardReasons = discardReasons or DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND
            }

            if (oldFormat.decodedWidth != Format.NO_VALUE && oldFormat.decodedHeight != Format.NO_VALUE && oldFormat.decodedWidth == newFormat.decodedWidth && oldFormat.decodedHeight == newFormat.decodedHeight && resolutionChanged) {
                // Work around a bug where MediaCodec fails to adapt between formats if the compressed
                // picture dimensions match but the cropped region for display differs.
                // See b/409036359.
                discardReasons = discardReasons or DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND
            }

            if (discardReasons == 0
                && newFormat.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
            ) {
                val oldCodecProfileLevel =
                    getCodecProfileAndLevel(oldFormat)
                val newCodecProfileLevel =
                    getCodecProfileAndLevel(newFormat)
                if (oldCodecProfileLevel == null || newCodecProfileLevel == null || (oldCodecProfileLevel.first != newCodecProfileLevel.first)) {
                    // DolbyVision profiles convey specific base encodings and decoders may not be able
                    // to be reused across profiles.
                    // See b/446011738.
                    discardReasons = discardReasons or DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND
                }
            }

            if (discardReasons == 0) {
                return DecoderReuseEvaluation(
                    name,
                    oldFormat,
                    newFormat,
                    if (oldFormat.initializationDataEquals(newFormat))
                        DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION
                    else
                        DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_RECONFIGURATION,  
                    0
                )
            }
        } else {
            if (oldFormat.channelCount != newFormat.channelCount) {
                discardReasons =
                    discardReasons or DecoderReuseEvaluation.DISCARD_REASON_AUDIO_CHANNEL_COUNT_CHANGED
            }
            if (oldFormat.sampleRate != newFormat.sampleRate) {
                discardReasons =
                    discardReasons or DecoderReuseEvaluation.DISCARD_REASON_AUDIO_SAMPLE_RATE_CHANGED
            }
            if (oldFormat.pcmEncoding != newFormat.pcmEncoding) {
                discardReasons =
                    discardReasons or DecoderReuseEvaluation.DISCARD_REASON_AUDIO_ENCODING_CHANGED
            }

            // Some audio formats allow adaptation without reconfiguration or flushing.
            if (discardReasons == 0
                && (mimeType == MimeTypes.AUDIO_AAC || mimeType == MimeTypes.AUDIO_AC4)
            ) {
                val oldCodecProfileLevel =
                    getCodecProfileAndLevel(oldFormat)
                val newCodecProfileLevel =
                    getCodecProfileAndLevel(newFormat)
                if (oldCodecProfileLevel != null && newCodecProfileLevel != null) {
                    val oldProfile: Int = oldCodecProfileLevel.first!!
                    val newProfile: Int = newCodecProfileLevel.first!!
                    // Check whether we're adapting between two xHE-AAC formats, for which adaptation is
                    // possible without reconfiguration or flushing.
                    if (oldProfile == CodecProfileLevel.AACObjectXHE
                        && newProfile == CodecProfileLevel.AACObjectXHE
                    ) {
                        return DecoderReuseEvaluation(
                            name,
                            oldFormat,
                            newFormat,
                            DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,  
                            0
                        )
                    }
                    // For ac4 with the same profile and level, adaptation is possible without reconfiguration
                    // or flushing.
                    if (mimeType == MimeTypes.AUDIO_AC4
                        && oldCodecProfileLevel == newCodecProfileLevel
                    ) {
                        return DecoderReuseEvaluation(
                            name,
                            oldFormat,
                            newFormat,
                            DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,  
                            0
                        )
                    }
                }
            }

            // For eac3 and eac3-joc, adaptation is possible without reconfiguration or flushing.
            if (discardReasons == 0
                && (mimeType == MimeTypes.AUDIO_E_AC3_JOC
                        || mimeType == MimeTypes.AUDIO_E_AC3)
            ) {
                return DecoderReuseEvaluation(
                    name,
                    oldFormat,
                    newFormat,
                    DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,  
                    0
                )
            }

            if (!oldFormat.initializationDataEquals(newFormat)) {
                discardReasons =
                    discardReasons or DecoderReuseEvaluation.DISCARD_REASON_INITIALIZATION_DATA_CHANGED
            }
            if (needsAdaptationFlushWorkaround(mimeType)) {
                discardReasons = discardReasons or DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND
            }

            if (discardReasons == 0) {
                return DecoderReuseEvaluation(
                    name,
                    oldFormat,
                    newFormat,
                    DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_FLUSH,  
                    0
                )
            }
        }

        return DecoderReuseEvaluation(
            name,
            oldFormat,
            newFormat,
            DecoderReuseEvaluation.REUSE_RESULT_NO,
            discardReasons
        )
    }

    fun isVideoSizeAndRateSupported(width: Int, height: Int, frameRate: Double): Boolean {
        if (capabilities == null) {
            logNoSupport("sizeAndRate.caps")
            return false
        }
        val videoCapabilities = capabilities.videoCapabilities
        if (videoCapabilities == null) {
            logNoSupport("sizeAndRate.vCaps")
            return false
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val evaluation: @PerformancePointCoverageResult Int =
                MediaCodecPerformancePointCoverageProvider.areResolutionAndFrameRateCovered(
                    videoCapabilities, width, height, frameRate
                )
            if (evaluation == MediaCodecPerformancePointCoverageProvider.COVERAGE_RESULT_YES) {
                return true
            } else if (evaluation == MediaCodecPerformancePointCoverageProvider.COVERAGE_RESULT_NO) {
                logNoSupport("sizeAndRate.cover, " + width + "x" + height + "@" + frameRate)
                return false
            }
            // If COVERAGE_RESULT_NO_PERFORMANCE_POINTS_UNSUPPORTED then logic falls through
            // to code below.
        }

        if (!areSizeAndRateSupported(videoCapabilities, width, height, frameRate)) {
            if (width >= height || !needsRotatedVerticalResolutionWorkaround(name) || !areSizeAndRateSupported(
                    videoCapabilities,
                    height,
                    width,
                    frameRate
                )
            ) {
                logNoSupport("sizeAndRate.support, " + width + "x" + height + "@" + frameRate)
                return false
            }
            logAssumedSupport("sizeAndRate.rotated, " + width + "x" + height + "@" + frameRate)
        }
        return true
    }

    @InlineMe(replacement = "this.isVideoSizeAndRateSupported(width, height, frameRate)")
    @Deprecated("Use {@link #isVideoSizeAndRateSupported(int, int, double)} instead.")
    fun isVideoSizeAndRateSupportedV21(width: Int, height: Int, frameRate: Double): Boolean {
        return isVideoSizeAndRateSupported(width, height, frameRate)
    }

    fun getMaxSupportedFrameRate(width: Int, height: Int): Float {
        if (!isVideo) {
            return C.RATE_UNSET
        }
        if (maxFrameRate != C.RATE_UNSET && maxFrameRateWidth == width && maxFrameRateHeight == height) {
            return maxFrameRate
        }
        maxFrameRate = computeMaxSupportedFrameRate(width, height)
        maxFrameRateWidth = width
        maxFrameRateHeight = height
        return maxFrameRate
    }

    private fun computeMaxSupportedFrameRate(width: Int, height: Int): Float {
        var maxFrameRate = 1024f
        var minFrameRate = 0f
        if (isVideoSizeAndRateSupported(width, height, maxFrameRate.toDouble())) {
            return maxFrameRate
        }
        while (abs(maxFrameRate - minFrameRate) > 5f) {
            val testFrameRate = minFrameRate + (maxFrameRate - minFrameRate) / 2
            if (isVideoSizeAndRateSupported(width, height, testFrameRate.toDouble())) {
                minFrameRate = testFrameRate
            } else {
                maxFrameRate = testFrameRate
            }
        }
        return minFrameRate
    }

    fun alignVideoSize(width: Int, height: Int): Point? {
        if (capabilities == null) {
            return null
        }
        val videoCapabilities = capabilities.videoCapabilities ?: return null
        return alignVideoSize(videoCapabilities, width, height)
    }

    @InlineMe(replacement = "this.alignVideoSize(width, height)")
    @Deprecated("Use {@link #alignVideoSize(int, int)} instead.")
    fun alignVideoSizeV21(width: Int, height: Int): Point? {
        return alignVideoSize(width, height)
    }

    fun isAudioSampleRateSupported(sampleRate: Int): Boolean {
        if (capabilities == null) {
            logNoSupport("sampleRate.caps")
            return false
        }
        val audioCapabilities = capabilities.audioCapabilities
        if (audioCapabilities == null) {
            logNoSupport("sampleRate.aCaps")
            return false
        }
        if (!audioCapabilities.isSampleRateSupported(sampleRate)) {
            logNoSupport("sampleRate.support, $sampleRate")
            return false
        }
        return true
    }

    @InlineMe(replacement = "this.isAudioSampleRateSupported(sampleRate)")
    @Deprecated("Use {@link #isAudioSampleRateSupported(int)} instead.")
    fun isAudioSampleRateSupportedV21(sampleRate: Int): Boolean {
        return isAudioSampleRateSupported(sampleRate)
    }

    fun isAudioChannelCountSupported(channelCount: Int): Boolean {
        if (capabilities == null) {
            logNoSupport("channelCount.caps")
            return false
        }
        val audioCapabilities = capabilities.audioCapabilities
        if (audioCapabilities == null) {
            logNoSupport("channelCount.aCaps")
            return false
        }
        val maxInputChannelCount: Int =
            adjustMaxInputChannelCount(name, mimeType, audioCapabilities.maxInputChannelCount)
        if (maxInputChannelCount < channelCount) {
            logNoSupport("channelCount.support, $channelCount")
            return false
        }
        return true
    }

    @InlineMe(replacement = "this.isAudioChannelCountSupported(channelCount)")
    @Deprecated("Use {@link #isAudioChannelCountSupported(int)} instead.")
    fun isAudioChannelCountSupportedV21(channelCount: Int): Boolean {
        return isAudioChannelCountSupported(channelCount)
    }

    private fun logNoSupport(message: String?) {
        d(
            TAG,
            ("NoSupport ["
                    + message
                    + "] ["
                    + name
                    + ", "
                    + mimeType
                    + "] ["
                    + Util.DEVICE_DEBUG_INFO
                    + "]")
        )
    }

    private fun logAssumedSupport(message: String?) {
        d(
            TAG,
            ("AssumedSupport ["
                    + message
                    + "] ["
                    + name
                    + ", "
                    + mimeType
                    + "] ["
                    + Util.DEVICE_DEBUG_INFO
                    + "]")
        )
    }

    companion object {
        const val TAG: String = "MediaCodecInfo"
        const val MAX_SUPPORTED_INSTANCES_UNKNOWN: Int = -1
        private val VENDOR_API_LEVEL: Supplier<Int?> = Suppliers.memoize {
            Ints.tryParse(
                Strings.nullToEmpty(getSystemProperty("ro.vendor.api_level"))
            )
        }

        fun newInstance(
            name: String,
            mimeType: String,
            codecMimeType: String?,
            capabilities: CodecCapabilities?,
            hardwareAccelerated: Boolean,
            softwareOnly: Boolean,
            vendor: Boolean,
            forceDisableAdaptive: Boolean,
            forceSecure: Boolean
        ): MediaCodecInfo {
            return MediaCodecInfo(
                name,
                mimeType,
                codecMimeType,
                capabilities,
                hardwareAccelerated,
                softwareOnly,
                vendor,  
                !forceDisableAdaptive && capabilities != null && isAdaptive(capabilities),  
                capabilities != null && isTunneling(capabilities),  
                forceSecure || (capabilities != null && isSecure(capabilities)),
                isDetachedSurfaceSupported(capabilities)
            )
        }

        private fun alignVideoSize(
            capabilities: VideoCapabilities,
            width: Int,
            height: Int
        ): Point {
            val widthAlignment = capabilities.widthAlignment
            val heightAlignment = capabilities.heightAlignment
            return Point(
                ceilDivide(width, widthAlignment) * widthAlignment,
                ceilDivide(height, heightAlignment) * heightAlignment
            )
        }

        private fun adjustMaxInputChannelCount(
            name: String?,
            mimeType: String?,
            maxChannelCount: Int
        ): Int {
            if (maxChannelCount > 1 || (Build.VERSION.SDK_INT >= 26 && maxChannelCount > 0)) {
                // The maximum channel count looks like it's been set correctly.
                return maxChannelCount
            }
            if (MimeTypes.AUDIO_MPEG == mimeType
                || MimeTypes.AUDIO_AMR_NB == mimeType
                || MimeTypes.AUDIO_AMR_WB == mimeType
                || MimeTypes.AUDIO_AAC == mimeType
                || MimeTypes.AUDIO_VORBIS == mimeType
                || MimeTypes.AUDIO_OPUS == mimeType
                || MimeTypes.AUDIO_RAW == mimeType
                || MimeTypes.AUDIO_FLAC == mimeType
                || MimeTypes.AUDIO_ALAW == mimeType
                || MimeTypes.AUDIO_MLAW == mimeType
                || MimeTypes.AUDIO_MSGSM == mimeType
            ) {
                // Platform code should have set a default.
                return maxChannelCount
            }
            // The maximum channel count looks incorrect. Adjust it to an assumed default.
            val assumedMaxChannelCount: Int
            if (MimeTypes.AUDIO_AC3 == mimeType) {
                assumedMaxChannelCount = 6
            } else if (MimeTypes.AUDIO_E_AC3 == mimeType) {
                assumedMaxChannelCount = 16
            } else {
                // Default to the platform limit, which is 30.
                assumedMaxChannelCount = 30
            }
            w(
                TAG,
                ("AssumedMaxChannelAdjustment: "
                        + name
                        + ", ["
                        + maxChannelCount
                        + " to "
                        + assumedMaxChannelCount
                        + "]")
            )
            return assumedMaxChannelCount
        }

        private fun isAdaptive(capabilities: CodecCapabilities): Boolean {
            return capabilities.isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback)
        }

        private fun isTunneling(capabilities: CodecCapabilities): Boolean {
            return capabilities.isFeatureSupported(CodecCapabilities.FEATURE_TunneledPlayback)
        }

        private fun isSecure(capabilities: CodecCapabilities): Boolean {
            return capabilities.isFeatureSupported(CodecCapabilities.FEATURE_SecurePlayback)
        }

        private fun isDetachedSurfaceSupported(capabilities: CodecCapabilities?): Boolean {
            return Build.VERSION.SDK_INT >= 35 && capabilities != null && capabilities.isFeatureSupported(
                CodecCapabilities.FEATURE_DetachedSurface
            )
                    && !needsDetachedSurfaceUnsupportedWorkaround()
        }

        private fun areSizeAndRateSupported(
            capabilities: VideoCapabilities, width: Int, height: Int, frameRate: Double
        ): Boolean {
            // Don't ever fail due to alignment. See: https://github.com/google/ExoPlayer/issues/6551.
            var width = width
            var height = height
            val alignedSize: Point = alignVideoSize(capabilities, width, height)
            width = alignedSize.x
            height = alignedSize.y

            // VideoCapabilities.areSizeAndRateSupported incorrectly returns false if frameRate < 1 on some
            // versions of Android, so we only check the size in this case [Internal ref: b/153940404].
            if (frameRate == Format.NO_VALUE.toDouble() || frameRate < 1) {
                return capabilities.isSizeSupported(width, height)
            } else {
                // The signaled frame rate may be slightly higher than the actual frame rate, so we take the
                // floor to avoid situations where a range check in areSizeAndRateSupported fails due to
                // slightly exceeding the limits for a standard format (e.g., 1080p at 30 fps).
                val floorFrameRate = floor(frameRate)
                if (!capabilities.areSizeAndRateSupported(width, height, floorFrameRate)) {
                    return false
                }
                if (Build.VERSION.SDK_INT < 24) {
                    return true
                }
                val achievableFrameRates =
                    capabilities.getAchievableFrameRatesFor(width, height) ?: return true
                return floorFrameRate <= achievableFrameRates.getUpper()!!
            }
        }

        private fun estimateLegacyAc4ProfileLevels(
            context: Context, capabilities: CodecCapabilities?
        ): Array<CodecProfileLevel> {
            var maxInChannelCount = 2
            if (capabilities != null) {
                val audioCapabilities = capabilities.audioCapabilities
                if (audioCapabilities != null) {
                    maxInChannelCount = audioCapabilities.maxInputChannelCount
                }
            }

            var level = CodecProfileLevel.AC4Level3
            if (maxInChannelCount > 18) { // AC-4 Level 3 stream is up to 17.1 channel
                level = CodecProfileLevel.AC4Level4
            }

            // Automotive platform MediaCodec AC-4 decoders are not expected to support AC4Profile22 and
            // deprecated profiles AC4Profile00, AC4Profile10 and AC4Profile11. See
            // https://professionalsupport.dolby.com/s/article/Support-Dolby-Atmos-in-Android-Automotive-OS-media-apps?language=en_US
            if (isAutomotive(context)) {
                return arrayOf(
                    MediaCodecUtil.createCodecProfileLevel(CodecProfileLevel.AC4Profile21, level)
                )
            }

            return arrayOf(
                MediaCodecUtil.createCodecProfileLevel(CodecProfileLevel.AC4Profile00, level),
                MediaCodecUtil.createCodecProfileLevel(CodecProfileLevel.AC4Profile10, level),
                MediaCodecUtil.createCodecProfileLevel(CodecProfileLevel.AC4Profile11, level),
                MediaCodecUtil.createCodecProfileLevel(CodecProfileLevel.AC4Profile21, level),
                MediaCodecUtil.createCodecProfileLevel(CodecProfileLevel.AC4Profile22, level)
            )
        }

        private fun estimateLegacyVp9ProfileLevels(
            capabilities: CodecCapabilities?
        ): Array<CodecProfileLevel> {
            var maxBitrate = 0
            if (capabilities != null) {
                val videoCapabilities = capabilities.getVideoCapabilities()
                if (videoCapabilities != null) {
                    maxBitrate = videoCapabilities.getBitrateRange().getUpper()
                }
            }

            // Values taken from https://www.webmproject.org/vp9/levels.
            val level: Int
            if (maxBitrate >= 180000000) {
                level = CodecProfileLevel.VP9Level52
            } else if (maxBitrate >= 120000000) {
                level = CodecProfileLevel.VP9Level51
            } else if (maxBitrate >= 60000000) {
                level = CodecProfileLevel.VP9Level5
            } else if (maxBitrate >= 30000000) {
                level = CodecProfileLevel.VP9Level41
            } else if (maxBitrate >= 18000000) {
                level = CodecProfileLevel.VP9Level4
            } else if (maxBitrate >= 12000000) {
                level = CodecProfileLevel.VP9Level31
            } else if (maxBitrate >= 7200000) {
                level = CodecProfileLevel.VP9Level3
            } else if (maxBitrate >= 3600000) {
                level = CodecProfileLevel.VP9Level21
            } else if (maxBitrate >= 1800000) {
                level = CodecProfileLevel.VP9Level2
            } else if (maxBitrate >= 800000) {
                level = CodecProfileLevel.VP9Level11
            } else { // Assume level 1 is always supported.
                level = CodecProfileLevel.VP9Level1
            }

            // Since this method is for legacy devices only, assume that only profile 0 is supported.
            return arrayOf(
                MediaCodecUtil.createCodecProfileLevel(
                    CodecProfileLevel.VP9Profile0,
                    level
                )
            )
        }

        private fun needsAdaptationReconfigureWorkaround(name: String?): Boolean {
            if (!enableWorkarounds()) {
                return false
            }
            return Build.MODEL.startsWith("SM-T230") && "OMX.MARVELL.VIDEO.HW.CODA7542DECODER" == name
        }

        private fun needsAdaptationFlushWorkaround(mimeType: String?): Boolean {
            // For Opus, we don't flush and reuse the codec because the decoder may discard samples after
            // flushing, which would result in audio being dropped just after a stream change (see
            // [Internal: b/143450854]). For other formats, we allow reuse after flushing if the codec
            // initialization data is unchanged.
            return MimeTypes.AUDIO_OPUS == mimeType
        }

        private fun needsRotatedVerticalResolutionWorkaround(name: String?): Boolean {
            if (!enableWorkarounds()) {
                return false
            }
            if ("OMX.MTK.VIDEO.DECODER.HEVC" == name && "mcv5a" == Build.DEVICE) {
                // See https://github.com/google/ExoPlayer/issues/6612.
                return false
            }
            return true
        }

        private fun needsProfileExcludedWorkaround(mimeType: String?, profile: Int): Boolean {
            if (!enableWorkarounds()) {
                return false
            }
            // See https://github.com/google/ExoPlayer/issues/3537
            return MimeTypes.VIDEO_H265 == mimeType
                    && CodecProfileLevel.HEVCProfileMain10 == profile && ("sailfish" == Build.DEVICE || "marlin" == Build.DEVICE)
        }

        private fun needsDetachedSurfaceUnsupportedWorkaround(): Boolean {
            // We deliberately don't check MediaLibraryInfo.enableWorkarounds() here, because this
            // workaround needs to remain active even during certification testing (b/535214648).
            val vendorApiLevel: Int? = VENDOR_API_LEVEL.get()
            if (vendorApiLevel == null || vendorApiLevel > 202604) {
                // Devices with vendor API level higher than SDK_INT 37 must only declare support for
                // FEATURE_DetachedSurface if they actually support it.
                return false
            }
            return Build.MANUFACTURER == "Xiaomi"
                    || Build.MANUFACTURER == "OPPO"
                    || Build.MANUFACTURER == "realme"
                    || Build.MANUFACTURER == "motorola"
                    || Build.MANUFACTURER == "LENOVO"
                    || Build.MANUFACTURER == "Fairphone"
        }
    }
}
