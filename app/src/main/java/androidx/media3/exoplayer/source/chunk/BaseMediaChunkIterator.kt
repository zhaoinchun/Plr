package androidx.media3.exoplayer.source.chunk

abstract class BaseMediaChunkIterator(private val fromIndex: Long, private val toIndex: Long) :
    MediaChunkIterator {
    protected var currentIndex: Long = 0
        private set

    init {
        reset()
    }

    override val isEnded: Boolean
        get() = currentIndex > toIndex

    override fun next(): Boolean {
        currentIndex++
        return !isEnded
    }

    override fun reset() {
        currentIndex = fromIndex - 1
    }

    protected fun checkInBounds() {
        if (currentIndex !in fromIndex..toIndex) {
            throw NoSuchElementException()
        }
    }
}
