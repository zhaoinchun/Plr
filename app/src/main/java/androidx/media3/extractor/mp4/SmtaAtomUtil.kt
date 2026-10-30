
package androidx.media3.extractor.mp4

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.container.Mp4Box
import androidx.media3.extractor.metadata.mp4.SmtaMetadataEntry

object SmtaAtomUtil {
    private const  val NO_VALUE = -1
    private const val CAMCORDER_NORMAL = 0
    private const val CAMCORDER_SINGLE_SUPERSLOW_MOTION = 7
    private const val CAMCORDER_FRC_SUPERSLOW_MOTION = 9
    private const val CAMCORDER_SLOW_MOTION_V2 = 12
    private const val CAMCORDER_SLOW_MOTION_V2_120 = 13
    private const val CAMCORDER_SLOW_MOTION_V2_HEVC = 21
    private const val CAMCORDER_FRC_SUPERSLOW_MOTION_HEVC = 22
    private const val CAMCORDER_QFRC_SUPERSLOW_MOTION = 23

    fun parseSmta(smta: ParsableByteArray, limit: Int): Metadata? {
        smta.skipBytes(Mp4Box.FULL_HEADER_SIZE)
        while (smta.getPosition() < limit) {
            val atomPosition = smta.getPosition()
            val atomSize = smta.readInt()
            val atomType = smta.readInt()
            if (atomType == Mp4Box.TYPE_saut) {
                // Size (4), Type (4), Author (4), Recording mode (2), SVC layer count (2).
                if (atomSize < 16) {
                    return null
                }
                smta.skipBytes(4) // Author (4)

                // Each field is stored as a key (1  byte) value (1 byte) pairs.
                // The order of the fields is not guaranteed.
                var recordingMode: @RecordingMode Int = NO_VALUE
                var svcTemporalLayerCount = 0
                for (i in 0..1) {
                    val key = smta.readUnsignedByte()
                    val value = smta.readUnsignedByte()
                    if (key == 0x00) { // recordingMode key
                        recordingMode = value
                    } else if (key == 0x01) { // svcTemporalLayerCount key
                        svcTemporalLayerCount = value
                    }
                }

                val captureFrameRate = getCaptureFrameRate(recordingMode, smta, limit)
                if (captureFrameRate == C.RATE_UNSET_INT) {
                    return null
                }

                return Metadata(
                    listOf(SmtaMetadataEntry(
                        captureFrameRate.toFloat(),
                        svcTemporalLayerCount
                    ))
                )
            }
            smta.setPosition(atomPosition + atomSize)
        }
        return null
    }

    private fun getCaptureFrameRate(
        recordingMode: @RecordingMode Int, smta: ParsableByteArray, limit: Int
    ): Int {
        // V2 and V2_120 have fixed capture frame rates.
        if (recordingMode == CAMCORDER_SLOW_MOTION_V2) {
            return 240
        } else if (recordingMode == CAMCORDER_SLOW_MOTION_V2_120) {
            return 120
        } else if (recordingMode != CAMCORDER_SLOW_MOTION_V2_HEVC) {
            return C.RATE_UNSET_INT
        }

        if (smta.bytesLeft() < Mp4Box.HEADER_SIZE || smta.getPosition() + Mp4Box.HEADER_SIZE > limit) {
            return C.RATE_UNSET_INT
        }

        val atomSize = smta.readInt()
        val atomType = smta.readInt()
        if (atomSize < 12 || atomType != Mp4Box.TYPE_srfr) {
            return C.RATE_UNSET_INT
        }
        // Capture frame rate is in Q16 format.
        return smta.readUnsignedFixedPoint1616()
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        open = true,
        value = [NO_VALUE, CAMCORDER_NORMAL, CAMCORDER_SINGLE_SUPERSLOW_MOTION, CAMCORDER_FRC_SUPERSLOW_MOTION, CAMCORDER_SLOW_MOTION_V2, CAMCORDER_SLOW_MOTION_V2_120, CAMCORDER_SLOW_MOTION_V2_HEVC, CAMCORDER_FRC_SUPERSLOW_MOTION_HEVC, CAMCORDER_QFRC_SUPERSLOW_MOTION
        ]
    )
    private annotation class RecordingMode
}
