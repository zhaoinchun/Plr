package androidx.media3.common

interface OnInputFrameProcessedListener {
    @Throws(VideoFrameProcessingException::class)
    fun onInputFrameProcessed(textureId: Int, syncObject: Long)
}
