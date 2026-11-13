
package androidx.media3.exoplayer

import android.os.Handler
import androidx.media3.common.Metadata

import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener

interface RenderersFactory {
    fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput?,
        onMetadata: (metadata: Metadata)-> Unit
    ): Array<Renderer>

    fun createSecondaryRenderer(
        renderer: Renderer,
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener?,
        audioRendererEventListener: AudioRendererEventListener?,
        textRendererOutput: TextOutput?,
        onMetadata: ((metadata: Metadata)-> Unit)?
    ): Renderer? {
        return null
    }
}
