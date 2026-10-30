
package androidx.media3.extractor.ts

import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.C.BufferFlags
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util
import androidx.media3.container.NalUnitUtil.clearPrefixFlags
import androidx.media3.container.NalUnitUtil.findNalUnit
import androidx.media3.container.NalUnitUtil.unescapeStream
import androidx.media3.extractor.CeaUtil.consume
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import com.google.common.base.Preconditions

class H262Reader internal constructor(
    private val userDataReader: UserDataReader?,
    private val containerMimeType: String?
) : ElementaryStreamReader {
    private var formatId:String? = null
    private var output:TrackOutput? = null

    private val userDataParsable: ParsableByteArray?

    // State that should be reset on seek.
    private val userData: NalUnitTargetBuffer?
    private val prefixFlags: BooleanArray
    private val csdBuffer: CsdBuffer
    private var totalBytesWritten: Long = 0
    private var startedFirstSample = false

    // State that should not be reset on seek.
    private var hasOutputFormat = false
    private var frameDurationUs: Long = 0

    // Per packet state that gets reset at the start of each packet.
    private var pesTimeUs: Long

    // Per sample state that gets reset at the start of each sample.
    private var samplePosition: Long = 0
    private var sampleTimeUs: Long
    private var sampleIsKeyframe = false
    private var sampleHasPicture = false

    constructor(containerMimeType: String?) : this(null, containerMimeType)

    init {
        prefixFlags = BooleanArray(4)
        csdBuffer = CsdBuffer(128)
        if (userDataReader != null) {
            userData = NalUnitTargetBuffer(START_USER_DATA, 128)
            userDataParsable = ParsableByteArray()
        } else {
            userData = null
            userDataParsable = null
        }
        pesTimeUs = C.TIME_UNSET
        sampleTimeUs = C.TIME_UNSET
    }

    override fun seek() {
        clearPrefixFlags(prefixFlags)
        csdBuffer.reset()
        if (userData != null) {
            userData.reset()
        }
        totalBytesWritten = 0
        startedFirstSample = false
        pesTimeUs = C.TIME_UNSET
        sampleTimeUs = C.TIME_UNSET
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.getFormatId()
        output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_VIDEO)
        if (userDataReader != null) {
            userDataReader.createTracks(extractorOutput, idGenerator)
        }
    }

    override fun packetStarted(pesTimeUs: Long, flags: @TsPayloadReader.Flags Int) {
        // TODO (Internal b/32267012): Consider using random access indicator.
        this.pesTimeUs = pesTimeUs
    }

    override fun consume(data: ParsableByteArray) {
        // Asserts that createTracks has been called.
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
                    csdBuffer.onData(dataArray, offset, startCodeOffset)
                }
                // This is the number of bytes belonging to the next start code that have already been
                // passed to csdBuffer.
                val bytesAlreadyPassed = if (lengthToStartCode < 0) -lengthToStartCode else 0
                if (csdBuffer.onStartCode(startCodeValue, bytesAlreadyPassed)) {
                    // The csd data is complete, so we can decode and output the media format.
                    val result: Pair<Format?, Long?> =
                        parseCsdBuffer(
                            csdBuffer,
                            formatId!!,
                            containerMimeType
                        )
                    output!!.format(result.first!!)
                    frameDurationUs = result.second!!
                    hasOutputFormat = true
                }
            }
            if (userData != null) {
                var bytesAlreadyPassed = 0
                if (lengthToStartCode > 0) {
                    userData.appendToNalUnit(dataArray, offset, startCodeOffset)
                } else {
                    bytesAlreadyPassed = -lengthToStartCode
                }

                if (userData.endNalUnit(bytesAlreadyPassed)) {
                    val unescapedLength = unescapeStream(userData.nalData, userData.nalLength)
                    userDataParsable!!.reset(userData.nalData, unescapedLength)
                    userDataReader!!.consume(sampleTimeUs, userDataParsable)
                }

                if (startCodeValue == START_USER_DATA && data.data[startCodeOffset + 2].toInt() == 0x1) {
                    userData.startNalUnit(startCodeValue)
                }
            }
            if (startCodeValue == START_PICTURE || startCodeValue == START_SEQUENCE_HEADER) {
                val bytesWrittenPastStartCode = limit - startCodeOffset
                if (sampleHasPicture && hasOutputFormat && sampleTimeUs != C.TIME_UNSET) {
                    // Output the sample.
                    val flags: @BufferFlags Int =
                        if (sampleIsKeyframe) C.BUFFER_FLAG_KEY_FRAME else 0
                    val size =
                        (totalBytesWritten - samplePosition).toInt() - bytesWrittenPastStartCode
                    output!!.sampleMetadata(
                        sampleTimeUs,
                        flags,
                        size,
                        bytesWrittenPastStartCode,
                        null
                    )
                }
                if (!startedFirstSample || sampleHasPicture) {
                    // Start the next sample.
                    samplePosition = totalBytesWritten - bytesWrittenPastStartCode
                    sampleTimeUs =
                        if (pesTimeUs != C.TIME_UNSET)
                            pesTimeUs
                        else
                            (if (sampleTimeUs != C.TIME_UNSET)
                                (sampleTimeUs + frameDurationUs)
                            else
                                C.TIME_UNSET)
                    sampleIsKeyframe = false
                    pesTimeUs = C.TIME_UNSET
                    startedFirstSample = true
                }
                sampleHasPicture = startCodeValue == START_PICTURE
            } else if (startCodeValue == START_GROUP) {
                sampleIsKeyframe = true
            }

            offset = startCodeOffset + 3
        }
    }

    override fun endOfInputReached() {
        // Asserts that createTracks has been called.
        val output = output!!
        if (sampleTimeUs != C.TIME_UNSET) {
            val flags: @BufferFlags Int = if (sampleIsKeyframe) C.BUFFER_FLAG_KEY_FRAME else 0
            val size = (totalBytesWritten - samplePosition).toInt()
            output!!.sampleMetadata(
                sampleTimeUs,
                flags,
                size,  
                0,  
                null
            )
        }
    }

    private class CsdBuffer(initialCapacity: Int) {
        private var isFilling = false

        var length: Int = 0
        var sequenceExtensionPosition: Int = 0
        var data: ByteArray

        init {
            data = ByteArray(initialCapacity)
        }

        fun reset() {
            isFilling = false
            length = 0
            sequenceExtensionPosition = 0
        }

        fun onStartCode(startCodeValue: Int, bytesAlreadyPassed: Int): Boolean {
            if (isFilling) {
                length -= bytesAlreadyPassed
                if (sequenceExtensionPosition == 0 && startCodeValue == START_EXTENSION) {
                    sequenceExtensionPosition = length
                } else {
                    isFilling = false
                    return true
                }
            } else if (startCodeValue == START_SEQUENCE_HEADER) {
                isFilling = true
            }
            onData(START_CODE, 0, START_CODE.size)
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
        }
    }

    companion object {
        private const val START_PICTURE = 0x00
        private const val START_SEQUENCE_HEADER = 0xB3
        private const val START_EXTENSION = 0xB5
        private const val START_GROUP = 0xB8
        private const val START_USER_DATA = 0xB2

        // Maps (frame_rate_code - 1) indices to values, as defined in ITU-T H.262 Table 6-4.
        private val FRAME_RATE_VALUES = doubleArrayOf(
            24000.0 / 1001,
            24.0,
            25.0,
            30000.0 / 1001,
            30.0,
            50.0,
            60000.0 / 1001,
            60.0
        )

        private fun parseCsdBuffer(
            csdBuffer: CsdBuffer, formatId: String?, containerMimeType: String?
        ): Pair<Format?, Long?> {
            val csdData = csdBuffer.data.copyOf(csdBuffer.length)

            val firstByte = csdData[4].toInt() and 0xFF
            val secondByte = csdData[5].toInt() and 0xFF
            val thirdByte = csdData[6].toInt() and 0xFF
            val width = (firstByte shl 4) or (secondByte shr 4)
            val height = (secondByte and 0x0F) shl 8 or thirdByte

            var pixelWidthHeightRatio = 1f
            val aspectRatioCode = (csdData[7].toInt() and 0xF0) shr 4
            when (aspectRatioCode) {
                2 -> pixelWidthHeightRatio = (4 * height) / (3 * width).toFloat()
                3 -> pixelWidthHeightRatio = (16 * height) / (9 * width).toFloat()
                4 -> pixelWidthHeightRatio = (121 * height) / (100 * width).toFloat()
                else -> {}
            }

            val format =
                Format.Builder()
                    .setId(formatId)
                    .setContainerMimeType(containerMimeType)
                    .setSampleMimeType(MimeTypes.VIDEO_MPEG2)
                    .setWidth(width)
                    .setHeight(height)
                    .setPixelWidthHeightRatio(pixelWidthHeightRatio)
                    .setInitializationData(mutableListOf<ByteArray>(csdData))
                    .build()

            var frameDurationUs: Long = 0
            val frameRateCodeMinusOne = (csdData[7].toInt() and 0x0F) - 1
            if (0 <= frameRateCodeMinusOne && frameRateCodeMinusOne < FRAME_RATE_VALUES.size) {
                var frameRate: Double = FRAME_RATE_VALUES[frameRateCodeMinusOne]
                val sequenceExtensionPosition = csdBuffer.sequenceExtensionPosition
                val frameRateExtensionN =
                    (csdData[sequenceExtensionPosition + 9].toInt() and 0x60) shr 5
                val frameRateExtensionD = (csdData[sequenceExtensionPosition + 9].toInt() and 0x1F)
                if (frameRateExtensionN != frameRateExtensionD) {
                    frameRate *= (frameRateExtensionN + 1.0) / (frameRateExtensionD + 1)
                }
                frameDurationUs = (C.MICROS_PER_SECOND / frameRate).toLong()
            }

            return Pair.create<Format?, Long?>(format, frameDurationUs)
        }
    }
}
