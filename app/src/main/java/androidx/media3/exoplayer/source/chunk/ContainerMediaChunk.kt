
package androidx.media3.exoplayer.source.chunk

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes.isImage
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil.closeQuietly
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.chunk.ChunkExtractor.TrackOutputProvider
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import com.google.errorprone.annotations.ForOverride
import java.io.IOException
import kotlin.concurrent.Volatile

class ContainerMediaChunk(
    dataSource: DataSource,
    dataSpec: DataSpec,
    trackFormat: Format,
    trackSelectionReason: @C.SelectionReason Int,
    trackSelectionData: Any?,
    startTimeUs: Long,
    endTimeUs: Long,
    clippedStartTimeUs: Long,
    clippedEndTimeUs: Long,
    chunkIndex: Long,
    private val chunkCount: Int,
    private val sampleOffsetUs: Long,
    private val chunkExtractor: ChunkExtractor
) : BaseMediaChunk(
    dataSource,
    dataSpec,
    trackFormat,
    trackSelectionReason,
    trackSelectionData,
    startTimeUs,
    endTimeUs,
    clippedStartTimeUs,
    clippedEndTimeUs,
    chunkIndex
) {
    var nextLoadPosition: Long = 0
        private set
    @Volatile
    var isLoadCanceled: Boolean = false
        private set
    private var loadCompleted = false

    override val nextChunkIndex: Long
        get() = chunkIndex + chunkCount

    override val isLoadCompleted: Boolean
        get() = loadCompleted

    // Loadable implementation.
    override fun cancelLoad() {
        this.isLoadCanceled = true
    }

    @Throws(IOException::class)
    override fun load() {
        val output = getOutput()
        if (nextLoadPosition == 0L) {
            // Configure the output and set it as the target for the extractor wrapper.
            output.setSampleOffsetUs(sampleOffsetUs)
            chunkExtractor.init(
                getTrackOutputProvider(output),
                if (clippedStartTimeUs == C.TIME_UNSET) C.TIME_UNSET else (clippedStartTimeUs - sampleOffsetUs),
                if (clippedEndTimeUs == C.TIME_UNSET) C.TIME_UNSET else (clippedEndTimeUs - sampleOffsetUs)
            )
        }
        try {
            // Create and open the input.
            val loadDataSpec = dataSpec.subrange(nextLoadPosition)
            val input: ExtractorInput =
                DefaultExtractorInput(
                    dataSource, loadDataSpec.position, dataSource.open(loadDataSpec)
                )
            // Load and decode the sample data.
            try {
                while (!this.isLoadCanceled && chunkExtractor.read(input)) {
                }
                maybeWriteEmptySamples(output)
            } finally {
                nextLoadPosition = input.position - dataSpec.position
            }
        } finally {
            onLoadEnded()
            closeQuietly(dataSource)
        }
        loadCompleted = !this.isLoadCanceled
    }

    protected fun getTrackOutputProvider(baseMediaChunkOutput: BaseMediaChunkOutput?): TrackOutputProvider? {
        return baseMediaChunkOutput
    }

    @ForOverride
    protected fun onLoadEnded() {
    }

    private fun maybeWriteEmptySamples(output: BaseMediaChunkOutput) {
        if (!isImage(trackFormat.containerMimeType)) {
            return
        }
        if ((trackFormat.tileCountHorizontal <= 1 && trackFormat.tileCountVertical <= 1)
            || trackFormat.tileCountHorizontal == Format.NO_VALUE || trackFormat.tileCountVertical == Format.NO_VALUE
        ) {
            return
        }

        val trackOutput = output.track( 0, C.TRACK_TYPE_IMAGE)
        val tileCount = trackFormat.tileCountHorizontal * trackFormat.tileCountVertical
        val tileDurationUs = (endTimeUs - startTimeUs) / tileCount

        for (i in 1..<tileCount) {
            val tileStartTimeUs = i * tileDurationUs
            trackOutput.sampleData(ParsableByteArray(),  0)
            trackOutput.sampleMetadata(
                tileStartTimeUs,  
                0,  
                0,  
                0,  
                null
            )
        }
    }
}
