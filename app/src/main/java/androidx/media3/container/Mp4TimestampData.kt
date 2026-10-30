package androidx.media3.container

import androidx.media3.common.Metadata
import com.google.common.primitives.Longs

class Mp4TimestampData : Metadata.Entry {
    val creationTimestampSeconds: Long
    val modificationTimestampSeconds: Long
    val timescale: Long

    constructor(creationTimestampSeconds: Long, modificationTimestampSeconds: Long) {
        this.creationTimestampSeconds = creationTimestampSeconds
        this.modificationTimestampSeconds = modificationTimestampSeconds
        this.timescale = TIMESCALE_UNSET.toLong()
    }

    constructor(
        creationTimestampSeconds: Long,
        modificationTimestampSeconds: Long,
        timescale: Long
    ) {
        this.creationTimestampSeconds = creationTimestampSeconds
        this.modificationTimestampSeconds = modificationTimestampSeconds
        this.timescale = timescale
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is Mp4TimestampData) {
            return false
        }
        return creationTimestampSeconds == other.creationTimestampSeconds && modificationTimestampSeconds == other.modificationTimestampSeconds && timescale == other.timescale
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + Longs.hashCode(creationTimestampSeconds)
        result = 31 * result + Longs.hashCode(modificationTimestampSeconds)
        result = 31 * result + Longs.hashCode(timescale)
        return result
    }

    override fun toString(): String {
        return ("Mp4Timestamp: creation time="
                + creationTimestampSeconds
                + ", modification time="
                + modificationTimestampSeconds
                + ", timescale="
                + timescale)
    }

    companion object {
        val TIMESCALE_UNSET: Int = -1

        private val UNIX_EPOCH_TO_MP4_TIME_DELTA_SECONDS =
            ((1970 - 1904) * 365 + 17 ) * (24 * 60 * 60)

        fun unixTimeToMp4TimeSeconds(unixTimestampMs: Long): Long {
            return (unixTimestampMs / 1000L) + UNIX_EPOCH_TO_MP4_TIME_DELTA_SECONDS
        }
    }
}
