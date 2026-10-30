
package androidx.media3.extractor

import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableBitArray

import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.getPcmEncoding
import androidx.media3.extractor.metadata.flac.PictureFrame

class FlacStreamMetadata {
    
    class SeekTable(
      
       val pointSampleNumbers: LongArray?,
      
        val pointOffsets: LongArray?
    )

    val minBlockSizeSamples: Int

    val maxBlockSizeSamples: Int

    val minFrameSize: Int

    val maxFrameSize: Int

    val sampleRate: Int

    val sampleRateLookupKey: Int

    val channels: Int

    val bitsPerSample: Int

    val bitsPerSampleLookupKey: Int

    val totalSamples: Long

    val seekTable: SeekTable?

    private val metadata: Metadata?

    constructor(data: ByteArray, offset: Int) {
        val scratch = ParsableBitArray(data)
        scratch.position=(offset * 8)
        minBlockSizeSamples = scratch.readBits(16)
        maxBlockSizeSamples = scratch.readBits(16)
        minFrameSize = scratch.readBits(24)
        maxFrameSize = scratch.readBits(24)
        sampleRate = scratch.readBits(20)
        sampleRateLookupKey = getSampleRateLookupKey(sampleRate)
        channels = scratch.readBits(3) + 1
        bitsPerSample = scratch.readBits(5) + 1
        bitsPerSampleLookupKey = getBitsPerSampleLookupKey(bitsPerSample)
        totalSamples = scratch.readBitsToLong(36)
        seekTable = null
        metadata = null
    }

    // Used in native code.
    constructor(
        minBlockSizeSamples: Int,
        maxBlockSizeSamples: Int,
        minFrameSize: Int,
        maxFrameSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        totalSamples: Long,
        vorbisComments: ArrayList<String>,
        pictureFrames: ArrayList<PictureFrame>
    ) : this(
        minBlockSizeSamples,
        maxBlockSizeSamples,
        minFrameSize,
        maxFrameSize,
        sampleRate,
        channels,
        bitsPerSample,
        totalSamples,  
        null,
        concatenateVorbisMetadata(vorbisComments, pictureFrames)
    )

    @VisibleForTesting
    internal constructor(
        minBlockSizeSamples: Int,
        maxBlockSizeSamples: Int,
        minFrameSize: Int,
        maxFrameSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        totalSamples: Long,
        seekTable: SeekTable?,
        metadata: Metadata?
    ) {
        this.minBlockSizeSamples = minBlockSizeSamples
        this.maxBlockSizeSamples = maxBlockSizeSamples
        this.minFrameSize = minFrameSize
        this.maxFrameSize = maxFrameSize
        this.sampleRate = sampleRate
        this.sampleRateLookupKey = getSampleRateLookupKey(sampleRate)
        this.channels = channels
        this.bitsPerSample = bitsPerSample
        this.bitsPerSampleLookupKey = getBitsPerSampleLookupKey(bitsPerSample)
        this.totalSamples = totalSamples
        this.seekTable = seekTable
        this.metadata = metadata
    }

    val maxDecodedFrameSize: Int
        
        get() = maxBlockSizeSamples * channels * (bitsPerSample / 8)

    val decodedBitrate: Int
        
        get() = bitsPerSample * sampleRate * channels

    val durationUs: Long
        
        get() = if (totalSamples == 0L) C.TIME_UNSET else totalSamples * C.MICROS_PER_SECOND / sampleRate

    fun getSampleNumber(timeUs: Long): Long {
        val sampleNumber = (timeUs * sampleRate) / C.MICROS_PER_SECOND
        return constrainValue(sampleNumber,  0, totalSamples - 1)
    }

    val approxBytesPerFrame: Long
        
        get() {
            val approxBytesPerFrame: Long
            if (maxFrameSize > 0) {
                approxBytesPerFrame = (maxFrameSize.toLong() + minFrameSize) / 2 + 1
            } else {
                // Uses the stream's block-size if it's a known fixed block-size stream, otherwise uses the
                // default value for FLAC block-size, which is 4096.
                val blockSizeSamples =
                    (
                            if (minBlockSizeSamples == maxBlockSizeSamples && minBlockSizeSamples > 0)
                                minBlockSizeSamples
                            else
                                4096).toLong()
                approxBytesPerFrame = (blockSizeSamples * channels * bitsPerSample) / 8 + 64
            }
            return approxBytesPerFrame
        }

