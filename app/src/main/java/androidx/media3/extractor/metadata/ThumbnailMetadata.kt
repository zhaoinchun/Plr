
package androidx.media3.extractor.metadata

import androidx.media3.common.Metadata
import com.google.common.primitives.Longs

class ThumbnailMetadata(
    val presentationTimeUs: Long
) : Metadata.Entry {
    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || javaClass != o.javaClass) {
            return false
        }
        val that = o as ThumbnailMetadata
        return presentationTimeUs == that.presentationTimeUs
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + Longs.hashCode(presentationTimeUs)
        return result
    }

    override fun toString(): String {
        return "ThumbnailMetadata: presentationTimeUs=" + presentationTimeUs
    }
}
