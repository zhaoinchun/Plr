package androidx.media3.exoplayer.source.preload

import androidx.media3.common.MediaItem

interface PreloadManagerListener {
    fun onCompleted(mediaItem: MediaItem) {}
    fun onError(exception: PreloadException) {}
}
