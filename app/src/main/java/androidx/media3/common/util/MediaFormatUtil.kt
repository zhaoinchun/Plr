package androidx.media3.common.util

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.isAudio
import androidx.media3.common.MimeTypes.isVideo
import androidx.media3.common.util.CodecSpecificDataUtil.buildDolbyVisionCodecString
import androidx.media3.common.util.CodecSpecificDataUtil.buildH263CodecString
import androidx.media3.common.util.CodecSpecificDataUtil.calculateMediaCodecDolbyVisionLevel
import androidx.media3.common.util.CodecSpecificDataUtil.dolbyVisionConstantToLevelNumber
import androidx.media3.common.util.CodecSpecificDataUtil.dolbyVisionConstantToProfileNumber
import androidx.media3.common.util.CodecSpecificDataUtil.h263ConstToLevelNumber
import androidx.media3.common.util.CodecSpecificDataUtil.h263ConstToProfileNumber
import androidx.media3.common.util.Util.getAudioTrackChannelConfig
import java.nio.ByteBuffer

object MediaFormatUtil {
    
    // The constant value must not be changed, because it's also set by the framework MediaParser API.
    const val KEY_PIXEL_WIDTH_HEIGHT_RATIO_FLOAT: String = "exo-pixel-width-height-ratio-float"

    // The constant value must not be changed, because it's also set by the framework MediaParser API.
    const val KEY_PCM_ENCODING_EXTENDED: String = "exo-pcm-encoding-int"

    // The constant value must not be changed, because it's also set by the framework MediaParser and
    // MediaExtractor APIs.
    const val KEY_MAX_BIT_RATE: String = "max-bitrate"

    private val MAX_POWER_OF_TWO_INT = 1 shl 30

    @SuppressLint("InlinedApi") // Inlined MediaFormat keys.
    fun createFormatFromMediaFormat(mediaFormat: MediaFormat): Format {
        val formatBuilder =
            Format.Builder()
                .setSampleMimeType(mediaFormat.getString(MediaFormat.KEY_MIME))
                .setLanguage(mediaFormat.getString(MediaFormat.KEY_LANGUAGE))
                .setPeakBitrate(
                    getInteger(mediaFormat, KEY_MAX_BIT_RATE,  Format.NO_VALUE)
                )
                .setAverageBitrate(
                    getInteger(
                        mediaFormat, MediaFormat.KEY_BIT_RATE,  Format.NO_VALUE
                    )
                )
                .setCodecs(getCodecString(mediaFormat))
                .setFrameRate(getFrameRate(mediaFormat))
                .setWidth(
                    getInteger(
                        mediaFormat,
                        MediaFormat.KEY_WIDTH,  
                        Format.NO_VALUE
                    )
                )
                .setHeight(
                    getInteger(
                        mediaFormat, MediaFormat.KEY_HEIGHT,  Format.NO_VALUE
                    )
                )
                .setPixelWidthHeightRatio(
                    getPixelWidthHeightRatio(mediaFormat,  1.0f)
                )
                .setMaxInputSize(
                    getInteger(
                        mediaFormat,
                        MediaFormat.KEY_MAX_INPUT_SIZE,  
                        Format.NO_VALUE
                    )
                )
                .setRotationDegrees(
                    getInteger(mediaFormat, MediaFormat.KEY_ROTATION,  0)
                )
                .setColorInfo(getColorInfo(mediaFormat))
                .setSampleRate(
                    getInteger(
                        mediaFormat,
                        MediaFormat.KEY_SAMPLE_RATE,  
                        Format.NO_VALUE
                    )
                )
                .setPcmEncoding(getPcmEncoding(mediaFormat))

        val channelCount =
            getInteger(
                mediaFormat,
                MediaFormat.KEY_CHANNEL_COUNT,  
                Format.NO_VALUE
            )
        var channelMask =
            getInteger(
                mediaFormat,
                MediaFormat.KEY_CHANNEL_MASK,  
                Format.NO_VALUE
            )
        if (channelCount != Format.NO_VALUE && channelMask != Format.NO_VALUE && Integer.bitCount(
                channelMask
            ) != channelCount
        ) {
            channelMask = Format.NO_VALUE
        }
        formatBuilder.setChannelCount(channelCount)
        formatBuilder.setChannelMask(channelMask)

        val csdBuffers = mutableListOf<ByteArray>()
        var csdIndex = 0
        while (true) {
            val csdByteBuffer = mediaFormat.getByteBuffer("csd-" + csdIndex)
            if (csdByteBuffer == null) {
                break
            }
            val csdBufferData = ByteArray(csdByteBuffer.remaining())
            csdByteBuffer.get(csdBufferData)
            csdByteBuffer.rewind()

            csdBuffers.add(csdBufferData)
            csdIndex++
        }

        formatBuilder.setInitializationData(csdBuffers.toList())

        if (mediaFormat.containsKey(MediaFormat.KEY_TRACK_ID)) {
            formatBuilder.setId(mediaFormat.getInteger(MediaFormat.KEY_TRACK_ID))
        }

        return formatBuilder.build()
    }

