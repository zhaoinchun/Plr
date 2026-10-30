package androidx.media3.extractor.amr

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.getUtf8Bytes
import androidx.media3.extractor.ConstantBitrateSeekMap
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.IndexSeekMap
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekMap.Unseekable
import androidx.media3.extractor.TrackOutput
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.io.EOFException
import java.io.IOException
import kotlin.math.abs

class AmrExtractor  constructor(flags: @Flags Int = 0) : Extractor {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true,
        value = [FLAG_ENABLE_CONSTANT_BITRATE_SEEKING, FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS, FLAG_ENABLE_INDEX_SEEKING]
    )
    annotation class Flags

    private val scratch: ByteArray
    private val flags: @Flags Int
    private val skippingTrackOutput: TrackOutput

    private var isWideBand = false
    private var currentSampleTimeUs: Long = 0
    private var currentSampleSize = 0
    private var currentSampleBytesRemaining = 0
    private var firstSamplePosition: Long = 0
    private var firstSampleSize: Int
    private var numSamplesWithSameSize = 0
    private var timeOffsetUs: Long = 0

    private var extractorOutput:ExtractorOutput? = null
    private var realTrackOutput:TrackOutput? = null
    private var currentTrackOutput: TrackOutput // skippingTrackOutput or realTrackOutput.
    private var seekMap:SeekMap? = null
    private var isSeekInProgress = false
    private var seekTimeUs: Long = 0
    private var hasOutputFormat = false

    init {
        var flags = flags
        if ((flags and FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS) != 0) {
            flags = flags or FLAG_ENABLE_CONSTANT_BITRATE_SEEKING
        }
        this.flags = flags
        scratch = ByteArray(1)
        firstSampleSize = C.LENGTH_UNSET
        skippingTrackOutput = DiscardingTrackOutput()
        currentTrackOutput = skippingTrackOutput
    }

    // Extractor implementation.
    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        return readAmrHeader(input)
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        realTrackOutput = output.track( 0, C.TRACK_TYPE_AUDIO)
        currentTrackOutput = realTrackOutput!!
        output.endTracks()
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        assertInitialized()
        if (input.position == 0L) {
            if (!readAmrHeader(input)) {
                throw ParserException.createForMalformedContainer(
                    "Could not find AMR header.",  null
                )
            }
        }
        maybeOutputFormat()
        val sampleReadResult = readSample(input)
        maybeOutputSeekMap(input.length, sampleReadResult)
        if (sampleReadResult == Extractor.RESULT_END_OF_INPUT && seekMap is IndexSeekMap) {
            // Set exact duration when end of input is reached.
            val durationUs = timeOffsetUs + currentSampleTimeUs
            (seekMap as IndexSeekMap).durationUs = (durationUs)
            extractorOutput!!.seekMap(seekMap!!)
            realTrackOutput!!.durationUs(durationUs)
        }
        return sampleReadResult
    }

    override fun seek(position: Long, timeUs: Long) {
        currentSampleTimeUs = 0
        currentSampleSize = 0
        currentSampleBytesRemaining = 0
        seekTimeUs = timeUs
        if (seekMap is IndexSeekMap) {
            timeOffsetUs = (seekMap as IndexSeekMap).getTimeUs(position)
            if (!isSeekTimeUsWithinRange(timeOffsetUs, seekTimeUs)) {
                isSeekInProgress = true
                currentTrackOutput = skippingTrackOutput
            }
        } else if (position != 0L && seekMap is ConstantBitrateSeekMap) {
            timeOffsetUs = (seekMap as ConstantBitrateSeekMap).getTimeUsAtPosition(position)
        } else {
            timeOffsetUs = 0
        }
    }

    override fun release() {
        // Do nothing
    }

    // Internal methods.
    @Throws(IOException::class)
    private fun readAmrHeader(input: ExtractorInput): Boolean {
        if (Companion.peekAmrSignature(input, amrSignatureNb!!)) {
            isWideBand = false
            input.skipFully(amrSignatureNb.size)
            return true
        } else if (Companion.peekAmrSignature(input, amrSignatureWb!!)) {
            isWideBand = true
            input.skipFully(amrSignatureWb.size)
            return true
        }
        return false
    }

    @RequiresNonNull("realTrackOutput")
    private fun maybeOutputFormat() {
        if (!hasOutputFormat) {
            hasOutputFormat = true
            val containerMimeType = if (isWideBand) MimeTypes.AUDIO_AMR_WB else MimeTypes.AUDIO_AMR
            val sampleMimeType = if (isWideBand) MimeTypes.AUDIO_AMR_WB else MimeTypes.AUDIO_AMR_NB
            val sampleRate: Int = if (isWideBand) SAMPLE_RATE_WB else SAMPLE_RATE_NB
            // Theoretical maximum frame size for a AMR frame.
            val maxInputSize: Int =
                if (isWideBand) frameSizeBytesByTypeWb[8] else frameSizeBytesByTypeNb[7]
            realTrackOutput!!.format(
                Format.Builder()
                    .setContainerMimeType(containerMimeType)
                    .setSampleMimeType(sampleMimeType)
                    .setMaxInputSize(maxInputSize)
                    .setChannelCount(1)
                    .setSampleRate(sampleRate)
                    .build()
            )
        }
    }

    @RequiresNonNull("realTrackOutput")
    @Throws(IOException::class)
    private fun readSample(extractorInput: ExtractorInput): Int {
        if (currentSampleBytesRemaining == 0) {
            try {
                currentSampleSize = peekNextSampleSize(extractorInput)
            } catch (e: EOFException) {
                return Extractor.RESULT_END_OF_INPUT
            }
            currentSampleBytesRemaining = currentSampleSize
            if (firstSampleSize == C.LENGTH_UNSET) {
                firstSamplePosition = extractorInput.position
                firstSampleSize = currentSampleSize
            }
            if (firstSampleSize == currentSampleSize) {
                numSamplesWithSameSize++
            }
            if (seekMap is IndexSeekMap) {
                val indexSeekMap = seekMap as IndexSeekMap
                // Add seek point corresponding to the next frame instead of the current one to be able to
                // start writing to the realTrackOutput on time when a seek is in progress.
                val nextSampleTimeUs: Long =
                    timeOffsetUs + currentSampleTimeUs + SAMPLE_TIME_PER_FRAME_US
                val nextSamplePosition = extractorInput.position + currentSampleSize
                if (!indexSeekMap.isTimeUsInIndex(
                        nextSampleTimeUs,  C.MICROS_PER_SECOND / 10
                    )
                ) {
                    indexSeekMap.addSeekPoint(nextSampleTimeUs, nextSamplePosition)
                }
                if (isSeekInProgress && isSeekTimeUsWithinRange(nextSampleTimeUs, seekTimeUs)) {
                    isSeekInProgress = false
                    currentTrackOutput = realTrackOutput!!
                }
            }
        }

        val bytesAppended =
            currentTrackOutput.sampleData(
                extractorInput, currentSampleBytesRemaining,  true
            )
        if (bytesAppended == C.RESULT_END_OF_INPUT) {
            return Extractor.RESULT_END_OF_INPUT
        }
        currentSampleBytesRemaining -= bytesAppended
        if (currentSampleBytesRemaining > 0) {
            return Extractor.RESULT_CONTINUE
        }

        currentTrackOutput.sampleMetadata(
            timeOffsetUs + currentSampleTimeUs,
            C.BUFFER_FLAG_KEY_FRAME,
            currentSampleSize,  
            0,  
            null
        )
        currentSampleTimeUs += SAMPLE_TIME_PER_FRAME_US.toLong()
        return Extractor.RESULT_CONTINUE
    }

    @Throws(IOException::class)
    private fun peekNextSampleSize(extractorInput: ExtractorInput): Int {
        extractorInput.resetPeekPosition()
        extractorInput.peekFully(scratch,  0,  1)

        val frameHeader = scratch[0]
        if ((frameHeader.toInt() and 0x83) > 0) {
            // The padding bits are at bit-1 positions in the following pattern: 1000 0011
            // Padding bits must be 0.
            throw ParserException.createForMalformedContainer(
                "Invalid padding bits for frame header " + frameHeader,  null
            )
        }

        val frameType = (frameHeader.toInt() shr 3) and 0x0f
        return getFrameSizeInBytes(frameType)
    }

    @Throws(ParserException::class)
    private fun getFrameSizeInBytes(frameType: Int): Int {
        if (!isValidFrameType(frameType)) {
            throw ParserException.createForMalformedContainer(
                "Illegal AMR " + (if (isWideBand) "WB" else "NB") + " frame type " + frameType,  
                null
            )
        }

        return if (isWideBand) frameSizeBytesByTypeWb[frameType] else frameSizeBytesByTypeNb[frameType]
    }

    private fun isValidFrameType(frameType: Int): Boolean {
        return frameType >= 0 && frameType <= 15 && (isWideBandValidFrameType(frameType) || isNarrowBandValidFrameType(
            frameType
        ))
    }

    private fun isWideBandValidFrameType(frameType: Int): Boolean {
        // For wide band, type 10-13 are for future use.
        return isWideBand && (frameType < 10 || frameType > 13)
    }

    private fun isNarrowBandValidFrameType(frameType: Int): Boolean {
        // For narrow band, type 12-14 are for future use.
        return !isWideBand && (frameType < 12 || frameType > 14)
    }

    @RequiresNonNull("extractorOutput", "realTrackOutput")
    private fun maybeOutputSeekMap(inputLength: Long, sampleReadResult: Int) {
        if (seekMap != null) {
            return
        }

        if ((flags and FLAG_ENABLE_INDEX_SEEKING) != 0) {
            seekMap =
                IndexSeekMap(
                    listOf(firstSamplePosition),
                    listOf(0L),
                    C.TIME_UNSET
                )
        } else if ((flags and FLAG_ENABLE_CONSTANT_BITRATE_SEEKING) == 0
            || (firstSampleSize != C.LENGTH_UNSET && firstSampleSize != currentSampleSize)
        ) {
            seekMap = Unseekable(C.TIME_UNSET)
        } else if (numSamplesWithSameSize >= NUM_SAME_SIZE_CONSTANT_BIT_RATE_THRESHOLD
            || sampleReadResult == Extractor.RESULT_END_OF_INPUT
        ) {
            seekMap =
                getConstantBitrateSeekMap(
                    inputLength, (flags and FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS) != 0
                )
            realTrackOutput!!.durationUs(seekMap!!.durationUs)
        }

        if (seekMap != null) {
            extractorOutput!!.seekMap(seekMap!!)
        }
    }

    private fun getConstantBitrateSeekMap(
        inputLength: Long,
        allowSeeksIfLengthUnknown: Boolean
    ): SeekMap {
        val bitrate: Int =
            getBitrateFromFrameSize(firstSampleSize, SAMPLE_TIME_PER_FRAME_US.toLong())
        return ConstantBitrateSeekMap(
            inputLength, firstSamplePosition, bitrate, firstSampleSize, allowSeeksIfLengthUnknown
        )
    }

    private fun assertInitialized() {
        realTrackOutput!!
        extractorOutput!!
    }

    private fun isSeekTimeUsWithinRange(timeUs: Long, seekTimeUs: Long): Boolean {
        return abs(seekTimeUs - timeUs) < SAMPLE_TIME_PER_FRAME_US
    }

    companion object {
        val FACTORY: ExtractorsFactory = object :ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> {
                return arrayOf<Extractor>(AmrExtractor())
            }
        }

        const val FLAG_ENABLE_CONSTANT_BITRATE_SEEKING: Int = 1

        const  val FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS: Int = 1 shl 1
        const val FLAG_ENABLE_INDEX_SEEKING: Int = 1 shl 2
        private val frameSizeBytesByTypeNb = intArrayOf(
            13,
            14,
            16,
            18,
            20,
            21,
            27,
            32,
            6,  // AMR SID
            7,  // GSM-EFR SID
            6,  // TDMA-EFR SID
            6,  // PDC-EFR SID
            1,  // Future use
            1,  // Future use
            1,  // Future use
            1 // No data
        )

        private val frameSizeBytesByTypeWb = intArrayOf(
            18,
            24,
            33,
            37,
            41,
            47,
            51,
            59,
            61,
            6,  // AMR-WB SID
            1,  // Future use
            1,  // Future use
            1,  // Future use
            1,  // Future use
            1,  // speech lost
            1 // No data
        )

        private val amrSignatureNb = getUtf8Bytes("#!AMR\n")
        private val amrSignatureWb = getUtf8Bytes("#!AMR-WB\n")

        private const val NUM_SAME_SIZE_CONSTANT_BIT_RATE_THRESHOLD = 20

        private const val SAMPLE_RATE_WB = 16000
        private const val SAMPLE_RATE_NB = 8000
        private const val SAMPLE_TIME_PER_FRAME_US = 20000

        fun frameSizeBytesByTypeNb(frameType: Int): Int {
            return frameSizeBytesByTypeNb[frameType]
        }

        fun frameSizeBytesByTypeWb(frameType: Int): Int {
            return frameSizeBytesByTypeWb[frameType]
        }

        fun amrSignatureNb(): ByteArray {
            return amrSignatureNb!!.copyOf(amrSignatureNb.size)
        }

        fun amrSignatureWb(): ByteArray {
            return amrSignatureWb!!.copyOf(amrSignatureWb.size)
        }

        @Throws(IOException::class)
        private fun peekAmrSignature(input: ExtractorInput, amrSignature: ByteArray): Boolean {
            input.resetPeekPosition()
            val header = ByteArray(amrSignature.size)
            input.peekFully(header, 0, amrSignature.size)
            return header.contentEquals(amrSignature)
        }

        private fun getBitrateFromFrameSize(frameSize: Int, durationUsPerFrame: Long): Int {
            return ((frameSize * (C.BITS_PER_BYTE.toLong()) * C.MICROS_PER_SECOND) / durationUsPerFrame).toInt()
        }
    }
}
