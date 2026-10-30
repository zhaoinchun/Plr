package androidx.media3.exoplayer.source.preload

import androidx.media3.common.MediaItem

class PreloadException(
    val mediaItem: MediaItem?, message: String?, cause: Throwable?
) : Exception(message, cause) {

    fun errorInfoEquals(other: PreloadException?): Boolean {
        if (this == other) {
            return true
        }
        if (other == null || javaClass != other.javaClass) {
            return false
        }
        val thisCause = cause
        val thatCause = other.cause
        if (thisCause != null && thatCause != null) {
            if (thisCause.message != thatCause.message) {
                return false
            }
            if (thisCause.javaClass != thatCause.javaClass) {
                return false
            }
        } else if (thisCause != null || thatCause != null) {
            return false
        }
        return mediaItem == other.mediaItem
                && message == other.message
    }
}
