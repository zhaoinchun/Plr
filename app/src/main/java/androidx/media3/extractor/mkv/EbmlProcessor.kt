
package androidx.media3.extractor.mkv

import androidx.annotation.IntDef
import androidx.media3.common.ParserException

import androidx.media3.extractor.ExtractorInput
import java.io.IOException

interface EbmlProcessor {
    fun getElementType(id: Int):  Int

    fun isLevel1Element(id: Int): Boolean

    @Throws(ParserException::class)
    fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long)

    @Throws(ParserException::class)
    fun endMasterElement(id: Int)

    @Throws(ParserException::class)
    fun integerElement(id: Int, value: Long)

    @Throws(ParserException::class)
    fun floatElement(id: Int, value: Double)

    @Throws(ParserException::class)
    fun stringElement(id: Int, value: String)

    @Throws(IOException::class)
    fun binaryElement(id: Int, contentsSize: Int, input: ExtractorInput)

    companion object {
        const val ELEMENT_TYPE_UNKNOWN: Int = 0
        const val ELEMENT_TYPE_MASTER: Int = 1
        const val ELEMENT_TYPE_UNSIGNED_INT: Int = 2
        const val ELEMENT_TYPE_STRING: Int = 3
        const val ELEMENT_TYPE_BINARY: Int = 4
        const val ELEMENT_TYPE_FLOAT: Int = 5
    }
}
