package androidx.media3.extractor.avi

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util.getPcmEncoding
import androidx.media3.common.util.Util.getTrackTypeString

internal class StreamFormatChunk(val format: Format) : AviChunk {
    override val type = AviExtractor.FOURCC_strf

    companion object {
        private const val TAG = "StreamFormatChunk"

        fun parseFrom(trackType: Int, body: ParsableByteArray): AviChunk? {
            if (trackType == C.TRACK_TYPE_VIDEO) {
                return parseBitmapInfoHeader(body)
            } else if (trackType == C.TRACK_TYPE_AUDIO) {
                return parseWaveFormatEx(body)
            } else {
                w(
                    TAG,
                    "Ignoring strf box for unsupported track type: " + getTrackTypeString(trackType)
                )
                return null
            }
        }

        private fun parseBitmapInfoHeader(body: ParsableByteArray): AviChunk? {
            body.skipBytes(4) // biSize.
            val width = body.readLittleEndianInt()
            val height = body.readLittleEndianInt()
            body.skipBytes(4) // biPlanes (2 bytes), biBitCount (2 bytes).
            val compression = body.readLittleEndianInt()
            val mimeType: String? = getMimeTypeFromCompression(compression)
            if (mimeType == null) {
                w(TAG, "Ignoring track with unsupported compression " + compression)
                return null
            }
            val formatBuilder = Format.Builder()
            formatBuilder.setWidth(width).setHeight(height).setSampleMimeType(mimeType)
            return StreamFormatChunk(formatBuilder.build())
        }

        // Syntax defined by the WAVEFORMATEX structure. See
        // https://docs.microsoft.com/en-us/previous-versions/dd757713(v=vs.85).
        private fun parseWaveFormatEx(body: ParsableByteArray): AviChunk? {
            val formatTag = body.readLittleEndianUnsignedShort()
            val mimeType: String? = getMimeTypeFromTag(formatTag)
            if (mimeType == null) {
                w(TAG, "Ignoring track with unsupported format tag " + formatTag)
                return null
            }
            val channelCount = body.readLittleEndianUnsignedShort()
            val samplesPerSecond = body.readLittleEndianInt()
            body.skipBytes(6) // averageBytesPerSecond (4 bytes), nBlockAlign (2 bytes).
            val bitsPerSample = body.readLittleEndianUnsignedShort()
            val pcmEncoding = getPcmEncoding(bitsPerSample)
            val cbSize = if (body.bytesLeft() > 0) body.readLittleEndianUnsignedShort() else 0
            val formatBuilder = Format.Builder()
            formatBuilder
                .setSampleMimeType(mimeType)
                .setChannelCount(channelCount)
                .setSampleRate(samplesPerSecond)
            if (mimeType == MimeTypes.AUDIO_RAW && pcmEncoding != C.ENCODING_INVALID) {
                formatBuilder.setPcmEncoding(pcmEncoding)
            }
            if (mimeType == MimeTypes.AUDIO_AAC && cbSize > 0) {
                val codecData = ByteArray(cbSize)
                body.readBytes(codecData,  0, codecData.size)
                formatBuilder.setInitializationData(listOf(codecData))
            }
            return StreamFormatChunk(formatBuilder.build())
        }

        private fun getMimeTypeFromTag(tag: Int): String? {
            when (tag) {
                0x1 -> return MimeTypes.AUDIO_RAW
                0x55 -> return MimeTypes.AUDIO_MPEG
                0xff -> return MimeTypes.AUDIO_AAC
                0x2000 -> return MimeTypes.AUDIO_AC3
                0x2001 -> return MimeTypes.AUDIO_DTS
                else -> return null
            }
        }

        private fun getMimeTypeFromCompression(compression: Int): String? {
            when (compression) {
                0x3234504d -> return MimeTypes.VIDEO_MP42
                0x3334504d -> return MimeTypes.VIDEO_MP43
                0x34363248, 0x31637661, 0x31435641 -> return MimeTypes.VIDEO_H264
                0x44495633, 0x78766964, 0x58564944, 0x30355844, 0x34504d46, 0x64697678, 0x44495658 -> return MimeTypes.VIDEO_MP4V
                0x47504a4d, 0x67706a6d -> return MimeTypes.VIDEO_MJPEG
                else -> return null
            }
        }
    }
}
