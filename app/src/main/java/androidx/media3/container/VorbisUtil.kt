package androidx.media3.container

import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.ParsableByteArray
import kotlin.math.floor
import kotlin.math.pow

object VorbisUtil {
    private const val TAG = "VorbisUtil"

    fun iLog(x: Int): Int {
        var x = x
        var `val` = 0
        while (x > 0) {
            `val`++
            x = x ushr 1
        }
        return `val`
    }

    @Throws(ParserException::class)
    fun readVorbisIdentificationHeader(headerData: ParsableByteArray): VorbisIdHeader {
        verifyVorbisHeaderCapturePattern(0x01, headerData, false)

        val version = headerData.readLittleEndianUnsignedIntToInt()
        val channels = headerData.readUnsignedByte()
        val sampleRate = headerData.readLittleEndianUnsignedIntToInt()
        var bitrateMaximum = headerData.readLittleEndianInt()
        if (bitrateMaximum <= 0) {
            bitrateMaximum = Format.NO_VALUE
        }
        var bitrateNominal = headerData.readLittleEndianInt()
        if (bitrateNominal <= 0) {
            bitrateNominal = Format.NO_VALUE
        }
        var bitrateMinimum = headerData.readLittleEndianInt()
        if (bitrateMinimum <= 0) {
            bitrateMinimum = Format.NO_VALUE
        }
        val blockSize = headerData.readUnsignedByte()
        val blockSize0 = 2.0.pow((blockSize and 0x0F).toDouble()).toInt()
        val blockSize1 = 2.0.pow(((blockSize and 0xF0) shr 4).toDouble()).toInt()

        val framingFlag = (headerData.readUnsignedByte() and 0x01) > 0
        // raw data of Vorbis setup header has to be passed to decoder as CSD buffer #1
        val data = headerData.data.copyOf(headerData.limit())

        return VorbisIdHeader(
            version,
            channels,
            sampleRate,
            bitrateMaximum,
            bitrateNominal,
            bitrateMinimum,
            blockSize0,
            blockSize1,
            framingFlag,
            data
        )
    }

    @Throws(ParserException::class)
    fun readVorbisCommentHeader(
        headerData: ParsableByteArray,
        hasMetadataHeader: Boolean = true,
        hasFramingBit: Boolean = true
    ): CommentHeader {
        if (hasMetadataHeader) {
            verifyVorbisHeaderCapturePattern( 0x03, headerData,  false)
        }
        var length = 7

        var len = headerData.readLittleEndianUnsignedInt().toInt()
        length += 4
        val vendor = headerData.readString(len)
        length += vendor.length

        val commentListLen = headerData.readLittleEndianUnsignedInt().toInt()
        val comments = Array<String>(commentListLen){""}
        length += 4
        for (i in 0..<commentListLen) {
            len = headerData.readLittleEndianUnsignedInt().toInt()
            length += 4
            comments[i] = headerData.readString(len)
            length += comments[i]!!.length
        }
        if (hasFramingBit && (headerData.readUnsignedByte() and 0x01) == 0) {
            throw ParserException.createForMalformedContainer(
                "framing bit expected to be set",  null
            )
        }
        length += 1
        return CommentHeader(vendor, comments, length)
    }

    @Throws(ParserException::class)
    fun verifyVorbisHeaderCapturePattern(
        headerType: Int, header: ParsableByteArray, quiet: Boolean
    ): Boolean {
        if (header.bytesLeft() < 7) {
            if (quiet) {
                return false
            } else {
                throw ParserException.createForMalformedContainer(
                    "too short header: " + header.bytesLeft(),  null
                )
            }
        }

        if (header.readUnsignedByte() != headerType) {
            if (quiet) {
                return false
            } else {
                throw ParserException.createForMalformedContainer(
                    "expected header type " + Integer.toHexString(headerType),  null
                )
            }
        }

        if (!(header.readUnsignedByte() == 'v'.code && header.readUnsignedByte() == 'o'.code && header.readUnsignedByte() == 'r'.code && header.readUnsignedByte() == 'b'.code && header.readUnsignedByte() == 'i'.code && header.readUnsignedByte() == 's'.code)) {
            if (quiet) {
                return false
            } else {
                throw ParserException.createForMalformedContainer(
                    "expected characters 'vorbis'",  null
                )
            }
        }
        return true
    }

    fun getPacketBlockSize(
        firstByteOfAudioPacket: Byte, idHeader: VorbisIdHeader, modes: Array<Mode?>
    ): Int {
        // Read modeNumber (https://www.xiph.org/vorbis/doc/Vorbis_I_spec.html#x1-730004.3.1)
        val iLogModes = iLog(modes.size - 1)
        val modeNumber = readBits(firstByteOfAudioPacket, iLogModes, 1)
        return if (modes[modeNumber]!!.blockFlag) idHeader.blockSize1 else idHeader.blockSize0
    }

