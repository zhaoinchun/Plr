package androidx.media3.common

import androidx.media3.common.util.Size

interface VideoCompositorSettings {
    fun getOutputSize(inputSizes: MutableList<Size>): Size

    fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings?

    companion object {
        // TODO: b/262694346 - Consider adding more features, like selecting a:
        //  * custom order for drawing (instead of primary stream on top), and
        //  * different primary source.
        val DEFAULT: VideoCompositorSettings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: MutableList<Size>): Size {
                return inputSizes[0]
            }

            override fun getOverlaySettings(
                inputId: Int,
                presentationTimeUs: Long
            ): OverlaySettings {
                return object : OverlaySettings {}
            }
        }
    }
}
