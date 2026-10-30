
package androidx.media3.exoplayer

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.MediaSource

internal interface MediaSourceInfoHolder {
    val uid: Any?
    val timeline: Timeline?
}