    @SuppressLint("InlinedApi") // Inlined MediaFormat keys.
    fun createMediaFormatFromFormat(format: Format): MediaFormat {
        val result = MediaFormat()
        maybeSetInteger(result, MediaFormat.KEY_BIT_RATE, format.bitrate)
        maybeSetInteger(result, KEY_MAX_BIT_RATE, format.peakBitrate)
        maybeSetInteger(result, MediaFormat.KEY_CHANNEL_COUNT, format.channelCount)
        val channelMask = getAudioTrackChannelConfig(format)
        if (channelMask != AudioFormat.CHANNEL_INVALID) {
            result.setInteger(MediaFormat.KEY_CHANNEL_MASK, channelMask)
        }

        maybeSetColorInfo(result, format.colorInfo)

        maybeSetString(result, MediaFormat.KEY_MIME, format.sampleMimeType)
        maybeSetString(result, MediaFormat.KEY_CODECS_STRING, format.codecs)
        maybeSetFloat(result, MediaFormat.KEY_FRAME_RATE, format.frameRate)
        maybeSetInteger(result, MediaFormat.KEY_WIDTH, format.width)
        maybeSetInteger(result, MediaFormat.KEY_HEIGHT, format.height)

        MediaFormatUtil.setCsdBuffers(result, format.initializationData)
        maybeSetPcmEncoding(result, format.pcmEncoding)
        maybeSetString(result, MediaFormat.KEY_LANGUAGE, format.language)
        maybeSetInteger(result, MediaFormat.KEY_MAX_INPUT_SIZE, format.maxInputSize)
        maybeSetInteger(result, MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
        maybeSetInteger(result, MediaFormat.KEY_CAPTION_SERVICE_NUMBER, format.accessibilityChannel)
        result.setInteger(MediaFormat.KEY_ROTATION, format.rotationDegrees)

        val selectionFlags = format.selectionFlags
        setBooleanAsInt(
            result, MediaFormat.KEY_IS_AUTOSELECT, selectionFlags and C.SELECTION_FLAG_AUTOSELECT
        )
        setBooleanAsInt(
            result,
            MediaFormat.KEY_IS_DEFAULT,
            selectionFlags and C.SELECTION_FLAG_DEFAULT
        )
        setBooleanAsInt(
            result, MediaFormat.KEY_IS_FORCED_SUBTITLE, selectionFlags and C.SELECTION_FLAG_FORCED
        )

        result.setInteger(MediaFormat.KEY_ENCODER_DELAY, format.encoderDelay)
        result.setInteger(MediaFormat.KEY_ENCODER_PADDING, format.encoderPadding)

        maybeSetPixelAspectRatio(result, format.pixelWidthHeightRatio)

        if (format.id != null) {
            try {
                result.setInteger(MediaFormat.KEY_TRACK_ID, format.id.toInt())
            } catch (e: NumberFormatException) {
                // Ignore, format.id is not always an integer, but KEY_TRACK_ID expects an int.
            }
        }
        return result
    }

    fun maybeSetString(format: MediaFormat, key: String, value: String?) {
        if (value != null) {
            format.setString(key, value)
        }
    }

    fun setCsdBuffers(format: MediaFormat, csdBuffers: List<ByteArray>) {
        for (i in csdBuffers.indices) {
            format.setByteBuffer("csd-" + i, ByteBuffer.wrap(csdBuffers[i]))
        }
    }

    fun maybeSetInteger(format: MediaFormat, key: String, value: Int) {
        if (value != Format.NO_VALUE) {
            format.setInteger(key, value)
        }
    }

    fun maybeSetFloat(format: MediaFormat, key: String, value: Float) {
        if (value != Format.NO_VALUE.toFloat()) {
            format.setFloat(key, value)
        }
    }

    fun maybeSetByteBuffer(format: MediaFormat, key: String, value: ByteArray?) {
        if (value != null) {
            format.setByteBuffer(key, ByteBuffer.wrap(value))
        }
    }

    fun maybeSetColorInfo(format: MediaFormat, colorInfo: ColorInfo?) {
        if (colorInfo != null) {
            maybeSetInteger(format, MediaFormat.KEY_COLOR_TRANSFER, colorInfo.colorTransfer)
            maybeSetInteger(format, MediaFormat.KEY_COLOR_STANDARD, colorInfo.colorSpace)
            maybeSetInteger(format, MediaFormat.KEY_COLOR_RANGE, colorInfo.colorRange)
            maybeSetByteBuffer(format, MediaFormat.KEY_HDR_STATIC_INFO, colorInfo.hdrStaticInfo)
        }
    }

    fun getColorInfo(mediaFormat: MediaFormat): ColorInfo? {
        if (Build.VERSION.SDK_INT < 24) {
            // MediaFormat KEY_COLOR_TRANSFER and other KEY_COLOR values available from API 24.
            return null
        }
        var colorSpace =
            getInteger(
                mediaFormat, MediaFormat.KEY_COLOR_STANDARD,  Format.NO_VALUE
            )
        var colorRange =
            getInteger(
                mediaFormat,
                MediaFormat.KEY_COLOR_RANGE,  
                Format.NO_VALUE
            )
        var colorTransfer =
            getInteger(
                mediaFormat, MediaFormat.KEY_COLOR_TRANSFER,  Format.NO_VALUE
            )
        val hdrStaticInfoByteBuffer = mediaFormat.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)
        val hdrStaticInfo =
            if (hdrStaticInfoByteBuffer != null) getArray(hdrStaticInfoByteBuffer) else null
        // Some devices may produce invalid values from MediaFormat#getInteger.
        // See b/239435670 for more information.
        if (!isValidColorSpace(colorSpace)) {
            colorSpace = Format.NO_VALUE
        }
        if (!isValidColorRange(colorRange)) {
            colorRange = Format.NO_VALUE
        }
        if (!isValidColorTransfer(colorTransfer)) {
            colorTransfer = Format.NO_VALUE
        }
        if (colorSpace != Format.NO_VALUE || colorRange != Format.NO_VALUE || colorTransfer != Format.NO_VALUE || hdrStaticInfo != null) {
            return ColorInfo.Builder()
                .setColorSpace(colorSpace)
                .setColorRange(colorRange)
                .setColorTransfer(colorTransfer)
                .setHdrStaticInfo(hdrStaticInfo)
                .build()
        }
        return null
    }

