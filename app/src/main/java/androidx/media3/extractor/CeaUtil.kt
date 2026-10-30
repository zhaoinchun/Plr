
package androidx.media3.extractor

import androidx.media3.common.C
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableByteArray

import com.google.common.base.Preconditions

object CeaUtil {
    private const val TAG = "CeaUtil"

    const val USER_DATA_IDENTIFIER_GA94: Int = 0x47413934
    const val USER_DATA_TYPE_CODE_MPEG_CC: Int = 0x3

    private const val PAYLOAD_TYPE_CC = 4
    private const val COUNTRY_CODE = 0xB5
    private const val PROVIDER_CODE_ATSC = 0x31
    private const val PROVIDER_CODE_DIRECTV = 0x2F

    fun consume(
        presentationTimeUs: Long, seiBuffer: ParsableByteArray, outputs: Array<TrackOutput?>
    ) {
        while (seiBuffer.bytesLeft() > 1 ) {
            val payloadType = readNon255TerminatedValue(seiBuffer)
            val payloadSize = readNon255TerminatedValue(seiBuffer)
            var nextPayloadPosition = seiBuffer.getPosition() + payloadSize
            // Process the payload.
            if (payloadSize == -1 || payloadSize > seiBuffer.bytesLeft()) {
                // This might occur if we're trying to read an encrypted SEI NAL unit.
                w(TAG, "Skipping remainder of malformed SEI NAL unit.")
                nextPayloadPosition = seiBuffer.limit()
            } else if (payloadType == PAYLOAD_TYPE_CC && payloadSize >= 8) {
                val countryCode = seiBuffer.readUnsignedByte()
                val providerCode = seiBuffer.readUnsignedShort()
                var userIdentifier = 0
                if (providerCode == PROVIDER_CODE_ATSC) {
                    userIdentifier = seiBuffer.readInt()
                }
                val userDataTypeCode = seiBuffer.readUnsignedByte()
                if (providerCode == PROVIDER_CODE_DIRECTV) {
                    seiBuffer.skipBytes(1) // user_data_length.
                }
                var messageIsSupportedCeaCaption =
                    countryCode == COUNTRY_CODE && (providerCode == PROVIDER_CODE_ATSC || providerCode == PROVIDER_CODE_DIRECTV)
                            && userDataTypeCode == USER_DATA_TYPE_CODE_MPEG_CC
                if (providerCode == PROVIDER_CODE_ATSC) {
                    messageIsSupportedCeaCaption =
                        messageIsSupportedCeaCaption and (userIdentifier == USER_DATA_IDENTIFIER_GA94)
                }
                if (messageIsSupportedCeaCaption) {
                    consumeCcData(presentationTimeUs, seiBuffer, outputs)
                }
            }
            seiBuffer.setPosition(nextPayloadPosition)
        }
    }

    fun consumeCcData(
        presentationTimeUs: Long, ccDataBuffer: ParsableByteArray, outputs: Array<TrackOutput?>
    ) {
        // First byte contains: reserved (1), process_cc_data_flag (1), zero_bit (1), cc_count (5).
        val firstByte = ccDataBuffer.readUnsignedByte()
        val processCcDataFlag = (firstByte and 0x40) != 0
        if (!processCcDataFlag) {
            // No need to process.
            return
        }
        val ccCount = firstByte and 0x1F
        ccDataBuffer.skipBytes(1) // Ignore em_data
        // Each data packet consists of 24 bits: marker bits (5) + cc_valid (1) + cc_type (2)
        // + cc_data_1 (8) + cc_data_2 (8).
        val sampleLength = ccCount * 3
        val sampleStartPosition = ccDataBuffer.getPosition()
        for (output in outputs) {
            ccDataBuffer.setPosition(sampleStartPosition)
            output?.sampleData(ccDataBuffer, sampleLength)
            Preconditions.checkState(presentationTimeUs != C.TIME_UNSET)
            output?.sampleMetadata(
                presentationTimeUs,
                C.BUFFER_FLAG_KEY_FRAME,
                sampleLength,  
                0,  
                null
            )
        }
    }

    private fun readNon255TerminatedValue(buffer: ParsableByteArray): Int {
        var b: Int
        var value = 0
        do {
            if (buffer.bytesLeft() == 0) {
                return -1
            }
            b = buffer.readUnsignedByte()
            value += b
        } while (b == 0xFF)
        return value
    }
}
