package androidx.media3.extractor.metadata.id3

import androidx.media3.common.MediaMetadata

class ApicFrame(
    val mimeType: String?,
    val description: String?,
    val pictureType: Int,
    val pictureData: ByteArray
) : Id3Frame(
    ID
) {
    override fun populateMediaMetadata(builder: MediaMetadata.Builder) {
        builder.maybeSetArtworkData(pictureData, pictureType)
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as ApicFrame
        return pictureType == other.pictureType && mimeType == other.mimeType
                && description == other.description
                && pictureData.contentEquals(other.pictureData)
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + pictureType
        result = 31 * result + (if (mimeType != null) mimeType.hashCode() else 0)
        result = 31 * result + (if (description != null) description.hashCode() else 0)
        result = 31 * result + pictureData.contentHashCode()
        return result
    }

    override fun toString(): String {
        return id + ": mimeType=" + mimeType + ", description=" + description
    }

    companion object {
        const val ID: String = "APIC"
    }
}
