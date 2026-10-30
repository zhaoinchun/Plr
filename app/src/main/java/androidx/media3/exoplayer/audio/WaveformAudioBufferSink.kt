package androidx.media3.exoplayer.audio

import android.util.SparseArray
import androidx.annotation.FloatRange
import androidx.media3.common.C
import androidx.media3.common.audio.AudioMixingUtil
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix

import androidx.media3.common.util.Util.getPcmFrameSize
import androidx.media3.exoplayer.audio.TeeAudioProcessor.AudioBufferSink
import com.google.common.base.Preconditions

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import androidx.core.util.size

class WaveformAudioBufferSink(
    private val barsPerSecond: Int,
    outputChannelCount: Int,
    private val listener: Listener
) : AudioBufferSink {
    
    class WaveformBar {
        private var minSampleValue = 1f
        private var maxSampleValue = -1f
        private var squareSum = 0.0
        var sampleCount: Int = 0
            private set

        fun getMinSampleValue(): Double {
            return minSampleValue.toDouble()
        }

        fun getMaxSampleValue(): Double {
            return maxSampleValue.toDouble()
        }

        val rootMeanSquare: Double
            get() = sqrt(squareSum / sampleCount)

        fun addSample(@FloatRange(from = -1.0, to = 1.0) sample: Float) {
            Preconditions.checkArgument(sample >= -1f && sample <= 1f)
            minSampleValue = min(minSampleValue, sample)
            maxSampleValue = max(maxSampleValue, sample)
            squareSum += sample.toDouble() * sample
            sampleCount++
        }
    }

    interface Listener {
        fun onNewWaveformBar(channelIndex: Int, waveformBar: WaveformBar?)
    }

    private val outputChannels = SparseArray<WaveformBar>(outputChannelCount).apply {
        for (i in 0..<outputChannelCount) {
            append(i, WaveformBar())
        }
    }
    private val mixingBuffer =
        ByteBuffer.allocate(getPcmFrameSize(C.ENCODING_PCM_FLOAT, outputChannelCount))
    private var inputAudioFormat:AudioProcessor.AudioFormat? = null
    private var mixingAudioFormat:AudioProcessor.AudioFormat? = null
    private var channelMixingMatrix:ChannelMixingMatrix? = null
    private var samplesPerBar = 0

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: @C.PcmEncoding Int) {
        samplesPerBar = sampleRateHz / barsPerSecond
        inputAudioFormat = AudioProcessor.AudioFormat(sampleRateHz, channelCount, encoding)
        mixingAudioFormat =
            AudioProcessor.AudioFormat(sampleRateHz, outputChannels.size, C.ENCODING_PCM_FLOAT)
        channelMixingMatrix =
            ChannelMixingMatrix.createForConstantGain(channelCount, outputChannels.size)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            mixingBuffer.rewind()
            AudioMixingUtil.mix(
                buffer,
                inputAudioFormat!!,
                mixingBuffer,
                mixingAudioFormat!!,
                channelMixingMatrix!!,  
                1,  
                false,  
                true
            )
            mixingBuffer.rewind()
            for (i in 0..<outputChannels.size) {
                val bar = outputChannels[i]
                bar.addSample(mixingBuffer.getFloat())
                if (bar.sampleCount >= samplesPerBar) {
                    listener.onNewWaveformBar(i, bar)
                    outputChannels.set(i, WaveformBar())
                }
            }
        }
    }
}
