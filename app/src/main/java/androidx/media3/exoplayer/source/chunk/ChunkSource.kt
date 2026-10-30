package androidx.media3.exoplayer.source.chunk

import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import java.io.IOException

interface ChunkSource {
    fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long

    @Throws(IOException::class)
    fun maybeThrowError()

    fun getPreferredQueueSize(playbackPositionUs: Long, queue: List<MediaChunk>): Int

    fun shouldCancelLoad(
        playbackPositionUs: Long, loadingChunk: Chunk, queue: List<MediaChunk>
    ): Boolean

    fun getNextChunk(
        loadingInfo: LoadingInfo,
        loadPositionUs: Long,
        queue: List<MediaChunk>,
        out: ChunkHolder
    )

    fun onChunkLoadCompleted(chunk: Chunk)

    fun onChunkLoadError(
        chunk: Chunk,
        cancelable: Boolean,
        loadErrorInfo: LoadErrorInfo,
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy
    ): Boolean

    fun release()
}
