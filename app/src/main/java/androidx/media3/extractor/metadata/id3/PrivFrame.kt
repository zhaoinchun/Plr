package androidx.media3.extractor.metadata.id3

class PrivFrame(val owner: String?, val privateData: ByteArray?) : Id3Frame(ID) {
    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as PrivFrame
        return owner == other.owner && privateData.contentEquals(other.privateData)
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + (if (owner != null) owner.hashCode() else 0)
        result = 31 * result + privateData.contentHashCode()
        return result
    }

    override fun toString(): String {
        return id + ": owner=" + owner
    }

    companion object {
        const val ID: String = "PRIV"
    }
}
