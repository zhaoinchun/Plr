package androidx.media3.common.util

import androidx.media3.common.VideoFrameProcessingException

interface OnInputFrameProcessedListener {
    @Throws(VideoFrameProcessingException::class)
    fun onInputFrameProcessed(textureId: Int, syncObject: Long)
}
