package androidx.media3.extractor.metadata.id3

class UrlLinkFrame(id: String?, val description: String?, val url: String?) : Id3Frame(id!!) {
    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as UrlLinkFrame
        return id == other.id
                && description == other.description
                && url == other.url
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + id.hashCode()
        result = 31 * result + (if (description != null) description.hashCode() else 0)
        result = 31 * result + (if (url != null) url.hashCode() else 0)
        return result
    }

    override fun toString(): String {
        return id + ": url=" + url
    }
}
