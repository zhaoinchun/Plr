package androidx.media3.extractor.avi

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray

internal class ListChunk private constructor(
    override val type: Int,
    val children: List<AviChunk>
) : AviChunk {

    fun <T : AviChunk?> getChild(c: Class<T>): T? {
        for (aviChunk in children) {
            if (aviChunk.javaClass == c) {
                return aviChunk as T
            }
        }
        return null
    }

    companion object {
        fun parseFrom(listType: Int, body: ParsableByteArray): ListChunk {
            val builder = mutableListOf<AviChunk>()
            val listBodyEndPosition = body.limit()
            var currentTrackType: @C.TrackType Int = C.TRACK_TYPE_NONE
            while (body.bytesLeft() > 8) {
                val type = body.readLittleEndianInt()
                val size = body.readLittleEndianInt()
                val innerBoxBodyEndPosition = body.getPosition() + size
                body.setLimit(innerBoxBodyEndPosition)
                val aviChunk: AviChunk?
                if (type == AviExtractor.FOURCC_LIST) {
                    val innerListType = body.readLittleEndianInt()
                    aviChunk = parseFrom(innerListType, body)
                } else {
                    aviChunk = createBox(type, currentTrackType, body)
                }
                if (aviChunk != null) {
                    if (aviChunk.type == AviExtractor.FOURCC_strh) {
                        currentTrackType = (aviChunk as AviStreamHeaderChunk).trackType
                    }
                    builder.add(aviChunk)
                }
                body.setPosition(innerBoxBodyEndPosition)
                body.setLimit(listBodyEndPosition)
            }
            return ListChunk(listType, builder.toList())
        }

        private fun createBox(
            chunkType: Int, trackType: @C.TrackType Int, body: ParsableByteArray
        ): AviChunk? {
            when (chunkType) {
                AviExtractor.FOURCC_avih -> return AviMainHeaderChunk.parseFrom(
                    body
                )

                AviExtractor.FOURCC_strh -> return AviStreamHeaderChunk.parseFrom(
                    body
                )

                AviExtractor.FOURCC_strf -> return StreamFormatChunk.parseFrom(
                    trackType,
                    body
                )

                AviExtractor.FOURCC_strn -> return StreamNameChunk.parseFrom(
                    body
                )

                else -> return null
            }
        }
    }
}
