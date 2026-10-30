package androidx.media3.extractor

import com.google.common.primitives.Ints
import com.google.common.primitives.Longs

class ChunkIndexMerger {
    private val chunkMap: MutableMap<Long?, ChunkIndex>

    init {
        this.chunkMap = LinkedHashMap<Long?, ChunkIndex>()
    }

    fun add(chunk: ChunkIndex) {
        if (chunk.timesUs.size > 0 && !chunkMap.containsKey(chunk.timesUs[0])) {
            chunkMap.put(chunk.timesUs[0], chunk)
        }
    }

    fun merge(): ChunkIndex {
        val sizesList = mutableListOf<IntArray?>()
        val offsetsList = mutableListOf<LongArray?>()
        val durationsList = mutableListOf<LongArray?>()
        val timesList = mutableListOf<LongArray?>()

        for (chunk in chunkMap.values) {
            sizesList.add(chunk.sizes)
            offsetsList.add(chunk.offsets)
            durationsList.add(chunk.durationsUs)
            timesList.add(chunk.timesUs)
        }

        return ChunkIndex(
            Ints.concat(*sizesList.toTypedArray<IntArray?>()),
            Longs.concat(*offsetsList.toTypedArray<LongArray?>()),
            Longs.concat(*durationsList.toTypedArray<LongArray?>()),
            Longs.concat(*timesList.toTypedArray<LongArray?>())
        )
    }

    fun clear() {
        chunkMap.clear()
    }

    fun size(): Int {
        return chunkMap.size
    }
}
