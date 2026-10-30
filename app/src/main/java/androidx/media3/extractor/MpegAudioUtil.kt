package androidx.media3.extractor

import androidx.media3.common.C
import androidx.media3.common.MimeTypes

object MpegAudioUtil {
        fun getFrameSize(headerData: Int): Int {
        if (!isMagicPresent(headerData)) {
            return C.LENGTH_UNSET
        }

        val version = (headerData ushr 19) and 3
        if (version == 1) {
            return C.LENGTH_UNSET
        }

        val layer = (headerData ushr 17) and 3
        if (layer == 0) {
            return C.LENGTH_UNSET
        }

        val bitrateIndex = (headerData ushr 12) and 15
        if (bitrateIndex == 0 || bitrateIndex == 0xF) {
            // Disallow "free" bitrate.
            return C.LENGTH_UNSET
        }

        val samplingRateIndex = (headerData ushr 10) and 3
        if (samplingRateIndex == 3) {
            return C.LENGTH_UNSET
        }

        var samplingRate = SAMPLING_RATE_V1[samplingRateIndex]
        if (version == 2) {
            // Version 2
            samplingRate /= 2
        } else if (version == 0) {
            // Version 2.5
            samplingRate /= 4
        }

        val bitrate: Int
        val padding = (headerData ushr 9) and 1
        if (layer == 3) {
            // Layer I (layer == 3)
            bitrate =
                if (version == 3) BITRATE_V1_L1[bitrateIndex - 1] else BITRATE_V2_L1[bitrateIndex - 1]
            return (12 * bitrate / samplingRate + padding) * 4
        } else {
            // Layer II (layer == 2) or III (layer == 1)
            if (version == 3) {
                bitrate =
                    if (layer == 2) BITRATE_V1_L2[bitrateIndex - 1] else BITRATE_V1_L3[bitrateIndex - 1]
            } else {
                // Version 2 or 2.5.
                bitrate = BITRATE_V2[bitrateIndex - 1]
            }
        }

        if (version == 3) {
            // Version 1
            return 144 * bitrate / samplingRate + padding
        } else {
            // Version 2 or 2.5
            return (if (layer == 1) 72 else 144) * bitrate / samplingRate + padding
        }
    }

    fun parseMpegAudioFrameSampleCount(headerData: Int): Int {
        if (!isMagicPresent(headerData)) {
            return C.LENGTH_UNSET
        }

        val version = (headerData ushr 19) and 3
        if (version == 1) {
            return C.LENGTH_UNSET
        }

        val layer = (headerData ushr 17) and 3
        if (layer == 0) {
            return C.LENGTH_UNSET
        }

        // Those header values are not used but are checked for consistency with the other methods
        val bitrateIndex = (headerData ushr 12) and 15
        val samplingRateIndex = (headerData ushr 10) and 3
        if (bitrateIndex == 0 || bitrateIndex == 0xF || samplingRateIndex == 3) {
            return C.LENGTH_UNSET
        }

        return getFrameSizeInSamples(version, layer)
    }

    const val MAX_FRAME_SIZE_BYTES: Int = 4096

    val MAX_RATE_BYTES_PER_SECOND: Int = 320 * 1000 / 8

    private val MIME_TYPE_BY_LAYER: Array<String> =
        arrayOf<String>(MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2, MimeTypes.AUDIO_MPEG)
    private val SAMPLING_RATE_V1 = intArrayOf(44100, 48000, 32000)
    private val BITRATE_V1_L1 = intArrayOf(
        32000, 64000, 96000, 128000, 160000, 192000, 224000, 256000, 288000, 320000, 352000, 384000,
        416000, 448000
    )
    private val BITRATE_V2_L1 = intArrayOf(
        32000, 48000, 56000, 64000, 80000, 96000, 112000, 128000, 144000, 160000, 176000, 192000,
        224000, 256000
    )
    private val BITRATE_V1_L2 = intArrayOf(
        32000, 48000, 56000, 64000, 80000, 96000, 112000, 128000, 160000, 192000, 224000, 256000,
        320000, 384000
    )
    private val BITRATE_V1_L3 = intArrayOf(
        32000,
        40000,
        48000,
        56000,
        64000,
        80000,
        96000,
        112000,
        128000,
        160000,
        192000,
        224000,
        256000,
        320000
    )
    private val BITRATE_V2 = intArrayOf(
        8000, 16000, 24000, 32000, 40000, 48000, 56000, 64000, 80000, 96000, 112000, 128000, 144000,
        160000
    )

