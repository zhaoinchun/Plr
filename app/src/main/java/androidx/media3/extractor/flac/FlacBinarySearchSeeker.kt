
package androidx.media3.extractor.flac

import androidx.media3.extractor.BinarySearchSeeker
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.FlacFrameReader.SampleNumberHolder
import androidx.media3.extractor.FlacFrameReader.checkFrameHeaderFromPeek
import androidx.media3.extractor.FlacStreamMetadata
import java.io.IOException
import kotlin.math.max

internal class FlacBinarySearchSeeker(
    flacStreamMetadata: FlacStreamMetadata,
    frameStartMarker: Int,
    firstFramePosition: Long,
    inputLength: Long
) : BinarySearchSeeker( 
    object :SeekTimestampConverter {
        override fun timeUsToTargetTime(timeUs: Long): Long {
          return  flacStreamMetadata.getSampleNumber(timeUs)
        }
    },
    FlacTimestampSeeker(flacStreamMetadata, frameStartMarker),
    flacStreamMetadata.durationUs,  
    0,  
    flacStreamMetadata.totalSamples,  
    firstFramePosition,  
    inputLength,  
    flacStreamMetadata.approxBytesPerFrame,  
    max(
        FlacConstants.MIN_FRAME_HEADER_SIZE, flacStreamMetadata.minFrameSize
    )
) {
    private class FlacTimestampSeeker(
        private val flacStreamMetadata: FlacStreamMetadata,
        private val frameStartMarker: Int
    ) : TimestampSeeker {
        private val sampleNumberHolder: SampleNumberHolder

        init {
            sampleNumberHolder = SampleNumberHolder()
        }

        @Throws(IOException::class)
        override fun searchForTimestamp(
            input: ExtractorInput,
            targetSampleNumber: Long
        ): TimestampSearchResult {
            val searchPosition = input.position

            // Find left frame.
            val leftFrameFirstSampleNumber = findNextFrame(input)
            val leftFramePosition = input.peekPosition

            input.advancePeekPosition(
                max(FlacConstants.MIN_FRAME_HEADER_SIZE, flacStreamMetadata.minFrameSize)
            )

            // Find right frame.
            val rightFrameFirstSampleNumber = findNextFrame(input)
            val rightFramePosition = input.peekPosition

            if (leftFrameFirstSampleNumber <= targetSampleNumber
                && rightFrameFirstSampleNumber > targetSampleNumber
            ) {
                return TimestampSearchResult.targetFoundResult(leftFramePosition)
            } else if (rightFrameFirstSampleNumber <= targetSampleNumber) {
                return TimestampSearchResult.underestimatedResult(
                    rightFrameFirstSampleNumber, rightFramePosition
                )
            } else {
                return TimestampSearchResult.overestimatedResult(
                    leftFrameFirstSampleNumber, searchPosition
                )
            }
        }

        @Throws(IOException::class)
        fun findNextFrame(input: ExtractorInput): Long {
            while (input.peekPosition < input.length - FlacConstants.MIN_FRAME_HEADER_SIZE
                && !checkFrameHeaderFromPeek(
                    input, flacStreamMetadata, frameStartMarker, sampleNumberHolder
                )
            ) {
                input.advancePeekPosition(1)
            }

            if (input.peekPosition >= input.length - FlacConstants.MIN_FRAME_HEADER_SIZE) {
                input.advancePeekPosition((input.length - input.peekPosition) as Int)
                return flacStreamMetadata.totalSamples
            }

            return sampleNumberHolder.sampleNumber
        }
    }
}
