package androidx.media3.extractor.metadata.id3

class MlltFrame(
    val mpegFramesBetweenReference: Int,
    val bytesBetweenReference: Int,
    val millisecondsBetweenReference: Int,
    val bytesDeviations: IntArray,
    val millisecondsDeviations: IntArray
) : Id3Frame(ID) {
    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as MlltFrame
        return mpegFramesBetweenReference == other.mpegFramesBetweenReference && bytesBetweenReference == other.bytesBetweenReference && millisecondsBetweenReference == other.millisecondsBetweenReference && bytesDeviations.contentEquals(
            other.bytesDeviations
        ) && millisecondsDeviations.contentEquals(other.millisecondsDeviations)
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + mpegFramesBetweenReference
        result = 31 * result + bytesBetweenReference
        result = 31 * result + millisecondsBetweenReference
        result = 31 * result + bytesDeviations.contentHashCode()
        result = 31 * result + millisecondsDeviations.contentHashCode()
        return result
    }

    companion object {
        const val ID: String = "MLLT"
    }
}
