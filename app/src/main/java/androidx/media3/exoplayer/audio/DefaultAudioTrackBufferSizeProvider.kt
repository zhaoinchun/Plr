package androidx.media3.exoplayer.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Util.constrainValue
import androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackBufferSizeProvider
import androidx.media3.extractor.ExtractorUtil.getMaximumEncodedRateBytesPerSecond
import com.google.common.base.Preconditions
import com.google.common.math.IntMath
import com.google.common.primitives.Ints
import java.math.RoundingMode
import kotlin.math.max

class DefaultAudioTrackBufferSizeProvider
private constructor(builder: Builder) : AudioTrackBufferSizeProvider {
    class Builder {
        var minPcmBufferDurationUs = MIN_PCM_BUFFER_DURATION_US
        var maxPcmBufferDurationUs = MAX_PCM_BUFFER_DURATION_US
        var pcmBufferMultiplicationFactor = PCM_BUFFER_MULTIPLICATION_FACTOR
        var passthroughBufferDurationUs = PASSTHROUGH_BUFFER_DURATION_US
        var offloadBufferDurationUs = OFFLOAD_BUFFER_DURATION_US
        var ac3BufferMultiplicationFactor = AC3_BUFFER_MULTIPLICATION_FACTOR
        var dtshdBufferMultiplicationFactor = DTSHD_BUFFER_MULTIPLICATION_FACTOR
        var targetPcmBufferDurationUs = DEFAULT_PCM_BUFFER_DURATION_US

        @Deprecated(
            """This constraint does not apply to the new default fixed buffer size logic. Use
          {@link #setTargetPcmBufferDurationUs(int)} to configure a fixed buffer size instead. This
          is only used if {@link #setPcmBufferMultiplicationFactor(int)} is explicitly set to
          enable deprecated dynamic logic."""
        )
        fun setMinPcmBufferDurationUs(minPcmBufferDurationUs: Int): Builder {
            this.minPcmBufferDurationUs = minPcmBufferDurationUs
            return this
        }

        @Deprecated(
            """This constraint does not apply to the new default fixed buffer size logic. Use
          {@link #setTargetPcmBufferDurationUs(int)} to configure a fixed buffer size instead. This
          is only used if {@link #setPcmBufferMultiplicationFactor(int)} is explicitly set to
          enable deprecated dynamic logic."""
        )
        fun setMaxPcmBufferDurationUs(maxPcmBufferDurationUs: Int): Builder {
            this.maxPcmBufferDurationUs = maxPcmBufferDurationUs
            return this
        }

        @Deprecated(
            """Use the default fixed buffer size logic instead, or configure a fixed target
          using {@link #setTargetPcmBufferDurationUs(int)}. Pass {@link C#LENGTH_UNSET} to not use
          this factor."""
        )
        fun setPcmBufferMultiplicationFactor(pcmBufferMultiplicationFactor: Int): Builder {
            this.pcmBufferMultiplicationFactor = pcmBufferMultiplicationFactor
            return this
        }

        fun setTargetPcmBufferDurationUs(targetPcmBufferDurationUs: Int): Builder {
            this.targetPcmBufferDurationUs = targetPcmBufferDurationUs
            return this
        }

        fun setPassthroughBufferDurationUs(passthroughBufferDurationUs: Int): Builder {
            this.passthroughBufferDurationUs = passthroughBufferDurationUs
            return this
        }

        fun setOffloadBufferDurationUs(offloadBufferDurationUs: Int): Builder {
            this.offloadBufferDurationUs = offloadBufferDurationUs
            return this
        }

        fun setAc3BufferMultiplicationFactor(ac3BufferMultiplicationFactor: Int): Builder {
            this.ac3BufferMultiplicationFactor = ac3BufferMultiplicationFactor
            return this
        }

        fun setDtshdBufferMultiplicationFactor(dtshdBufferMultiplicationFactor: Int): Builder {
            this.dtshdBufferMultiplicationFactor = dtshdBufferMultiplicationFactor
            return this
        }

        fun build(): DefaultAudioTrackBufferSizeProvider {
            return DefaultAudioTrackBufferSizeProvider(this)
        }
    }

    @Deprecated(
        """This constraint does not apply to the new default fixed buffer size logic. Use
        {@link #targetPcmBufferDurationUs} to configure a fixed buffer size instead. This is only
        used if {@link #pcmBufferMultiplicationFactor} is set to enable deprecated dynamic logic."""
    )
    private val minPcmBufferDurationUs = builder.minPcmBufferDurationUs

    @Deprecated(
        """This constraint does not apply to the new default fixed buffer size logic. Use
        {@link #targetPcmBufferDurationUs} to configure a fixed buffer size instead. This is only
        used if {@link #pcmBufferMultiplicationFactor} is set to enable deprecated dynamic logic."""
    )
    private val maxPcmBufferDurationUs = builder.maxPcmBufferDurationUs

    @Deprecated(
        """Use the default fixed buffer size logic instead, or {@link C#LENGTH_UNSET} to not
        use this factor."""
    )
    private val pcmBufferMultiplicationFactor = builder.pcmBufferMultiplicationFactor
    private val passthroughBufferDurationUs = builder.passthroughBufferDurationUs
    private val offloadBufferDurationUs = builder.offloadBufferDurationUs
    val ac3BufferMultiplicationFactor = builder.ac3BufferMultiplicationFactor
    val dtshdBufferMultiplicationFactor = builder.dtshdBufferMultiplicationFactor
    private val targetPcmBufferDurationUs = builder.targetPcmBufferDurationUs

    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        encoding: @C.Encoding Int,
        outputMode: @DefaultAudioSink.OutputMode Int,
        pcmFrameSize: Int,
        sampleRate: Int,
        bitrate: Int,
        maxAudioTrackPlaybackSpeed: Double
    ): Int {
        var bufferSize =
            get1xBufferSizeInBytes(
                minBufferSizeInBytes, encoding, outputMode, pcmFrameSize, sampleRate, bitrate
            )
        // Maintain the buffer duration by scaling the size accordingly.
        bufferSize = (bufferSize * maxAudioTrackPlaybackSpeed).toInt()
        // Buffer size must not be lower than the AudioTrack min buffer size for this format.
        bufferSize = max(minBufferSizeInBytes, bufferSize)
        // Increase if needed to make sure the buffers contains an integer number of frames.
        return (bufferSize + pcmFrameSize - 1) / pcmFrameSize * pcmFrameSize
    }

    private fun get1xBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        encoding: Int,
        outputMode: Int,
        pcmFrameSize: Int,
        sampleRate: Int,
        bitrate: Int
    ): Int {
        when (outputMode) {
            DefaultAudioSink.OUTPUT_MODE_PCM -> return getPcmBufferSizeInBytes(
                minBufferSizeInBytes,
                sampleRate,
                pcmFrameSize
            )

            DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH -> return getPassthroughBufferSizeInBytes(
                encoding,
                bitrate
            )

            DefaultAudioSink.OUTPUT_MODE_OFFLOAD -> return getOffloadBufferSizeInBytes(
                encoding
            )

            else -> throw IllegalArgumentException()
        }
    }

    private fun getPcmBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        samplingRate: Int,
        frameSize: Int
    ): Int {
        if (pcmBufferMultiplicationFactor != C.LENGTH_UNSET) {
            val targetBufferSize = minBufferSizeInBytes * pcmBufferMultiplicationFactor
            val minAppBufferSize: Int =
                durationUsToBytes(minPcmBufferDurationUs, samplingRate, frameSize)
            val maxAppBufferSize: Int =
                durationUsToBytes(maxPcmBufferDurationUs, samplingRate, frameSize)
            return constrainValue(targetBufferSize, minAppBufferSize, maxAppBufferSize)
        } else {
            return durationUsToBytes(targetPcmBufferDurationUs, samplingRate, frameSize)
        }
    }

    private fun getPassthroughBufferSizeInBytes(encoding: @C.Encoding Int, bitrate: Int): Int {
        var bufferSizeUs = passthroughBufferDurationUs
        if (encoding == C.ENCODING_AC3) {
            bufferSizeUs *= ac3BufferMultiplicationFactor
        } else if (encoding == C.ENCODING_DTS_HD) {
            // DTS-HD (DTS Express) for streaming uses a frame size (number of audio samples per channel
            // per frame) of 4096. This requires a higher multiple for the buffersize computation.
            // Otherwise, there will be buffer underflow during DASH playback.
            bufferSizeUs *= dtshdBufferMultiplicationFactor
        }

        val byteRate =
            if (bitrate != Format.NO_VALUE)
                IntMath.divide(bitrate, 8, RoundingMode.CEILING)
            else
                getNonPcmMaximumEncodedRateBytesPerSecond(encoding)
        return Ints.checkedCast(bufferSizeUs.toLong() * byteRate / C.MICROS_PER_SECOND)
    }

    private fun getOffloadBufferSizeInBytes(encoding: @C.Encoding Int): Int {
        val maxByteRate: Int = getNonPcmMaximumEncodedRateBytesPerSecond(encoding)
        return Ints.checkedCast(offloadBufferDurationUs.toLong() * maxByteRate / C.MICROS_PER_SECOND)
    }

    companion object {
        private const val MIN_PCM_BUFFER_DURATION_US = 250000
        private const val DEFAULT_PCM_BUFFER_DURATION_US = 500000
        private const val MAX_PCM_BUFFER_DURATION_US = 750000
        private const val PCM_BUFFER_MULTIPLICATION_FACTOR = C.LENGTH_UNSET
        private const val PASSTHROUGH_BUFFER_DURATION_US = 250000
        private const val OFFLOAD_BUFFER_DURATION_US = 50000000
        private const val AC3_BUFFER_MULTIPLICATION_FACTOR = 2
        private const val DTSHD_BUFFER_MULTIPLICATION_FACTOR = 4

        private fun durationUsToBytes(durationUs: Int, samplingRate: Int, frameSize: Int): Int {
            return Ints.checkedCast(durationUs.toLong() * samplingRate * frameSize / C.MICROS_PER_SECOND)
        }

        private fun getNonPcmMaximumEncodedRateBytesPerSecond(encoding: @C.Encoding Int): Int {
            val rate = getMaximumEncodedRateBytesPerSecond(encoding)
            Preconditions.checkState(rate != C.RATE_UNSET_INT)
            return rate
        }
    }
}
