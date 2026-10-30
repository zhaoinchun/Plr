package androidx.media3.extractor

import androidx.media3.common.C.BufferFlags
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.TrackOutput.CryptoData
import androidx.media3.extractor.TrackOutput.SampleDataPart
import java.io.IOException

open class ForwardingTrackOutput(private val trackOutput: TrackOutput) :
    TrackOutput {
    override fun durationUs(durationUs: Long) {
        trackOutput.durationUs(durationUs)
    }

    override fun format(format: Format) {
        trackOutput.format(format)
    }

    @Throws(IOException::class)
    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int {
        return trackOutput.sampleData(input, length, allowEndOfInput)
    }

    override fun sampleData(data: ParsableByteArray, length: Int) {
        trackOutput.sampleData(data, length)
    }

    @Throws(IOException::class)
    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: @SampleDataPart Int
    ): Int {
        return trackOutput.sampleData(input, length, allowEndOfInput, sampleDataPart)
    }

    override fun sampleData(
        data: ParsableByteArray,
        length: Int,
        sampleDataPart: @SampleDataPart Int
    ) {
        trackOutput.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: @BufferFlags Int,
        size: Int,
        offset: Int,
        cryptoData: CryptoData?
    ) {
        trackOutput.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }
}
