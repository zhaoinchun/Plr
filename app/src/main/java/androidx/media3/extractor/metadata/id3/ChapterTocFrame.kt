package androidx.media3.extractor.metadata.id3

class ChapterTocFrame(
    val elementId: String?,
    val isRoot: Boolean,
    val isOrdered: Boolean,
    val children: Array<String?>?,
    private val subFrames: Array<Id3Frame?>
) : Id3Frame(ID) {
    val subFrameCount: Int
        
        get() = subFrames.size

    fun getSubFrame(index: Int): Id3Frame? {
        return subFrames[index]
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as ChapterTocFrame
        return isRoot == other.isRoot && isOrdered == other.isOrdered && elementId == other.elementId
                && children.contentEquals(other.children) && subFrames.contentEquals(other.subFrames)
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + (if (isRoot) 1 else 0)
        result = 31 * result + (if (isOrdered) 1 else 0)
        result = 31 * result + (if (elementId != null) elementId.hashCode() else 0)
        return result
    }

    companion object {
        const val ID: String = "CTOC"
    }
}
