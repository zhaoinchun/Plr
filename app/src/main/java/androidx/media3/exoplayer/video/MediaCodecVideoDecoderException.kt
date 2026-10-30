package androidx.media3.exoplayer.video

import android.view.Surface
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo

class MediaCodecVideoDecoderException(
    cause: Throwable,
    codecInfo: MediaCodecInfo?,
    surface: Surface?
) : MediaCodecDecoderException(cause, codecInfo) {
    val surfaceIdentityHashCode= System.identityHashCode(surface)
    val isSurfaceValid= surface == null || surface.isValid

}
