package androidx.media3.common.util

import androidx.media3.common.VideoFrameProcessingException

interface OnInputFrameProcessedListener {
    fun onInputFrameProcessed(textureId: Int, syncObject: Long)
}
