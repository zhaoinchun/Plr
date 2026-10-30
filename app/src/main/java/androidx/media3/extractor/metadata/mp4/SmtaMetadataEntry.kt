
package androidx.media3.extractor.metadata.mp4

import androidx.media3.common.Metadata

import com.google.common.primitives.Floats

class SmtaMetadataEntry(

    val captureFrameRate: Float,
    val svcTemporalLayerCount: Int
) : Metadata.Entry {
    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as SmtaMetadataEntry
        return captureFrameRate == other.captureFrameRate
                && svcTemporalLayerCount == other.svcTemporalLayerCount
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + Floats.hashCode(captureFrameRate)
        result = 31 * result + svcTemporalLayerCount
        return result
    }

    override fun toString(): String {
        return ("smta: captureFrameRate="
                + captureFrameRate
                + ", svcTemporalLayerCount="
                + svcTemporalLayerCount)
    }
}
