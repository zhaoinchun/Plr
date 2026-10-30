package androidx.media3.exoplayer.source.chunk

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.StatsDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.upstream.Loader.Loadable

abstract class Chunk(
    dataSource: DataSource,
    val dataSpec: DataSpec,
    val type: @C.DataType Int,
    val trackFormat: Format,
    val trackSelectionReason: @C.SelectionReason Int,
    val trackSelectionData: Any?,
    val startTimeUs: Long,
    val endTimeUs: Long,
    val steeredPathwayId: String?
) : Loadable {
    val loadTaskId: Long = LoadEventInfo.newId
    protected val dataSource = StatsDataSource(dataSource)

    val durationUs: Long
        get() = endTimeUs - startTimeUs

    fun bytesLoaded(): Long {
        return dataSource.bytesRead
    }

    val uri: Uri
        get() = dataSource.lastOpenedUri

    val responseHeaders: MutableMap<String, List<String>>
        get() = dataSource.lastResponseHeaders
}
