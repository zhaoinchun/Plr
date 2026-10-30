package androidx.media3.extractor.avi

import androidx.media3.common.util.ParsableByteArray

internal class AviMainHeaderChunk  constructor(
    val frameDurationUs: Int,
    val flags: Int,
    val totalFrames: Int,
    val streams: Int
) : AviChunk {
    override val type: Int=AviExtractor.FOURCC_avih

    fun hasIndex(): Boolean {
        return (flags and AVIF_HAS_INDEX) == AVIF_HAS_INDEX
    }

    companion object {
        private const val AVIF_HAS_INDEX = 0x10

        fun parseFrom(body: ParsableByteArray): AviMainHeaderChunk {
            val microSecPerFrame = body.readLittleEndianInt()
            body.skipBytes(8) // Skip dwMaxBytesPerSec (4 bytes), dwPaddingGranularity (4 bytes).
            val flags = body.readLittleEndianInt()
            val totalFrames = body.readLittleEndianInt()
            body.skipBytes(4) // dwInitialFrames (4 bytes).
            val streams = body.readLittleEndianInt()
            body.skipBytes(12) // dwSuggestedBufferSize (4 bytes), dwWidth (4 bytes), dwHeight (4 bytes).
            return AviMainHeaderChunk(microSecPerFrame, flags, totalFrames, streams)
        }
    }
}
