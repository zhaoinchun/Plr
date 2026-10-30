package androidx.media3.extractor.mp3

import androidx.media3.common.C
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekMap.Unseekable

internal interface Seeker : SeekMap {
    fun getTimeUs(position: Long): Long
    val dataStartPosition: Long
    val dataEndPosition: Long
    val averageBitrate: Int

    class UnseekableSeeker : Unseekable( C.TIME_UNSET), Seeker {
        override fun getTimeUs(position: Long): Long {
            return 0
        }

        override val dataStartPosition: Long=0
        override val dataEndPosition: Long=C.INDEX_UNSET.toLong()
        override val averageBitrate: Int=C.RATE_UNSET_INT
    }
}
