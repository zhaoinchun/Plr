
package androidx.media3.exoplayer.video

import android.media.MediaFormat
import androidx.media3.common.Format

fun interface VideoFrameMetadataListener {
    fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?
    )
}
