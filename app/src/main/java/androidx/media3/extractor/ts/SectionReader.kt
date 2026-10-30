
package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.Util
import androidx.media3.extractor.ExtractorOutput
import kotlin.math.max
import kotlin.math.min

class SectionReader(private val reader: SectionPayloadReader) : TsPayloadReader {
    private val sectionData: ParsableByteArray

    private var totalSectionLength = 0
    private var bytesRead = 0
    private var sectionSyntaxIndicator = false
    private var waitingForPayloadStart = false

    init {
        sectionData = ParsableByteArray(DEFAULT_SECTION_BUFFER_LENGTH)
    }

    override fun init(
        timestampAdjuster: TimestampAdjuster,
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator
    ) {
        reader.init(timestampAdjuster, extractorOutput, idGenerator)
        waitingForPayloadStart = true
    }

    override fun seek() {
        waitingForPayloadStart = true
    }

    override fun consume(data: ParsableByteArray, flags: @TsPayloadReader.Flags Int) {
        val payloadUnitStartIndicator =
            (flags and TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR) != 0
        var payloadStartPosition = C.INDEX_UNSET
        if (payloadUnitStartIndicator) {
            val payloadStartOffset: Int = data.readUnsignedByte()
            payloadStartPosition = data.getPosition() + payloadStartOffset
        }

        if (waitingForPayloadStart) {
            if (!payloadUnitStartIndicator) {
                return
            }
            waitingForPayloadStart = false
            data.setPosition(payloadStartPosition)
            bytesRead = 0
        }

        while (data.bytesLeft() > 0) {
            if (bytesRead < SECTION_HEADER_LENGTH) {
                // Note: see ISO/IEC 13818-1, section 2.4.4.3 for detailed information on the format of
                // the header.
                if (bytesRead == 0) {
                    val tableId: Int = data.readUnsignedByte()
                    data.setPosition(data.getPosition() - 1)
                    if (tableId == 0xFF ) {
                        // No more sections in this ts packet.
                        waitingForPayloadStart = true
                        return
                    }
                }
                val headerBytesToRead: Int =
                    min(data.bytesLeft(), SECTION_HEADER_LENGTH - bytesRead)
                // sectionData is guaranteed to have enough space because it's initialized with a 32-element
                // backing array and headerBytesToRead is at most 3.
                data.readBytes(sectionData.data, bytesRead, headerBytesToRead)
                bytesRead += headerBytesToRead
                if (bytesRead == SECTION_HEADER_LENGTH) {
                    sectionData.setPosition(0)
                    sectionData.setLimit(SECTION_HEADER_LENGTH)
                    sectionData.skipBytes(1) // Skip table id (8).
                    val secondHeaderByte = sectionData.readUnsignedByte()
                    val thirdHeaderByte = sectionData.readUnsignedByte()
                    sectionSyntaxIndicator = (secondHeaderByte and 0x80) != 0
                    totalSectionLength =
                        (((secondHeaderByte and 0x0F) shl 8) or thirdHeaderByte) + SECTION_HEADER_LENGTH
                    if (sectionData.capacity() < totalSectionLength) {
                        // Ensure there is enough space to keep the whole section.
                        val limit =
                            min(
                                MAX_SECTION_LENGTH,
                                max(totalSectionLength, sectionData.capacity() * 2)
                            )
                        sectionData.ensureCapacity(limit)
                    }
                }
            } else {
                // Reading the body.
                val bodyBytesToRead = min(data.bytesLeft(), totalSectionLength - bytesRead)
                // sectionData has been sized large enough for totalSectionLength when reading the header.
                data.readBytes(sectionData.data, bytesRead, bodyBytesToRead)
                bytesRead += bodyBytesToRead
                if (bytesRead == totalSectionLength) {
                    if (sectionSyntaxIndicator) {
                        // This section has common syntax as defined in ISO/IEC 13818-1, section 2.4.4.11.
                        if (Util.crc32(sectionData.data, 0, totalSectionLength, -0x1) != 0) {
                            // The CRC is invalid so discard the section.
                            waitingForPayloadStart = true
                            return
                        }
                        sectionData.setLimit(totalSectionLength - 4) // Exclude the CRC_32 field.
                    } else {
                        // This is a private section with private defined syntax.
                        sectionData.setLimit(totalSectionLength)
                    }
                    sectionData.setPosition(0)
                    reader.consume(sectionData)
                    bytesRead = 0
                }
            }
        }
    }

    companion object {
        private const val SECTION_HEADER_LENGTH = 3
        private const val DEFAULT_SECTION_BUFFER_LENGTH = 32
        private const val MAX_SECTION_LENGTH = 4098
    }
}
