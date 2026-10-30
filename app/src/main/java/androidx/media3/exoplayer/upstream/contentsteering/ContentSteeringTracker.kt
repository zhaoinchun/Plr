package androidx.media3.exoplayer.upstream.contentsteering

import android.net.Uri

import androidx.media3.exoplayer.source.MediaSourceEventListener

interface ContentSteeringTracker {
    fun start(
        initialSteeringManifestUri: Uri?,
        initialPathwayId: String?,
        eventDispatcher: MediaSourceEventListener.EventDispatcher?
    )

    fun excludeCurrentPathway(excludeDurationMs: Long): Boolean
    val isActive: Boolean
    fun stop()
}
