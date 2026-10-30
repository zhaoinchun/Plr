package androidx.media3.exoplayer.source.chunk

import androidx.media3.common.C
import androidx.media3.common.util.Log.e

import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.source.chunk.ChunkExtractor.TrackOutputProvider
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.TrackOutput

class BaseMediaChunkOutput(
    private val trackTypes: IntArray,
    private val sampleQueues: Array<SampleQueue>
) :
    TrackOutputProvider {
    override fun track(id: Int, type: @C.TrackType Int): TrackOutput {
        for (i in trackTypes.indices) {
            if (type == trackTypes[i]) {
                return sampleQueues[i]
            }
        }
        e(TAG, "Unmatched track of type: $type")
        return DiscardingTrackOutput()
    }

    val writeIndices: IntArray
        get() {
            val writeIndices = IntArray(sampleQueues.size)
            for (i in sampleQueues.indices) {
                writeIndices[i] = sampleQueues[i].writeIndex
            }
            return writeIndices
        }

    fun setSampleOffsetUs(sampleOffsetUs: Long) {
        for (sampleQueue in sampleQueues) {
            sampleQueue.setSampleOffsetUs(sampleOffsetUs)
        }
    }

    companion object {
        private const val TAG = "BaseMediaChunkOutput"
    }
}
