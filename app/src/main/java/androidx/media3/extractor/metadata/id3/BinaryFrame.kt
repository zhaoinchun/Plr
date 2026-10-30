package androidx.media3.extractor.metadata.id3

class BinaryFrame(id: String, val data: ByteArray?) : Id3Frame(id) {
    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as BinaryFrame
        return id == other.id && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + id.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
