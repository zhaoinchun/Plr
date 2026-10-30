package androidx.media3.exoplayer.image

import android.graphics.Bitmap

interface ImageOutput {
    fun onImageAvailable(presentationTimeUs: Long, bitmap: Bitmap)
    fun onDisabled()

    companion object {
        val NO_OP: ImageOutput = object : ImageOutput {
            override fun onImageAvailable(presentationTimeUs: Long, bitmap: Bitmap) {
                // Do nothing.
            }

            override fun onDisabled() {
                // Do nothing.
            }
        }
    }
}
