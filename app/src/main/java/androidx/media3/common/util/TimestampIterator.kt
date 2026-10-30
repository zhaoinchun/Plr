package androidx.media3.common.util

import androidx.media3.common.C

interface TimestampIterator {
    
    fun hasNext(): Boolean

    fun next(): Long

    fun copyOf(): TimestampIterator

    val lastTimestampUs: Long
        
        get() = C.TIME_UNSET
}
