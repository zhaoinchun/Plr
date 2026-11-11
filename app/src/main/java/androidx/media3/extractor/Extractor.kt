package androidx.media3.extractor

import androidx.annotation.IntDef
import androidx.media3.common.C
import org.checkerframework.dataflow.qual.SideEffectFree
import java.io.IOException

interface Extractor {
    @Throws(IOException::class)
    fun sniff(input: ExtractorInput): Boolean

    val sniffFailureDetails: List<SniffFailure>
        get() = listOf()

    fun init(output: ExtractorOutput)

    @Throws(IOException::class)
    fun read(input: ExtractorInput, seekPosition: PositionHolder):  Int
    fun seek(position: Long, timeUs: Long)
    fun release()

    @get:SideEffectFree
    val underlyingImplementation: Extractor
        get() = this

    companion object {
        const val RESULT_CONTINUE: Int = 0
        const val RESULT_SEEK: Int = 1
        const val RESULT_END_OF_INPUT: Int = C.RESULT_END_OF_INPUT
    }
}
