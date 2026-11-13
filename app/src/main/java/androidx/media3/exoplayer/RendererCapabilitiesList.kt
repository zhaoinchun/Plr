package androidx.media3.exoplayer

interface RendererCapabilitiesList {
    val rendererCapabilities: Array<RendererCapabilities>?
    fun size(): Int
    fun release()
}
