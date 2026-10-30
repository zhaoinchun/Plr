package androidx.media3.exoplayer.offline

import androidx.media3.common.StreamKey

interface FilterableManifest<T> {
    fun copy(streamKeys: List<StreamKey>): T
}
