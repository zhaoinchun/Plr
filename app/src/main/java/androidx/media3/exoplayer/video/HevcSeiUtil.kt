package androidx.media3.exoplayer.video

import androidx.media3.container.NalUnitUtil
import java.nio.ByteBuffer

object HevcSeiUtil {
    private const val SEI_PAYLOAD_TYPE_ITU_T_T35 = 4
    const val SEI_PAYLOAD_TYPE_UNSPECIFIED: Int = 254

    fun stripAllT35Metadata(buffer: ByteBuffer) {
        val position = buffer.position()
        val limit = buffer.limit()
        if (limit - position < 4) {
            return
        }

        var searchOffset = position

        while (searchOffset < limit) {
            val nalUnitOffset = findNalUnit(buffer, searchOffset, limit)
            if (nalUnitOffset == limit) {
                break // No more NAL units
            }

            if (nalUnitOffset + 3 >= limit) {
                break // Not enough bytes for a NAL header.
            }

            val nalUnitType = (buffer.get(nalUnitOffset + 3).toInt() and 0x7E) shr 1

            // NAL unit types 0 to 31 indicate VCL NAL units.
            // Prefix SEI messages always precede VCL NAL units, so we can safely stop parsing
            // once we hit a VCL NAL unit to avoid scanning the encoded video data.
            if (nalUnitType in 0..31) {
                break
            }

            if (nalUnitType == NalUnitUtil.H265_NAL_UNIT_TYPE_PREFIX_SEI) {
                var seiOffset =
                    nalUnitOffset + 3 + 2 // Skip 3-byte start code and 2-byte NAL header

                val nextNalUnitOffset = findNalUnit(buffer, seiOffset, limit)
                val seiLimit =
                    nextNalUnitOffset // The SEI payload ends where the next NAL unit starts

                var consecutiveZeros = 0 // State tracked across the entire SEI NAL unit

                while (seiOffset < seiLimit) {
                    // Read payloadType
                    var payloadType = 0
                    while (seiOffset < seiLimit) {
                        val b = buffer.get(seiOffset).toInt() and 0xFF
                        if (b == 0x03 && consecutiveZeros >= 2) {
                            consecutiveZeros = 0
                            seiOffset++
                            continue
                        }
                        consecutiveZeros = if (b == 0x00) consecutiveZeros + 1 else 0

                        val currentOffset = seiOffset
                        seiOffset++
                        payloadType += b
                        if (b != 0xFF) {
                            if (payloadType == SEI_PAYLOAD_TYPE_ITU_T_T35) {
                                buffer.put(currentOffset, SEI_PAYLOAD_TYPE_UNSPECIFIED.toByte())
                            }
                            break
                        }
                    }

                    if (seiOffset >= seiLimit) {
                        break
                    }

                    // Read payloadSize
                    var payloadSize = 0
                    while (seiOffset < seiLimit) {
                        val b = buffer.get(seiOffset).toInt() and 0xFF
                        if (b == 0x03 && consecutiveZeros >= 2) {
                            consecutiveZeros = 0
                            seiOffset++
                            continue
                        }
                        consecutiveZeros = if (b == 0x00) consecutiveZeros + 1 else 0

                        seiOffset++
                        payloadSize += b
                        if (b != 0xFF) {
                            break
                        }
                    }

                    // Skip the logical payloadSize bytes, taking into account physical emulation
                    // prevention bytes (0x03) that might be in the payload.
                    var i = 0
                    while (i < payloadSize) {
                        if (seiOffset >= seiLimit) {
                            break
                        }
                        val b = buffer.get(seiOffset++).toInt() and 0xFF
                        if (b == 0x03 && consecutiveZeros >= 2) {
                            // Emulation prevention byte. Doesn't count towards logical payloadSize.
                            consecutiveZeros = 0
                            i--
                        } else {
                            consecutiveZeros = if (b == 0x00) consecutiveZeros + 1 else 0
                        }
                        i++
                    }
                }
                searchOffset = nextNalUnitOffset
            } else {
                searchOffset = nalUnitOffset + 3
            }
        }
    }

    private fun findNalUnit(buffer: ByteBuffer, offset: Int, limit: Int): Int {
        var consecutiveZeros = 0
        var currentOffset = offset
        while (currentOffset < limit) {
            val b = buffer.get(currentOffset).toInt() and 0xFF
            if (consecutiveZeros >= 2 && b == 0x01) {
                return currentOffset - 2
            }
            consecutiveZeros = if (b == 0x00) consecutiveZeros + 1 else 0
            currentOffset++
        }
        return limit
    }
}
