package androidx.media3.extractor

import androidx.annotation.IntDef
import androidx.media3.common.C

import androidx.media3.common.util.Util.constrainValue
import androidx.media3.extractor.SeekMap.SeekPoints
import com.google.common.base.Preconditions
import java.io.IOException

abstract class BinarySearchSeeker protected constructor(
    seekTimestampConverter: SeekTimestampConverter,
    protected val timestampSeeker: TimestampSeeker,
    durationUs: Long,
    floorTimePosition: Long,
    ceilingTimePosition: Long,
    floorBytePosition: Long,
    ceilingBytePosition: Long,
    approxBytesPerFrame: Long,
    private val minimumSearchRange: Int
) {
    protected interface TimestampSeeker {
       
        @Throws(IOException::class)
        fun searchForTimestamp(input: ExtractorInput, targetTimestamp: Long): TimestampSearchResult

        fun onSeekFinished() {}
    }

    class DefaultSeekTimestampConverter : SeekTimestampConverter {
        override fun timeUsToTargetTime(timeUs: Long): Long {
            return timeUs
        }
    }

     interface SeekTimestampConverter {
        fun timeUsToTargetTime(timeUs: Long): Long
    }

    protected val seekMap: BinarySearchSeekMap
    protected var seekOperationParams: SeekOperationParams? = null

    init {
        this.seekMap =
            BinarySearchSeekMap(
                seekTimestampConverter,
                durationUs,
                floorTimePosition,
                ceilingTimePosition,
                floorBytePosition,
                ceilingBytePosition,
                approxBytesPerFrame
            )
    }

    fun getSeekMap(): SeekMap {
        return seekMap
    }

    fun setSeekTargetUs(timeUs: Long) {
        if (seekOperationParams != null && seekOperationParams?.seekTimeUs == timeUs) {
            return
        }
        seekOperationParams = createSeekParamsForTargetTimeUs(timeUs)
    }

    val isSeeking: Boolean
        get() = seekOperationParams != null

    @Throws(IOException::class)
    fun handlePendingSeek(input: ExtractorInput, seekPositionHolder: PositionHolder): Int {
        while (true) {
            val seekOperationParams: SeekOperationParams =this.seekOperationParams!!
            val floorPosition = seekOperationParams.floorBytePosition
            val ceilingPosition = seekOperationParams.ceilingBytePosition
            val searchPosition = seekOperationParams.nextSearchBytePosition

            if (ceilingPosition - floorPosition <= minimumSearchRange) {
                // The seeking range is too small, so we can just continue from the floor position.
                markSeekOperationFinished( false, floorPosition)
                return seekToPosition(input, floorPosition, seekPositionHolder)
            }
            if (!skipInputUntilPosition(input, searchPosition)) {
                return seekToPosition(input, searchPosition, seekPositionHolder)
            }

            input.resetPeekPosition()
            val timestampSearchResult =
                timestampSeeker.searchForTimestamp(input, seekOperationParams.targetTimePosition)

            when (timestampSearchResult.type) {
                TimestampSearchResult.TYPE_POSITION_OVERESTIMATED -> seekOperationParams.updateSeekCeiling(
                    timestampSearchResult.timestampToUpdate,
                    timestampSearchResult.bytePositionToUpdate
                )

                TimestampSearchResult.TYPE_POSITION_UNDERESTIMATED -> seekOperationParams.updateSeekFloor(
                    timestampSearchResult.timestampToUpdate,
                    timestampSearchResult.bytePositionToUpdate
                )

                TimestampSearchResult.TYPE_TARGET_TIMESTAMP_FOUND -> {
                    skipInputUntilPosition(input, timestampSearchResult.bytePositionToUpdate)
                    markSeekOperationFinished( 
                        true, timestampSearchResult.bytePositionToUpdate
                    )
                    return seekToPosition(
                        input, timestampSearchResult.bytePositionToUpdate, seekPositionHolder
                    )
                }

                TimestampSearchResult.TYPE_NO_TIMESTAMP -> {
                    // We can't find any timestamp in the search range from the search position.
                    // Give up, and just continue reading from the last search position in this case.
                    markSeekOperationFinished( false, searchPosition)
                    return seekToPosition(input, searchPosition, seekPositionHolder)
                }

                else -> throw IllegalStateException("Invalid case")
            }
        }
    }

    protected fun createSeekParamsForTargetTimeUs(timeUs: Long): SeekOperationParams {
        return SeekOperationParams(
            timeUs,
            seekMap.timeUsToTargetTime(timeUs),
            seekMap.floorTimePosition,
            seekMap.ceilingTimePosition,
            seekMap.floorBytePosition,
            seekMap.ceilingBytePosition,
            seekMap.approxBytesPerFrame
        )
    }

    protected fun markSeekOperationFinished(foundTargetFrame: Boolean, resultPosition: Long) {
        seekOperationParams = null
        timestampSeeker.onSeekFinished()
        onSeekOperationFinished(foundTargetFrame, resultPosition)
    }

    protected fun onSeekOperationFinished(foundTargetFrame: Boolean, resultPosition: Long) {
        // Do nothing.
    }

    @Throws(IOException::class)
    protected fun skipInputUntilPosition(input: ExtractorInput, position: Long): Boolean {
        val bytesToSkip = position - input.position
        if (bytesToSkip in 0..MAX_SKIP_BYTES) {
            input.skipFully(bytesToSkip.toInt())
            return true
        }
        return false
    }

    protected fun seekToPosition(
        input: ExtractorInput, position: Long, seekPositionHolder: PositionHolder
    ): Int {
        if (position == input.position) {
            return Extractor.RESULT_CONTINUE
        } else {
            seekPositionHolder.position = position
            return Extractor.RESULT_SEEK
        }
    }

    protected class SeekOperationParams(
         val seekTimeUs: Long,
         val targetTimePosition: Long,
         var floorTimePosition: Long,
         var ceilingTimePosition: Long,
       var floorBytePosition: Long,
      var ceilingBytePosition: Long,
        private val approxBytesPerFrame: Long
    ) {
         var nextSearchBytePosition: Long

        init {
            this.nextSearchBytePosition =
                calculateNextSearchBytePosition(
                    targetTimePosition,
                    floorTimePosition,
                    ceilingTimePosition,
                    floorBytePosition,
                    ceilingBytePosition,
                    approxBytesPerFrame
                )
        }

         fun updateSeekFloor(floorTimePosition: Long, floorBytePosition: Long) {
            this.floorTimePosition = floorTimePosition
            this.floorBytePosition = floorBytePosition
            updateNextSearchBytePosition()
        }

         fun updateSeekCeiling(ceilingTimePosition: Long, ceilingBytePosition: Long) {
            this.ceilingTimePosition = ceilingTimePosition
            this.ceilingBytePosition = ceilingBytePosition
            updateNextSearchBytePosition()
        }

        private fun updateNextSearchBytePosition() {
            this.nextSearchBytePosition =
                calculateNextSearchBytePosition(
                    targetTimePosition,
                    floorTimePosition,
                    ceilingTimePosition,
                    floorBytePosition,
                    ceilingBytePosition,
                    approxBytesPerFrame
                )
        }

        companion object {
            fun calculateNextSearchBytePosition(
                targetTimePosition: Long,
                floorTimePosition: Long,
                ceilingTimePosition: Long,
                floorBytePosition: Long,
                ceilingBytePosition: Long,
                approxBytesPerFrame: Long
            ): Long {
                if (floorBytePosition + 1 >= ceilingBytePosition
                    || floorTimePosition + 1 >= ceilingTimePosition
                ) {
                    return floorBytePosition
                }
                val seekTimeDuration = targetTimePosition - floorTimePosition
                val estimatedBytesPerTimeUnit =
                    (ceilingBytePosition - floorBytePosition).toFloat() / (ceilingTimePosition - floorTimePosition)
                // It's better to under-estimate rather than over-estimate, because the extractor
                // input can skip forward easily, but cannot rewind easily (it may require a new connection
                // to be made).
                // Therefore, we should reduce the estimated position by some amount, so it will converge to
                // the correct frame earlier.
                val bytesToSkip = (seekTimeDuration * estimatedBytesPerTimeUnit).toLong()
                val confidenceInterval = bytesToSkip / 20
                val estimatedFramePosition = floorBytePosition + bytesToSkip - approxBytesPerFrame
                val estimatedPosition = estimatedFramePosition - confidenceInterval
                return constrainValue(estimatedPosition, floorBytePosition, ceilingBytePosition - 1)
            }
        }
    }

    class TimestampSearchResult private constructor(
         val type: @Type Int,
         val timestampToUpdate: Long,
         val bytePositionToUpdate: Long
    ) {
        @MustBeDocumented
        @Retention(AnnotationRetention.SOURCE)
        @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
        @IntDef(
            value = [TYPE_TARGET_TIMESTAMP_FOUND, TYPE_POSITION_OVERESTIMATED, TYPE_POSITION_UNDERESTIMATED, TYPE_NO_TIMESTAMP
            ]
        )
        internal annotation class Type

        companion object {
            const val TYPE_TARGET_TIMESTAMP_FOUND: Int = 0
            const val TYPE_POSITION_OVERESTIMATED: Int = -1
            const  val TYPE_POSITION_UNDERESTIMATED: Int = -2
            const  val TYPE_NO_TIMESTAMP: Int = -3

            val NO_TIMESTAMP_IN_RANGE_RESULT: TimestampSearchResult = TimestampSearchResult(
                TYPE_NO_TIMESTAMP, C.TIME_UNSET, C.INDEX_UNSET.toLong()
            )

            fun overestimatedResult(
                newCeilingTimestamp: Long, newCeilingBytePosition: Long
            ): TimestampSearchResult {
                return TimestampSearchResult(
                    TYPE_POSITION_OVERESTIMATED, newCeilingTimestamp, newCeilingBytePosition
                )
            }

            fun underestimatedResult(
                newFloorTimestamp: Long, newCeilingBytePosition: Long
            ): TimestampSearchResult {
                return TimestampSearchResult(
                    TYPE_POSITION_UNDERESTIMATED, newFloorTimestamp, newCeilingBytePosition
                )
            }

            fun targetFoundResult(resultBytePosition: Long): TimestampSearchResult {
                return TimestampSearchResult(
                    TYPE_TARGET_TIMESTAMP_FOUND, C.TIME_UNSET, resultBytePosition
                )
            }
        }
    }

    class BinarySearchSeekMap(
        val seekTimestampConverter: SeekTimestampConverter,
        override val durationUs: Long,
        val floorTimePosition: Long,
        val ceilingTimePosition: Long,
        val floorBytePosition: Long,
        val ceilingBytePosition: Long,
        val approxBytesPerFrame: Long
    ) : SeekMap {
        override val isSeekable=true

        override fun getSeekPoints(timeUs: Long): SeekPoints {
            val nextSearchPosition =
                SeekOperationParams.calculateNextSearchBytePosition( 
                    seekTimestampConverter.timeUsToTargetTime(timeUs),  
                    floorTimePosition,  
                    ceilingTimePosition,  
                    floorBytePosition,  
                    ceilingBytePosition,  
                    approxBytesPerFrame
                )
            return SeekPoints(SeekPoint(timeUs, nextSearchPosition))
        }

        fun timeUsToTargetTime(timeUs: Long): Long {
            return seekTimestampConverter.timeUsToTargetTime(timeUs)
        }
    }

    companion object {
        private val MAX_SKIP_BYTES = (256 * 1024).toLong()
    }
}
