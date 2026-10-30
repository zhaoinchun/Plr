package androidx.media3.exoplayer.source.chunk

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

abstract class BaseMediaChunk(
    dataSource: DataSource,
    dataSpec: DataSpec,
    trackFormat: Format,
    trackSelectionReason: @C.SelectionReason Int,
    trackSelectionData: Any?,
    startTimeUs: Long,
    endTimeUs: Long,
    val clippedStartTimeUs: Long,
    val clippedEndTimeUs: Long,
    chunkIndex: Long
) : MediaChunk(
    dataSource,
    dataSpec,
    trackFormat,
    trackSelectionReason,
    trackSelectionData,
    startTimeUs,
    endTimeUs,
    chunkIndex,
    null
) {
    private var output:BaseMediaChunkOutput? = null
    private var firstSampleIndices:IntArray? = null

    fun init(output: BaseMediaChunkOutput) {
        this.output = output
        firstSampleIndices = output.writeIndices
    }

    fun getFirstSampleIndex(trackIndex: Int): Int {
        return firstSampleIndices!![trackIndex]
    }

    protected fun getOutput(): BaseMediaChunkOutput {
        return output!!
    }
}
