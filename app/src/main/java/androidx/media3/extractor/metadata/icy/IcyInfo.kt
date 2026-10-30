package androidx.media3.extractor.metadata.icy

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata

class IcyInfo
(
    
    val rawMetadata: ByteArray,
    
    val title: String?,
    
    val url: String?
) : Metadata.Entry {
    override fun populateMediaMetadata(builder: MediaMetadata.Builder) {
        if (title != null) {
            builder.setTitle(title)
        }
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as IcyInfo
        // title & url are derived from rawMetadata, so no need to include them in the comparison.
        return rawMetadata.contentEquals(other.rawMetadata)
    }

    override fun hashCode(): Int {
        // title & url are derived from rawMetadata, so no need to include them in the hash.
        return rawMetadata.contentHashCode()
    }

    override fun toString(): String {
        return String.format(
            "ICY: title=\"%s\", url=\"%s\", rawMetadata.length=\"%s\"", title, url, rawMetadata.size
        )
    }
}
