package androidx.media3.extractor.wav

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.common.util.WavUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class WavExtractor : Extractor {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [STATE_READING_FILE_TYPE, STATE_READING_RF64_SAMPLE_DATA_SIZE, STATE_READING_FORMAT, STATE_SKIPPING_TO_SAMPLE_DATA, STATE_READING_SAMPLE_DATA
        ]
    )
    private annotation class State

    private lateinit var extractorOutput:ExtractorOutput
    private lateinit var trackOutput:TrackOutput
    private var state: @State Int
    private var rf64SampleDataSize: Long
    private var outputWriter:OutputWriter? = null
    private var dataStartPosition: Int
    private var dataEndPosition: Long

    init {
        state = STATE_READING_FILE_TYPE
        rf64SampleDataSize = C.LENGTH_UNSET.toLong()
        dataStartPosition = C.INDEX_UNSET
        dataEndPosition = C.INDEX_UNSET.toLong()
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        return WavHeaderReader.checkFileType(input)
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun seek(position: Long, timeUs: Long) {
        state = if (position == 0L) STATE_READING_FILE_TYPE else STATE_READING_SAMPLE_DATA
        if (outputWriter != null) {
            outputWriter!!.reset(timeUs)
        }
    }

    override fun release() {
        // Do nothing
    }

    @Throws(IOException::class)
    override fun read(
        input: ExtractorInput,
        seekPosition: PositionHolder
    ): @Extractor.ReadResult Int {
        assertInitialized()
        when (state) {
            STATE_READING_FILE_TYPE -> {
                readFileType(input)
                return Extractor.RESULT_CONTINUE
            }

            STATE_READING_RF64_SAMPLE_DATA_SIZE -> {
                readRf64SampleDataSize(input)
                return Extractor.RESULT_CONTINUE
            }

            STATE_READING_FORMAT -> {
                readFormat(input)
                return Extractor.RESULT_CONTINUE
            }

            STATE_SKIPPING_TO_SAMPLE_DATA -> {
                skipToSampleData(input)
                return Extractor.RESULT_CONTINUE
            }

            STATE_READING_SAMPLE_DATA -> return readSampleData(input)
            else -> throw IllegalStateException()
        }
    }

    private fun assertInitialized() {
        trackOutput!!
        extractorOutput!!
    }

    @Throws(IOException::class)
    private fun readFileType(input: ExtractorInput) {
        Preconditions.checkState(input.position == 0L)
        if (dataStartPosition != C.INDEX_UNSET) {
            input.skipFully(dataStartPosition)
            state = STATE_READING_SAMPLE_DATA
            return
        }
        if (!WavHeaderReader.checkFileType(input)) {
            // Should only happen if the media wasn't sniffed.
            throw ParserException.createForMalformedContainer(
                "Unsupported or unrecognized wav file type.",  null
            )
        }
        input.skipFully((input.peekPosition - input.position) as Int)
        state = STATE_READING_RF64_SAMPLE_DATA_SIZE
    }

    @Throws(IOException::class)
    private fun readRf64SampleDataSize(input: ExtractorInput) {
        rf64SampleDataSize = WavHeaderReader.readRf64SampleDataSize(input)
        state = STATE_READING_FORMAT
    }

    @RequiresNonNull("extractorOutput", "trackOutput")
    @Throws(IOException::class)
    private fun readFormat(input: ExtractorInput) {
        val wavFormat = WavHeaderReader.readFormat(input)
        if (wavFormat.formatType == WavUtil.TYPE_IMA_ADPCM) {
            outputWriter = ImaAdPcmOutputWriter(extractorOutput, trackOutput, wavFormat)
        } else if (wavFormat.formatType == WavUtil.TYPE_ALAW) {
            outputWriter =
                PassthroughOutputWriter(
                    extractorOutput,
                    trackOutput,
                    wavFormat,
                    MimeTypes.AUDIO_ALAW,  
                    Format.NO_VALUE
                )
        } else if (wavFormat.formatType == WavUtil.TYPE_MLAW) {
            outputWriter =
                PassthroughOutputWriter(
                    extractorOutput,
                    trackOutput,
                    wavFormat,
                    MimeTypes.AUDIO_MLAW,  
                    Format.NO_VALUE
                )
        } else {
            val pcmEncoding: @C.PcmEncoding Int =
                WavUtil.getPcmEncodingForType(wavFormat.formatType, wavFormat.bitsPerSample)
            if (pcmEncoding == C.ENCODING_INVALID) {
                throw ParserException.createForUnsupportedContainerFeature(
                    "Unsupported WAV format type: " + wavFormat.formatType
                )
            }
            outputWriter =
                PassthroughOutputWriter(
                    extractorOutput, trackOutput, wavFormat, MimeTypes.AUDIO_RAW, pcmEncoding
                )
        }
        state = STATE_SKIPPING_TO_SAMPLE_DATA
    }

    @Throws(IOException::class)
    private fun skipToSampleData(input: ExtractorInput) {
        val dataBounds = WavHeaderReader.skipToSampleData(input)
        dataStartPosition = dataBounds.first!!.toInt()
        var dataSize: Long = dataBounds.second!!
        if (rf64SampleDataSize != C.LENGTH_UNSET.toLong() && dataSize == 0xFFFFFFFFL) {
            // Following EBU - Tech 3306-2007, the data size indicated in the ds64 chunk should only be
            // used if the size of the data chunk is unset.
            dataSize = rf64SampleDataSize
        }
        dataEndPosition = dataStartPosition + dataSize
        val inputLength: Long = input.length
        if (inputLength != C.LENGTH_UNSET.toLong() && dataEndPosition > inputLength) {
            Log.w(TAG, "Data exceeds input length: " + dataEndPosition + ", " + inputLength)
            dataEndPosition = inputLength
        }
        outputWriter!!.init(dataStartPosition, dataEndPosition)
        state = STATE_READING_SAMPLE_DATA
    }

    @Throws(IOException::class)
    private fun readSampleData(input: ExtractorInput): @Extractor.ReadResult Int {
        Preconditions.checkState(dataEndPosition != C.INDEX_UNSET.toLong())
        val bytesLeft: Long = dataEndPosition - input.position
        return if (outputWriter!!.sampleData(input, bytesLeft))
            Extractor.RESULT_END_OF_INPUT
        else
            Extractor.RESULT_CONTINUE
    }

    private interface OutputWriter {
        
        fun reset(timeUs: Long)

        @Throws(ParserException::class)
        fun init(dataStartPosition: Int, dataEndPosition: Long)

        @Throws(IOException::class)
        fun sampleData(input: ExtractorInput, bytesLeft: Long): Boolean
    }

    private class PassthroughOutputWriter(
        extractorOutput: ExtractorOutput,
        trackOutput: TrackOutput,
        val wavFormat: WavFormat,
        mimeType: String?,
        pcmEncoding: @C.PcmEncoding Int
    ) : OutputWriter {
        private val extractorOutput: ExtractorOutput
        private val trackOutput: TrackOutput
        private val format: Format
        private val targetSampleSizeBytes: Int
        private var startTimeUs: Long = 0
        private var pendingOutputBytes = 0
        private var outputFrameCount: Long = 0

        init {
            this.extractorOutput = extractorOutput
            this.trackOutput = trackOutput

            val bytesPerFrame = wavFormat.numChannels * wavFormat.bitsPerSample / 8
            // Validate the WAV format. Blocks are expected to correspond to single frames.
            if (wavFormat.blockSize != bytesPerFrame) {
                throw ParserException.createForMalformedContainer(
                    "Expected block size: " + bytesPerFrame + "; got: " + wavFormat.blockSize,  
                    null
                )
            }

            val constantBitrate = wavFormat.frameRateHz * bytesPerFrame * 8
            targetSampleSizeBytes =
                max(
                    bytesPerFrame,
                    wavFormat.frameRateHz * bytesPerFrame / TARGET_SAMPLES_PER_SECOND
                )
            format =
                Format.Builder()
                    .setContainerMimeType(MimeTypes.AUDIO_WAV)
                    .setSampleMimeType(mimeType)
                    .setAverageBitrate(constantBitrate)
                    .setPeakBitrate(constantBitrate)
                    .setMaxInputSize(targetSampleSizeBytes)
                    .setChannelCount(wavFormat.numChannels)
                    .setChannelMask(WavUtil.mapToAudioFormatChannelMask(wavFormat.channelMask))
                    .setSampleRate(wavFormat.frameRateHz)
                    .setPcmEncoding(pcmEncoding)
                    .build()
        }

        override fun reset(timeUs: Long) {
            startTimeUs = timeUs
            pendingOutputBytes = 0
            outputFrameCount = 0
        }

        override fun init(dataStartPosition: Int, dataEndPosition: Long) {
            val wavSeekMap =
                WavSeekMap(
                    wavFormat,  
                    1,
                    dataStartPosition.toLong(),
                    dataEndPosition
                )
            extractorOutput.seekMap(wavSeekMap)
            trackOutput.format(format)
            trackOutput.durationUs(wavSeekMap.durationUs)
        }

        @Throws(IOException::class)
        override fun sampleData(input: ExtractorInput, bytesLeft: Long): Boolean {
            // Write sample data until we've reached the target sample size, or the end of the data.
            var bytesLeft = bytesLeft
            while (bytesLeft > 0 && pendingOutputBytes < targetSampleSizeBytes) {
                val bytesToRead =
                    min((targetSampleSizeBytes - pendingOutputBytes).toLong(), bytesLeft).toInt()
                val bytesAppended = trackOutput.sampleData(input, bytesToRead, true)
                if (bytesAppended == Extractor.RESULT_END_OF_INPUT) {
                    bytesLeft = 0
                } else {
                    pendingOutputBytes += bytesAppended
                    bytesLeft -= bytesAppended.toLong()
                }
            }

            // Write the corresponding sample metadata. Samples must be a whole number of frames. It's
            // possible that the number of pending output bytes is not a whole number of frames if the
            // stream ended unexpectedly.
            val bytesPerFrame = wavFormat.blockSize
            val pendingFrames = pendingOutputBytes / bytesPerFrame
            if (pendingFrames > 0) {
                val timeUs =
                    (startTimeUs
                            + Util.scaleLargeTimestamp(
                        outputFrameCount, C.MICROS_PER_SECOND, wavFormat.frameRateHz.toLong()
                    ))
                val size = pendingFrames * bytesPerFrame
                val offset = pendingOutputBytes - size
                trackOutput.sampleMetadata(
                    timeUs, C.BUFFER_FLAG_KEY_FRAME, size, offset,  null
                )
                outputFrameCount += pendingFrames.toLong()
                pendingOutputBytes = offset
            }

            return bytesLeft <= 0
        }
    }

    private class ImaAdPcmOutputWriter(
        extractorOutput: ExtractorOutput,
        trackOutput: TrackOutput,
        val wavFormat: WavFormat
    ) : OutputWriter {
        private val extractorOutput: ExtractorOutput
        private val trackOutput: TrackOutput
        private val framesPerBlock: Int
        private val inputData: ByteArray
        private val decodedData: ParsableByteArray
        private val targetSampleSizeFrames: Int
        private val format: Format
        private var pendingInputBytes = 0
        private var startTimeUs: Long = 0
        private var pendingOutputBytes = 0
        private var outputFrameCount: Long = 0

        init {
            this.extractorOutput = extractorOutput
            this.trackOutput = trackOutput
            targetSampleSizeFrames = max(1, wavFormat.frameRateHz / TARGET_SAMPLES_PER_SECOND)

            val scratch: ParsableByteArray = ParsableByteArray(wavFormat.extraData)
            scratch.readLittleEndianUnsignedShort()
            framesPerBlock = scratch.readLittleEndianUnsignedShort()

            val numChannels = wavFormat.numChannels
            // Validate the WAV format. This calculation is defined in "Microsoft Multimedia Standards
            // Update
            // - New Multimedia Types and Data Techniques" (1994). See the "IMA ADPCM Wave Type" and "DVI
            // ADPCM Wave Type" sections, and the calculation of wSamplesPerBlock in the latter.
            val expectedFramesPerBlock =
                ((((wavFormat.blockSize - (4 * numChannels)) * 8)
                        / (wavFormat.bitsPerSample * numChannels))
                        + 1)
            if (framesPerBlock != expectedFramesPerBlock) {
                throw ParserException.createForMalformedContainer(
                    "Expected frames per block: " + expectedFramesPerBlock + "; got: " + framesPerBlock,  
                    null
                )
            }

            // Calculate the number of blocks we'll need to decode to obtain an output sample of the
            // target sample size, and allocate suitably sized buffers for input and decoded data.
            val maxBlocksToDecode = Util.ceilDivide(targetSampleSizeFrames, framesPerBlock)
            inputData = ByteArray(maxBlocksToDecode * wavFormat.blockSize)
            decodedData =
                ParsableByteArray(
                    maxBlocksToDecode * numOutputFramesToBytes(framesPerBlock, numChannels)
                )

            // Create the format. We calculate the bitrate of the data before decoding, since this is the
            // bitrate of the stream itself.
            val constantBitrate = wavFormat.frameRateHz * wavFormat.blockSize * 8 / framesPerBlock
            format =
                Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setAverageBitrate(constantBitrate)
                    .setPeakBitrate(constantBitrate)
                    .setMaxInputSize(numOutputFramesToBytes(targetSampleSizeFrames, numChannels))
                    .setChannelCount(wavFormat.numChannels)
                    .setChannelMask(WavUtil.mapToAudioFormatChannelMask(wavFormat.channelMask))
                    .setSampleRate(wavFormat.frameRateHz)
                    .setPcmEncoding(C.ENCODING_PCM_16BIT)
                    .build()
        }

        override fun reset(timeUs: Long) {
            pendingInputBytes = 0
            startTimeUs = timeUs
            pendingOutputBytes = 0
            outputFrameCount = 0
        }

        override fun init(dataStartPosition: Int, dataEndPosition: Long) {
            val wavSeekMap =
                WavSeekMap(wavFormat, framesPerBlock, dataStartPosition.toLong(), dataEndPosition)
            extractorOutput.seekMap(wavSeekMap)
            trackOutput.format(format)
            trackOutput.durationUs(wavSeekMap.durationUs)
        }

        @Throws(IOException::class)
        override fun sampleData(input: ExtractorInput, bytesLeft: Long): Boolean {
            // Calculate the number of additional frames that we need on the output side to complete a
            // sample of the target size.
            val targetFramesRemaining =
                targetSampleSizeFrames - numOutputBytesToFrames(pendingOutputBytes)
            // Calculate the whole number of blocks that we need to decode to obtain this many frames.
            val blocksToDecode = Util.ceilDivide(targetFramesRemaining, framesPerBlock)
            val targetReadBytes = blocksToDecode * wavFormat.blockSize

            // Read input data until we've reached the target number of blocks, or the end of the data.
            var endOfSampleData = bytesLeft == 0L
            while (!endOfSampleData && pendingInputBytes < targetReadBytes) {
                val bytesToRead =
                    min((targetReadBytes - pendingInputBytes).toLong(), bytesLeft).toInt()
                val bytesAppended: Int = input.read(inputData, pendingInputBytes, bytesToRead)
                if (bytesAppended == Extractor.RESULT_END_OF_INPUT) {
                    endOfSampleData = true
                } else {
                    pendingInputBytes += bytesAppended
                }
            }

            val pendingBlockCount = pendingInputBytes / wavFormat.blockSize
            if (pendingBlockCount > 0) {
                // We have at least one whole block to decode.
                decode(inputData, pendingBlockCount, decodedData)
                pendingInputBytes -= pendingBlockCount * wavFormat.blockSize

                // Write all of the decoded data to the track output.
                val decodedDataSize = decodedData.limit()
                trackOutput.sampleData(decodedData, decodedDataSize)
                pendingOutputBytes += decodedDataSize

                // Output the next sample at the target size.
                val pendingOutputFrames = numOutputBytesToFrames(pendingOutputBytes)
                if (pendingOutputFrames >= targetSampleSizeFrames) {
                    writeSampleMetadata(targetSampleSizeFrames)
                }
            }

            // If we've reached the end of the data, we might need to output a final partial sample.
            if (endOfSampleData) {
                val pendingOutputFrames = numOutputBytesToFrames(pendingOutputBytes)
                if (pendingOutputFrames > 0) {
                    writeSampleMetadata(pendingOutputFrames)
                }
            }

            return endOfSampleData
        }

        fun writeSampleMetadata(sampleFrames: Int) {
            val timeUs =
                (startTimeUs
                        + Util.scaleLargeTimestamp(
                    outputFrameCount, C.MICROS_PER_SECOND, wavFormat.frameRateHz.toLong()
                ))
            val size = numOutputFramesToBytes(sampleFrames)
            val offset = pendingOutputBytes - size
            trackOutput.sampleMetadata(
                timeUs, C.BUFFER_FLAG_KEY_FRAME, size, offset,  null
            )
            outputFrameCount += sampleFrames.toLong()
            pendingOutputBytes -= size
        }

        fun decode(input: ByteArray, blockCount: Int, output: ParsableByteArray) {
            for (blockIndex in 0..<blockCount) {
                for (channelIndex in 0..<wavFormat.numChannels) {
                    decodeBlockForChannel(input, blockIndex, channelIndex, output.data)
                }
            }
            val decodedDataSize = numOutputFramesToBytes(framesPerBlock * blockCount)
            output.setPosition(0)
            output.setLimit(decodedDataSize)
        }

        fun decodeBlockForChannel(
            input: ByteArray, blockIndex: Int, channelIndex: Int, output: ByteArray
        ) {
            val blockSize = wavFormat.blockSize
            val numChannels = wavFormat.numChannels

            // The input data consists for a four byte header [Ci] for each of the N channels, followed
            // by interleaved data segments [Ci-DATAj], each of which are four bytes long.
            //
            // [C1][C2]...[CN] [C1-Data0][C2-Data0]...[CN-Data0] [C1-Data1][C2-Data1]...[CN-Data1] etc
            //
            // Compute the start indices for the [Ci] and [Ci-Data0] for the current channel, as well as
            // the number of data bytes for the channel in the block.
            val blockStartIndex = blockIndex * blockSize
            val headerStartIndex = blockStartIndex + channelIndex * 4
            val dataStartIndex = headerStartIndex + numChannels * 4
            val dataSizeBytes = blockSize / numChannels - 4

            // Decode initialization. Casting to a short is necessary for the most significant bit to be
            // treated as -2^15 rather than 2^15.
            var predictedSample =
                (((input[headerStartIndex + 1].toInt() and 0xFF) shl 8) or (input[headerStartIndex].toInt() and 0xFF)).toShort()
                    .toInt()
            var stepIndex = min(input[headerStartIndex + 2].toInt() and 0xFF, 88)
            var step = STEP_TABLE[stepIndex]

            // Output the initial 16 bit PCM sample from the header.
            var outputIndex = (blockIndex * framesPerBlock * numChannels + channelIndex) * 2
            output[outputIndex] = (predictedSample and 0xFF).toByte()
            output[outputIndex + 1] = (predictedSample shr 8).toByte()

            // We examine each data byte twice during decode.
            for (i in 0..<dataSizeBytes * 2) {
                val dataSegmentIndex = i / 8
                val dataSegmentOffset = (i / 2) % 4
                val dataIndex =
                    dataStartIndex + (dataSegmentIndex * numChannels * 4) + dataSegmentOffset

                var originalSample = input[dataIndex].toInt() and 0xFF
                if (i % 2 == 0) {
                    originalSample = originalSample and 0x0F // Bottom four bits.
                } else {
                    originalSample = originalSample shr 4 // Top four bits.
                }

                val delta = originalSample and 0x07
                var difference = ((2 * delta + 1) * step) shr 3

                if ((originalSample and 0x08) != 0) {
                    difference = -difference
                }

                predictedSample += difference
                predictedSample =
                    Util.constrainValue(predictedSample,  -32768,  32767)

                // Output the next 16 bit PCM sample to the correct position in the output.
                outputIndex += 2 * numChannels
                output[outputIndex] = (predictedSample and 0xFF).toByte()
                output[outputIndex + 1] = (predictedSample shr 8).toByte()

                stepIndex += INDEX_TABLE[originalSample]
                stepIndex =
                    Util.constrainValue(stepIndex,  0,  STEP_TABLE.size - 1)
                step = STEP_TABLE[stepIndex]
            }
        }

        fun numOutputBytesToFrames(bytes: Int): Int {
            return bytes / (2 * wavFormat.numChannels)
        }

        private fun numOutputFramesToBytes(frames: Int): Int {
            return numOutputFramesToBytes(frames, wavFormat.numChannels)
        }

        companion object {
            private val INDEX_TABLE = intArrayOf(
                -1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8
            )

            private val STEP_TABLE = intArrayOf(
                7,
                8,
                9,
                10,
                11,
                12,
                13,
                14,
                16,
                17,
                19,
                21,
                23,
                25,
                28,
                31,
                34,
                37,
                41,
                45,
                50,
                55,
                60,
                66,
                73,
                80,
                88,
                97,
                107,
                118,
                130,
                143,
                157,
                173,
                190,
                209,
                230,
                253,
                279,
                307,
                337,
                371,
                408,
                449,
                494,
                544,
                598,
                658,
                724,
                796,
                876,
                963,
                1060,
                1166,
                1282,
                1411,
                1552,
                1707,
                1878,
                2066,
                2272,
                2499,
                2749,
                3024,
                3327,
                3660,
                4026,
                4428,
                4871,
                5358,
                5894,
                6484,
                7132,
                7845,
                8630,
                9493,
                10442,
                11487,
                12635,
                13899,
                15289,
                16818,
                18500,
                20350,
                22385,
                24623,
                27086,
                29794,
                32767
            )

            private fun numOutputFramesToBytes(
                frames: Int,
                numChannels: Int
            ): Int {
                return frames * 2 * numChannels
            }
        }
    }

    companion object {
        private const val TAG = "WavExtractor"

        private const val TARGET_SAMPLES_PER_SECOND = 10

        val FACTORY: ExtractorsFactory = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> {
                return arrayOf(WavExtractor())
            }
        }

        private const val STATE_READING_FILE_TYPE = 0
        private const val STATE_READING_RF64_SAMPLE_DATA_SIZE = 1
        private const val STATE_READING_FORMAT = 2
        private const val STATE_SKIPPING_TO_SAMPLE_DATA = 3
        private const val STATE_READING_SAMPLE_DATA = 4
    }
}
