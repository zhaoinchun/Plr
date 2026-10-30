
package androidx.media3.extractor.flv

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.TrackOutput

 abstract class TagPayloadReader constructor(protected val output: TrackOutput) {
    class UnsupportedFormatException(msg: String?) :
        ParserException(msg,  null,  false, C.DATA_TYPE_MEDIA)

    abstract fun seek()

    @Throws(ParserException::class)
    fun consume(data: ParsableByteArray, timeUs: Long): Boolean {
        return parseHeader(data) && parsePayload(data, timeUs)
    }

    @Throws(ParserException::class)
    protected abstract fun parseHeader(data: ParsableByteArray): Boolean

    @Throws(ParserException::class)
    protected abstract fun parsePayload(data: ParsableByteArray, timeUs: Long): Boolean
}
