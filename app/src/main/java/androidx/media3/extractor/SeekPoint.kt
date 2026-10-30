package androidx.media3.extractor

class SeekPoint(
   val timeUs: Long,
   val position: Long
) {
    override fun toString(): String {
        return "[timeUs=" + timeUs + ", position=" + position + "]"
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as SeekPoint
        return timeUs == other.timeUs && position == other.position
    }

    override fun hashCode(): Int {
        var result = timeUs.toInt()
        result = 31 * result + position.toInt()
        return result
    }

    companion object {
        val START: SeekPoint = SeekPoint(0, 0)
    }
}
