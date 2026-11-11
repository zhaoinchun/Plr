package androidx.media3.exoplayer.source

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import java.io.IOException

interface SampleStream {

    val isReady: Boolean

    @Throws(IOException::class)
    fun maybeThrowError()

    fun readData(
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags:  Int
    ):  Int

    fun skipData(positionUs: Long): Int

    val flags:  Int
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