    fun getSampleCountInPacket(
        packetBlockSize: Int, previousPacketBlockSize: Int, seenFirstAudioPacket: Boolean
    ): Int {
        // A packet contains samples produced from overlapping the previous and current frame data.
        // See
        // (https://www.xiph.org/vorbis/doc/Vorbis_I_spec.html#x1-350001.3.2)
        return if (seenFirstAudioPacket) (packetBlockSize + previousPacketBlockSize) / 4 else 0
    }

    fun readBits(src: Byte, length: Int, leastSignificantBitIndex: Int): Int {
        return (src.toInt() shr leastSignificantBitIndex) and (255 ushr (8 - length))
    }

    @Throws(ParserException::class)
    fun readVorbisModes(headerData: ParsableByteArray, channels: Int): Array<Mode?> {
        verifyVorbisHeaderCapturePattern(0x05, headerData, false)

        val numberOfBooks = headerData.readUnsignedByte() + 1

        val bitArray = VorbisBitArray(headerData.data)
        bitArray.skipBits(headerData.getPosition() * 8)

        for (i in 0..<numberOfBooks) {
            skipBook(bitArray)
        }

        val timeCount = bitArray.readBits(6) + 1
        for (i in 0..<timeCount) {
            if (bitArray.readBits(16) != 0x00) {
                throw ParserException.createForMalformedContainer(
                    "placeholder of time domain transforms not zeroed out",  null
                )
            }
        }
        readFloors(bitArray)
        readResidues(bitArray)
        readMappings(channels, bitArray)

        val modes = readModes(bitArray)
        if (!bitArray.readBit()) {
            throw ParserException.createForMalformedContainer(
                "framing bit after modes not set as expected",  null
            )
        }
        return modes
    }

    private fun readModes(bitArray: VorbisBitArray): Array<Mode?> {
        val modeCount = bitArray.readBits(6) + 1
        val modes = arrayOfNulls<Mode>(modeCount)
        for (i in 0..<modeCount) {
            val blockFlag = bitArray.readBit()
            val windowType = bitArray.readBits(16)
            val transformType = bitArray.readBits(16)
            val mapping = bitArray.readBits(8)
            modes[i] = Mode(blockFlag, windowType, transformType, mapping)
        }
        return modes
    }

    @Throws(ParserException::class)
    private fun readMappings(channels: Int, bitArray: VorbisBitArray) {
        val mappingsCount = bitArray.readBits(6) + 1
        for (i in 0..<mappingsCount) {
            val mappingType = bitArray.readBits(16)
            if (mappingType != 0) {
                e(TAG, "mapping type other than 0 not supported: " + mappingType)
                continue
            }
            val submaps: Int
            if (bitArray.readBit()) {
                submaps = bitArray.readBits(4) + 1
            } else {
                submaps = 1
            }
            val couplingSteps: Int
            if (bitArray.readBit()) {
                couplingSteps = bitArray.readBits(8) + 1
                for (j in 0..<couplingSteps) {
                    bitArray.skipBits(iLog(channels - 1)) // magnitude
                    bitArray.skipBits(iLog(channels - 1)) // angle
                }
            } /*else {
          couplingSteps = 0;
        }*/
            if (bitArray.readBits(2) != 0x00) {
                throw ParserException.createForMalformedContainer(
                    "to reserved bits must be zero after mapping coupling steps",  null
                )
            }
            if (submaps > 1) {
                for (j in 0..<channels) {
                    bitArray.skipBits(4) // mappingMux
                }
            }
            for (j in 0..<submaps) {
                bitArray.skipBits(8) // discard
                bitArray.skipBits(8) // submapFloor
                bitArray.skipBits(8) // submapResidue
            }
        }
    }

    @Throws(ParserException::class)
    private fun readResidues(bitArray: VorbisBitArray) {
        val residueCount = bitArray.readBits(6) + 1
        for (i in 0..<residueCount) {
            val residueType = bitArray.readBits(16)
            if (residueType > 2) {
                throw ParserException.createForMalformedContainer(
                    "residueType greater than 2 is not decodable",  null
                )
            } else {
                bitArray.skipBits(24) // begin
                bitArray.skipBits(24) // end
                bitArray.skipBits(24) // partitionSize (add one)
                val classifications = bitArray.readBits(6) + 1
                bitArray.skipBits(8) // classbook
                val cascade = IntArray(classifications)
                for (j in 0..<classifications) {
                    var highBits = 0
                    val lowBits = bitArray.readBits(3)
                    if (bitArray.readBit()) {
                        highBits = bitArray.readBits(5)
                    }
                    cascade[j] = highBits * 8 + lowBits
                }
                for (j in 0..<classifications) {
                    for (k in 0..7) {
                        if ((cascade[j] and (0x01 shl k)) != 0) {
                            bitArray.skipBits(8) // discard
                        }
                    }
                }
            }
        }
    }

