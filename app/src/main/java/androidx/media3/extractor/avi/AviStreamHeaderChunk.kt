package androidx.media3.extractor.avi

import androidx.media3.common.C
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util.scaleLargeTimestamp

internal class AviStreamHeaderChunk private constructor(
    val streamType: Int,
    val initialFrames: Int,
    val scale: Int,
    val rate: Int,
    val length: Int,
    val suggestedBufferSize: Int,
    val sampleSize: Int
) : AviChunk {
    override val type: Int=AviExtractor.FOURCC_strh

    val trackType: @C.TrackType Int
        get() {
            when (streamType) {
                AviExtractor.FOURCC_auds -> return C.TRACK_TYPE_AUDIO
                AviExtractor.FOURCC_vids -> return C.TRACK_TYPE_VIDEO
                AviExtractor.FOURCC_txts -> return C.TRACK_TYPE_TEXT
                else -> {
                    w(
                        TAG,
                        "Found unsupported streamType fourCC: " + Integer.toHexString(
                            streamType
                        )
                    )
                    return C.TRACK_TYPE_UNKNOWN
                }
            }
        }

    val frameRate: Float
        get() = rate / scale.toFloat()

    val durationUs: Long
        get() = scaleLargeTimestamp( 
            length.toLong(),  
            C.MICROS_PER_SECOND * scale,  
            rate.toLong()
        )

    companion object {
        private const val TAG = "AviStreamHeaderChunk"

        fun parseFrom(body: ParsableByteArray): AviStreamHeaderChunk {
            val streamType = body.readLittleEndianInt()
            body.skipBytes(12) // fccHandler (4 bytes), dwFlags (4 bytes), wPriority (2 bytes),
            // wLanguage (2 bytes).
            val initialFrames = body.readLittleEndianInt()
            val scale = body.readLittleEndianInt()
            val rate = body.readLittleEndianInt()
            body.skipBytes(4) // dwStart (4 bytes).
            val length = body.readLittleEndianInt()
            val suggestedBufferSize = body.readLittleEndianInt()
            body.skipBytes(4) // dwQuality (4 bytes).
            val sampleSize = body.readLittleEndianInt()
            return AviStreamHeaderChunk(
                streamType, initialFrames, scale, rate, length, suggestedBufferSize, sampleSize
            )
        }
    }
}
