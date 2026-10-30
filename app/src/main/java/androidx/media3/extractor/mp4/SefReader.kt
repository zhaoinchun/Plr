/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.mp4

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.metadata.mp4.SlowMotionData
import com.google.common.base.Splitter
import java.io.IOException

internal class SefReader {
    
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [STATE_SHOULD_CHECK_FOR_SEF, STATE_CHECKING_FOR_SEF, STATE_READING_SDRS, STATE_READING_SEF_DATA
        ]
    )
    private annotation class State

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [TYPE_SLOW_MOTION_DATA, TYPE_SUPER_SLOW_MOTION_DATA, TYPE_SUPER_SLOW_MOTION_BGM, TYPE_SUPER_SLOW_MOTION_EDIT_DATA, TYPE_SUPER_SLOW_DEFLICKERING_ON
        ]
    )
    private annotation class DataType

    private val dataReferences: MutableList<DataReference>
    private var readerState: @State Int
    private var tailLength = 0

    init {
        dataReferences = ArrayList<DataReference>()
        readerState = STATE_SHOULD_CHECK_FOR_SEF
    }

    fun reset() {
        dataReferences.clear()
        readerState = STATE_SHOULD_CHECK_FOR_SEF
    }

    @Throws(IOException::class)
    fun read(
        input: ExtractorInput,
        seekPosition: PositionHolder,
        slowMotionMetadataEntries: MutableList<Metadata.Entry>
    ): @Extractor.ReadResult Int {
        when (readerState) {
            STATE_SHOULD_CHECK_FOR_SEF -> {
                val inputLength = input.length
                seekPosition.position =
                    if (inputLength == C.LENGTH_UNSET.toLong() || inputLength < TAIL_FOOTER_LENGTH)
                        0
                    else
                        inputLength - TAIL_FOOTER_LENGTH
                readerState = STATE_CHECKING_FOR_SEF
            }

            STATE_CHECKING_FOR_SEF -> checkForSefData(input, seekPosition)
            STATE_READING_SDRS -> readSdrs(input, seekPosition)
            STATE_READING_SEF_DATA -> {
                readSefData(input, slowMotionMetadataEntries)
                seekPosition.position = 0
            }

            else -> throw IllegalStateException()
        }
        return Extractor.RESULT_SEEK
    }

    @Throws(IOException::class)
    private fun checkForSefData(input: ExtractorInput, seekPosition: PositionHolder) {
        val scratch: ParsableByteArray = ParsableByteArray( TAIL_FOOTER_LENGTH)
        input.readFully(scratch.data,  0,  TAIL_FOOTER_LENGTH)
        tailLength = scratch.readLittleEndianInt() + TAIL_FOOTER_LENGTH
        if (scratch.readInt() != SAMSUNG_TAIL_SIGNATURE) {
            seekPosition.position = 0
            return
        }

        // input.getPosition is at the very end of the tail, so jump forward by tailLength, but
        // account for the tail header, which needs to be ignored.
        seekPosition.position = input.position - (tailLength - TAIL_HEADER_LENGTH)
        readerState = STATE_READING_SDRS
    }

    @Throws(IOException::class)
    private fun readSdrs(input: ExtractorInput, seekPosition: PositionHolder) {
        val streamLength = input.length
        val sdrsLength: Int = tailLength - TAIL_HEADER_LENGTH - TAIL_FOOTER_LENGTH
        val scratch = ParsableByteArray( sdrsLength)
        input.readFully(scratch.data,  0,  sdrsLength)

        for (i in 0..<sdrsLength / LENGTH_OF_ONE_SDR) {
            scratch.skipBytes(2) // SDR data sub info flag and reserved bits (2).
            val dataType: @DataType Int = scratch.readLittleEndianShort().toInt()
            when (dataType) {
                TYPE_SLOW_MOTION_DATA, TYPE_SUPER_SLOW_MOTION_DATA, TYPE_SUPER_SLOW_MOTION_BGM, TYPE_SUPER_SLOW_MOTION_EDIT_DATA, TYPE_SUPER_SLOW_DEFLICKERING_ON -> {
                    // The read int is the distance from the tail info to the start of the metadata.
                    // Calculated as an offset from the start by working backwards.
                    val startOffset = streamLength - tailLength - scratch.readLittleEndianInt()
                    val size = scratch.readLittleEndianInt()
                    dataReferences.add(DataReference(dataType, startOffset, size))
                }

                else -> scratch.skipBytes(8) // startPosition (4), size (4).
            }
        }

        if (dataReferences.isEmpty()) {
            seekPosition.position = 0
            return
        }

        readerState = STATE_READING_SEF_DATA
        seekPosition.position = dataReferences[0].startOffset
    }

    @Throws(IOException::class)
    private fun readSefData(
        input: ExtractorInput,
        slowMotionMetadataEntries: MutableList<Metadata.Entry>
    ) {
        val dataStartOffset = input.position
        val totalDataLength = (input.length - input.position - tailLength) as Int
        val data = ParsableByteArray( totalDataLength)
        input.readFully(data.data, 0, totalDataLength)

        for (i in dataReferences.indices) {
            val dataReference = dataReferences[i]
            val intendedPosition = (dataReference.startOffset - dataStartOffset).toInt()
            data.setPosition(intendedPosition)

            // The data type is derived from the name because the SEF format has inconsistent data type
            // values.
            data.skipBytes(4) // data type (2), data sub info (2).
            val nameLength = data.readLittleEndianInt()
            val name = data.readString(nameLength)
            val dataType: @DataType Int = nameToDataType(name)

            val remainingDataLength = dataReference.size - (8 + nameLength)
            when (dataType) {
                TYPE_SLOW_MOTION_DATA -> slowMotionMetadataEntries.add(
                    readSlowMotionData(
                        data,
                        remainingDataLength
                    )
                )

                TYPE_SUPER_SLOW_MOTION_DATA, TYPE_SUPER_SLOW_MOTION_BGM, TYPE_SUPER_SLOW_MOTION_EDIT_DATA, TYPE_SUPER_SLOW_DEFLICKERING_ON -> {}
                else -> throw IllegalStateException()
            }
        }
    }

    private class DataReference(val dataType: @DataType Int, val startOffset: Long, val size: Int)
    companion object {
        private const val STATE_SHOULD_CHECK_FOR_SEF = 0
        private const val STATE_CHECKING_FOR_SEF = 1
        private const val STATE_READING_SDRS = 2
        private const val STATE_READING_SEF_DATA = 3

        private const val TYPE_SLOW_MOTION_DATA = 0x0890 // 2192
        private const val TYPE_SUPER_SLOW_MOTION_DATA = 0x0b00 // 2816
        private const val TYPE_SUPER_SLOW_MOTION_BGM = 0x0b01 // 2817
        private const val TYPE_SUPER_SLOW_MOTION_EDIT_DATA = 0x0b03 // 2819
        private const val TYPE_SUPER_SLOW_DEFLICKERING_ON = 0x0b04 // 2820

        private const val TAG = "SefReader"

        private const val SAMSUNG_TAIL_SIGNATURE = 0x53454654

        private const val TAIL_HEADER_LENGTH = 12

        private const val TAIL_FOOTER_LENGTH = 8

        private const val LENGTH_OF_ONE_SDR = 12
        private val COLON_SPLITTER: Splitter = Splitter.on(':')
        private val ASTERISK_SPLITTER: Splitter = Splitter.on('*')

        @Throws(ParserException::class)
        private fun readSlowMotionData(data: ParsableByteArray, dataLength: Int): SlowMotionData {
            val segments: MutableList<SlowMotionData.Segment?> =
                ArrayList<SlowMotionData.Segment?>()
            val dataString = data.readString(dataLength)
            val segmentStrings: MutableList<String?> = ASTERISK_SPLITTER.splitToList(dataString)
            for (i in segmentStrings.indices) {
                val values: MutableList<String?> = COLON_SPLITTER.splitToList(segmentStrings[i])
                if (values.size != 3) {
                    throw ParserException.createForMalformedContainer( null,  
                        null
                    )
                }
                try {
                    val startTimeMs = values[0]!!.toLong()
                    val endTimeMs = values.get(1)!!.toLong()
                    val speedMode = values.get(2)!!.toInt()
                    val speedDivisor = 1 shl (speedMode - 1)
                    segments.add(SlowMotionData.Segment(startTimeMs, endTimeMs, speedDivisor))
                } catch (e: NumberFormatException) {
                    throw ParserException.createForMalformedContainer( null,  
                        e
                    )
                }
            }
            return SlowMotionData(segments)
        }

        @Throws(ParserException::class)
        private fun nameToDataType(name: String): @DataType Int {
            when (name) {
                "SlowMotion_Data" -> return TYPE_SLOW_MOTION_DATA
                "Super_SlowMotion_Data" -> return TYPE_SUPER_SLOW_MOTION_DATA
                "Super_SlowMotion_BGM" -> return TYPE_SUPER_SLOW_MOTION_BGM
                "Super_SlowMotion_Edit_Data" -> return TYPE_SUPER_SLOW_MOTION_EDIT_DATA
                "Super_SlowMotion_Deflickering_On" -> return TYPE_SUPER_SLOW_DEFLICKERING_ON
                else -> throw ParserException.createForMalformedContainer(
                    "Invalid SEF name",  
                    null
                )
            }
        }
    }
}
