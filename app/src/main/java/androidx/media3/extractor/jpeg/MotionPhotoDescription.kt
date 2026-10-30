package androidx.media3.extractor.jpeg

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.metadata.MotionPhotoMetadata

internal class MotionPhotoDescription(
    val photoPresentationTimestampUs: Long,
    val items: MutableList<ContainerItem>
) {
    class ContainerItem(
        val mime: String,
        val semantic: String?,
        val length: Long,
        val padding: Long
    )

    fun getMotionPhotoMetadata(motionPhotoLength: Long): MotionPhotoMetadata? {
        if (items.size < 2) {
            // We need a primary item (photo) and at least one secondary item (video).
            return null
        }
        // Iterate backwards through the items to find the earlier video in the list. If we find a video
        // item with length zero, we need to keep scanning backwards to find the preceding item with
        // non-zero length, which is the item that contains the video data.
        var photoStartPosition = C.INDEX_UNSET.toLong()
        var photoLength = C.LENGTH_UNSET.toLong()
        var videoStartPosition = C.INDEX_UNSET.toLong()
        var videoLength = C.LENGTH_UNSET.toLong()
        var itemStartPosition = motionPhotoLength
        var itemEndPosition = motionPhotoLength
        for (i in items.indices.reversed()) {
            val item = items[i]
            var itemContainsBmffVideo =
                item.mime == MimeTypes.VIDEO_MP4 || item.mime == MimeTypes.VIDEO_QUICK_TIME
            itemEndPosition = itemStartPosition
            if (i == 0) {
                // Padding is only applied for the primary item.
                itemStartPosition = 0
                itemEndPosition -= item.padding
            } else {
                itemStartPosition -= item.length
            }
            if (itemContainsBmffVideo && itemStartPosition != itemEndPosition) {
                videoStartPosition = itemStartPosition
                videoLength = itemEndPosition - itemStartPosition
                // Reset in case there's another video earlier in the list.
                itemContainsBmffVideo = false
            }
            if (i == 0) {
                photoStartPosition = itemStartPosition
                photoLength = itemEndPosition
            }
        }
        if (videoStartPosition == C.INDEX_UNSET.toLong() || videoLength == C.LENGTH_UNSET.toLong() || photoStartPosition == C.INDEX_UNSET.toLong() || photoLength == C.LENGTH_UNSET.toLong()) {
            return null
        }
        return MotionPhotoMetadata(
            photoStartPosition,
            photoLength,
            photoPresentationTimestampUs,
            videoStartPosition,
            videoLength
        )
    }
}
