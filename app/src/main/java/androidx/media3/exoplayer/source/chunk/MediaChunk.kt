package androidx.media3.exoplayer.source.chunk

import androidx.media3.common.C
import androidx.media3.common.Format

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

abstract class MediaChunk(
    dataSource: DataSource,
    dataSpec: DataSpec,
    trackFormat: Format,
    trackSelectionReason: @C.SelectionReason Int,
    trackSelectionData: Any?,
    startTimeUs: Long,
    endTimeUs: Long,
    val chunkIndex: Long,
    steeredPathwayId: String?
) : Chunk(
    dataSource,
    dataSpec,
    C.DATA_TYPE_MEDIA,
    trackFormat,
    trackSelectionReason,
    trackSelectionData,
    startTimeUs,
    endTimeUs,
    steeredPathwayId
) {
    open val nextChunkIndex: Long
        get() = if (chunkIndex != C.INDEX_UNSET.toLong()) chunkIndex + 1 else C.INDEX_UNSET.toLong()

    abstract val isLoadCompleted: Boolean
}