    @Throws(ParserException::class)
    private fun readFloors(bitArray: VorbisBitArray) {
        val floorCount = bitArray.readBits(6) + 1
        for (i in 0..<floorCount) {
            val floorType = bitArray.readBits(16)
            when (floorType) {
                0 -> {
                    bitArray.skipBits(8) // order
                    bitArray.skipBits(16) // rate
                    bitArray.skipBits(16) // barkMapSize
                    bitArray.skipBits(6) // amplitudeBits
                    bitArray.skipBits(8) // amplitudeOffset
                    val floorNumberOfBooks = bitArray.readBits(4) + 1
                    var j = 0
                    while (j < floorNumberOfBooks) {
                        bitArray.skipBits(8)
                        j++
                    }
                }

                1 -> {
                    val partitions = bitArray.readBits(5)
                    var maximumClass = -1
                    val partitionClassList = IntArray(partitions)
                    run {
                        var j = 0
                        while (j < partitions) {
                            partitionClassList[j] = bitArray.readBits(4)
                            if (partitionClassList[j] > maximumClass) {
                                maximumClass = partitionClassList[j]
                            }
                            j++
                        }
                    }
                    val classDimensions = IntArray(maximumClass + 1)
                    run {
                        var j = 0
                        while (j < classDimensions.size) {
                            classDimensions[j] = bitArray.readBits(3) + 1
                            val classSubclasses = bitArray.readBits(2)
                            if (classSubclasses > 0) {
                                bitArray.skipBits(8) // classMasterbooks
                            }
                            var k = 0
                            while (k < (1 shl classSubclasses)) {
                                bitArray.skipBits(8) // subclassBook (subtract 1)
                                k++
                            }
                            j++
                        }
                    }
                    bitArray.skipBits(2) // multiplier (add one)
                    val rangeBits = bitArray.readBits(4)
                    var count = 0
                    var j = 0
                    var k = 0
                    while (j < partitions) {
                        val idx = partitionClassList[j]
                        count += classDimensions[idx]
                        while (k < count) {
                            bitArray.skipBits(rangeBits) // floorValue
                            k++
                        }
                        j++
                    }
                }

                else -> throw ParserException.createForMalformedContainer(
                    "floor type greater than 1 not decodable: " + floorType,  null
                )
            }
        }
    }

    @Throws(ParserException::class)
    private fun skipBook(bitArray: VorbisBitArray) {
        if (bitArray.readBits(24) != 0x564342) {
            throw ParserException.createForMalformedContainer(
                "expected code book to start with [0x56, 0x43, 0x42] at " + bitArray.position,  
                null
            )
        }
        val dimensions = bitArray.readBits(16)
        val entries = bitArray.readBits(24)

        val isOrdered = bitArray.readBit()
        if (!isOrdered) {
            val isSparse = bitArray.readBit()
            for (i in 0..<entries) {
                if (isSparse) {
                    if (bitArray.readBit()) {
                        bitArray.skipBits(5) // lengthMap entry
                    }
                } else { // not sparse
                    bitArray.skipBits(5) // lengthMap entry
                }
            }
        } else {
            bitArray.skipBits(5) // length
            var i = 0
            while (i < entries) {
                i += bitArray.readBits(iLog(entries - i)) // num
            }
        }

        val lookupType = bitArray.readBits(4)
        if (lookupType > 2) {
            throw ParserException.createForMalformedContainer(
                "lookup type greater than 2 not decodable: " + lookupType,  null
            )
        } else if (lookupType == 1 || lookupType == 2) {
            bitArray.skipBits(32) // minimumValue
            bitArray.skipBits(32) // deltaValue
            val valueBits = bitArray.readBits(4) + 1
            bitArray.skipBits(1) // sequenceP
            val lookupValuesCount: Long
            if (lookupType == 1) {
                if (dimensions != 0) {
                    lookupValuesCount = mapType1QuantValues(entries.toLong(), dimensions.toLong())
                } else {
                    lookupValuesCount = 0
                }
            } else {
                lookupValuesCount = entries.toLong() * dimensions
            }
            // discard (no decoding required yet)
            bitArray.skipBits((lookupValuesCount * valueBits).toInt())
        }
    }

    private fun mapType1QuantValues(entries: Long, dimension: Long): Long {
        return floor(entries.toDouble().pow(1.0 / dimension)).toLong()
    }

    class CommentHeader(val vendor: String?, val comments: Array<String>, val length: Int)

    class VorbisIdHeader(
        val version: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitrateMaximum: Int,
        val bitrateNominal: Int,
        val bitrateMinimum: Int,
        val blockSize0: Int,
        val blockSize1: Int,
        val framingFlag: Boolean,
        val data: ByteArray
    )

    class Mode(
        val blockFlag: Boolean,
        val windowType: Int,
        val transformType: Int,
        val mapping: Int
    )
}
