package androidx.media3.exoplayer.source

import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline

class TimelineWithUpdatedMediaItem private constructor(
    timeline: Timeline,
    private val updatedMediaItem: MediaItem
) : ForwardingTimeline(timeline) {
    @Suppress("deprecation") // Setting deprecated field for backward compatibility.
    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long
    ): Window {
        super.getWindow(windowIndex, window, defaultPositionProjectionUs)
        window.mediaItem = updatedMediaItem
        window.tag = updatedMediaItem.localConfiguration?.tag
        return window
    }

    companion object {
        fun create(timeline: Timeline, mediaItem: MediaItem): TimelineWithUpdatedMediaItem {
            if (timeline is TimelineWithUpdatedMediaItem) {
                // Replace existing wrapper instead of wrapping it further. This avoids keeping a reference to
                // the previous MediaItem, see https://github.com/androidx/media/issues/2993.
                return TimelineWithUpdatedMediaItem(
                    timeline.timeline, mediaItem
                )
            }
            return TimelineWithUpdatedMediaItem(timeline, mediaItem)
        }
    }
}
