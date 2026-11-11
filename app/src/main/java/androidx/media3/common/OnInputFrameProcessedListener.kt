package androidx.media3.common

interface OnInputFrameProcessedListener {
    fun onInputFrameProcessed(textureId: Int, syncObject: Long)
}
