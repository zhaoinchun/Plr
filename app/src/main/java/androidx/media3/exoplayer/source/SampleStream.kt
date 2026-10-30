package androidx.media3.exoplayer.source

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import java.io.IOException

interface SampleStream {

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(flag = true, value = [FLAG_PEEK, FLAG_REQUIRE_FORMAT, FLAG_OMIT_SAMPLE_DATA])
    annotation class ReadFlags

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true, value = [FLAG_STRICT_DURATION, FLAG_MAYBE_HAS_PREROLL, FLAG_HAS_PREROLL]
    )
    annotation class Flags

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [C.RESULT_NOTHING_READ, C.RESULT_FORMAT_READ, C.RESULT_BUFFER_READ])
    annotation class ReadDataResult

    val isReady: Boolean

    @Throws(IOException::class)
    fun maybeThrowError()

    fun readData(
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: @ReadFlags Int
    ): @ReadDataResult Int

    fun skipData(positionUs: Long): Int

    val flags: @Flags Int
        get() = 0

    companion object {
        const val FLAG_PEEK = 1
        const val FLAG_REQUIRE_FORMAT = 1 shl 1
        const val FLAG_OMIT_SAMPLE_DATA = 1 shl 2
        const val FLAG_STRICT_DURATION = 1
        const val FLAG_MAYBE_HAS_PREROLL = 1 shl 1
        const val FLAG_HAS_PREROLL = 1 shl 2
    }
}
