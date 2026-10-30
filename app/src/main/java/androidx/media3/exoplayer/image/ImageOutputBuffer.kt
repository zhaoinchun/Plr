package androidx.media3.exoplayer.image

import android.graphics.Bitmap
import androidx.annotation.CallSuper
import androidx.media3.decoder.DecoderOutputBuffer

abstract class ImageOutputBuffer : DecoderOutputBuffer() {
    var bitmap: Bitmap? = null

    @CallSuper
    override fun clear() {
        bitmap = null
        super.clear()
    }
}
