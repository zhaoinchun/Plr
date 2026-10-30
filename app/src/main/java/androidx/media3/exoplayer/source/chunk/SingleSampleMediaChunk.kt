package androidx.media3.exoplayer.source.chunk

import androidx.media3.common.C
import androidx.media3.common.Format

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil.closeQuietly
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import java.io.IOException

@Deprecated(
    """The only use for this class is subtitle playback, but it is only compatible with
      legacy subtitle decoding, which is not supported by default. Users of this class almost
      certainly need to {@linkplain TextRenderer#experimentalSetLegacyDecodingEnabled(boolean)
 *     enable legacy subtitle decoding} on their {@link ExoPlayer} instance in order to avoid
      playback errors when trying to play subtitles."""
)
class SingleSampleMediaChunk
    (
    dataSource: DataSource,
    dataSpec: DataSpec,
    trackFormat: Format,
    trackSelectionReason: @C.SelectionReason Int,
    trackSelectionData: Any?,
    startTimeUs: Long,
    endTimeUs: Long,
    chunkIndex: Long,
    private val trackType: @C.TrackType Int,
    private val sampleFormat: Format
) : BaseMediaChunk(
    dataSource,
    dataSpec,
    trackFormat,
    trackSelectionReason,
    trackSelectionData,
    startTimeUs,
    endTimeUs,
    C.TIME_UNSET,
    C.TIME_UNSET,
    chunkIndex
) {
    private var nextLoadPosition: Long = 0
    private var loadCompleted = false

    override val isLoadCompleted: Boolean
        get() = loadCompleted

    // Loadable implementation.
    override fun cancelLoad() {
        // Do nothing.
    }

    @Throws(IOException::class)
    override fun load() {
        val output = getOutput()
        output.setSampleOffsetUs(0)
        val trackOutput = output.track(0, trackType)
        trackOutput.format(sampleFormat)
        try {
            // Create and open the input.
            val loadDataSpec = dataSpec.subrange(nextLoadPosition)
            var length = dataSource.open(loadDataSpec)
            if (length != C.LENGTH_UNSET.toLong()) {
                length += nextLoadPosition
            }
            val extractorInput: ExtractorInput =
                DefaultExtractorInput(dataSource, nextLoadPosition, length)
            // Load the sample data.
            var result = 0
            while (result != C.RESULT_END_OF_INPUT) {
                nextLoadPosition += result.toLong()
                result = trackOutput.sampleData(extractorInput, Int.MAX_VALUE, true)
            }
            val sampleSize = nextLoadPosition.toInt()
            trackOutput.sampleMetadata(startTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleSize, 0, null)
        } finally {
            closeQuietly(dataSource)
        }
        loadCompleted = true
    }
}
