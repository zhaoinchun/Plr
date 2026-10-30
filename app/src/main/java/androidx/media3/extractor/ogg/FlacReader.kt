
package androidx.media3.extractor.ogg

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.FlacFrameReader.readFrameBlockSizeSamplesFromKey
import androidx.media3.extractor.FlacMetadataReader.readSeekTableMetadataBlock
import androidx.media3.extractor.FlacSeekTableSeekMap
import androidx.media3.extractor.FlacStreamMetadata
import androidx.media3.extractor.FlacStreamMetadata.SeekTable
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.flac.FlacConstants
import com.google.common.base.Preconditions
import java.util.Arrays

internal class FlacReader : StreamReader() {
    private var streamMetadata: FlacStreamMetadata? = null
    private var flacOggSeeker: FlacOggSeeker? = null

    override fun reset(headerData: Boolean) {
        super.reset(headerData)
        if (headerData) {
            streamMetadata = null
            flacOggSeeker = null
        }
    }

    override fun preparePayload(packet: ParsableByteArray): Long {
        if (!isAudioPacket(packet.data)) {
            return -1
        }
        return getFlacFrameBlockSize(packet).toLong()
    }

    override fun readHeaders(
        packet: ParsableByteArray,
        position: Long,
        setupData: SetupData
    ): Boolean {
        val data = packet.data
        var streamMetadata = this.streamMetadata
        if (streamMetadata == null) {
            streamMetadata = FlacStreamMetadata(data, 17)
            this.streamMetadata = streamMetadata
            val metadata = Arrays.copyOfRange(data, 9, packet.limit())
            val format = streamMetadata.getFormat(metadata,  null)
            setupData.format = format.buildUpon().setContainerMimeType(MimeTypes.AUDIO_OGG).build()
            return true
        }

        if ((data[0].toInt() and 0x7F) == FlacConstants.METADATA_TYPE_SEEK_TABLE) {
            val seekTable = readSeekTableMetadataBlock(packet)
            streamMetadata = streamMetadata.copyWithSeekTable(seekTable)
            this.streamMetadata = streamMetadata
            flacOggSeeker = FlacOggSeeker(streamMetadata, seekTable)
            return true
        }

        if (isAudioPacket(data)) {
            if (flacOggSeeker != null) {
                flacOggSeeker!!.setFirstFrameOffset(position)
                setupData.oggSeeker = flacOggSeeker
            }
            setupData.format!!
            return false
        }

        return true
    }

    private fun getFlacFrameBlockSize(packet: ParsableByteArray): Int {
        val blockSizeKey = (packet.data[2].toInt() and 0xFF) shr 4
        if (blockSizeKey == 6 || blockSizeKey == 7) {
            // Skip the sample number.
            packet.skipBytes(FRAME_HEADER_SAMPLE_NUMBER_OFFSET)
            packet.readUtf8EncodedLong()
        }
        val result = readFrameBlockSizeSamplesFromKey(packet, blockSizeKey)
        packet.setPosition(0)
        return result
    }

    private class FlacOggSeeker(
        private val streamMetadata: FlacStreamMetadata,
        private val seekTable: SeekTable
    ) : OggSeeker {
        private var firstFrameOffset: Long
        private var pendingSeekGranule: Long

        init {
            firstFrameOffset = -1
            pendingSeekGranule = -1
        }

        fun setFirstFrameOffset(firstFrameOffset: Long) {
            this.firstFrameOffset = firstFrameOffset
        }

        override fun read(input: ExtractorInput): Long {
            if (pendingSeekGranule >= 0) {
                val result = -(pendingSeekGranule + 2)
                pendingSeekGranule = -1
                return result
            }
            return -1
        }

        override fun startSeek(targetGranule: Long) {
            val seekPointGranules = seekTable.pointSampleNumbers
            val index =
                Util.binarySearchFloor(
                    seekPointGranules!!,
                    targetGranule,  
                    true,  
                    true
                )
            pendingSeekGranule = seekPointGranules[index]
        }

        override fun createSeekMap(): SeekMap {
            Preconditions.checkState(firstFrameOffset != -1L)
            return FlacSeekTableSeekMap(streamMetadata, firstFrameOffset)
        }
    }

    companion object {
        private val AUDIO_PACKET_TYPE = 0xFF.toByte()

        private const val FRAME_HEADER_SAMPLE_NUMBER_OFFSET = 4

        fun verifyBitstreamType(data: ParsableByteArray): Boolean {
            return data.bytesLeft() >= 5 && data.readUnsignedByte() == 0x7F &&  // packet type
                    data.readUnsignedInt() == 0x464C4143L // ASCII signature "FLAC"
        }

        private fun isAudioPacket(data: ByteArray): Boolean {
            return data[0] == AUDIO_PACKET_TYPE
        }
    }
}
