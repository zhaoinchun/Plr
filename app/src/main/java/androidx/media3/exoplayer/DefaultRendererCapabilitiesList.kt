package androidx.media3.exoplayer

import androidx.media3.common.Metadata
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Util.createHandlerForCurrentOrMainLooper
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.video.VideoRendererEventListener

class DefaultRendererCapabilitiesList private constructor(renderers: Array<Renderer>) :
    RendererCapabilitiesList {
    private val renderers: Array<Renderer> = renderers.copyOf()

    init {
        for (i in renderers.indices) {
            this.renderers[i].init(i, PlayerId.UNSET, Clock.DEFAULT)
        }
    }

    override val rendererCapabilities: Array<RendererCapabilities>
        get() {
            return Array(renderers.size) {
                renderers[it].capabilities!!
            }
        }

    override fun size(): Int {
        return renderers.size
    }

    override fun release() {
        for (renderer in renderers) {
            renderer.release()
        }
    }

    companion object {
        fun create(renderersFactory: RenderersFactory): DefaultRendererCapabilitiesList {
            val renderers =
                renderersFactory.createRenderers(
                    createHandlerForCurrentOrMainLooper(),
                    object : VideoRendererEventListener {},
                    object : AudioRendererEventListener {},
                    { cueGroup: CueGroup? -> },
                    { metadata: Metadata? -> })
            return DefaultRendererCapabilitiesList(renderers)
        }
    }
}
