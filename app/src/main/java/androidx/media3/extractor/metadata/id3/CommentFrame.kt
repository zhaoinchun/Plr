package androidx.media3.extractor.metadata.id3

class CommentFrame(val language: String?, val description: String?, val text: String?) : Id3Frame(
    ID
) {
    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as CommentFrame
        return description == other.description
                && language == other.language
                && text == other.text
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + (if (language != null) language.hashCode() else 0)
        result = 31 * result + (if (description != null) description.hashCode() else 0)
        result = 31 * result + (if (text != null) text.hashCode() else 0)
        return result
    }

    override fun toString(): String {
        return id + ": language=" + language + ", description=" + description + ", text=" + text
    }

    companion object {
        const val ID: String = "COMM"
    }
}
