package androidx.media3.exoplayer.source.chunk

import androidx.media3.datasource.DataSpec

interface MediaChunkIterator {
    val isEnded: Boolean
    fun next(): Boolean
    val dataSpec: DataSpec
    val chunkStartTimeUs: Long
    val chunkEndTimeUs: Long
    fun reset()

    companion object {
        val EMPTY: MediaChunkIterator = object : MediaChunkIterator {
            override val isEnded: Boolean = true
            override fun next(): Boolean {
                return false
            }

            override val dataSpec: DataSpec
                get() = throw NoSuchElementException()
            override val chunkStartTimeUs: Long
                get() = throw NoSuchElementException()
            override val chunkEndTimeUs: Long
                get() = throw NoSuchElementException()

            override fun reset() {
                // Do nothing.
            }
        }
    }
}
