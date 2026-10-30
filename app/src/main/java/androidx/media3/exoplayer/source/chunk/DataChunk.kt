package androidx.media3.exoplayer.source.chunk

import androidx.media3.common.C
import androidx.media3.common.Format

import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil.closeQuietly
import androidx.media3.datasource.DataSpec
import java.io.IOException
import kotlin.concurrent.Volatile

abstract class DataChunk(
    dataSource: DataSource,
    dataSpec: DataSpec,
    type: @C.DataType Int,
    trackFormat: Format,
    trackSelectionReason: @C.SelectionReason Int,
    trackSelectionData: Any?,
    data: ByteArray?
) : Chunk(
    dataSource,
    dataSpec,
    type,
    trackFormat,
    trackSelectionReason,
    trackSelectionData,
    C.TIME_UNSET,
    C.TIME_UNSET,
    null
) {
    var dataHolder: ByteArray = data ?: Util.EMPTY_BYTE_ARRAY
        private set

    @Volatile
    private var loadCanceled = false

    // Loadable implementation
    override fun cancelLoad() {
        loadCanceled = true
    }

    @Throws(IOException::class)
    override fun load() {
        try {
            dataSource.open(dataSpec)
            var limit = 0
            var bytesRead = 0
            while (bytesRead != C.RESULT_END_OF_INPUT && !loadCanceled) {
                maybeExpandData(limit)
                bytesRead = dataSource.read(this.dataHolder, limit, READ_GRANULARITY)
                if (bytesRead != -1) {
                    limit += bytesRead
                }
            }
            if (!loadCanceled) {
                consume(this.dataHolder, limit)
            }
        } finally {
            closeQuietly(dataSource)
        }
    }

    @Throws(IOException::class)
    protected abstract fun consume(data: ByteArray?, limit: Int)

    private fun maybeExpandData(limit: Int) {
        if (dataHolder.size < limit + READ_GRANULARITY) {
            // The new length is calculated as (data.length + READ_GRANULARITY) rather than
            // (limit + READ_GRANULARITY) in order to avoid small increments in the length.
            this.dataHolder = dataHolder.copyOf(dataHolder.size + READ_GRANULARITY)
        }
    }

    companion object {
        private const val READ_GRANULARITY = 16 * 1024
    }
}
