package androidx.media3.extractor.avi

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.util.Util.binarySearchFloor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.SeekMap.SeekPoints
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import com.google.common.base.Preconditions
import java.io.IOException
import java.util.Arrays

internal class ChunkReader(
    id: Int,
    private val streamHeaderChunk: AviStreamHeaderChunk,
    trackOutput: TrackOutput,
    private val allSamplesAreSyncSamples: Boolean
) {
    
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [CHUNK_TYPE_VIDEO_COMPRESSED, CHUNK_TYPE_VIDEO_UNCOMPRESSED, CHUNK_TYPE_AUDIO
        ]
    )
    private annotation class ChunkType

    private val trackOutput: TrackOutput
    private val chunkId: Int
    private val alternativeChunkId: Int
    private val durationUs: Long
    private var chunkCount: Int
    private var currentChunkSize = 0
    private var bytesRemainingInCurrentChunk = 0
    private var currentChunkIndex = 0

    private var indexChunkCount = 0
    private var indexSize = 0
    private var firstIndexChunkOffset: Long
    private var keyFrameOffsets: LongArray
    private var keyFrameIndices: IntArray

    init {
        val trackType: @C.TrackType Int = streamHeaderChunk.trackType
        Preconditions.checkArgument(trackType == C.TRACK_TYPE_AUDIO || trackType == C.TRACK_TYPE_VIDEO)
        val chunkType: @ChunkType Int =
            if (trackType == C.TRACK_TYPE_VIDEO) CHUNK_TYPE_VIDEO_COMPRESSED else CHUNK_TYPE_AUDIO
        chunkId = getChunkIdFourCc(id, chunkType)
        durationUs = streamHeaderChunk.durationUs
        this.trackOutput = trackOutput
        alternativeChunkId =
            if (trackType == C.TRACK_TYPE_VIDEO) getChunkIdFourCc(
                id,
                CHUNK_TYPE_VIDEO_UNCOMPRESSED
            ) else -1
        firstIndexChunkOffset = C.INDEX_UNSET.toLong()
        keyFrameOffsets = LongArray(INITIAL_INDEX_SIZE)
        keyFrameIndices = IntArray(INITIAL_INDEX_SIZE)
        chunkCount = streamHeaderChunk.length
    }

    fun appendIndexChunk(offset: Long, isKeyFrame: Boolean) {
        if (firstIndexChunkOffset == C.INDEX_UNSET.toLong()) {
            firstIndexChunkOffset = offset
        }
        if (isKeyFrame || allSamplesAreSyncSamples) {
            if (indexSize == keyFrameIndices.size) {
                keyFrameOffsets = keyFrameOffsets.copyOf(keyFrameOffsets.size * 3 / 2)
                keyFrameIndices = keyFrameIndices.copyOf(keyFrameIndices.size * 3 / 2)
            }
            keyFrameOffsets[indexSize] = offset
            keyFrameIndices[indexSize] = indexChunkCount
            indexSize++
        }
        indexChunkCount++
    }

    fun advanceCurrentChunk() {
        currentChunkIndex++
    }

    val currentChunkTimestampUs: Long
        get() = getChunkTimestampUs(currentChunkIndex)

    val frameDurationUs: Long
        get() = getChunkTimestampUs( 1)

    fun commitIndex() {
        keyFrameOffsets = keyFrameOffsets.copyOf(indexSize)
        keyFrameIndices = keyFrameIndices.copyOf(indexSize)
        if (allSamplesAreSyncSamples && streamHeaderChunk.sampleSize != 0 && indexSize > 0) {
            // In some files the AVI stream header chunk for audio has the number of bytes of audio in
            // dwLength instead of the number of chunks. Overwrite the chunk size to use the size of the
            // index, which should match the number of chunks because we only support formats where every
            // audio sample is a sync sample, and every sync sample should be in the index.
            chunkCount = indexSize
        }
    }

    fun handlesChunkId(chunkId: Int): Boolean {
        return this.chunkId == chunkId || alternativeChunkId == chunkId
    }

    val isCurrentFrameAKeyFrame: Boolean
        get() = allSamplesAreSyncSamples || Arrays.binarySearch(
            keyFrameIndices,
            currentChunkIndex
        ) >= 0

    val isVideo: Boolean
        get() = (chunkId and CHUNK_TYPE_VIDEO_COMPRESSED) == CHUNK_TYPE_VIDEO_COMPRESSED

    val isAudio: Boolean
        get() = (chunkId and CHUNK_TYPE_AUDIO) == CHUNK_TYPE_AUDIO

    fun onChunkStart(size: Int) {
        currentChunkSize = size
        bytesRemainingInCurrentChunk = size
    }

    @Throws(IOException::class)
    fun onChunkData(input: ExtractorInput): Boolean {
        bytesRemainingInCurrentChunk -=
            trackOutput.sampleData(input, bytesRemainingInCurrentChunk, false)
        val done = bytesRemainingInCurrentChunk == 0
        if (done) {
            if (currentChunkSize > 0) {
                trackOutput.sampleMetadata(
                    this.currentChunkTimestampUs,
                    (if (this.isCurrentFrameAKeyFrame) C.BUFFER_FLAG_KEY_FRAME else 0),
                    currentChunkSize,
                    0,
                    null
                )
            }
            advanceCurrentChunk()
        }
        return done
    }

    fun seekToPosition(position: Long) {
        if (indexSize == 0) {
            currentChunkIndex = 0
        } else {
            val index =
                binarySearchFloor(
                    keyFrameOffsets, position,  true,  true
                )
            currentChunkIndex = keyFrameIndices[index]
        }
    }

    fun getSeekPoints(timeUs: Long): SeekPoints {
        if (indexSize == 0) {
            // Return the offset of the first chunk as there are no keyframes in the index.
            return SeekPoints(
                SeekPoint( 0,  firstIndexChunkOffset)
            )
        }
        val targetFrameIndex = (timeUs / this.frameDurationUs).toInt()
        val keyFrameIndex =
            binarySearchFloor(
                keyFrameIndices, targetFrameIndex,  true,  true
            )
        if (keyFrameIndices[keyFrameIndex] == targetFrameIndex) {
            return SeekPoints(getSeekPoint(keyFrameIndex))
        }
        // The target frame is not a key frame, we look for the two closest ones.
        val precedingKeyFrameSeekPoint = getSeekPoint(keyFrameIndex)
        if (keyFrameIndex + 1 < keyFrameOffsets.size) {
            return SeekPoints(precedingKeyFrameSeekPoint, getSeekPoint(keyFrameIndex + 1))
        } else {
            return SeekPoints(precedingKeyFrameSeekPoint)
        }
    }

    private fun getChunkTimestampUs(chunkIndex: Int): Long {
        return durationUs * chunkIndex / chunkCount
    }

    private fun getSeekPoint(keyFrameIndex: Int): SeekPoint {
        return SeekPoint(
            keyFrameIndices[keyFrameIndex] * this.frameDurationUs, keyFrameOffsets[keyFrameIndex]
        )
    }

    companion object {
        private const val INITIAL_INDEX_SIZE = 512
        private const val CHUNK_TYPE_VIDEO_COMPRESSED = ('d'.code shl 16) or ('c'.code shl 24)
        private const  val CHUNK_TYPE_VIDEO_UNCOMPRESSED = ('d'.code shl 16) or ('b'.code shl 24)
        private const val CHUNK_TYPE_AUDIO = ('w'.code shl 16) or ('b'.code shl 24)

        private fun getChunkIdFourCc(streamId: Int, chunkType: @ChunkType Int): Int {
            val tens = streamId / 10
            val ones = streamId % 10
            return (('0'.code + ones) shl 8) or ('0'.code + tens) or chunkType
        }
    }
}
