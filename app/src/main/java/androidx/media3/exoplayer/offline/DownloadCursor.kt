package androidx.media3.exoplayer.offline

import java.io.Closeable

interface DownloadCursor : Closeable {
    val download: Download
    val count: Int
    val position: Int
    fun moveToPosition(position: Int): Boolean

    fun moveToFirst(): Boolean {
        return moveToPosition(0)
    }

    fun moveToLast(): Boolean {
        return moveToPosition(this.count - 1)
    }

    fun moveToNext(): Boolean {
        return moveToPosition(this.position + 1)
    }

    fun moveToPrevious(): Boolean {
        return moveToPosition(this.position - 1)
    }

    val isFirst: Boolean
        get() = this.position == 0 && this.count != 0

    val isLast: Boolean
        get() {
            val count = this.count
            return this.position == (count - 1) && count != 0
        }

    val isBeforeFirst: Boolean
        get() {
            if (this.count == 0) {
                return true
            }
            return this.position == -1
        }

    val isAfterLast: Boolean
        get() {
            if (this.count == 0) {
                return true
            }
            return this.position == this.count
        }

    val isClosed: Boolean

    override fun close()
}
