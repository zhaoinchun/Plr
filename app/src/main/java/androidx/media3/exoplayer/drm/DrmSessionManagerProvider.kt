
package androidx.media3.exoplayer.drm

import androidx.media3.common.MediaItem

fun interface DrmSessionManagerProvider {
    fun get(mediaItem: MediaItem): DrmSessionManager
}
