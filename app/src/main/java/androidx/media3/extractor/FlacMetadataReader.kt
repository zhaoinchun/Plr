
package androidx.media3.extractor

import androidx.media3.common.Metadata
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.container.VorbisUtil.readVorbisCommentHeader
import androidx.media3.extractor.FlacStreamMetadata.SeekTable
import androidx.media3.extractor.flac.FlacConstants
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import java.io.IOException

object FlacMetadataReader {
    private const val STREAM_MARKER = 0x664C6143 // ASCII for "fLaC"
    private const val SYNC_CODE = 0x3FFE
    private const val SEEK_POINT_SIZE = 18

    @Throws(IOException::class)
    fun peekId3Metadata(
        input: ExtractorInput, parseData: Boolean, ignoreArtwork: Boolean
    ): Metadata? {
        val id3FramePredicate =
            if (!parseData)
                Id3Decoder.NO_FRAMES_PREDICATE
            else
                (if (ignoreArtwork) Id3Decoder.NO_ARTWORK_PREDICATE else null)
        val id3Metadata =
            Id3Peeker().peekId3Data(input, id3FramePredicate,  0)
        return if (id3Metadata == null || id3Metadata.length() == 0) null else id3Metadata
    }

    @Throws(IOException::class)
    fun checkAndPeekStreamMarker(input: ExtractorInput): Boolean {
        val scratch = ParsableByteArray(FlacConstants.STREAM_MARKER_SIZE)
        input.peekFully(scratch.data, 0, FlacConstants.STREAM_MARKER_SIZE)
        return scratch.readUnsignedInt() == STREAM_MARKER.toLong()
    }

    @Throws(IOException::class)
    fun readId3Metadata(
        input: ExtractorInput, parseData: Boolean, ignoreArtwork: Boolean
    ): Metadata? {
        input.resetPeekPosition()
        val startingPeekPosition = input.peekPosition
        val id3Metadata = peekId3Metadata(input, parseData, ignoreArtwork)
        val peekedId3Bytes = (input.peekPosition - startingPeekPosition).toInt()
        input.skipFully(peekedId3Bytes)
        return id3Metadata
    }

    @Throws(IOException::class)
    fun readStreamMarker(input: ExtractorInput) {
        val scratch = ParsableByteArray(FlacConstants.STREAM_MARKER_SIZE)
        input.readFully(scratch.data, 0, FlacConstants.STREAM_MARKER_SIZE)
        if (scratch.readUnsignedInt() != STREAM_MARKER.toLong()) {
            throw ParserException.createForMalformedContainer(
                "Failed to read FLAC stream marker.",  null
            )
        }
    }

    @Throws(IOException::class)
    fun readMetadataBlock(
        input: ExtractorInput, metadataHolder: FlacStreamMetadataHolder, ignoreArtwork: Boolean
    ): Boolean {
        input.resetPeekPosition()
        val scratch = ParsableBitArray(ByteArray(4))
        input.peekFully(scratch.data, 0, FlacConstants.METADATA_BLOCK_HEADER_SIZE)

        val isLastMetadataBlock = scratch.readBit()
        val type = scratch.readBits(7)
        val length = FlacConstants.METADATA_BLOCK_HEADER_SIZE + scratch.readBits(24)
        if (type == FlacConstants.METADATA_TYPE_STREAM_INFO) {
            metadataHolder.flacStreamMetadata = readStreamInfoBlock(input)
        } else {
            val flacStreamMetadata = metadataHolder.flacStreamMetadata
            requireNotNull(flacStreamMetadata)
            if (type == FlacConstants.METADATA_TYPE_SEEK_TABLE) {
                val seekTable = readSeekTableMetadataBlock(input, length)
                metadataHolder.flacStreamMetadata = flacStreamMetadata.copyWithSeekTable(seekTable)
            } else if (type == FlacConstants.METADATA_TYPE_VORBIS_COMMENT) {
                val vorbisComments = readVorbisCommentMetadataBlock(input, length)
                metadataHolder.flacStreamMetadata =
                    flacStreamMetadata.copyWithVorbisComments(vorbisComments)
            } else if (type == FlacConstants.METADATA_TYPE_PICTURE) {
                if (ignoreArtwork) {
                    input.skipFully(length)
                } else {
                    val pictureBlock = ParsableByteArray(length)
                    input.readFully(pictureBlock.data, 0, length)
                    pictureBlock.skipBytes(FlacConstants.METADATA_BLOCK_HEADER_SIZE)
                    val pictureFrame = PictureFrame.fromPictureBlock(pictureBlock)
                    metadataHolder.flacStreamMetadata =
                        flacStreamMetadata.copyWithPictureFrames(
                            listOf(pictureFrame)
                        )
                }
            } else {
                input.skipFully(length)
            }
        }

        return isLastMetadataBlock
    }

