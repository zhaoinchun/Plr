
package androidx.media3.extractor.flv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.AacUtil.parseAudioSpecificConfig
import androidx.media3.extractor.TrackOutput

internal class AudioTagPayloadReader(output: TrackOutput) : TagPayloadReader(output) {
    // State variables
    private var hasParsedAudioDataHeader = false
    private var hasOutputFormat = false
    private var audioFormat = 0

    override fun seek() {
        // Do nothing.
    }

    @Throws(UnsupportedFormatException::class)
    override fun parseHeader(data: ParsableByteArray): Boolean {
        if (!hasParsedAudioDataHeader) {
            val header = data.readUnsignedByte()
            audioFormat = (header shr 4) and 0x0F
            if (audioFormat == AUDIO_FORMAT_MP3) {
                val sampleRateIndex = (header shr 2) and 0x03
                val sampleRate: Int = AUDIO_SAMPLING_RATE_TABLE[sampleRateIndex]
                val format =
                    Format.Builder()
                        .setContainerMimeType(MimeTypes.VIDEO_FLV)
                        .setSampleMimeType(MimeTypes.AUDIO_MPEG)
                        .setChannelCount(1)
                        .setSampleRate(sampleRate)
                        .build()
                output.format(format)
                hasOutputFormat = true
            } else if (audioFormat == AUDIO_FORMAT_ALAW || audioFormat == AUDIO_FORMAT_ULAW) {
                val mimeType =
                    if (audioFormat == AUDIO_FORMAT_ALAW) MimeTypes.AUDIO_ALAW else MimeTypes.AUDIO_MLAW
                val format =
                    Format.Builder()
                        .setContainerMimeType(MimeTypes.VIDEO_FLV)
                        .setSampleMimeType(mimeType)
                        .setChannelCount(1)
                        .setSampleRate(8000)
                        .build()
                output.format(format)
                hasOutputFormat = true
            } else if (audioFormat != AUDIO_FORMAT_AAC) {
                throw UnsupportedFormatException("Audio format not supported: " + audioFormat)
            }
            hasParsedAudioDataHeader = true
        } else {
            // Skip header if it was parsed previously.
            data.skipBytes(1)
        }
        return true
    }

    @Throws(ParserException::class)
    override fun parsePayload(data: ParsableByteArray, timeUs: Long): Boolean {
        if (audioFormat == AUDIO_FORMAT_MP3) {
            val sampleSize = data.bytesLeft()
            output.sampleData(data, sampleSize)
            output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, sampleSize, 0, null)
            return true
        } else {
            val packetType = data.readUnsignedByte()
            if (packetType == AAC_PACKET_TYPE_SEQUENCE_HEADER && !hasOutputFormat) {
                // Parse the sequence header.
                val audioSpecificConfig = ByteArray(data.bytesLeft())
                data.readBytes(audioSpecificConfig, 0, audioSpecificConfig.size)
                val aacConfig = parseAudioSpecificConfig(audioSpecificConfig)
                val format =
                    Format.Builder()
                        .setContainerMimeType(MimeTypes.VIDEO_FLV)
                        .setSampleMimeType(MimeTypes.AUDIO_AAC)
                        .setCodecs(aacConfig.codecs)
                        .setChannelCount(aacConfig.channelCount)
                        .setSampleRate(aacConfig.sampleRateHz)
                        .setInitializationData(mutableListOf<ByteArray>(audioSpecificConfig))
                        .build()
                output.format(format)
                hasOutputFormat = true
                return false
            } else if (audioFormat != AUDIO_FORMAT_AAC || packetType == AAC_PACKET_TYPE_AAC_RAW) {
                val sampleSize = data.bytesLeft()
                output.sampleData(data, sampleSize)
                output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, sampleSize, 0, null)
                return true
            } else {
                return false
            }
        }
    }

    companion object {
        private const val AUDIO_FORMAT_MP3 = 2
        private const val AUDIO_FORMAT_ALAW = 7
        private const val AUDIO_FORMAT_ULAW = 8
        private const val AUDIO_FORMAT_AAC = 10

        private const val AAC_PACKET_TYPE_SEQUENCE_HEADER = 0
        private const val AAC_PACKET_TYPE_AAC_RAW = 1

        private val AUDIO_SAMPLING_RATE_TABLE = intArrayOf(5512, 11025, 22050, 44100)
    }
}
