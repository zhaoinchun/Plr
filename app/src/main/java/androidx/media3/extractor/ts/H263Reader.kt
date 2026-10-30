package androidx.media3.extractor.ts

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.C.BufferFlags
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.container.NalUnitUtil.clearPrefixFlags
import androidx.media3.container.NalUnitUtil.findNalUnit
import androidx.media3.container.NalUnitUtil.unescapeStream
import androidx.media3.extractor.CeaUtil.consume
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import com.google.common.base.Preconditions

class H263Reader internal constructor(
    private val userDataReader: UserDataReader?,
    private val containerMimeType: String?
) : ElementaryStreamReader {
    private val userDataParsable: ParsableByteArray?

    // State that should be reset on seek.
    private val prefixFlags: BooleanArray
    private val csdBuffer: CsdBuffer
    private val userData: NalUnitTargetBuffer?
    private var sampleReader:SampleReader? = null
    private var totalBytesWritten: Long = 0

    // State initialized once when tracks are created.
    private var formatId:String? = null
    private var output:TrackOutput? = null

    // State that should not be reset on seek.
    private var hasOutputFormat = false

    // Per packet state that gets reset at the start of each packet.
    private var pesTimeUs: Long

    constructor(containerMimeType: String?) : this(null, containerMimeType)

    init {
        prefixFlags = BooleanArray(4)
        csdBuffer = CsdBuffer(128)
        pesTimeUs = C.TIME_UNSET
        if (userDataReader != null) {
            userData = NalUnitTargetBuffer(START_CODE_VALUE_USER_DATA, 128)
            userDataParsable = ParsableByteArray()
        } else {
            userData = null
            userDataParsable = null
        }
    }

    override fun seek() {
        clearPrefixFlags(prefixFlags)
        csdBuffer.reset()
        if (sampleReader != null) {
            sampleReader!!.reset()
        }
        if (userData != null) {
            userData.reset()
        }
        totalBytesWritten = 0
        pesTimeUs = C.TIME_UNSET
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.getFormatId()
        output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_VIDEO)
        sampleReader = H263Reader.SampleReader(output!!)
        if (userDataReader != null) {
            userDataReader.createTracks(extractorOutput, idGenerator)
        }
    }

    override fun packetStarted(pesTimeUs: Long, flags: @TsPayloadReader.Flags Int) {
        // TODO (Internal b/32267012): Consider using random access indicator.
        this.pesTimeUs = pesTimeUs
    }

    override fun consume(data: ParsableByteArray) {
        // Assert that createTracks has been called.
        sampleReader!!
        val output = output!!
        var offset = data.getPosition()
        val limit = data.limit()
        val dataArray = data.data

        // Append the data to the buffer.
        totalBytesWritten += data.bytesLeft().toLong()
        output!!.sampleData(data, data.bytesLeft())

        while (true) {
            val startCodeOffset = findNalUnit(dataArray, offset, limit, prefixFlags)

            if (startCodeOffset == limit) {
                // We've scanned to the end of the data without finding another start code.
                if (!hasOutputFormat) {
                    csdBuffer.onData(dataArray, offset, limit)
                }
                sampleReader!!.onData(dataArray, offset, limit)
                if (userData != null) {
                    userData.appendToNalUnit(dataArray, offset, limit)
                }
                return
            }

            // We've found a start code with the following value.
            val startCodeValue = data.data[startCodeOffset + 3].toInt() and 0xFF
            // This is the number of bytes from the current offset to the start of the next start
            // code. It may be negative if the start code started in the previously consumed data.
            val lengthToStartCode = startCodeOffset - offset

            if (!hasOutputFormat) {
                if (lengthToStartCode > 0) {
                    csdBuffer.onData(dataArray, offset,  startCodeOffset)
                }
                // This is the number of bytes belonging to the next start code that have already been
                // passed to csdBuffer.
                val bytesAlreadyPassed = if (lengthToStartCode < 0) -lengthToStartCode else 0
                if (csdBuffer.onStartCode(startCodeValue, bytesAlreadyPassed)) {
                    // The csd data is complete, so we can decode and output the media format.
                    output!!.format(
                        parseCsdBuffer(
                            csdBuffer,
                            csdBuffer.volStartPosition,
                            formatId!!,
                            containerMimeType
                        )
                    )
                    hasOutputFormat = true
                }
            }

            sampleReader!!.onData(dataArray, offset,  startCodeOffset)

            if (userData != null) {
                var bytesAlreadyPassed = 0
                if (lengthToStartCode > 0) {
                    userData.appendToNalUnit(dataArray, offset,  startCodeOffset)
                } else {
                    bytesAlreadyPassed = -lengthToStartCode
                }

                if (userData.endNalUnit(bytesAlreadyPassed)) {
                    val unescapedLength = unescapeStream(userData.nalData, userData.nalLength)
                    userDataParsable!!.reset(userData.nalData, unescapedLength)
                    userDataReader!!.consume(pesTimeUs, userDataParsable)
                }

                if (startCodeValue == START_CODE_VALUE_USER_DATA
                    && data.data[startCodeOffset + 2].toInt() == 0x1
                ) {
                    userData.startNalUnit(startCodeValue)
                }
            }

            val bytesWrittenPastPosition = limit - startCodeOffset
            val absolutePosition = totalBytesWritten - bytesWrittenPastPosition
            sampleReader!!.onDataEnd(absolutePosition, bytesWrittenPastPosition, hasOutputFormat)
            // Indicate the start of the next chunk.
            sampleReader!!.onStartCode(startCodeValue, pesTimeUs)
            // Continue scanning the data.
            offset = startCodeOffset + 3
        }
    }

    override fun endOfInputReached() {
        // Assert that createTracks has been called.
        sampleReader!!.onDataEnd(
            totalBytesWritten,  
            0,
            hasOutputFormat
        )
        sampleReader!!.reset()
    }

    private class CsdBuffer(initialCapacity: Int) {
        @MustBeDocumented
        @Retention(AnnotationRetention.SOURCE)
        @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
        @IntDef(
            value = [STATE_SKIP_TO_VISUAL_OBJECT_SEQUENCE_START, STATE_EXPECT_VISUAL_OBJECT_START, STATE_EXPECT_VIDEO_OBJECT_START, STATE_EXPECT_VIDEO_OBJECT_LAYER_START, STATE_WAIT_FOR_VOP_START
            ]
        )
        private annotation class State

        private var isFilling = false
        private var state: @State Int = 0

        var length: Int = 0
        var volStartPosition: Int = 0
        var data: ByteArray

        init {
            data = ByteArray(initialCapacity)
        }

        fun reset() {
            isFilling = false
            length = 0
            state = STATE_SKIP_TO_VISUAL_OBJECT_SEQUENCE_START
        }

        fun onStartCode(startCodeValue: Int, bytesAlreadyPassed: Int): Boolean {
            when (state) {
                STATE_SKIP_TO_VISUAL_OBJECT_SEQUENCE_START -> if (startCodeValue == START_CODE_VALUE_VISUAL_OBJECT_SEQUENCE) {
                    state = STATE_EXPECT_VISUAL_OBJECT_START
                    isFilling = true
                }

                STATE_EXPECT_VISUAL_OBJECT_START -> if (startCodeValue != START_CODE_VALUE_VISUAL_OBJECT) {
                    w(TAG, "Unexpected start code value")
                    reset()
                } else {
                    state = STATE_EXPECT_VIDEO_OBJECT_START
                }

                STATE_EXPECT_VIDEO_OBJECT_START -> if (startCodeValue > START_CODE_VALUE_MAX_VIDEO_OBJECT) {
                    w(TAG, "Unexpected start code value")
                    reset()
                } else {
                    state = STATE_EXPECT_VIDEO_OBJECT_LAYER_START
                }

                STATE_EXPECT_VIDEO_OBJECT_LAYER_START -> if ((startCodeValue and 0xF0) != 0x20) {
                    w(TAG, "Unexpected start code value")
                    reset()
                } else {
                    volStartPosition = length
                    state = STATE_WAIT_FOR_VOP_START
                }

                STATE_WAIT_FOR_VOP_START -> if (startCodeValue == START_CODE_VALUE_GROUP_OF_VOP
                    || startCodeValue == START_CODE_VALUE_VISUAL_OBJECT
                ) {
                    length -= bytesAlreadyPassed
                    isFilling = false
                    return true
                }

                else -> throw IllegalStateException()
            }
            onData(START_CODE,  0,  START_CODE.size)
            return false
        }

        fun onData(newData: ByteArray, offset: Int, limit: Int) {
            if (!isFilling) {
                return
            }
            val readLength = limit - offset
            if (data.size < length + readLength) {
                data = data.copyOf((length + readLength) * 2)
            }
            System.arraycopy(newData, offset, data, length, readLength)
            length += readLength
        }

        companion object {
            private val START_CODE = byteArrayOf(0, 0, 1)

            private const val STATE_SKIP_TO_VISUAL_OBJECT_SEQUENCE_START = 0
            private const val STATE_EXPECT_VISUAL_OBJECT_START = 1
            private const val STATE_EXPECT_VIDEO_OBJECT_START = 2
            private const val STATE_EXPECT_VIDEO_OBJECT_LAYER_START = 3
            private const val STATE_WAIT_FOR_VOP_START = 4
        }
    }

    private class SampleReader(private val output: TrackOutput) {
        private var readingSample = false
        private var lookingForVopCodingType = false
        private var sampleIsKeyframe = false
        private var startCodeValue = 0
        private var vopBytesRead = 0
        private var samplePosition: Long = 0
        private var sampleTimeUs: Long = 0

        fun reset() {
            readingSample = false
            lookingForVopCodingType = false
            sampleIsKeyframe = false
            startCodeValue = START_CODE_VALUE_UNSET
        }

        fun onStartCode(startCodeValue: Int, pesTimeUs: Long) {
            this.startCodeValue = startCodeValue
            sampleIsKeyframe = false
            readingSample =
                startCodeValue == START_CODE_VALUE_VOP || startCodeValue == START_CODE_VALUE_GROUP_OF_VOP
            lookingForVopCodingType = startCodeValue == START_CODE_VALUE_VOP
            vopBytesRead = 0
            sampleTimeUs = pesTimeUs
        }

        fun onData(data: ByteArray, offset: Int, limit: Int) {
            if (lookingForVopCodingType) {
                val headerOffset: Int = offset + OFFSET_VOP_CODING_TYPE - vopBytesRead
                if (headerOffset < limit) {
                    sampleIsKeyframe =
                        ((data[headerOffset].toInt() and 0xC0) shr 6) == VOP_CODING_TYPE_INTRA
                    lookingForVopCodingType = false
                } else {
                    vopBytesRead += limit - offset
                }
            }
        }

        fun onDataEnd(position: Long, bytesWrittenPastPosition: Int, hasOutputFormat: Boolean) {
            // packetStarted method must be called before reading sample.
            Preconditions.checkState(sampleTimeUs != C.TIME_UNSET)
            if (startCodeValue == START_CODE_VALUE_VOP && hasOutputFormat && readingSample) {
                val size = (position - samplePosition).toInt()
                val flags: @BufferFlags Int = if (sampleIsKeyframe) C.BUFFER_FLAG_KEY_FRAME else 0
                output.sampleMetadata(
                    sampleTimeUs, flags, size, bytesWrittenPastPosition,  null
                )
            }
            // Start a new sample, unless this is a 'group of video object plane' in which case we
            // include the data at the start of a 'video object plane' coming next.
            if (startCodeValue != START_CODE_VALUE_GROUP_OF_VOP) {
                samplePosition = position
            }
        }

        companion object {
            
            private const val OFFSET_VOP_CODING_TYPE = 1

            private const val VOP_CODING_TYPE_INTRA = 0
        }
    }

    companion object {
        private const val TAG = "H263Reader"

        private const val START_CODE_VALUE_VISUAL_OBJECT_SEQUENCE = 0xB0
        private const val START_CODE_VALUE_USER_DATA = 0xB2
        private const val START_CODE_VALUE_GROUP_OF_VOP = 0xB3
        private const val START_CODE_VALUE_VISUAL_OBJECT = 0xB5
        private const val START_CODE_VALUE_VOP = 0xB6
        private const val START_CODE_VALUE_MAX_VIDEO_OBJECT = 0x1F
        private val START_CODE_VALUE_UNSET = -1

        // See ISO 14496-2 (2001) table 6-12 for the mapping from aspect_ratio_info to pixel aspect ratio.
        private val PIXEL_WIDTH_HEIGHT_RATIO_BY_ASPECT_RATIO_INFO =
            floatArrayOf(1f, 1f, 12 / 11f, 10 / 11f, 16 / 11f, 40 / 33f, 1f)
        private const val VIDEO_OBJECT_LAYER_SHAPE_RECTANGULAR = 0

        private fun parseCsdBuffer(
            csdBuffer: CsdBuffer,
            volStartPosition: Int,
            formatId: String?,
            containerMimeType: String?
        ): Format {
            val csdData = csdBuffer.data.copyOf(csdBuffer.length)
            val buffer = ParsableBitArray(csdData)
            buffer.skipBytes(volStartPosition)

            // Parse the video object layer defined in ISO 14496-2 (2001) subsection 6.2.3.
            buffer.skipBytes(4) // video_object_layer_start_code
            buffer.skipBit() // random_accessible_vol
            buffer.skipBits(8) // video_object_type_indication
            if (buffer.readBit()) { // is_object_layer_identifier
                buffer.skipBits(4) // video_object_layer_verid
                buffer.skipBits(3) // video_object_layer_priority
            }
            val pixelWidthHeightRatio: Float
            val aspectRatioInfo = buffer.readBits(4)
            if (aspectRatioInfo == 0x0F) { // extended_PAR
                val parWidth = buffer.readBits(8)
                val parHeight = buffer.readBits(8)
                if (parHeight == 0) {
                    w(TAG, "Invalid aspect ratio")
                    pixelWidthHeightRatio = 1f
                } else {
                    pixelWidthHeightRatio = parWidth.toFloat() / parHeight
                }
            } else if (aspectRatioInfo < PIXEL_WIDTH_HEIGHT_RATIO_BY_ASPECT_RATIO_INFO.size) {
                pixelWidthHeightRatio =
                    PIXEL_WIDTH_HEIGHT_RATIO_BY_ASPECT_RATIO_INFO[aspectRatioInfo]
            } else {
                w(TAG, "Invalid aspect ratio")
                pixelWidthHeightRatio = 1f
            }
            if (buffer.readBit()) { // vol_control_parameters
                buffer.skipBits(2) // chroma_format
                buffer.skipBits(1) // low_delay
                if (buffer.readBit()) { // vbv_parameters
                    buffer.skipBits(15) // first_half_bit_rate
                    buffer.skipBit() // marker_bit
                    buffer.skipBits(15) // latter_half_bit_rate
                    buffer.skipBit() // marker_bit
                    buffer.skipBits(15) // first_half_vbv_buffer_size
                    buffer.skipBit() // marker_bit
                    buffer.skipBits(3) // latter_half_vbv_buffer_size
                    buffer.skipBits(11) // first_half_vbv_occupancy
                    buffer.skipBit() // marker_bit
                    buffer.skipBits(15) // latter_half_vbv_occupancy
                    buffer.skipBit() // marker_bit
                }
            }
            val videoObjectLayerShape = buffer.readBits(2)
            if (videoObjectLayerShape != VIDEO_OBJECT_LAYER_SHAPE_RECTANGULAR) {
                w(TAG, "Unhandled video object layer shape")
            }
            buffer.skipBit() // marker_bit
            var vopTimeIncrementResolution = buffer.readBits(16)
            buffer.skipBit() // marker_bit
            if (buffer.readBit()) { // fixed_vop_rate
                if (vopTimeIncrementResolution == 0) {
                    w(TAG, "Invalid vop_increment_time_resolution")
                } else {
                    vopTimeIncrementResolution--
                    var numBits = 0
                    while (vopTimeIncrementResolution > 0) {
                        ++numBits
                        vopTimeIncrementResolution = vopTimeIncrementResolution shr 1
                    }
                    buffer.skipBits(numBits) // fixed_vop_time_increment
                }
            }
            buffer.skipBit() // marker_bit
            val videoObjectLayerWidth = buffer.readBits(13)
            buffer.skipBit() // marker_bit
            val videoObjectLayerHeight = buffer.readBits(13)
            buffer.skipBit() // marker_bit
            buffer.skipBit() // interlaced
            return Format.Builder()
                .setId(formatId)
                .setContainerMimeType(containerMimeType)
                .setSampleMimeType(MimeTypes.VIDEO_MP4V)
                .setWidth(videoObjectLayerWidth)
                .setHeight(videoObjectLayerHeight)
                .setPixelWidthHeightRatio(pixelWidthHeightRatio)
                .setInitializationData(mutableListOf<ByteArray>(csdData))
                .build()
        }
    }
}
