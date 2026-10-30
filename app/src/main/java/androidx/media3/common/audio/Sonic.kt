package androidx.media3.common.audio

import com.google.common.base.Preconditions
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class Sonic(
    private val inputSampleRateHz: Int,
    private val channelCount: Int,
    private val speed: Float,
    private val pitch: Float,
    outputSampleRateHz: Int,
    useFloatSamples: Boolean
) {
    private val rate: Float
    private val minPeriod: Int
    private val maxPeriod: Int
    private val maxRequiredFrameCount: Int
    private val impl: SonicImpl<*>

    private var inputFrameCount = 0
    private var outputFrameCount = 0
    private var pitchFrameCount = 0
    private var oldRatePosition = 0
    private var newRatePosition = 0

    private var remainingInputToCopyFrameCount = 0

    private var prevPeriod = 0
    private var accumulatedSpeedAdjustmentError = 0.0

    init {
        rate = inputSampleRateHz.toFloat() / outputSampleRateHz
        minPeriod = inputSampleRateHz / MAXIMUM_PITCH
        maxPeriod = inputSampleRateHz / MINIMUM_PITCH
        maxRequiredFrameCount = 2 * maxPeriod
        impl = if (useFloatSamples) SonicFloatImpl() else SonicShortImpl()
    }

    val pendingInputBytes: Int
        
        get() = inputFrameCount * channelCount * impl.bytesPerSample()

    fun queueInput(buffer: ByteBuffer) {
        val bytesToWrite = buffer.remaining()
        val framesToWrite = bytesToWrite / (channelCount * impl.bytesPerSample())
        impl.ensureAdditionalFramesInInputBuffer(framesToWrite)
        impl.copyBufferToInputBuffer(buffer, bytesToWrite)
        inputFrameCount += framesToWrite
        processStreamInput()
    }

    fun getOutput(buffer: ByteBuffer) {
        Preconditions.checkState(outputFrameCount >= 0)
        val framesToRead =
            min(buffer.remaining() / (channelCount * impl.bytesPerSample()), outputFrameCount)
        impl.copyOutputToByteBuffer(buffer, framesToRead)
        outputFrameCount -= framesToRead
        System.arraycopy(
            impl.outputBuffer,
            framesToRead * channelCount,
            impl.outputBuffer,
            0,
            outputFrameCount * channelCount
        )
    }

    fun queueEndOfStream() {
        val remainingFrameCount = inputFrameCount
        val s = (speed / pitch).toDouble()
        val r = (rate * pitch).toDouble()

        // If there are frames to be copied directly onto the output buffer, we should not count those
        // as "input frames" because Sonic is not applying any processing on them.
        val adjustedRemainingFrames = remainingFrameCount - remainingInputToCopyFrameCount

        // We add directly to the output the number of frames in remainingInputToCopyFrameCount.
        // Otherwise, expectedOutputFrames will be off and will make Sonic output an incorrect number of
        // frames.
        val expectedOutputFrames =
            (outputFrameCount
                    + ((adjustedRemainingFrames / s + remainingInputToCopyFrameCount
                    + accumulatedSpeedAdjustmentError
                    + pitchFrameCount)
                    / r
                    + 0.5).toInt())
        accumulatedSpeedAdjustmentError = 0.0

        // Add enough silence to flush both input and pitch buffers.
        impl.ensureAdditionalFramesInInputBuffer(remainingFrameCount + 2 * maxRequiredFrameCount)
        impl.zeroInputBuffer(remainingFrameCount * channelCount, 2 * maxRequiredFrameCount)
        inputFrameCount += 2 * maxRequiredFrameCount
        processStreamInput()
        // Throw away any extra frames we generated due to the silence we added.
        if (outputFrameCount > expectedOutputFrames) {
            // expectedOutputFrames might be negative, so set lower bound to 0.
            outputFrameCount = max(expectedOutputFrames, 0)
        }
        // Empty input and pitch buffers.
        inputFrameCount = 0
        remainingInputToCopyFrameCount = 0
        pitchFrameCount = 0
    }

    fun flush() {
        inputFrameCount = 0
        outputFrameCount = 0
        pitchFrameCount = 0
        oldRatePosition = 0
        newRatePosition = 0
        remainingInputToCopyFrameCount = 0
        prevPeriod = 0
        accumulatedSpeedAdjustmentError = 0.0
        impl.flush()
    }

    val outputSize: Int
        
        get() {
            Preconditions.checkState(outputFrameCount >= 0)
            return outputFrameCount * channelCount * impl.bytesPerSample()
        }

    // Internal methods.
    private fun copyToOutput(positionFrames: Int, frameCount: Int) {
        impl.ensureAdditionalFramesInOutputBuffer(frameCount)
        System.arraycopy(
            impl.inputBuffer,
            positionFrames * channelCount,
            impl.outputBuffer,
            outputFrameCount * channelCount,
            frameCount * channelCount
        )
        outputFrameCount += frameCount
    }

    private fun copyInputToOutput(positionFrames: Int): Int {
        val frameCount = min(maxRequiredFrameCount, remainingInputToCopyFrameCount)
        copyToOutput(positionFrames, frameCount)
        remainingInputToCopyFrameCount -= frameCount
        return frameCount
    }

    private fun findPitchPeriod(positionFrames: Int): Int {
        // Find the pitch period. This is a critical step, and we may have to try multiple ways to get a
        // good answer. This version uses AMDF. To improve speed, we down sample by an integer factor
        // get in the 11 kHz range, and then do it again with a narrower frequency range without down
        // sampling.
        var period: Int
        val retPeriod: Int
        val skip = if (inputSampleRateHz > AMDF_FREQUENCY) inputSampleRateHz / AMDF_FREQUENCY else 1
        if (channelCount == 1 && skip == 1) {
            period =
                impl.findPitchPeriodInRangeWithInputBuffer(positionFrames, minPeriod, maxPeriod)
        } else {
            impl.downSampleInput(positionFrames, skip)
            period =
                impl.findPitchPeriodInRangeWithDownsampleBuffer(
                    0,
                    minPeriod / skip,
                    maxPeriod / skip
                )
            if (skip != 1) {
                period *= skip
                var minP = period - (skip * 4)
                var maxP = period + (skip * 4)
                if (minP < minPeriod) {
                    minP = minPeriod
                }
                if (maxP > maxPeriod) {
                    maxP = maxPeriod
                }
                if (channelCount == 1) {
                    period = impl.findPitchPeriodInRangeWithInputBuffer(positionFrames, minP, maxP)
                } else {
                    impl.downSampleInput(positionFrames, 1)
                    period = impl.findPitchPeriodInRangeWithDownsampleBuffer(0, minP, maxP)
                }
            }
        }
        if (impl.isPreviousPeriodBetter) {
            retPeriod = prevPeriod
        } else {
            retPeriod = period
        }
        impl.updatePreviousMinDiff()
        prevPeriod = period
        return retPeriod
    }

    private fun adjustRate(rate: Float, originalOutputFrameCount: Int) {
        if (outputFrameCount == originalOutputFrameCount) {
            return
        }

        // Use long to avoid overflows int-int multiplications. The actual value of newSampleRate and
        // oldSampleRate should always be comfortably within the int range.
        var newSampleRate = (inputSampleRateHz / rate).toLong()
        var oldSampleRate = inputSampleRateHz.toLong()
        // Set these values to help with the integer math.
        while (newSampleRate != 0L && oldSampleRate != 0L && newSampleRate % 2 == 0L && oldSampleRate % 2 == 0L) {
            newSampleRate /= 2
            oldSampleRate /= 2
        }
        moveNewSamplesToPitchBuffer(originalOutputFrameCount)
        // Leave at least one pitch sample in the buffer.
        for (position in 0..<pitchFrameCount - 1) {
            // Cast to long to avoid overflow.
            while ((oldRatePosition + 1) * newSampleRate > newRatePosition * oldSampleRate) {
                impl.ensureAdditionalFramesInOutputBuffer( 1)
                impl.interpolateFrame(position, oldSampleRate, newSampleRate)
                newRatePosition++
                outputFrameCount++
            }
            oldRatePosition++
            if (oldRatePosition.toLong() == oldSampleRate) {
                oldRatePosition = 0
                Preconditions.checkState(newRatePosition.toLong() == newSampleRate)
                newRatePosition = 0
            }
        }
        removePitchFrames(pitchFrameCount - 1)
    }

    private fun moveNewSamplesToPitchBuffer(originalOutputFrameCount: Int) {
        val frameCount = outputFrameCount - originalOutputFrameCount
        impl.ensureAdditionalFramesInPitchBuffer(frameCount)
        System.arraycopy(
            impl.outputBuffer,
            originalOutputFrameCount * channelCount,
            impl.pitchBuffer,
            pitchFrameCount * channelCount,
            frameCount * channelCount
        )
        outputFrameCount = originalOutputFrameCount
        pitchFrameCount += frameCount
    }

    private fun removePitchFrames(frameCount: Int) {
        if (frameCount == 0) {
            return
        }
        System.arraycopy(
            impl.pitchBuffer,
            frameCount * channelCount,
            impl.pitchBuffer,
            0,
            (pitchFrameCount - frameCount) * channelCount
        )
        pitchFrameCount -= frameCount
    }

    private fun skipPitchPeriod(position: Int, speed: Double, period: Int): Int {
        // Skip over a pitch period, and copy period/speed samples to the output.
        val newFrameCount: Int
        if (speed >= 2.0f) {
            val expectedFrameCount = period / (speed - 1.0) + accumulatedSpeedAdjustmentError
            newFrameCount = Math.round(expectedFrameCount).toInt()
            accumulatedSpeedAdjustmentError = expectedFrameCount - newFrameCount
        } else {
            newFrameCount = period
            val expectedInputToCopy =
                period * (2.0f - speed) / (speed - 1.0f) + accumulatedSpeedAdjustmentError
            remainingInputToCopyFrameCount = Math.round(expectedInputToCopy).toInt()
            accumulatedSpeedAdjustmentError = expectedInputToCopy - remainingInputToCopyFrameCount
        }
        impl.ensureAdditionalFramesInOutputBuffer(newFrameCount)
        impl.overlapAdd(newFrameCount, channelCount, outputFrameCount, position, position + period)
        outputFrameCount += newFrameCount
        return newFrameCount
    }

    private fun insertPitchPeriod(position: Int, speed: Double, period: Int): Int {
        // Insert a pitch period, and determine how much input to copy directly.
        val newFrameCount: Int
        if (speed < 0.5f) {
            val expectedFrameCount =
                period * speed / (1.0f - speed) + accumulatedSpeedAdjustmentError
            newFrameCount = Math.round(expectedFrameCount).toInt()
            accumulatedSpeedAdjustmentError = expectedFrameCount - newFrameCount
        } else {
            newFrameCount = period
            val expectedInputToCopy =
                period * (2.0f * speed - 1.0f) / (1.0f - speed) + accumulatedSpeedAdjustmentError
            remainingInputToCopyFrameCount = Math.round(expectedInputToCopy).toInt()
            accumulatedSpeedAdjustmentError = expectedInputToCopy - remainingInputToCopyFrameCount
        }
        impl.ensureAdditionalFramesInOutputBuffer(period + newFrameCount)
        System.arraycopy(
            impl.inputBuffer,
            position * channelCount,
            impl.outputBuffer,
            outputFrameCount * channelCount,
            period * channelCount
        )
        impl.overlapAdd(
            newFrameCount, channelCount, outputFrameCount + period, position + period, position
        )
        outputFrameCount += period + newFrameCount
        return newFrameCount
    }

    private fun changeSpeed(speed: Double) {
        if (inputFrameCount < maxRequiredFrameCount) {
            return
        }
        val frameCount = inputFrameCount
        var positionFrames = 0
        do {
            if (remainingInputToCopyFrameCount > 0) {
                positionFrames += copyInputToOutput(positionFrames)
            } else {
                val period = findPitchPeriod(positionFrames)
                if (speed > 1.0) {
                    positionFrames += period + skipPitchPeriod(positionFrames, speed, period)
                } else {
                    positionFrames += insertPitchPeriod(positionFrames, speed, period)
                }
            }
        } while (positionFrames + maxRequiredFrameCount <= frameCount)
        removeProcessedInputFrames(positionFrames)
    }

    private fun removeProcessedInputFrames(positionFrames: Int) {
        val remainingFrames = inputFrameCount - positionFrames
        System.arraycopy(
            impl.inputBuffer,
            positionFrames * channelCount,
            impl.inputBuffer,
            0,
            remainingFrames * channelCount
        )
        inputFrameCount = remainingFrames
    }

    private fun processStreamInput() {
        // Resample as many pitch periods as we have buffered on the input.
        val originalOutputFrameCount = outputFrameCount
        val s = (speed / pitch).toDouble()
        val r = rate * pitch
        if (s > MINIMUM_SPEEDUP_RATE || s < MINIMUM_SLOWDOWN_RATE) {
            changeSpeed(s)
        } else {
            copyToOutput(0, inputFrameCount)
            inputFrameCount = 0
        }
        if (r != 1.0f) {
            adjustRate(r, originalOutputFrameCount)
        }
    }

    private interface SonicImpl<T : Any> {
        
        fun bytesPerSample(): Int

        fun interpolateFrame(positionFrames: Int, oldSampleRate: Long, newSampleRate: Long)

        val isPreviousPeriodBetter: Boolean

        fun downSampleInput(positionFrames: Int, skip: Int)

        fun findPitchPeriodInRangeWithDownsampleBuffer(
            positionFrames: Int, minPeriod: Int, maxPeriod: Int
        ): Int

        fun findPitchPeriodInRangeWithInputBuffer(
            positionFrames: Int,
            minPeriod: Int,
            maxPeriod: Int
        ): Int

        fun flush()

        fun overlapAdd(
            frameCount: Int,
            channelCount: Int,
            outPosition: Int,
            rampDownPosition: Int,
            rampUpPosition: Int
        )

        fun updatePreviousMinDiff()

        fun ensureAdditionalFramesInInputBuffer(additionalFrameCount: Int)

        fun ensureAdditionalFramesInOutputBuffer(additionalFrameCount: Int)

        fun ensureAdditionalFramesInPitchBuffer(additionalFrameCount: Int)

        fun zeroInputBuffer(startPosition: Int, length: Int)

        fun copyBufferToInputBuffer(buffer: ByteBuffer, bytesToWrite: Int)

        fun copyOutputToByteBuffer(buffer: ByteBuffer, framesToRead: Int)

        val inputBuffer: T?

        val outputBuffer: T?

        val pitchBuffer: T?
    }

    private inner class SonicFloatImpl : SonicImpl<FloatArray> {
        private val downSampleBuffer: FloatArray

        override var inputBuffer: FloatArray
        override var outputBuffer: FloatArray
        override var pitchBuffer: FloatArray

        private var minDiff = 0.0
        private var maxDiff = 0.0
        private var prevMinDiff = 0.0

        init {
            downSampleBuffer = FloatArray(maxRequiredFrameCount)
            inputBuffer = FloatArray(maxRequiredFrameCount * channelCount)
            outputBuffer = FloatArray(maxRequiredFrameCount * channelCount)
            pitchBuffer = FloatArray(maxRequiredFrameCount * channelCount)
        }

        override fun bytesPerSample(): Int {
            return 4
        }

        override fun interpolateFrame(
            positionFrame: Int,
            oldSampleRate: Long,
            newSampleRate: Long
        ) {
            for (i in 0..<channelCount) {
                outputBuffer[outputFrameCount * channelCount + i] =
                    interpolate(
                        pitchBuffer, positionFrame * channelCount + i, oldSampleRate, newSampleRate
                    )
            }
        }

        override val isPreviousPeriodBetter: Boolean
            get() {
                if (minDiff == 0.0 || prevPeriod == 0) {
                    return false
                }
                if (maxDiff > minDiff * 3) {
                    // Got a reasonable match this period.
                    return false
                }
                if (minDiff * 2 <= prevMinDiff * 3) {
                    // Mismatch is not that much greater this period.
                    return false
                }
                return true
            }
        override fun downSampleInput(positionFrames: Int, skip: Int) {
            // If skip is greater than one, average skip samples together and write them to the
            // down-sample
            // buffer. If channelCount is greater than one, mix the channels together as we down sample.
            var positionFrames = positionFrames
            val frameCount = maxRequiredFrameCount / skip
            val samplesPerValue = channelCount * skip
            positionFrames *= channelCount
            for (i in 0..<frameCount) {
                var value = 0.0
                for (j in 0..<samplesPerValue) {
                    value += inputBuffer[positionFrames + i * samplesPerValue + j].toDouble()
                }
                value /= samplesPerValue.toDouble()
                downSampleBuffer[i] = value.toFloat()
            }
        }

        override fun findPitchPeriodInRangeWithDownsampleBuffer(
            positionFrames: Int, minPeriod: Int, maxPeriod: Int
        ): Int {
            return findPitchPeriodInRange(downSampleBuffer, positionFrames, minPeriod, maxPeriod)
        }

        override fun findPitchPeriodInRangeWithInputBuffer(
            positionFrames: Int, minPeriod: Int, maxPeriod: Int
        ): Int {
            return findPitchPeriodInRange(inputBuffer, positionFrames, minPeriod, maxPeriod)
        }

        override fun flush() {
            prevMinDiff = 0.0
            minDiff = 0.0
            maxDiff = 0.0
        }

        override fun overlapAdd(
            frameCount: Int,
            channelCount: Int,
            outPosition: Int,
            rampDownPosition: Int,
            rampUpPosition: Int
        ) {
            overlapAdd(
                frameCount,
                channelCount,
                outputBuffer,
                outPosition,
                inputBuffer,
                rampDownPosition,
                inputBuffer,
                rampUpPosition
            )
        }

        fun overlapAdd(
            frameCount: Int,
            channelCount: Int,
            out: FloatArray,
            outPosition: Int,
            rampDown: FloatArray,
            rampDownPosition: Int,
            rampUp: FloatArray,
            rampUpPosition: Int
        ) {
            for (i in 0..<channelCount) {
                var o = outPosition * channelCount + i
                var u = rampUpPosition * channelCount + i
                var d = rampDownPosition * channelCount + i
                for (t in 0..<frameCount) {
                    out[o] = (rampDown[d] * (frameCount - t) + rampUp[u] * t) / frameCount
                    o += channelCount
                    d += channelCount
                    u += channelCount
                }
            }
        }

        override fun updatePreviousMinDiff() {
            prevMinDiff = minDiff
        }

        override fun ensureAdditionalFramesInInputBuffer(additionalFrameCount: Int) {
            inputBuffer =
                ensureSpaceForAdditionalFrames(inputBuffer, inputFrameCount, additionalFrameCount)
        }

        override fun ensureAdditionalFramesInOutputBuffer(additionalFrameCount: Int) {
            outputBuffer =
                ensureSpaceForAdditionalFrames(outputBuffer, outputFrameCount, additionalFrameCount)
        }

        override fun ensureAdditionalFramesInPitchBuffer(additionalFrameCount: Int) {
            pitchBuffer =
                ensureSpaceForAdditionalFrames(pitchBuffer, pitchFrameCount, additionalFrameCount)
        }

        override fun zeroInputBuffer(startPosition: Int, length: Int) {
            for (i in 0..<length * channelCount) {
                inputBuffer[startPosition + i] = 0f
            }
        }

        override fun copyBufferToInputBuffer(buffer: ByteBuffer, bytesToWrite: Int) {
            buffer
                .asFloatBuffer()
                .get(inputBuffer, inputFrameCount * channelCount, bytesToWrite / bytesPerSample())
            buffer.position(buffer.position() + bytesToWrite)
        }

        override fun copyOutputToByteBuffer(buffer: ByteBuffer, framesToRead: Int) {
            buffer.asFloatBuffer().put(outputBuffer, 0, framesToRead * channelCount)
            buffer.position(buffer.position() + framesToRead * bytesPerSample() * channelCount)
        }

        fun interpolate(
            `in`: FloatArray,
            inPos: Int,
            oldSampleRate: Long,
            newSampleRate: Long
        ): Float {
            val left = `in`[inPos]
            val right = `in`[inPos + channelCount]
            val position = newRatePosition * oldSampleRate
            val leftPosition = oldRatePosition * newSampleRate
            val rightPosition = (oldRatePosition + 1) * newSampleRate
            val ratio = rightPosition - position
            val width = rightPosition - leftPosition
            return (ratio * left + (width - ratio) * right) / width
        }

        fun findPitchPeriodInRange(
            samples: FloatArray, positionFrames: Int, minPeriod: Int, maxPeriod: Int
        ): Int {
            // Find the best frequency match in the range, and given a sample skip multiple. For now, just
            // find the pitch of the first channel.
            var positionFrames = positionFrames
            var bestPeriod = 0
            var worstPeriod = 255
            var minDiff = 1.0
            var maxDiff = 0.0
            positionFrames *= channelCount
            for (period in minPeriod..maxPeriod) {
                var diff = 0.0
                for (i in 0..<period) {
                    val sVal = samples[positionFrames + i]
                    val pVal = samples[positionFrames + period + i]
                    diff += abs(sVal - pVal).toDouble()
                }
                if (diff * bestPeriod < minDiff * period) {
                    minDiff = diff
                    bestPeriod = period
                }
                if (diff * worstPeriod > maxDiff * period) {
                    maxDiff = diff
                    worstPeriod = period
                }
            }
            this.minDiff = minDiff / bestPeriod
            this.maxDiff = maxDiff / worstPeriod
            return bestPeriod
        }

        fun ensureSpaceForAdditionalFrames(
            buffer: FloatArray, frameCount: Int, additionalFrameCount: Int
        ): FloatArray {
            val currentCapacityFrames = buffer.size / channelCount
            if (frameCount + additionalFrameCount <= currentCapacityFrames) {
                return buffer
            } else {
                val newCapacityFrames = 3 * currentCapacityFrames / 2 + additionalFrameCount
                return buffer.copyOf(newCapacityFrames * channelCount)
            }
        }
    }

    private inner class SonicShortImpl : SonicImpl<ShortArray> {
        private val downSampleBuffer: ShortArray

        override var inputBuffer: ShortArray
        override var outputBuffer: ShortArray
        override var pitchBuffer: ShortArray

        private var minDiff = 0
        private var maxDiff = 0
        private var prevMinDiff = 0

        init {
            downSampleBuffer = ShortArray(maxRequiredFrameCount)
            inputBuffer = ShortArray(maxRequiredFrameCount * channelCount)
            outputBuffer = ShortArray(maxRequiredFrameCount * channelCount)
            pitchBuffer = ShortArray(maxRequiredFrameCount * channelCount)
        }

        override fun bytesPerSample(): Int {
            return 2
        }

        override fun interpolateFrame(
            positionFrames: Int,
            oldSampleRate: Long,
            newSampleRate: Long
        ) {
            for (i in 0..<channelCount) {
                outputBuffer[outputFrameCount * channelCount + i] =
                    interpolate(
                        pitchBuffer, positionFrames * channelCount + i, oldSampleRate, newSampleRate
                    )
            }
        }

        override val isPreviousPeriodBetter: Boolean
            get() {
                if (minDiff == 0 || prevPeriod == 0) {
                    return false
                }
                if (maxDiff > minDiff * 3) {
                    // Got a reasonable match this period.
                    return false
                }
                if (minDiff * 2 <= prevMinDiff * 3) {
                    // Mismatch is not that much greater this period.
                    return false
                }
                return true
            }

        override fun downSampleInput(positionFrames: Int, skip: Int) {
            var positionFrames = positionFrames
            val samples = inputBuffer
            // If skip is greater than one, average skip samples together and write them to the
            // down-sample
            // buffer. If channelCount is greater than one, mix the channels together as we down sample.
            val frameCount = maxRequiredFrameCount / skip
            val samplesPerValue = channelCount * skip
            positionFrames *= channelCount
            for (i in 0..<frameCount) {
                var value = 0
                for (j in 0..<samplesPerValue) {
                    value += samples[positionFrames + i * samplesPerValue + j].toInt()
                }
                value /= samplesPerValue
                downSampleBuffer[i] = value.toShort()
            }
        }

        override fun findPitchPeriodInRangeWithDownsampleBuffer(
            positionFrames: Int, minPeriod: Int, maxPeriod: Int
        ): Int {
            return findPitchPeriodInRange(downSampleBuffer, positionFrames, minPeriod, maxPeriod)
        }

        override fun findPitchPeriodInRangeWithInputBuffer(
            positionFrames: Int, minPeriod: Int, maxPeriod: Int
        ): Int {
            return findPitchPeriodInRange(inputBuffer, positionFrames, minPeriod, maxPeriod)
        }

        override fun flush() {
            prevMinDiff = 0
            minDiff = 0
            maxDiff = 0
        }

        override fun overlapAdd(
            frameCount: Int,
            channelCount: Int,
            outPosition: Int,
            rampDownPosition: Int,
            rampUpPosition: Int
        ) {
            overlapAdd(
                frameCount,
                channelCount,
                outputBuffer,
                outPosition,
                inputBuffer,
                rampDownPosition,
                inputBuffer,
                rampUpPosition
            )
        }

        fun overlapAdd(
            frameCount: Int,
            channelCount: Int,
            out: ShortArray,
            outPosition: Int,
            rampDown: ShortArray,
            rampDownPosition: Int,
            rampUp: ShortArray,
            rampUpPosition: Int
        ) {
            for (i in 0..<channelCount) {
                var o = outPosition * channelCount + i
                var u = rampUpPosition * channelCount + i
                var d = rampDownPosition * channelCount + i
                for (t in 0..<frameCount) {
                    out[o] =
                        ((rampDown[d] * (frameCount - t) + rampUp[u] * t) / frameCount).toShort()
                    o += channelCount
                    d += channelCount
                    u += channelCount
                }
            }
        }

        override fun updatePreviousMinDiff() {
            prevMinDiff = minDiff
        }

        override fun ensureAdditionalFramesInInputBuffer(additionalFrameCount: Int) {
            inputBuffer =
                ensureSpaceForAdditionalFrames(inputBuffer, inputFrameCount, additionalFrameCount)
        }

        override fun ensureAdditionalFramesInOutputBuffer(additionalFrameCount: Int) {
            outputBuffer =
                ensureSpaceForAdditionalFrames(outputBuffer, outputFrameCount, additionalFrameCount)
        }

        override fun ensureAdditionalFramesInPitchBuffer(additionalFrameCount: Int) {
            pitchBuffer =
                ensureSpaceForAdditionalFrames(pitchBuffer, pitchFrameCount, additionalFrameCount)
        }

        override fun zeroInputBuffer(startPosition: Int, length: Int) {
            for (i in 0..<length * channelCount) {
                inputBuffer[startPosition + i] = 0
            }
        }

        override fun copyBufferToInputBuffer(buffer: ByteBuffer, bytesToWrite: Int) {
            buffer.asShortBuffer()
                .get(inputBuffer, inputFrameCount * channelCount, bytesToWrite / 2)
            buffer.position(buffer.position() + bytesToWrite)
        }

        override fun copyOutputToByteBuffer(buffer: ByteBuffer, framesToRead: Int) {
            buffer.asShortBuffer().put(outputBuffer, 0, framesToRead * channelCount)
            buffer.position(buffer.position() + framesToRead * bytesPerSample() * channelCount)
        }

        fun findPitchPeriodInRange(
            samples: ShortArray, positionFrames: Int, minPeriod: Int, maxPeriod: Int
        ): Int {
            // Find the best frequency match in the range, and given a sample skip multiple. For now, just
            // find the pitch of the first channel.
            var positionFrames = positionFrames
            var bestPeriod = 0
            var worstPeriod = 255
            var minDiff = 1
            var maxDiff = 0
            positionFrames *= channelCount
            for (period in minPeriod..maxPeriod) {
                var diff = 0
                for (i in 0..<period) {
                    val sVal = samples[positionFrames + i]
                    val pVal = samples[positionFrames + period + i]
                    diff += abs(sVal - pVal)
                }
                // Note that the highest number of samples we add into diff will be less than 256, since we
                // skip samples. Thus, diff is a 24 bit number, and we can safely multiply by numSamples
                // without overflow.
                if (diff * bestPeriod < minDiff * period) {
                    minDiff = diff
                    bestPeriod = period
                }
                if (diff * worstPeriod > maxDiff * period) {
                    maxDiff = diff
                    worstPeriod = period
                }
            }
            this.minDiff = minDiff / bestPeriod
            this.maxDiff = maxDiff / worstPeriod
            return bestPeriod
        }

        fun interpolate(
            `in`: ShortArray,
            inPos: Int,
            oldSampleRate: Long,
            newSampleRate: Long
        ): Short {
            val left = `in`[inPos]
            val right = `in`[inPos + channelCount]
            val position = newRatePosition * oldSampleRate
            val leftPosition = oldRatePosition * newSampleRate
            val rightPosition = (oldRatePosition + 1) * newSampleRate
            val ratio = rightPosition - position
            val width = rightPosition - leftPosition
            return ((ratio * left + (width - ratio) * right) / width).toShort()
        }

        fun ensureSpaceForAdditionalFrames(
            buffer: ShortArray, frameCount: Int, additionalFrameCount: Int
        ): ShortArray {
            val currentCapacityFrames = buffer.size / channelCount
            if (frameCount + additionalFrameCount <= currentCapacityFrames) {
                return buffer
            } else {
                val newCapacityFrames = 3 * currentCapacityFrames / 2 + additionalFrameCount
                return buffer.copyOf(newCapacityFrames * channelCount)
            }
        }
    }

    companion object {
        private const val MINIMUM_PITCH = 65
        private const val MAXIMUM_PITCH = 400
        private const val AMDF_FREQUENCY = 4000

        private const val MINIMUM_SPEEDUP_RATE = 1.00001f
        private const val MINIMUM_SLOWDOWN_RATE = 0.99999f

        fun getExpectedFrameCountAfterProcessorApplied(
            inputSampleRateHz: Int,
            outputSampleRateHz: Int,
            speed: Float,
            pitch: Float,
            inputFrameCount: Long
        ): Long {
            var resamplingRate = inputSampleRateHz.toFloat() / outputSampleRateHz
            resamplingRate *= pitch
            val speedRate = (speed / pitch).toDouble()
            val bigResamplingRate = BigDecimal(resamplingRate.toString())

            val length = BigDecimal.valueOf(inputFrameCount)
            val framesAfterTimeStretching: BigDecimal
            if (speedRate > MINIMUM_SPEEDUP_RATE || speedRate < MINIMUM_SLOWDOWN_RATE) {
                framesAfterTimeStretching =
                    length.divide(BigDecimal.valueOf(speedRate), RoundingMode.HALF_EVEN)
            } else {
                // If speed is almost 1, then just copy the buffers without modifying them.
                framesAfterTimeStretching = length
            }

            if (resamplingRate == 1.0f) {
                return framesAfterTimeStretching.longValueExact()
            }

            val framesAfterResampling =
                framesAfterTimeStretching.divide(bigResamplingRate, RoundingMode.HALF_EVEN)

            return (framesAfterResampling.longValueExact()
                    - calculateAccumulatedTruncationErrorForResampling(
                framesAfterTimeStretching,
                BigDecimal.valueOf(inputSampleRateHz.toLong()),
                bigResamplingRate
            ))
        }

        fun calculateAccumulatedTruncationErrorForResampling(
            length: BigDecimal, sampleRate: BigDecimal, resamplingRate: BigDecimal?
        ): Long {
            // Calculate number of times that Sonic accumulates truncation error. Set scale to 20 decimal
            // places, so that division doesn't return an integer.
            val errorCount = length.divide(sampleRate,  20, RoundingMode.HALF_EVEN)

            // Calculate what truncation error Sonic is accumulating, calculated as:
            // inputSampleRate / resamplingRate - (int) inputSampleRate / resamplingRate. Set scale to 20
            // decimal places, so that division doesn't return an integer.
            var individualError =
                sampleRate.divide(resamplingRate,  20, RoundingMode.HALF_EVEN)
            individualError =
                individualError.subtract(
                    individualError.setScale( 0,
                        RoundingMode.FLOOR
                    )
                )
            // Calculate total accumulated error = (int) floor(errorCount * individualError).
            val accumulatedError =
                errorCount.multiply(individualError).setScale( 0, RoundingMode.FLOOR)

            return accumulatedError.longValueExact()
        }

        fun getExpectedInputFrameCountForOutputFrameCount(
            inputSampleRateHz: Int,
            outputSampleRateHz: Int,
            speed: Float,
            pitch: Float,
            outputFrameCount: Long
        ): Long {
            var resamplingRate = inputSampleRateHz.toFloat() / outputSampleRateHz
            resamplingRate *= pitch
            val bigResamplingRate = BigDecimal(resamplingRate.toString())
            val framesBeforeResampling: Long =
                getFrameCountBeforeResamplingForOutputCount(
                    BigDecimal.valueOf(inputSampleRateHz.toLong()),
                    bigResamplingRate,
                    BigDecimal.valueOf(outputFrameCount)
                )
            val speedRate = (speed / pitch).toDouble()

            if (speedRate > MINIMUM_SPEEDUP_RATE || speedRate < MINIMUM_SLOWDOWN_RATE) {
                return BigDecimal.valueOf(framesBeforeResampling)
                    .multiply(BigDecimal.valueOf(speedRate))
                    .setScale(0, RoundingMode.FLOOR)
                    .longValueExact()
            } else {
                // If speed is almost 1, then just copy the buffers without modifying them.
                return framesBeforeResampling
            }
        }

        private fun getFrameCountBeforeResamplingForOutputCount(
            sampleRate: BigDecimal, resamplingRate: BigDecimal?, outputLength: BigDecimal?
        ): Long {
            val denominator = sampleRate.divide(resamplingRate,  0, RoundingMode.FLOOR)
            val numerator = sampleRate.multiply(outputLength)
            return numerator.divide(denominator,  0, RoundingMode.FLOOR).longValueExact()
        }
    }
}