    private const val SAMPLES_PER_FRAME_L1 = 384
    private const val SAMPLES_PER_FRAME_L2 = 1152
    private const val SAMPLES_PER_FRAME_L3_V1 = 1152
    private const val SAMPLES_PER_FRAME_L3_V2 = 576

    private fun isMagicPresent(headerData: Int): Boolean {
        return (headerData and -0x200000) == -0x200000
    }

    private fun getFrameSizeInSamples(version: Int, layer: Int): Int {
        when (layer) {
            1 -> return if (version == 3) SAMPLES_PER_FRAME_L3_V1 else SAMPLES_PER_FRAME_L3_V2 // Layer III
            2 -> return SAMPLES_PER_FRAME_L2 // Layer II
            3 -> return SAMPLES_PER_FRAME_L1 // Layer I
            else -> throw IllegalArgumentException()
        }
    }

    class Header {
        var version: Int = 0
        var mimeType: String? = null
        var frameSize: Int = 0
        var sampleRate: Int = 0
        var channels: Int = 0
        var bitrate: Int = 0
        var samplesPerFrame: Int = 0
        constructor()

        constructor(header: Header) {
            this.version = header.version
            this.mimeType = header.mimeType
            this.frameSize = header.frameSize
            this.sampleRate = header.sampleRate
            this.channels = header.channels
            this.bitrate = header.bitrate
            this.samplesPerFrame = header.samplesPerFrame
        }

        fun setForHeaderData(headerData: Int): Boolean {
            if (!isMagicPresent(headerData)) {
                return false
            }

            val version = (headerData ushr 19) and 3
            if (version == 1) {
                return false
            }

            val layer = (headerData ushr 17) and 3
            if (layer == 0) {
                return false
            }

            val bitrateIndex = (headerData ushr 12) and 15
            if (bitrateIndex == 0 || bitrateIndex == 0xF) {
                // Disallow "free" bitrate.
                return false
            }

            val samplingRateIndex = (headerData ushr 10) and 3
            if (samplingRateIndex == 3) {
                return false
            }

            this.version = version
            mimeType = MIME_TYPE_BY_LAYER[3 - layer]
            sampleRate = SAMPLING_RATE_V1[samplingRateIndex]
            if (version == 2) {
                // Version 2
                sampleRate /= 2
            } else if (version == 0) {
                // Version 2.5
                sampleRate /= 4
            }
            val padding = (headerData ushr 9) and 1
            samplesPerFrame = getFrameSizeInSamples(version, layer)
            if (layer == 3) {
                // Layer I (layer == 3)
                bitrate =
                    if (version == 3) BITRATE_V1_L1[bitrateIndex - 1] else BITRATE_V2_L1[bitrateIndex - 1]
                frameSize = (12 * bitrate / sampleRate + padding) * 4
            } else {
                // Layer II (layer == 2) or III (layer == 1)
                if (version == 3) {
                    // Version 1
                    bitrate =
                        if (layer == 2) BITRATE_V1_L2[bitrateIndex - 1] else BITRATE_V1_L3[bitrateIndex - 1]
                    frameSize = 144 * bitrate / sampleRate + padding
                } else {
                    // Version 2 or 2.5.
                    bitrate = BITRATE_V2[bitrateIndex - 1]
                    frameSize = (if (layer == 1) 72 else 144) * bitrate / sampleRate + padding
                }
            }
            channels = if (((headerData shr 6) and 3) == 3) 1 else 2
            return true
        }
    }
}
