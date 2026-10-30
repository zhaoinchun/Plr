package androidx.media3.exoplayer.image

import androidx.media3.common.Format

fun interface ImageMetadataListener {
    fun onImageAboutToBeAvailable(presentationTimeUs: Long, format: Format)
}
