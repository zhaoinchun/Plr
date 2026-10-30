package androidx.media3.decoder

import androidx.annotation.CallSuper

abstract class DecoderOutputBuffer : Buffer() {
    interface Owner<S : DecoderOutputBuffer> {
        fun releaseOutputBuffer(outputBuffer: S?)
    }

    var timeUs: Long = 0
    var skippedOutputBufferCount: Int = 0
    var shouldBeSkipped: Boolean = false

    abstract fun release()

    @CallSuper
    override fun clear() {
        super.clear()
        timeUs = 0
        skippedOutputBufferCount = 0
        shouldBeSkipped = false
    }
}
