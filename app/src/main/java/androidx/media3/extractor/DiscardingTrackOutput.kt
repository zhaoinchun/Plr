package androidx.media3.extractor

import androidx.media3.common.C
import androidx.media3.common.C.BufferFlags
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.TrackOutput.CryptoData
import androidx.media3.extractor.TrackOutput.SampleDataPart
import java.io.EOFException
import java.io.IOException
import kotlin.math.min

class DiscardingTrackOutput : TrackOutput {
    // Even though read data is discarded, data source implementations could be making use of the
    // buffer contents. For example, caches. So we cannot use a static field for this which could be
    // shared between different threads.
    private val readBuffer: ByteArray

    init {
        readBuffer = ByteArray(4096)
    }

    override fun format(format: Format) {
        // Do nothing.
    }

    @Throws(IOException::class)
    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: @SampleDataPart Int
    ): Int {
        val bytesToSkipByReading = min(readBuffer.size, length)
        val bytesSkipped = input.read(readBuffer,  0, bytesToSkipByReading)
        if (bytesSkipped == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) {
                return C.RESULT_END_OF_INPUT
            }
            throw EOFException()
        }
        return bytesSkipped
    }

    override fun sampleData(
        data: ParsableByteArray,
        length: Int,
        sampleDataPart: @SampleDataPart Int
    ) {
        data.skipBytes(length)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: @BufferFlags Int,
        size: Int,
        offset: Int,
        cryptoData: CryptoData?
    ) {
        // Do nothing.
    }
}
