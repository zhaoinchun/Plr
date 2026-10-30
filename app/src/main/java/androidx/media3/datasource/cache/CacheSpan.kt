package androidx.media3.datasource.cache

import androidx.media3.common.C
import java.io.File

open class CacheSpan  constructor(
    val key: String,
    val position: Long,
    val length: Long,
    val lastTouchTimestamp: Long = C.TIME_UNSET,
    val file: File? = null
) : Comparable<CacheSpan> {
    val isCached: Boolean

    init {
        this.isCached = file != null
    }

    val isOpenEnded: Boolean
        get() = length == C.LENGTH_UNSET.toLong()

    val isHoleSpan: Boolean
        get() = !isCached

    override fun compareTo(another: CacheSpan): Int {
        if (key != another.key) {
            return key.compareTo(another.key)
        }
        val startOffsetDiff = position - another.position
        return if (startOffsetDiff == 0L) 0 else (if (startOffsetDiff < 0) -1 else 1)
    }

    override fun toString(): String {
        return "[" + position + ", " + length + "]"
    }
}
