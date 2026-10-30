package androidx.media3.extractor.metadata.id3

class GeobFrame(
    val mimeType: String?,
    val filename: String?,
    val description: String?,
    val data: ByteArray?
) : Id3Frame(
    ID
) {
    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as GeobFrame
        return mimeType == other.mimeType
                && filename == other.filename
                && description == other.description
                && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + (if (mimeType != null) mimeType.hashCode() else 0)
        result = 31 * result + (if (filename != null) filename.hashCode() else 0)
        result = 31 * result + (if (description != null) description.hashCode() else 0)
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun toString(): String {
        return (id
                + ": mimeType="
                + mimeType
                + ", filename="
                + filename
                + ", description="
                + description)
    }

    companion object {
        const val ID: String = "GEOB"
    }
}
