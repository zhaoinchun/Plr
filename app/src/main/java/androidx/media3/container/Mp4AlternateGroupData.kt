package androidx.media3.container

import androidx.media3.common.Metadata

class Mp4AlternateGroupData(val alternateGroup: Int) : Metadata.Entry {
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is Mp4AlternateGroupData) {
            return false
        }
        return alternateGroup == other.alternateGroup
    }

    override fun hashCode(): Int {
        return alternateGroup
    }

    override fun toString(): String {
        return "Mp4AlternateGroup: " + alternateGroup
    }
}
