package androidx.media3.common

import android.util.Pair

interface OverlaySettings {
    val alphaScale: Float
        get() = DEFAULT_ALPHA_SCALE

    val backgroundFrameAnchor: Pair<Float, Float>
        get() = DEFAULT_BACKGROUND_FRAME_ANCHOR

    val overlayFrameAnchor: Pair<Float, Float>
        get() = DEFAULT_OVERLAY_FRAME_ANCHOR

    val scale: Pair<Float, Float>
        get() = DEFAULT_SCALE

    val rotationDegrees: Float
        get() = DEFAULT_ROTATION_DEGREES

    val hdrLuminanceMultiplier: Float
        get() = DEFAULT_HDR_LUMINANCE_MULTIPLIER

    companion object {
        const val DEFAULT_ALPHA_SCALE: Float = 1f
        val DEFAULT_BACKGROUND_FRAME_ANCHOR: Pair<Float, Float> =
            Pair.create<Float, Float>(0f, 0f)
        val DEFAULT_OVERLAY_FRAME_ANCHOR: Pair<Float, Float> =
            Pair.create<Float, Float>(0f, 0f)
        val DEFAULT_SCALE: Pair<Float, Float> = Pair.create<Float, Float>(1f, 1f)
        const val DEFAULT_ROTATION_DEGREES: Float = 0f
        const val DEFAULT_HDR_LUMINANCE_MULTIPLIER: Float = 1f
    }
}
