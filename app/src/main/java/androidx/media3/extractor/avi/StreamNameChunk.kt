package androidx.media3.extractor.avi

import androidx.media3.common.util.ParsableByteArray

internal class StreamNameChunk private constructor(val name: String?) : AviChunk {
    override val type: Int = AviExtractor.FOURCC_strn

    companion object {
        fun parseFrom(body: ParsableByteArray): StreamNameChunk {
            return StreamNameChunk(body.readString(body.bytesLeft()))
        }
    }
}
