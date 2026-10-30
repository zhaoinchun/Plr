
package androidx.media3.extractor.mp4

import androidx.media3.common.C
import androidx.media3.common.util.Util.ceilDivide
import kotlin.math.max
import kotlin.math.min

internal object FixedSampleSizeRechunker {
    
    private val MAX_SAMPLE_SIZE = 8 * 1024

    fun rechunk(
        fixedSampleSize: Int,
        chunkOffsets: LongArray,
        chunkSampleCounts: IntArray,
        timestampDeltaInTimeUnits: Long
    ): Results {
        val maxSampleCount = MAX_SAMPLE_SIZE / fixedSampleSize

        // Count the number of new, rechunked buffers.
        var rechunkedSampleCount = 0
        for (chunkSampleCount in chunkSampleCounts) {
            rechunkedSampleCount += ceilDivide(chunkSampleCount, maxSampleCount)
        }

        val offsets = LongArray(rechunkedSampleCount)
        val sizes = IntArray(rechunkedSampleCount)
        var maximumSize = 0
        val timestamps = LongArray(rechunkedSampleCount)
        val flags = IntArray(rechunkedSampleCount)
        var totalSize = 0

        var originalSampleIndex = 0
        var newSampleIndex = 0
        for (chunkIndex in chunkSampleCounts.indices) {
            var chunkSamplesRemaining = chunkSampleCounts[chunkIndex]
            var sampleOffset = chunkOffsets[chunkIndex]

            while (chunkSamplesRemaining > 0) {
                val bufferSampleCount = min(maxSampleCount, chunkSamplesRemaining)

                offsets[newSampleIndex] = sampleOffset
                sizes[newSampleIndex] = fixedSampleSize * bufferSampleCount
                totalSize += sizes[newSampleIndex]
                maximumSize = max(maximumSize, sizes[newSampleIndex])
                timestamps[newSampleIndex] = (timestampDeltaInTimeUnits * originalSampleIndex)
                flags[newSampleIndex] = C.BUFFER_FLAG_KEY_FRAME

                sampleOffset += sizes[newSampleIndex].toLong()
                originalSampleIndex += bufferSampleCount

                chunkSamplesRemaining -= bufferSampleCount
                newSampleIndex++
            }
        }
        val duration = timestampDeltaInTimeUnits * originalSampleIndex

        return Results(offsets, sizes, maximumSize, timestamps, flags, duration, totalSize.toLong())
    }

    class Results  constructor(
        val offsets: LongArray,
        val sizes: IntArray,
        val maximumSize: Int,
        val timestamps: LongArray,
        val flags: IntArray,
        val duration: Long,
        val totalSize: Long
    )
}
