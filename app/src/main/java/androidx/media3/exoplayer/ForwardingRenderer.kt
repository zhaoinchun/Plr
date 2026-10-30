package androidx.media3.exoplayer

class ForwardingRenderer(private val renderer: Renderer) : Renderer by renderer
