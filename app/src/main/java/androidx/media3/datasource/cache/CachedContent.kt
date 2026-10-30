package androidx.media3.datasource.cache

import androidx.media3.common.C
import androidx.media3.common.util.Log.w
import com.google.common.base.Preconditions
import java.io.File
import java.util.TreeSet
import kotlin.math.max
import kotlin.math.min

internal class CachedContent  constructor(
    val id: Int,
    val key: String,
    var metadata: DefaultContentMetadata = DefaultContentMetadata.EMPTY
) {
    val spans: TreeSet<SimpleCacheSpan>
    private val lockedRanges: ArrayList<Range?>

    init {
        this.spans = TreeSet<SimpleCacheSpan>()
        lockedRanges = ArrayList<Range?>()
    }

    fun applyMetadataMutations(mutations: ContentMetadataMutations): Boolean {
        val oldMetadata = metadata
        metadata = metadata.copyWithMutationsApplied(mutations)
        return metadata != oldMetadata
    }

    val isFullyUnlocked: Boolean
        get() = lockedRanges.isEmpty()

    fun isFullyLocked(position: Long, length: Long): Boolean {
        for (i in lockedRanges.indices) {
            if (lockedRanges[i]!!.contains(position, length)) {
                return true
            }
        }
        return false
    }

    fun lockRange(position: Long, length: Long): Boolean {
        for (i in lockedRanges.indices) {
            if (lockedRanges[i]!!.intersects(position, length)) {
                return false
            }
        }
        lockedRanges.add(Range(position, length))
        return true
    }

    fun unlockRange(position: Long) {
        for (i in lockedRanges.indices) {
            if (lockedRanges[i]!!.position == position) {
                lockedRanges.removeAt(i)
                return
            }
        }
        throw IllegalStateException()
    }

    fun addSpan(span: SimpleCacheSpan?) {
        spans.add(span!!)
    }

    fun getSpan(position: Long, length: Long): SimpleCacheSpan {
        var length = length
        val lookupSpan: SimpleCacheSpan = SimpleCacheSpan.createLookup(key, position)
        val floorSpan = spans.floor(lookupSpan)
        if (floorSpan != null && floorSpan.position + floorSpan.length > position) {
            return floorSpan
        }
        val ceilSpan = spans.ceiling(lookupSpan)
        if (ceilSpan != null) {
            val holeLength = ceilSpan.position - position
            length = if (length == C.LENGTH_UNSET.toLong()) holeLength else min(holeLength, length)
        }
        return SimpleCacheSpan.createHole(key, position, length)
    }

    fun getCachedBytesLength(position: Long, length: Long): Long {
        Preconditions.checkArgument(position >= 0)
        Preconditions.checkArgument(length >= 0)
        val span = getSpan(position, length)
        if (span.isHoleSpan) {
            // We don't have a span covering the start of the queried region.
            return -min(if (span.isHoleSpan) Long.MAX_VALUE else span.length, length)
        }
        var queryEndPosition = position + length
        if (queryEndPosition < 0) {
            // The calculation rolled over (length is probably Long.MAX_VALUE).
            queryEndPosition = Long.MAX_VALUE
        }
        var currentEndPosition = span.position + span.length
        if (currentEndPosition < queryEndPosition) {
            for (next in spans.tailSet(span, false)) {
                if (next.position > currentEndPosition) {
                    // There's a hole in the cache within the queried region.
                    break
                }
                // We expect currentEndPosition to always equal (next.position + next.length), but
                // perform a max check anyway to guard against the existence of overlapping spans.
                currentEndPosition = max(currentEndPosition, next.position + next.length)
                if (currentEndPosition >= queryEndPosition) {
                    // We've found spans covering the queried region.
                    break
                }
            }
        }
        return min(currentEndPosition - position, length)
    }

    fun setLastTouchTimestamp(
        cacheSpan: SimpleCacheSpan, lastTouchTimestamp: Long, updateFile: Boolean
    ): SimpleCacheSpan {
        Preconditions.checkState(spans.remove(cacheSpan))
        var file = cacheSpan.file!!
        if (updateFile) {
            val directory = file.parentFile!!
            val position = cacheSpan.position
            val newFile: File =
                SimpleCacheSpan.getCacheFile(directory, id, position, lastTouchTimestamp)
            if (file.renameTo(newFile)) {
                file = newFile
            } else {
                w(TAG, "Failed to rename " + file + " to " + newFile)
            }
        }
        val newCacheSpan =
            cacheSpan.copyWithFileAndLastTouchTimestamp(file, lastTouchTimestamp)
        spans.add(newCacheSpan)
        return newCacheSpan
    }

    val isEmpty: Boolean
        get() = spans.isEmpty()

    fun removeSpan(span: CacheSpan): Boolean {
        if (spans.remove(span)) {
            if (span.file != null) {
                span.file.delete()
            }
            return true
        }
        return false
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + key.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || javaClass != o.javaClass) {
            return false
        }
        val that = o as CachedContent
        return id == that.id && key == that.key
                && this.spans == that.spans
                && metadata == that.metadata
    }

    private class Range(
        val position: Long,
        val length: Long
    ) {
        fun contains(otherPosition: Long, otherLength: Long): Boolean {
            if (length == C.LENGTH_UNSET.toLong()) {
                return otherPosition >= position
            } else if (otherLength == C.LENGTH_UNSET.toLong()) {
                return false
            } else {
                return position <= otherPosition && (otherPosition + otherLength) <= (position + length)
            }
        }

        fun intersects(otherPosition: Long, otherLength: Long): Boolean {
            if (position <= otherPosition) {
                return length == C.LENGTH_UNSET.toLong() || position + length > otherPosition
            } else {
                return otherLength == C.LENGTH_UNSET.toLong() || otherPosition + otherLength > position
            }
        }
    }

    companion object {
        private const val TAG = "CachedContent"
    }
}
