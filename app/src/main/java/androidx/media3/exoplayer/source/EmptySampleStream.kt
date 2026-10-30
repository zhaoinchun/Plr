package androidx.media3.exoplayer.source

import androidx.media3.common.C
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleStream.ReadFlags

class EmptySampleStream : SampleStream {
    override val isReady: Boolean
        get() = true

    override fun maybeThrowError() {
        // Do nothing.
    }

    override fun readData(
        formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: @ReadFlags Int
    ): Int {
        buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
        return C.RESULT_BUFFER_READ
    }

    override fun skipData(positionUs: Long): Int {
        return 0
    }
}
