package androidx.media3.exoplayer.source.chunk

import androidx.media3.common.C
import androidx.media3.common.Format

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil.closeQuietly
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.chunk.ChunkExtractor.TrackOutputProvider
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput

import java.io.IOException
import kotlin.concurrent.Volatile

class InitializationChunk(
    dataSource: DataSource,
    dataSpec: DataSpec,
    trackFormat: Format,
    trackSelectionReason: @C.SelectionReason Int,
    trackSelectionData: Any?,
    private val chunkExtractor: ChunkExtractor
) : Chunk(
    dataSource,
    dataSpec,
    C.DATA_TYPE_MEDIA_INITIALIZATION,
    trackFormat,
    trackSelectionReason,
    trackSelectionData,
    C.TIME_UNSET,
    C.TIME_UNSET,
    null
) {
    private var trackOutputProvider:TrackOutputProvider? = null
    var chunkIndex: ChunkIndex? = null
        private set
    private var nextLoadPosition: Long = 0

    @Volatile
    private var loadCanceled = false

    fun init(trackOutputProvider: TrackOutputProvider?) {
        this.trackOutputProvider = trackOutputProvider
    }

    // Loadable implementation.
    override fun cancelLoad() {
        loadCanceled = true
    }

    @Throws(IOException::class)
    override fun load() {
        if (nextLoadPosition == 0L) {
            chunkExtractor.init(
                trackOutputProvider, C.TIME_UNSET, C.TIME_UNSET
            )
        }
        try {
            // Create and open the input.
            val loadDataSpec = dataSpec.subrange(nextLoadPosition)
            val input: ExtractorInput =
                DefaultExtractorInput(
                    dataSource, loadDataSpec.position, dataSource.open(loadDataSpec)
                )
            // Load and decode the initialization data.
            try {
                while (!loadCanceled && chunkExtractor.read(input)) {
                }
            } finally {
                nextLoadPosition = input.position - dataSpec.position
                chunkIndex = chunkExtractor.chunkIndex
            }
        } finally {
            closeQuietly(dataSource)
        }
    }
}