    fun getFormat(streamMarkerAndInfoBlock: ByteArray, id3Metadata: Metadata?): Format {
        // Set the last metadata block flag, ignore the other blocks.
        streamMarkerAndInfoBlock[4] = 0x80.toByte()
        val maxInputSize = if (maxFrameSize > 0) maxFrameSize else Format.NO_VALUE
        val metadataWithId3 = getMetadataCopyWithAppendedEntriesFrom(id3Metadata)
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setMaxInputSize(maxInputSize)
            .setChannelCount(channels)
            .setSampleRate(sampleRate)
            .setPcmEncoding(getPcmEncoding(bitsPerSample))
            .setInitializationData(mutableListOf<ByteArray>(streamMarkerAndInfoBlock))
            .setMetadata(metadataWithId3)
            .build()
    }

    fun getMetadataCopyWithAppendedEntriesFrom(other: Metadata?): Metadata? {
        return if (metadata == null) other else metadata.copyWithAppendedEntriesFrom(other)
    }

    fun copyWithSeekTable(seekTable: SeekTable?): FlacStreamMetadata {
        return FlacStreamMetadata(
            minBlockSizeSamples,
            maxBlockSizeSamples,
            minFrameSize,
            maxFrameSize,
            sampleRate,
            channels,
            bitsPerSample,
            totalSamples,
            seekTable,
            metadata
        )
    }

    fun copyWithVorbisComments(vorbisComments: MutableList<String>): FlacStreamMetadata {
        val appendedMetadata =
            getMetadataCopyWithAppendedEntriesFrom(VorbisUtil.parseVorbisComments(vorbisComments))
        return FlacStreamMetadata(
            minBlockSizeSamples,
            maxBlockSizeSamples,
            minFrameSize,
            maxFrameSize,
            sampleRate,
            channels,
            bitsPerSample,
            totalSamples,
            seekTable,
            appendedMetadata
        )
    }

    fun copyWithPictureFrames(pictureFrames: List<PictureFrame>): FlacStreamMetadata {
        val appendedMetadata = getMetadataCopyWithAppendedEntriesFrom(Metadata(pictureFrames))
        return FlacStreamMetadata(
            minBlockSizeSamples,
            maxBlockSizeSamples,
            minFrameSize,
            maxFrameSize,
            sampleRate,
            channels,
            bitsPerSample,
            totalSamples,
            seekTable,
            appendedMetadata
        )
    }

    companion object {
        private const val TAG = "FlacStreamMetadata"

        val NOT_IN_LOOKUP_TABLE: Int = -1

        private fun concatenateVorbisMetadata(
            vorbisComments: MutableList<String>, pictureFrames: MutableList<PictureFrame>
        ): Metadata? {
            val parsedVorbisComments = VorbisUtil.parseVorbisComments(vorbisComments)
            if (parsedVorbisComments == null && pictureFrames.isEmpty()) {
                return null
            }
            return Metadata(pictureFrames).copyWithAppendedEntriesFrom(parsedVorbisComments)
        }

        private fun getSampleRateLookupKey(sampleRate: Int): Int {
            when (sampleRate) {
                88200 -> return 1
                176400 -> return 2
                192000 -> return 3
                8000 -> return 4
                16000 -> return 5
                22050 -> return 6
                24000 -> return 7
                32000 -> return 8
                44100 -> return 9
                48000 -> return 10
                96000 -> return 11
                else -> return NOT_IN_LOOKUP_TABLE
            }
        }

        private fun getBitsPerSampleLookupKey(bitsPerSample: Int): Int {
            when (bitsPerSample) {
                8 -> return 1
                12 -> return 2
                16 -> return 4
                20 -> return 5
                24 -> return 6
                32 -> return 7
                else -> return NOT_IN_LOOKUP_TABLE
            }
        }
    }
}
