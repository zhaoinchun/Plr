package androidx.media3.common

import android.os.Bundle

data class VideoSize(
    val width: Int,
    val height: Int,
    val pixelWidthHeightRatio: Float = DEFAULT_PIXEL_WIDTH_HEIGHT_RATIO
) {
    fun toBundle(): Bundle {
        val bundle = Bundle()
        if (width != 0) {
            bundle.putInt(FIELD_WIDTH, width)
        }
        if (height != 0) {
            bundle.putInt(FIELD_HEIGHT, height)
        }
        if (pixelWidthHeightRatio != 1f) {
            bundle.putFloat(FIELD_PIXEL_WIDTH_HEIGHT_RATIO, pixelWidthHeightRatio)
        }
        return bundle
    }

    companion object {
        private const val DEFAULT_PIXEL_WIDTH_HEIGHT_RATIO = 1f
        val UNKNOWN: VideoSize = VideoSize(0, 0)
        private const val FIELD_WIDTH = "0"
        private const val FIELD_HEIGHT = "1"
        private const val FIELD_PIXEL_WIDTH_HEIGHT_RATIO = "3"

        fun fromBundle(bundle: Bundle): VideoSize {
            val width = bundle.getInt(FIELD_WIDTH, 0)
            val height = bundle.getInt(FIELD_HEIGHT, 0)
            val pixelWidthHeightRatio =
                bundle.getFloat(FIELD_PIXEL_WIDTH_HEIGHT_RATIO, DEFAULT_PIXEL_WIDTH_HEIGHT_RATIO)
            return VideoSize(width, height, pixelWidthHeightRatio)
        }
    }
}
