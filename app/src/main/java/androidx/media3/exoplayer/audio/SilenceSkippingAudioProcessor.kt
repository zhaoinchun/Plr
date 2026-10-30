package androidx.media3.exoplayer.audio

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor

import androidx.media3.common.util.Util
import com.google.common.base.Preconditions
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min

class SilenceSkippingAudioProcessor  constructor(
    minimumSilenceDurationUs: Long = DEFAULT_MINIMUM_SILENCE_DURATION_US,
    silenceRetentionRatio: Float = DEFAULT_SILENCE_RETENTION_RATIO,
    maxSilenceToKeepDurationUs: Long = DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
    minVolumeToKeepPercentageWhenMuting: Int = DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
    silenceThresholdLevel: Short = DEFAULT_SILENCE_THRESHOLD_LEVEL
) : BaseAudioProcessor() {
    
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [STATE_NOISY, STATE_SHORTENING_SILENCE]
    )
    private annotation class State

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value =  [FADE_OUT, MUTE, FADE_IN, DO_NOT_CHANGE_VOLUME]
    )
    private annotation class VolumeChangeType

    private val silenceRetentionRatio: Float
    private val silenceThresholdLevel: Short
    private val minVolumeToKeepPercentageWhenMuting: Int
    private val minimumSilenceDurationUs: Long
    private val maxSilenceToKeepDurationUs: Long
    private var bytesPerFrame = 0
    private var enabled = false
    private var state: @State Int = 0
    var skippedFrames: Long = 0
        private set
    private var outputSilenceFramesSinceNoise = 0
    private var maybeSilenceBuffer: ByteArray
    private var maybeSilenceBufferStartIndex = 0
    private var maybeSilenceBufferContentsSize = 0

    //  modifyVolume() can be applied to a non-contiguous contents, the code is just more complex.
    private var contiguousOutputBuffer: ByteArray

    @Deprecated("Use {@link #SilenceSkippingAudioProcessor(long, float, long, int, short)} instead.")
    constructor(
        minimumSilenceDurationUs: Long,
        paddingSilenceUs: Long,
        silenceThresholdLevel: Short
    ) : this(
        minimumSilenceDurationUs,  
        paddingSilenceUs.toFloat() / minimumSilenceDurationUs,  
        minimumSilenceDurationUs,  
        0,
        silenceThresholdLevel
    )

    init {
        Preconditions.checkArgument(silenceRetentionRatio in 0f..1f)
        this.minimumSilenceDurationUs = minimumSilenceDurationUs
        this.silenceRetentionRatio = silenceRetentionRatio
        this.maxSilenceToKeepDurationUs = maxSilenceToKeepDurationUs
        this.minVolumeToKeepPercentageWhenMuting = minVolumeToKeepPercentageWhenMuting
        this.silenceThresholdLevel = silenceThresholdLevel
        maybeSilenceBuffer = Util.EMPTY_BYTE_ARRAY
        contiguousOutputBuffer = Util.EMPTY_BYTE_ARRAY
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.sampleRate == Format.NO_VALUE) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override val isActive: Boolean
        get() = super.isActive && enabled

    override fun queueInput(inputBuffer: ByteBuffer) {
        while (inputBuffer.hasRemaining() && !hasPendingOutput()) {
            when (state) {
                STATE_NOISY -> processNoisy(inputBuffer)
                STATE_SHORTENING_SILENCE -> shortenSilenceSilenceUntilNoise(inputBuffer)
                else -> throw IllegalStateException()
            }
        }
    }

    override fun onQueueEndOfStream() {
        // The maybeSilenceBuffer is only written to in the STATE_SHORTENING_SILENCE state, and
        // is always completely flushed before leaving the STATE_SHORTENING_SILENCE.
        if (maybeSilenceBufferContentsSize > 0) {
            // There's bytes in the buffer. So the final chunk of shortened silence will be output to
            // simulate a transition back to the noisy state and the end of output.
            outputShortenedSilenceBuffer( true)
            outputSilenceFramesSinceNoise = 0
        }
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        if (this.isActive) {
            bytesPerFrame = inputAudioFormat.channelCount * 2
            // Divide by 2 to allow the buffer to be split into two bytesPerFrame aligned parts.
            val maybeSilenceBufferSize =
                alignToBytePerFrameBoundary(durationUsToFrames(minimumSilenceDurationUs) / 2) * 2
            if (maybeSilenceBuffer.size != maybeSilenceBufferSize) {
                maybeSilenceBuffer = ByteArray(maybeSilenceBufferSize)
                contiguousOutputBuffer = ByteArray(maybeSilenceBufferSize)
            }
        }
        state = STATE_NOISY
        skippedFrames = 0
        outputSilenceFramesSinceNoise = 0
        maybeSilenceBufferStartIndex = 0
        maybeSilenceBufferContentsSize = 0
    }

    override fun onReset() {
        enabled = false
        maybeSilenceBuffer = Util.EMPTY_BYTE_ARRAY
        contiguousOutputBuffer = Util.EMPTY_BYTE_ARRAY
    }

    private fun processNoisy(inputBuffer: ByteBuffer) {
        val limit = inputBuffer.limit()

        // Check if there's any noise within the maybe silence buffer duration.
        inputBuffer.limit(min(limit, inputBuffer.position() + maybeSilenceBuffer.size))
        val noiseLimit = findNoiseLimit(inputBuffer)
        if (noiseLimit == inputBuffer.position()) {
            // The buffer contains the start of possible silence.
            state = STATE_SHORTENING_SILENCE
        } else {
            inputBuffer.limit(min(noiseLimit, inputBuffer.capacity()))
            output(inputBuffer)
        }

        // Restore the limit.
        inputBuffer.limit(limit)
    }

    private fun shortenSilenceSilenceUntilNoise(inputBuffer: ByteBuffer) {
        Preconditions.checkState(maybeSilenceBufferStartIndex < maybeSilenceBuffer.size)

        val limit = inputBuffer.limit()
        val noisePosition = findNoisePosition(inputBuffer)
        val silenceInputSize = noisePosition - inputBuffer.position()

        val indexToWriteTo: Int
        val contiguousBufferRemaining: Int
        if (maybeSilenceBufferStartIndex + maybeSilenceBufferContentsSize < maybeSilenceBuffer.size) {
            // ^0---^start---^end---^length
            contiguousBufferRemaining =
                (maybeSilenceBuffer.size
                        - (maybeSilenceBufferContentsSize + maybeSilenceBufferStartIndex))
            indexToWriteTo = maybeSilenceBufferStartIndex + maybeSilenceBufferContentsSize
        } else {
            // The bytes have wrapped around.  ^0---^end---^start---^length
            val amountInUpperPartOfBuffer = maybeSilenceBuffer.size - maybeSilenceBufferStartIndex
            indexToWriteTo = maybeSilenceBufferContentsSize - amountInUpperPartOfBuffer
            contiguousBufferRemaining = maybeSilenceBufferStartIndex - indexToWriteTo
        }

        val noiseFound = noisePosition < limit
        // Fill as much of the silence buffer as possible.
        val bytesOfInput = min(silenceInputSize, contiguousBufferRemaining)
        inputBuffer.limit(inputBuffer.position() + bytesOfInput)
        inputBuffer.get(maybeSilenceBuffer, indexToWriteTo, bytesOfInput)
        maybeSilenceBufferContentsSize += bytesOfInput

        Preconditions.checkState(maybeSilenceBufferContentsSize <= maybeSilenceBuffer.size)

        val shouldTransitionToNoisyState =
            noiseFound
                    &&  
                    silenceInputSize < contiguousBufferRemaining

        outputShortenedSilenceBuffer(shouldTransitionToNoisyState)

        if (shouldTransitionToNoisyState) {
            state = STATE_NOISY
            outputSilenceFramesSinceNoise = 0
        }

        // Restore the limit.
        inputBuffer.limit(limit)
    }

    private fun outputShortenedSilenceBuffer(shouldTransitionToNoisyState: Boolean) {
        val sizeBeforeOutput = maybeSilenceBufferContentsSize
        val bytesToOutput: Int
        val volumeChangeType: @VolumeChangeType Int
        val bytesConsumed: Int
        // Only output when buffer is full or transitioning to noisy state.
        if (maybeSilenceBufferContentsSize == maybeSilenceBuffer.size
            || shouldTransitionToNoisyState
        ) {
            if (outputSilenceFramesSinceNoise == 0) {
                // This is the beginning of a silence chunk so keep MINIMUM_SILENCE_DURATION_US / 2 of the
                // silence.
                if (shouldTransitionToNoisyState) {
                    volumeChangeType = DO_NOT_CHANGE_VOLUME
                    bytesToOutput = maybeSilenceBufferContentsSize
                    outputSilence(bytesToOutput, volumeChangeType)
                    bytesConsumed = bytesToOutput
                } else {
                    Preconditions.checkState(maybeSilenceBufferContentsSize >= maybeSilenceBuffer.size / 2)
                    // To keep this block a tad simpler, by always outputting exactly buffer size / 2 to avoid
                    // needing to add the shortening code here.
                    volumeChangeType = FADE_OUT
                    bytesToOutput = maybeSilenceBuffer.size / 2
                    outputSilence(bytesToOutput, volumeChangeType)
                    bytesConsumed = bytesToOutput
                }
            } else if (shouldTransitionToNoisyState) {
                volumeChangeType = FADE_IN

                val bytesRemainingAfterOutputtingHalfMin =
                    maybeSilenceBufferContentsSize - maybeSilenceBuffer.size / 2

                bytesConsumed = bytesRemainingAfterOutputtingHalfMin + maybeSilenceBuffer.size / 2
                val shortenedSilenceLength =
                    calculateShortenedSilenceLength(bytesRemainingAfterOutputtingHalfMin)

                // For simplicity we fade in over the shortened silence and the half buffer of padding.
                // This acts to increase the padding a bit which only helps (probably imperceptibly)
                // the sound quality.
                bytesToOutput = maybeSilenceBuffer.size / 2 + shortenedSilenceLength
                outputSilence(bytesToOutput, volumeChangeType)
            } else {
                volumeChangeType = MUTE
                // Output as much as possible while still keeping half the buffer full so that half the
                // min silence can be output later as padding.
                bytesConsumed = maybeSilenceBufferContentsSize - maybeSilenceBuffer.size / 2

                bytesToOutput = calculateShortenedSilenceLength(bytesConsumed)
                outputSilence(bytesToOutput, volumeChangeType)
            }

            Preconditions.checkState(
                bytesConsumed % bytesPerFrame == 0,
                "bytesConsumed is not aligned to frame size: %s",
                bytesConsumed
            )

            Preconditions.checkState((sizeBeforeOutput >= bytesToOutput))

            maybeSilenceBufferContentsSize -= bytesConsumed
            maybeSilenceBufferStartIndex += bytesConsumed
            // The start index might wrap back around to the start of the buffer.
            maybeSilenceBufferStartIndex %= maybeSilenceBuffer.size

            outputSilenceFramesSinceNoise += bytesToOutput / bytesPerFrame
            skippedFrames += ((bytesConsumed - bytesToOutput) / bytesPerFrame).toLong()
        }
    }

    private fun calculateShortenedSilenceLength(silenceToShortenBytes: Int): Int {
        // Start skipping silence to keep the silence below MAX_SILENCE_DURATION_US long.
        val bytesNeededToReachMax =
            ((durationUsToFrames(maxSilenceToKeepDurationUs) - outputSilenceFramesSinceNoise)
                    * bytesPerFrame
                    - maybeSilenceBuffer.size / 2)

        Preconditions.checkState(bytesNeededToReachMax >= 0)

        return alignToBytePerFrameBoundary(
            min(
                silenceToShortenBytes * silenceRetentionRatio + .5f,
                bytesNeededToReachMax.toFloat()
            )
        )
    }

    private fun alignToBytePerFrameBoundary(value: Int): Int {
        return (value / bytesPerFrame) * bytesPerFrame
    }

    private fun alignToBytePerFrameBoundary(value: Float): Int {
        return alignToBytePerFrameBoundary(value.toInt())
    }

    private fun outputRange(data: ByteArray, size: Int, rampType: @VolumeChangeType Int) {
        Preconditions.checkArgument(
            size % bytesPerFrame == 0, "byteOutput size is not aligned to frame size %s", size
        )

        modifyVolume(data, size, rampType)
        replaceOutputBuffer(size).put(data, 0, size).flip()
    }

    private fun outputSilence(sizeToOutput: Int, rampType: @VolumeChangeType Int) {
        if (sizeToOutput == 0) {
            return
        }

        Preconditions.checkArgument(maybeSilenceBufferContentsSize >= sizeToOutput)

        if (rampType == FADE_IN) {
            // Keeps the end of the buffer because we are padding the start of the next chunk of noise.
            if (maybeSilenceBufferStartIndex + maybeSilenceBufferContentsSize
                <= maybeSilenceBuffer.size
            ) {
                // ^0---^start---^end---^length
                System.arraycopy(
                    maybeSilenceBuffer,
                    maybeSilenceBufferStartIndex + maybeSilenceBufferContentsSize - sizeToOutput,
                    contiguousOutputBuffer,
                    0,
                    sizeToOutput
                )
            } else {
                // ^0---^end--^start---^length
                val sizeInUpperPartOfArray = maybeSilenceBuffer.size - maybeSilenceBufferStartIndex
                val sizeInLowerPartOfArray = maybeSilenceBufferContentsSize - sizeInUpperPartOfArray
                if (sizeInLowerPartOfArray >= sizeToOutput) {
                    // We just need the lower part of the array.
                    System.arraycopy(
                        maybeSilenceBuffer,
                        sizeInLowerPartOfArray - sizeToOutput,
                        contiguousOutputBuffer,
                        0,
                        sizeToOutput
                    )
                } else {
                    val sizeToOutputInUpperPart = sizeToOutput - sizeInLowerPartOfArray
                    System.arraycopy(
                        maybeSilenceBuffer,
                        maybeSilenceBuffer.size - sizeToOutputInUpperPart,
                        contiguousOutputBuffer,
                        0,
                        sizeToOutputInUpperPart
                    )

                    // Copy everything from lower part. DO_NOT_CHANGE_VOLUME (which keeps everything) and
                    // MUTE (where the content that is kept only provides background noise).
                    System.arraycopy(
                        maybeSilenceBuffer,
                        0,
                        contiguousOutputBuffer,
                        sizeToOutputInUpperPart,
                        sizeInLowerPartOfArray
                    )
                }
            }
        } else {
            if (maybeSilenceBufferStartIndex + sizeToOutput <= maybeSilenceBuffer.size) {
                // ^0---^start---^end---^length
                System.arraycopy(
                    maybeSilenceBuffer,
                    maybeSilenceBufferStartIndex,
                    contiguousOutputBuffer,
                    0,
                    sizeToOutput
                )
            } else {
                // ^0---^end (of content to output now)---^start---^length
                val sizeToCopyInUpperPartOfArray =
                    maybeSilenceBuffer.size - maybeSilenceBufferStartIndex
                // Copy the upper part of the array.
                System.arraycopy(
                    maybeSilenceBuffer,
                    maybeSilenceBufferStartIndex,
                    contiguousOutputBuffer,
                    0,
                    sizeToCopyInUpperPartOfArray
                )
                val amountToCopyFromLowerPartOfArray = sizeToOutput - sizeToCopyInUpperPartOfArray
                System.arraycopy(
                    maybeSilenceBuffer,
                    0,
                    contiguousOutputBuffer,
                    sizeToCopyInUpperPartOfArray,
                    amountToCopyFromLowerPartOfArray
                )
            }
        }

        Preconditions.checkArgument(
            sizeToOutput % bytesPerFrame == 0,
            "sizeToOutput is not aligned to frame size: %s",
            sizeToOutput
        )
        Preconditions.checkState(maybeSilenceBufferStartIndex < maybeSilenceBuffer.size)

        outputRange(contiguousOutputBuffer, sizeToOutput, rampType)
    }

    private fun modifyVolume(
        sampleBuffer: ByteArray,
        size: Int,
        volumeChangeType: @VolumeChangeType Int
    ) {
        if (volumeChangeType == DO_NOT_CHANGE_VOLUME) {
            return
        }
        var idx = 0
        while (idx < size) {
            val mostSignificantByte = sampleBuffer[idx + 1]
            val leastSignificantByte = sampleBuffer[idx]
            var sample: Int = twoByteSampleToInt(mostSignificantByte, leastSignificantByte)

            val volumeModificationPercentage: Int
            if (volumeChangeType == FADE_OUT) {
                volumeModificationPercentage =
                    calculateFadeOutPercentage( idx,  size - 1)
            } else if (volumeChangeType == FADE_IN) {
                volumeModificationPercentage =
                    calculateFadeInPercentage( idx,  size - 1)
            } else {
                volumeModificationPercentage = minVolumeToKeepPercentageWhenMuting
            }

            sample = (sample * volumeModificationPercentage) / 100
            sampleIntToTwoBigEndianBytes(sampleBuffer, idx, sample)
            idx += 2
        }
    }

    private fun calculateFadeOutPercentage(value: Int, max: Int): Int {
        return (((minVolumeToKeepPercentageWhenMuting - 100) * ((AVOID_TRUNCATION_FACTOR * value) / max))
                / AVOID_TRUNCATION_FACTOR
                + 100)
    }

    private fun calculateFadeInPercentage(value: Int, max: Int): Int {
        return (minVolumeToKeepPercentageWhenMuting
                + ((100 - minVolumeToKeepPercentageWhenMuting) * (AVOID_TRUNCATION_FACTOR * value) / max)
                / AVOID_TRUNCATION_FACTOR)
    }

    private fun output(data: ByteBuffer) {
        replaceOutputBuffer(data.remaining()).put(data).flip()
    }

    private fun durationUsToFrames(durationUs: Long): Int {
        return ((durationUs * inputAudioFormat.sampleRate) / C.MICROS_PER_SECOND).toInt()
    }

    private fun findNoisePosition(buffer: ByteBuffer): Int {
        // The input is in ByteOrder.nativeOrder(), which is little endian on Android.
        var i = buffer.position() + 1
        while (i < buffer.limit()) {
            if (isNoise(buffer[i], buffer.get(i - 1))) {
                // Round to the start of the frame.
                return bytesPerFrame * (i / bytesPerFrame)
            }
            i += 2
        }
        return buffer.limit()
    }

    private fun findNoiseLimit(buffer: ByteBuffer): Int {
        // The input is in ByteOrder.nativeOrder(), which is little endian on Android.
        var i = buffer.limit() - 1
        while (i >= buffer.position()) {
            if (isNoise(buffer[i], buffer.get(i - 1))) {
                // Return the start of the next frame.
                return bytesPerFrame * (i / bytesPerFrame) + bytesPerFrame
            }
            i -= 2
        }
        return buffer.position()
    }

    private fun isNoise(mostSignificantByte: Byte, leastSignificantByte: Byte): Boolean {
        return abs(
            twoByteSampleToInt(
                mostSignificantByte,
                leastSignificantByte
            )
        ) > silenceThresholdLevel
    }

    companion object {
        const val DEFAULT_SILENCE_RETENTION_RATIO: Float = 0.2f
        const val DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE: Int = 10
        const val DEFAULT_SILENCE_THRESHOLD_LEVEL: Short = 1024
        const val DEFAULT_MINIMUM_SILENCE_DURATION_US: Long = 100000
        const val DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US: Long = 2000000

        @Deprecated("Specify silence behaviour via {@code silenceRetentionRatio} instead.")
        const val DEFAULT_PADDING_SILENCE_US: Long = 20000
        private const val STATE_NOISY = 0
        private const val STATE_SHORTENING_SILENCE = 1
        private const val FADE_OUT = 0
        private const val MUTE = 1
        private const val FADE_IN = 2
        private const val DO_NOT_CHANGE_VOLUME = 3
        private const val AVOID_TRUNCATION_FACTOR = 1000

        private fun twoByteSampleToInt(mostSignificantByte: Byte, leastSignificantByte: Byte): Int {
            return ((leastSignificantByte.toInt() and 0xFF) or (mostSignificantByte.toInt() shl 8))
        }

        private fun sampleIntToTwoBigEndianBytes(
            byteArray: ByteArray,
            startIndex: Int,
            sample: Int
        ) {
            // Avoid 16-bit-integer overflow when writing back the manipulated data.
            if (sample >= Short.MAX_VALUE) {
                byteArray[startIndex] = 0xFF.toByte()
                byteArray[startIndex + 1] = 0x7F.toByte()
            } else if (sample <= Short.MIN_VALUE) {
                byteArray[startIndex] = 0x00.toByte()
                byteArray[startIndex + 1] = 0x80.toByte()
            } else {
                byteArray[startIndex] = (sample and 0xFF).toByte()
                byteArray[startIndex + 1] = (sample shr 8).toByte()
            }
        }
    }
}
