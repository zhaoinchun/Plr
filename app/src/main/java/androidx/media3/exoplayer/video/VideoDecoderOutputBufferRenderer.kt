
package androidx.media3.exoplayer.video

import androidx.media3.decoder.VideoDecoderOutputBuffer

interface VideoDecoderOutputBufferRenderer {
    fun setOutputBuffer(outputBuffer: VideoDecoderOutputBuffer)
}
