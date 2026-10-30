package androidx.media3.exoplayer

interface RendererCapabilitiesList {
    interface Factory {
        fun createRendererCapabilitiesList(): RendererCapabilitiesList?
    }

    val rendererCapabilities: Array<RendererCapabilities>?
    fun size(): Int
    fun release()
}
