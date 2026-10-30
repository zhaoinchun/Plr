package androidx.media3.extractor

interface SeekMap {
    open class Unseekable(
        override val durationUs: Long,
        startPosition: Long = 0
    ) : SeekMap {
        private val startSeekPoints: SeekPoints

        init {
            startSeekPoints =
                SeekPoints(
                    if (startPosition == 0L) SeekPoint.START else SeekPoint(
                        0,
                        startPosition
                    )
                )
        }

        override val isSeekable: Boolean
            get() = false

        override fun getSeekPoints(timeUs: Long): SeekPoints {
            return startSeekPoints
        }
    }

    class SeekPoints(val first: SeekPoint, val second: SeekPoint) {

        constructor(point: SeekPoint) : this(point, point)

        override fun toString(): String {
            return "[" + first + (if (first == second) "" else (", " + second)) + "]"
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj == null || javaClass != obj.javaClass) {
                return false
            }
            val other = obj as SeekPoints
            return first == other.first && second == other.second
        }

        override fun hashCode(): Int {
            return (31 * first.hashCode()) + second.hashCode()
        }
    }

    val isSeekable: Boolean
    val durationUs: Long

    fun getSeekPoints(timeUs: Long): SeekPoints

    val isEstimated: Boolean
        get() = false
}
