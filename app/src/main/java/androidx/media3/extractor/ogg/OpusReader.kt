
package androidx.media3.extractor.ogg

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.OpusUtil
import androidx.media3.container.OpusUtil.buildInitializationData
import androidx.media3.container.OpusUtil.getChannelCount
import androidx.media3.container.OpusUtil.getPacketDurationUs
import androidx.media3.container.VorbisUtil.readVorbisCommentHeader
import androidx.media3.extractor.VorbisUtil.parseVorbisComments

internal class OpusReader : StreamReader() {
    private var firstCommentHeaderSeen = false

    override fun reset(headerData: Boolean) {
        super.reset(headerData)
        if (headerData) {
            firstCommentHeaderSeen = false
        }
    }

    override fun preparePayload(packet: ParsableByteArray): Long {
        return convertTimeToGranule(getPacketDurationUs(packet.data))
    }

    @Throws(ParserException::class)
    override fun readHeaders(
        packet: ParsableByteArray,
        position: Long,
        setupData: SetupData
    ): Boolean {
        if (peekPacketStartsWith(packet, OPUS_ID_HEADER_SIGNATURE)) {
            val headerBytes = packet.data.copyOf(packet.limit())
            val channelCount = getChannelCount(headerBytes)
            val initializationData = buildInitializationData(headerBytes)

            if (setupData.format != null) {
                // setupData.format being non-null indicates we've already seen an ID header. Multiple ID
                // headers are not permitted by the Opus spec [1], but have been observed in real files [2],
                // so we just ignore all subsequent ones.
                // [1] https://datatracker.ietf.org/doc/html/rfc7845#section-3 and
                //     https://datatracker.ietf.org/doc/html/rfc7845#section-5
                // [2] https://github.com/google/ExoPlayer/issues/10038
                return true
            }
            setupData.format =
                Format.Builder()
                    .setContainerMimeType(MimeTypes.AUDIO_OGG)
                    .setSampleMimeType(MimeTypes.AUDIO_OPUS)
                    .setChannelCount(channelCount)
                    .setSampleRate(OpusUtil.SAMPLE_RATE)
                    .setInitializationData(initializationData)
                    .build()
            return true
        } else if (peekPacketStartsWith(packet, OPUS_COMMENT_HEADER_SIGNATURE)) {
            // The comment header must come immediately after the ID header, so the format will already
            // be populated: https://datatracker.ietf.org/doc/html/rfc7845#section-3
            setupData.format!!
            if (firstCommentHeaderSeen) {
                // Multiple comment headers are not permitted by the Opus spec [1], but have been observed
                // in real files [2], so we just ignore all subsequent ones.
                // [1] https://datatracker.ietf.org/doc/html/rfc7845#section-3 and
                //     https://datatracker.ietf.org/doc/html/rfc7845#section-5
                // [2] https://github.com/google/ExoPlayer/issues/10038
                return true
            }
            firstCommentHeaderSeen = true
            packet.skipBytes(OPUS_COMMENT_HEADER_SIGNATURE.size)
            val commentHeader =
                readVorbisCommentHeader(
                    packet,  false,  false
                )
            val vorbisMetadata =
                parseVorbisComments(commentHeader.comments.toList() )
            if (vorbisMetadata == null) {
                return true
            }
            setupData.format =
                setupData
                    .format!!
                    .buildUpon()
                    .setMetadata(vorbisMetadata.copyWithAppendedEntriesFrom(setupData.format!!.metadata))
                    .build()
            return true
        } else {
            // The ID header must come at the start of the file, so the format must already be populated:
            // https://datatracker.ietf.org/doc/html/rfc7845#section-3
            setupData.format!!
            return false
        }
    }

    companion object {
        private val OPUS_ID_HEADER_SIGNATURE = byteArrayOf(
            'O'.code.toByte(),
            'p'.code.toByte(),
            'u'.code.toByte(),
            's'.code.toByte(),
            'H'.code.toByte(),
            'e'.code.toByte(),
            'a'.code.toByte(),
            'd'.code.toByte()
        )
        private val OPUS_COMMENT_HEADER_SIGNATURE = byteArrayOf(
            'O'.code.toByte(),
            'p'.code.toByte(),
            'u'.code.toByte(),
            's'.code.toByte(),
            'T'.code.toByte(),
            'a'.code.toByte(),
            'g'.code.toByte(),
            's'.code.toByte()
        )

        fun verifyBitstreamType(data: ParsableByteArray): Boolean {
            return peekPacketStartsWith(data, OPUS_ID_HEADER_SIGNATURE)
        }

        private fun peekPacketStartsWith(
            packet: ParsableByteArray,
            expectedPrefix: ByteArray
        ): Boolean {
            if (packet.bytesLeft() < expectedPrefix.size) {
                return false
            }
            val startPosition = packet.getPosition()
            val header = ByteArray(expectedPrefix.size)
            packet.readBytes(header, 0, expectedPrefix.size)
            packet.setPosition(startPosition)
            return header.contentEquals(expectedPrefix)
        }
    }
}
