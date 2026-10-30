package androidx.media3.exoplayer.trackselection

class TrackSelectionArray(private val trackSelections: Array<out TrackSelection?>) {
    val length: Int = trackSelections.size
    private var hashCode = 0

    fun get(index: Int): TrackSelection? {
        return trackSelections[index]
    }

    val all: Array<out TrackSelection?>
        get() = trackSelections.copyOf()

    override fun hashCode(): Int {
        if (hashCode == 0) {
            var result = 17
            result = 31 * result + trackSelections.contentHashCode()
            hashCode = result
        }
        return hashCode
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null || javaClass != other.javaClass) {
            return false
        }
        other as TrackSelectionArray
        return trackSelections.contentEquals(other.trackSelections)
    }
}