    fun readSeekTableMetadataBlock(data: ParsableByteArray): SeekTable {
        data.skipBytes(1)
        val length = data.readUnsignedInt24()

        val seekTableEndPosition = data.getPosition().toLong() + length
        val seekPointCount = length / SEEK_POINT_SIZE
        var pointSampleNumbers = LongArray(seekPointCount)
        var pointOffsets = LongArray(seekPointCount)
        for (i in 0..<seekPointCount) {
            // The sample number is expected to fit in a signed long, except if it is a placeholder, in
            // which case its value is -1.
            val sampleNumber = data.readLong()
            if (sampleNumber == -1L) {
                pointSampleNumbers = pointSampleNumbers.copyOf(i)
                pointOffsets = pointOffsets.copyOf(i)
                break
            }
            pointSampleNumbers[i] = sampleNumber
            pointOffsets[i] = data.readLong()
            data.skipBytes(2)
        }

        data.skipBytes((seekTableEndPosition - data.getPosition()).toInt())
        return SeekTable(pointSampleNumbers, pointOffsets)
    }

    @Throws(IOException::class)
    fun getFrameStartMarker(input: ExtractorInput): Int {
        input.resetPeekPosition()
        val scratch = ParsableByteArray(2)
        input.peekFully(scratch.data, 0, 2)

        val frameStartMarker = scratch.readUnsignedShort()
        val syncCode = frameStartMarker shr 2
        if (syncCode != SYNC_CODE) {
            input.resetPeekPosition()
            throw ParserException.createForMalformedContainer(
                "First frame does not start with sync code.",  null
            )
        }

        input.resetPeekPosition()
        return frameStartMarker
    }

    @Throws(IOException::class)
    private fun readStreamInfoBlock(input: ExtractorInput): FlacStreamMetadata {
        val scratchData = ByteArray(FlacConstants.STREAM_INFO_BLOCK_SIZE)
        input.readFully(scratchData, 0, FlacConstants.STREAM_INFO_BLOCK_SIZE)
        return FlacStreamMetadata(
            scratchData,  FlacConstants.METADATA_BLOCK_HEADER_SIZE
        )
    }

    @Throws(IOException::class)
    private fun readSeekTableMetadataBlock(
        input: ExtractorInput, length: Int
    ): SeekTable {
        val scratch = ParsableByteArray(length)
        input.readFully(scratch.data, 0, length)
        return readSeekTableMetadataBlock(scratch)
    }

    @Throws(IOException::class)
    private fun readVorbisCommentMetadataBlock(
        input: ExtractorInput,
        length: Int
    ): MutableList<String> {
        val scratch = ParsableByteArray(length)
        input.readFully(scratch.data, 0, length)
        scratch.skipBytes(FlacConstants.METADATA_BLOCK_HEADER_SIZE)
        val commentHeader =
            readVorbisCommentHeader(
                scratch,  false,  false
            )
        return commentHeader.comments.toMutableList()
    }

    class FlacStreamMetadataHolder(
         var flacStreamMetadata: FlacStreamMetadata?
    )
}
