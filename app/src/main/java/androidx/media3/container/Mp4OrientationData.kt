package androidx.media3.container

import androidx.media3.common.Metadata
import com.google.common.base.Preconditions

class Mp4OrientationData(orientation: Int) : Metadata.Entry {
    val orientation: Int

    init {
        Preconditions.checkArgument(
            orientation == 0 || orientation == 90 || orientation == 180 || orientation == 270,
            "Unsupported orientation"
        )
        this.orientation = orientation
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is Mp4OrientationData) {
            return false
        }
        return orientation == other.orientation
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + Integer.hashCode(orientation)
        return result
    }

    override fun toString(): String {
        return "Orientation= " + orientation
    }
}
