package androidx.media3.common.audio

import androidx.annotation.GuardedBy
import androidx.annotation.IntRange
import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.LongArrayQueue
import androidx.media3.common.util.SpeedProviderUtil
import androidx.media3.common.util.SpeedProviderUtil.SpeedProviderMapper
import androidx.media3.common.util.TimestampConsumer

import androidx.media3.common.util.Util.durationUsToSampleCount
import androidx.media3.common.util.Util.sampleCountToDurationUs
import androidx.media3.common.util.Util.scaleLargeValue
import com.google.common.base.Preconditions
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Queue
import kotlin.math.min

class SpeedChangingAudioProcessor @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP) constructor(
    
    val speedProvider: SpeedProvider,
    areInputTimestampsAdjusted: Boolean,
    shouldMaintainPitch: Boolean
) : AudioProcessor {
    private val lock: Any

    private val sonicAudioProcessor: SynchronizedSonicAudioProcessor

    // Elements in the same positions in the queues are associated.
    @GuardedBy("lock")
    private val pendingCallbackInputTimesUs: LongArrayQueue

    @GuardedBy("lock")
    private val pendingCallbacks: Queue<TimestampConsumer>

    private val areInputTimestampsAdjusted: Boolean
    private val speedProviderMapper: SpeedProviderMapper
    private val shouldMaintainPitch: Boolean

    private var currentSpeed: Float
    private var framesRead: Long = 0
    private var endOfStreamQueuedToSonic = false

    @GuardedBy("lock")
    private var inputAudioFormat: AudioProcessor.AudioFormat

    private var pendingInputAudioFormat: AudioProcessor.AudioFormat
    private var pendingOutputAudioFormat: AudioProcessor.AudioFormat?
    private var inputEnded = false

    constructor(speedProvider: SpeedProvider) : this(
        speedProvider,  
        false,  
        false
    )

    init {
        pendingInputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET

        this.speedProviderMapper = SpeedProviderMapper(speedProvider)
        lock = Any()
        sonicAudioProcessor =
            SynchronizedSonicAudioProcessor(lock,  true)
        pendingCallbackInputTimesUs = LongArrayQueue()
        pendingCallbacks = ArrayDeque<TimestampConsumer>()
        currentSpeed = 1f
        this.areInputTimestampsAdjusted = areInputTimestampsAdjusted
        this.shouldMaintainPitch = shouldMaintainPitch
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat? {
        pendingInputAudioFormat = inputAudioFormat
        pendingOutputAudioFormat = sonicAudioProcessor.configure(inputAudioFormat)
        return pendingOutputAudioFormat
    }

    override val isActive: Boolean
        get() = !pendingOutputAudioFormat!!.equals(AudioProcessor.AudioFormat.NOT_SET)

    override fun getDurationAfterProcessorApplied(durationUs: Long): Long {
        if (areInputTimestampsAdjusted) {
            return durationUs
        }
        // TODO: b/473853921 - Migrate to SpeedProviderMapper after unexpected dynamic SpeedProvider
        // changes are removed.
        return SpeedProviderUtil.getDurationAfterSpeedProviderApplied(speedProvider, durationUs)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format: AudioProcessor.AudioFormat
        synchronized(lock) {
            format = inputAudioFormat
        }

        val newSpeed =
            SpeedProviderUtil.getSampleAlignedSpeed(speedProvider, framesRead, format.sampleRate)
        val nextSpeedChangeSamplePosition =
            SpeedProviderUtil.getNextSpeedChangeSamplePosition(
                speedProvider,
                framesRead,
                format.sampleRate
            )

        updateSpeed(newSpeed)

        val inputBufferLimit = inputBuffer.limit()
        val bytesToNextSpeedChange: Int
        if (nextSpeedChangeSamplePosition != C.INDEX_UNSET.toLong()) {
            bytesToNextSpeedChange =
                ((nextSpeedChangeSamplePosition - framesRead) * format.bytesPerFrame).toInt()
            // Update the input buffer limit to make sure that all samples processed have the same speed.
            inputBuffer.limit(
                min(
                    inputBufferLimit,
                    inputBuffer.position() + bytesToNextSpeedChange
                )
            )
        } else {
            bytesToNextSpeedChange = C.LENGTH_UNSET
        }

        val startPosition = inputBuffer.position().toLong()
        sonicAudioProcessor.queueInput(inputBuffer)
        if (bytesToNextSpeedChange != C.LENGTH_UNSET
            && (inputBuffer.position() - startPosition) == bytesToNextSpeedChange.toLong()
        ) {
            sonicAudioProcessor.queueEndOfStream()
            endOfStreamQueuedToSonic = true
        }
        val bytesRead = inputBuffer.position() - startPosition
        Preconditions.checkState(
            bytesRead % format.bytesPerFrame == 0L,
            "A frame was not queued completely."
        )
        framesRead += bytesRead / format.bytesPerFrame
        inputBuffer.limit(inputBufferLimit)
    }

    override fun queueEndOfStream() {
        inputEnded = true
        if (!endOfStreamQueuedToSonic) {
            sonicAudioProcessor.queueEndOfStream()
            endOfStreamQueuedToSonic = true
        }
    }

    override val output: ByteBuffer?
        get() = sonicAudioProcessor.output

    override val isEnded: Boolean
        get() = inputEnded && sonicAudioProcessor.isEnded

    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
        inputEnded = false
        resetInternalState( false)
        synchronized(lock) {
            inputAudioFormat = pendingInputAudioFormat
            sonicAudioProcessor.flush(streamMetadata)
            processPendingCallbacks()
            var positionOffsetUs = streamMetadata.positionOffsetUs
            if (areInputTimestampsAdjusted) {
                positionOffsetUs =
                    speedProviderMapper.getOriginalTimeUs(streamMetadata.positionOffsetUs)
            }
            framesRead = durationUsToSampleCount(positionOffsetUs, inputAudioFormat.sampleRate)
        }
    }

    override fun reset() {
        flush(AudioProcessor.StreamMetadata.DEFAULT)
        pendingInputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        synchronized(lock) {
            inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
            pendingCallbackInputTimesUs.clear()
            pendingCallbacks.clear()
        }
        resetInternalState( true)
        sonicAudioProcessor.reset()
    }

    // TODO(b/381553948): Accept an executor on which to dispatch the callback.
    fun getSpeedAdjustedTimeAsync(inputTimeUs: Long, callback: TimestampConsumer) {
        val sampleRate: Int
        synchronized(lock) {
            sampleRate = inputAudioFormat.sampleRate
            if (sampleRate == Format.NO_VALUE) {
                pendingCallbackInputTimesUs.add(inputTimeUs)
                pendingCallbacks.add(callback)
                return
            }
        }
        // TODO(b/381553948): Use an executor to invoke callback.
        callback.onTimestamp(
            getDurationUsAfterProcessorApplied(speedProvider, sampleRate, inputTimeUs)
        )
    }

    fun getMediaDurationUs(playoutDurationUs: Long): Long {
        val sampleRate: Int
        synchronized(lock) {
            sampleRate = inputAudioFormat.sampleRate
        }
        if (sampleRate == Format.NO_VALUE) {
            return playoutDurationUs
        }
        val outputSamples: Long =
            scaleLargeValue(
                playoutDurationUs,
                sampleRate.toLong(),
                C.MICROS_PER_SECOND,
                RoundingMode.HALF_EVEN
            )
        val inputSamples: Long =
            getInputFrameCountForOutput(speedProvider, sampleRate, outputSamples)
        return sampleCountToDurationUs(inputSamples, sampleRate)
    }

    private fun processPendingCallbacks() {
        synchronized(lock) {
            if (inputAudioFormat.sampleRate == Format.NO_VALUE) {
                return
            }
            while (!pendingCallbacks.isEmpty()) {
                val inputTimeUs = pendingCallbackInputTimesUs.remove()
                val consumer = pendingCallbacks.remove()
                // TODO(b/381553948): Use an executor to invoke callback.
                consumer.onTimestamp(
                    getDurationUsAfterProcessorApplied(
                        speedProvider, inputAudioFormat.sampleRate, inputTimeUs
                    )
                )
            }
        }
    }

    private fun updateSpeed(newSpeed: Float) {
        if (newSpeed != currentSpeed) {
            currentSpeed = newSpeed
            sonicAudioProcessor.setSpeed(newSpeed)
            if (!shouldMaintainPitch) {
                sonicAudioProcessor.setPitch(newSpeed)
            }
            // Invalidate any previously created buffers in SonicAudioProcessor and the base class.
            sonicAudioProcessor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            endOfStreamQueuedToSonic = false
        }
    }

    private fun resetInternalState(shouldResetSpeed: Boolean) {
        if (shouldResetSpeed) {
            currentSpeed = 1f
        }
        framesRead = 0
        endOfStreamQueuedToSonic = false
    }

    companion object {
        
        fun getSampleCountAfterProcessorApplied(
            speedProvider: SpeedProvider,
            @IntRange(from = 1) inputSampleRateHz: Int,
            @IntRange(from = 0) inputSamples: Long
        ): Long {
            Preconditions.checkArgument(speedProvider != null)
            Preconditions.checkArgument(inputSampleRateHz > 0)
            Preconditions.checkArgument(inputSamples >= 0)

            var outputSamples: Long = 0
            var positionSamples: Long = 0

            while (positionSamples < inputSamples) {
                var boundarySamples =
                    SpeedProviderUtil.getNextSpeedChangeSamplePosition(
                        speedProvider,
                        positionSamples,
                        inputSampleRateHz
                    )

                if (boundarySamples == C.INDEX_UNSET.toLong() || boundarySamples > inputSamples) {
                    boundarySamples = inputSamples
                }

                val speed = SpeedProviderUtil.getSampleAlignedSpeed(
                    speedProvider,
                    positionSamples,
                    inputSampleRateHz
                )
                // Input and output sample rates match because SpeedChangingAudioProcessor does not modify the
                // output sample rate.
                outputSamples +=
                    Sonic.getExpectedFrameCountAfterProcessorApplied( 
                        inputSampleRateHz,  
                        inputSampleRateHz,  
                        speed,  
                        speed,  
                        boundarySamples - positionSamples
                    )
                positionSamples = boundarySamples
            }

            return outputSamples
        }

        @VisibleForTesting  fun getInputFrameCountForOutput(
            speedProvider: SpeedProvider,
            @IntRange(from = 1) inputSampleRate: Int,
            @IntRange(from = 0) outputFrameCount: Long
        ): Long {
            var outputFrameCount = outputFrameCount
            Preconditions.checkArgument(inputSampleRate > 0)
            Preconditions.checkArgument(outputFrameCount >= 0)

            var inputSampleCount: Long = 0
            while (outputFrameCount > 0) {
                val boundarySamples =
                    SpeedProviderUtil.getNextSpeedChangeSamplePosition(
                        speedProvider,
                        inputSampleCount,
                        inputSampleRate
                    )
                val speed = SpeedProviderUtil.getSampleAlignedSpeed(
                    speedProvider,
                    inputSampleCount,
                    inputSampleRate
                )

                val outputSamplesForSection: Long =
                    Sonic.getExpectedFrameCountAfterProcessorApplied( 
                        inputSampleRate,  
                        inputSampleRate,  
                        speed,  
                        speed,  
                        boundarySamples - inputSampleCount
                    )

                if (boundarySamples == C.INDEX_UNSET.toLong() || outputSamplesForSection > outputFrameCount) {
                    inputSampleCount +=
                        Sonic.getExpectedInputFrameCountForOutputFrameCount( 
                            inputSampleRate,  
                            inputSampleRate,  
                            speed,  
                            speed,
                            outputFrameCount
                        )
                    outputFrameCount = 0
                } else {
                    outputFrameCount -= outputSamplesForSection
                    inputSampleCount = boundarySamples
                }
            }

            return inputSampleCount
        }

        private fun getDurationUsAfterProcessorApplied(
            speedProvider: SpeedProvider, sampleRate: Int, inputDurationUs: Long
        ): Long {
            val inputSamples: Long =
                scaleLargeValue(
                    inputDurationUs,
                    sampleRate.toLong(),
                    C.MICROS_PER_SECOND,
                    RoundingMode.HALF_EVEN
                )
            val outputSamples: Long =
                getSampleCountAfterProcessorApplied(speedProvider, sampleRate, inputSamples)
            return sampleCountToDurationUs(outputSamples, sampleRate)
        }
    }
}
