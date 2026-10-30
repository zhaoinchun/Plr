
package androidx.media3.exoplayer.offline

import androidx.media3.common.MediaItem
import java.util.concurrent.Executor

internal interface SegmentDownloaderFactory {
    fun setExecutor(executor: Executor): SegmentDownloaderFactory
    fun setMaxMergedSegmentStartTimeDiffMs(maxMergedSegmentStartTimeDiffMs: Long): SegmentDownloaderFactory
    fun setStartPositionUs(startPositionUs: Long): SegmentDownloaderFactory
    fun setDurationUs(durationUs: Long): SegmentDownloaderFactory
    fun create(mediaItem: MediaItem): SegmentDownloader<*>
}
