package androidx.media3.extractor.metadata.id3

class InternalFrame(val domain: String?, val description: String?, val text: String?) :
    Id3Frame(ID) {
    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as InternalFrame
        return description == other.description
                && domain == other.domain
                && text == other.text
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + (if (domain != null) domain.hashCode() else 0)
        result = 31 * result + (if (description != null) description.hashCode() else 0)
        result = 31 * result + (if (text != null) text.hashCode() else 0)
        return result
    }

    override fun toString(): String {
        return id + ": domain=" + domain + ", description=" + description
    }

    companion object {
        const val ID: String = "----"
    }
}