    fun getInteger(mediaFormat: MediaFormat, name: String, defaultValue: Int): Int {
        if (Build.VERSION.SDK_INT >= 29) {
            return mediaFormat.getInteger(name, defaultValue)
        } else {
            return if (mediaFormat.containsKey(name)) mediaFormat.getInteger(name) else defaultValue
        }
    }

    fun getFloat(mediaFormat: MediaFormat, name: String, defaultValue: Float): Float {
        if (Build.VERSION.SDK_INT >= 29) {
            return mediaFormat.getFloat(name, defaultValue)
        } else {
            return if (mediaFormat.containsKey(name)) mediaFormat.getFloat(name) else defaultValue
        }
    }

    fun getString(mediaFormat: MediaFormat, name: String, defaultValue: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            return mediaFormat.getString(name, defaultValue)
        } else {
            return if (mediaFormat.containsKey(name))
                mediaFormat.getString(name)!!
            else
                defaultValue
        }
    }

    fun getFloatFromIntOrFloat(
        mediaFormat: MediaFormat, keyName: String, defaultValue: Float
    ): Float {
        if (!mediaFormat.containsKey(keyName)) {
            return defaultValue
        }
        var value: Float
        if (Build.VERSION.SDK_INT >= 29) {
            val valueType = mediaFormat.getValueTypeForKey(keyName)
            if (valueType == MediaFormat.TYPE_FLOAT) {
                value = mediaFormat.getFloat(keyName)
            } else {
                // It should be int.
                value = mediaFormat.getInteger(keyName).toFloat()
            }
        } else {
            try {
                value = mediaFormat.getFloat(keyName)
            } catch (ex: ClassCastException) {
                value = mediaFormat.getInteger(keyName).toFloat()
            }
        }
        return value
    }

    @SuppressLint("InlinedApi") // Inlined MediaFormat keys.
    private fun getCodecString(mediaFormat: MediaFormat): String? {
        // Add H263 profile and level to codec string as per RFC 6381.
        if (mediaFormat.getString(MediaFormat.KEY_MIME) == MimeTypes.VIDEO_H263
            && mediaFormat.containsKey(MediaFormat.KEY_PROFILE)
            && mediaFormat.containsKey(MediaFormat.KEY_LEVEL)
        ) {
            return buildH263CodecString(
                h263ConstToProfileNumber(mediaFormat.getInteger(MediaFormat.KEY_PROFILE)),
                h263ConstToLevelNumber(mediaFormat.getInteger(MediaFormat.KEY_LEVEL))
            )
        } else if (mediaFormat.getString(MediaFormat.KEY_MIME) == MimeTypes.VIDEO_DOLBY_VISION
            && mediaFormat.containsKey(MediaFormat.KEY_PROFILE)
        ) {
            var dolbyVisionLevel = getInteger(mediaFormat, MediaFormat.KEY_LEVEL, Format.NO_VALUE)
            if (dolbyVisionLevel == Format.NO_VALUE) {
                val width =
                    getInteger(
                        mediaFormat,
                        MediaFormat.KEY_WIDTH,  
                        Format.NO_VALUE
                    )
                val height =
                    getInteger(
                        mediaFormat,
                        MediaFormat.KEY_HEIGHT,  
                        Format.NO_VALUE
                    )
                val frameRate =
                    getFloatFromIntOrFloat(
                        mediaFormat,
                        MediaFormat.KEY_FRAME_RATE,  
                        Format.NO_VALUE.toFloat()
                    )
                dolbyVisionLevel = calculateMediaCodecDolbyVisionLevel(width, height, frameRate)
            }
            // Add Dolby Vision profile and level to codec string as per Dolby Vision ISO media format.
            return buildDolbyVisionCodecString(
                dolbyVisionConstantToProfileNumber(
                    mediaFormat.getInteger(MediaFormat.KEY_PROFILE)
                ),
                dolbyVisionConstantToLevelNumber(dolbyVisionLevel)
            )
        } else {
            return mediaFormat.getString(MediaFormat.KEY_CODECS_STRING)
        }
    }

    @SuppressLint("InlinedApi") // Inlined MediaFormat.KEY_FRAME_RATE.
    private fun getFrameRate(mediaFormat: MediaFormat): Float {
        var frameRate =
            getFloatFromIntOrFloat(
                mediaFormat,
                MediaFormat.KEY_FRAME_RATE,  
                Format.NO_VALUE.toFloat()
            )
        // Some devices may produce non-positive frame rates from MediaFormat.
        // See b/493371202 for more information.
        if (frameRate <= 0f) {
            frameRate = Format.NO_VALUE.toFloat()
        }
        return frameRate
    }

     // Inlined MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH and MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT.
    @SuppressLint("InlinedApi")
    private fun getPixelWidthHeightRatio(mediaFormat: MediaFormat, defaultValue: Float): Float {
        if (mediaFormat.containsKey(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH)
            && mediaFormat.containsKey(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT)
        ) {
            return mediaFormat.getInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH)
                .toFloat() / mediaFormat.getInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT)
                .toFloat()
        }

        return defaultValue
    }

    fun getArray(byteBuffer: ByteBuffer): ByteArray {
        val array = ByteArray(byteBuffer.remaining())
        byteBuffer.get(array)
        return array
    }

    fun isVideoFormat(mediaFormat: MediaFormat): Boolean {
        return isVideo(mediaFormat.getString(MediaFormat.KEY_MIME))
    }

    fun isAudioFormat(mediaFormat: MediaFormat): Boolean {
        return isAudio(mediaFormat.getString(MediaFormat.KEY_MIME))
    }

    fun getTimeLapseFrameRate(format: MediaFormat): Int? {
        if (format.containsKey("time-lapse-enable")
            && format.getInteger("time-lapse-enable") > 0 && format.containsKey("time-lapse-fps")
        ) {
            return format.getInteger("time-lapse-fps")
        } else {
            return null
        }
    }

    // Internal methods.
    private fun setBooleanAsInt(format: MediaFormat, key: String, value: Int) {
        format.setInteger(key, if (value != 0) 1 else 0)
    }

    // Inlined MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH and MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT.
    @SuppressLint("InlinedApi")
    private fun maybeSetPixelAspectRatio(
        mediaFormat: MediaFormat, pixelWidthHeightRatio: Float
    ) {
        mediaFormat.setFloat(KEY_PIXEL_WIDTH_HEIGHT_RATIO_FLOAT, pixelWidthHeightRatio)
        var pixelAspectRatioWidth = 1
        var pixelAspectRatioHeight = 1
        // ExoPlayer extractors output the pixel aspect ratio as a float. Do our best to recreate the
        // pixel aspect ratio width and height by using a large power of two factor.
        if (pixelWidthHeightRatio < 1.0f) {
            pixelAspectRatioHeight = MAX_POWER_OF_TWO_INT
            pixelAspectRatioWidth = (pixelWidthHeightRatio * pixelAspectRatioHeight).toInt()
        } else if (pixelWidthHeightRatio > 1.0f) {
            pixelAspectRatioWidth = MAX_POWER_OF_TWO_INT
            pixelAspectRatioHeight = (pixelAspectRatioWidth / pixelWidthHeightRatio).toInt()
        }
        mediaFormat.setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH, pixelAspectRatioWidth)
        mediaFormat.setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT, pixelAspectRatioHeight)
    }

    @SuppressLint("InlinedApi") // Inlined MediaFormat keys.
    private fun getPcmEncoding(mediaFormat: MediaFormat): @C.PcmEncoding Int {
        val exoPcmEncoding: @C.PcmEncoding Int =
            getInteger(mediaFormat, KEY_PCM_ENCODING_EXTENDED,  Format.NO_VALUE)
        if (exoPcmEncoding != Format.NO_VALUE) {
            return exoPcmEncoding
        }
        return getInteger(
            mediaFormat, MediaFormat.KEY_PCM_ENCODING,  Format.NO_VALUE
        )
    }

    @SuppressLint("InlinedApi") // Inlined KEY_PCM_ENCODING.
    private fun maybeSetPcmEncoding(
        mediaFormat: MediaFormat, exoPcmEncoding: @C.PcmEncoding Int
    ) {
        if (exoPcmEncoding == Format.NO_VALUE) {
            return
        }
        val mediaFormatPcmEncoding: Int
        maybeSetInteger(mediaFormat, KEY_PCM_ENCODING_EXTENDED, exoPcmEncoding)
        when (exoPcmEncoding) {
            C.ENCODING_PCM_8BIT -> mediaFormatPcmEncoding = AudioFormat.ENCODING_PCM_8BIT
            C.ENCODING_PCM_16BIT -> mediaFormatPcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            C.ENCODING_PCM_FLOAT -> mediaFormatPcmEncoding = AudioFormat.ENCODING_PCM_FLOAT
            C.ENCODING_PCM_24BIT -> mediaFormatPcmEncoding = AudioFormat.ENCODING_PCM_24BIT_PACKED
            C.ENCODING_PCM_32BIT -> mediaFormatPcmEncoding = AudioFormat.ENCODING_PCM_32BIT
            C.ENCODING_INVALID -> mediaFormatPcmEncoding = AudioFormat.ENCODING_INVALID
            Format.NO_VALUE, C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT_BIG_ENDIAN, C.ENCODING_PCM_DOUBLE, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN ->         // No matching value. Do nothing.
                return

            else ->
                return
        }
        mediaFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, mediaFormatPcmEncoding)
    }

    private fun isValidColorSpace(colorSpace: Int): Boolean {
        // LINT.IfChange(color_space)
        return colorSpace == C.COLOR_SPACE_BT601 || colorSpace == C.COLOR_SPACE_BT709 || colorSpace == C.COLOR_SPACE_BT2020 || colorSpace == Format.NO_VALUE
        // LINT.ThenChange(../C.java:color_space)
    }

    private fun isValidColorRange(colorRange: Int): Boolean {
        // LINT.IfChange(color_range)
        return colorRange == C.COLOR_RANGE_LIMITED || colorRange == C.COLOR_RANGE_FULL || colorRange == Format.NO_VALUE
        // LINT.ThenChange(../C.java:color_range)
    }

    private fun isValidColorTransfer(colorTransfer: Int): Boolean {
        // LINT.IfChange(color_transfer)
        // C.COLOR_TRANSFER_GAMMA_2_2 is not valid because MediaCodec, and hence MediaFormat, do not
        // support them.
        return colorTransfer == C.COLOR_TRANSFER_LINEAR || colorTransfer == C.COLOR_TRANSFER_SDR || colorTransfer == C.COLOR_TRANSFER_SRGB || colorTransfer == C.COLOR_TRANSFER_ST2084 || colorTransfer == C.COLOR_TRANSFER_HLG || colorTransfer == Format.NO_VALUE
        // LINT.ThenChange(../C.java:color_transfer)
    }
}
