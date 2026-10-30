package androidx.media3.exoplayer.upstream

import androidx.media3.common.util.Log.e
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.extractor.ChunkIndex
import com.google.common.base.Preconditions
import java.util.Arrays
import java.util.NavigableSet
import java.util.TreeSet

class CachedRegionTracker(
    private val cache: Cache,
    private val cacheKey: String,
    private val chunkIndex: ChunkIndex
) : Cache.Listener {
    private val regions = TreeSet<Region?>()
    private val lookupRegion = Region(0L, 0L)

    init {
        synchronized(this) {
            val cacheSpans: NavigableSet<CacheSpan> = cache.addListener(cacheKey, this)
            // Merge the spans into regions. mergeSpan is more efficient when merging from high to low,
            // which is why a descending iterator is used here.
            val spanIterator: MutableIterator<CacheSpan> = cacheSpans.descendingIterator()
            while (spanIterator.hasNext()) {
                val span = spanIterator.next()
                mergeSpan(span)
            }
        }
    }

    fun release() {
        cache.removeListener(cacheKey, this)
    }

    @Synchronized
    fun getRegionEndTimeMs(byteOffset: Long): Int {
        lookupRegion.startOffset = byteOffset
        val floorRegion = regions.floor(lookupRegion)
        if (floorRegion == null || byteOffset > floorRegion.endOffset || floorRegion.endOffsetIndex == -1) {
            return NOT_CACHED
        }
        val index = floorRegion.endOffsetIndex
        if (index == chunkIndex.length - 1
            && floorRegion.endOffset == (chunkIndex.offsets[index] + chunkIndex.sizes!![index])
        ) {
            return CACHED_TO_END
        }
        val segmentFractionUs =
            ((chunkIndex.durationsUs!![index] * (floorRegion.endOffset - chunkIndex.offsets[index]))
                    / chunkIndex.sizes!![index])
        return ((chunkIndex.timesUs[index] + segmentFractionUs) / 1000).toInt()
    }

    @Synchronized
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        mergeSpan(span)
    }

    @Synchronized
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        val removedRegion = Region(span.position, span.position + span.length)

        // Look up a region this span falls into.
        val floorRegion = regions.floor(removedRegion)
        if (floorRegion == null) {
            e(TAG, "Removed a span we were not aware of")
            return
        }

        // Remove it.
        regions.remove(floorRegion)

        // Add new floor and ceiling regions, if necessary.
        if (floorRegion.startOffset < removedRegion.startOffset) {
            val newFloorRegion = Region(floorRegion.startOffset, removedRegion.startOffset)

            val index = Arrays.binarySearch(chunkIndex.offsets, newFloorRegion.endOffset)
            newFloorRegion.endOffsetIndex = if (index < 0) -index - 2 else index
            regions.add(newFloorRegion)
        }

        if (floorRegion.endOffset > removedRegion.endOffset) {
            val newCeilingRegion = Region(removedRegion.endOffset + 1, floorRegion.endOffset)
            newCeilingRegion.endOffsetIndex = floorRegion.endOffsetIndex
            regions.add(newCeilingRegion)
        }
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        // Do nothing.
    }

    private fun mergeSpan(span: CacheSpan) {
        val newRegion = Region(span.position, span.position + span.length)
        val floorRegion = regions.floor(newRegion)
        val ceilingRegion = regions.ceiling(newRegion)
        val floorConnects = regionsConnect(floorRegion, newRegion)
        val ceilingConnects = regionsConnect(newRegion, ceilingRegion)

        if (ceilingConnects) {
            ceilingRegion!!
            if (floorConnects) {
                // Extend floorRegion to cover both newRegion and ceilingRegion.
                floorRegion!!.endOffset = ceilingRegion.endOffset
                floorRegion.endOffsetIndex = ceilingRegion.endOffsetIndex
            } else {
                // Extend newRegion to cover ceilingRegion. Add it.
                newRegion.endOffset = ceilingRegion.endOffset
                newRegion.endOffsetIndex = ceilingRegion.endOffsetIndex
                regions.add(newRegion)
            }
            regions.remove(ceilingRegion)
        } else if (floorConnects) {
            // Extend floorRegion to the right to cover newRegion.
            floorRegion!!.endOffset = newRegion.endOffset
            var index = floorRegion.endOffsetIndex
            while (index < chunkIndex.length - 1
                && (chunkIndex.offsets[index + 1] <= floorRegion.endOffset)
            ) {
                index++
            }
            floorRegion.endOffsetIndex = index
        } else {
            // This is a new region.
            val index = Arrays.binarySearch(chunkIndex.offsets, newRegion.endOffset)
            newRegion.endOffsetIndex = if (index < 0) -index - 2 else index
            regions.add(newRegion)
        }
    }

    private fun regionsConnect(lower: Region?, upper: Region?): Boolean {
        return lower != null && upper != null && lower.endOffset == upper.startOffset
    }

    private class Region(
        var startOffset: Long,
        var endOffset: Long
    ) : Comparable<Region> {
        var endOffsetIndex: Int = 0

        override fun compareTo(another: Region): Int {
            return startOffset.compareTo(another.startOffset)
        }
    }

    companion object {
        private const val TAG = "CachedRegionTracker"

        val NOT_CACHED: Int = -1
        val CACHED_TO_END: Int = -2
    }
}
