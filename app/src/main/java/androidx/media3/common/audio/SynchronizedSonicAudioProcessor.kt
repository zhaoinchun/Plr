package androidx.media3.common.audio

import java.nio.ByteBuffer

internal class SynchronizedSonicAudioProcessor(
    private val lock: Any,
    keepActiveWithDefaultParameters: Boolean
) : AudioProcessor {
    private val sonicAudioProcessor: SonicAudioProcessor

    init {
        sonicAudioProcessor = SonicAudioProcessor(keepActiveWithDefaultParameters)
    }

    fun setSpeed(speed: Float) {
        synchronized(lock) {
            sonicAudioProcessor.setSpeed(speed)
        }
    }

    fun setPitch(pitch: Float) {
        synchronized(lock) {
            sonicAudioProcessor.setPitch(pitch)
        }
    }

    fun setOutputSampleRateHz(sampleRateHz: Int) {
        synchronized(lock) {
            sonicAudioProcessor.setOutputSampleRateHz(sampleRateHz)
        }
    }

    fun getMediaDuration(playoutDuration: Long): Long {
        synchronized(lock) {
            return sonicAudioProcessor.getMediaDuration(playoutDuration)
        }
    }

    fun getPlayoutDuration(mediaDuration: Long): Long {
        synchronized(lock) {
            return sonicAudioProcessor.getPlayoutDuration(mediaDuration)
        }
    }

    val processedInputBytes: Long
        get() {
            synchronized(lock) {
                return sonicAudioProcessor.processedInputBytes
            }
        }

    override fun getDurationAfterProcessorApplied(durationUs: Long): Long {
        return getPlayoutDuration(durationUs)
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat? {
        synchronized(lock) {
            return sonicAudioProcessor.configure(inputAudioFormat)
        }
    }

    override val isActive: Boolean
        get() {
            synchronized(lock) {
                return sonicAudioProcessor.isActive
            }
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        synchronized(lock) {
            sonicAudioProcessor.queueInput(inputBuffer)
        }
    }

    override fun queueEndOfStream() {
        synchronized(lock) {
            sonicAudioProcessor.queueEndOfStream()
        }
    }

    override val output: ByteBuffer?
        get() {
            synchronized(lock) {
                return sonicAudioProcessor.output
            }
        }

    override val isEnded: Boolean
        get() {
            synchronized(lock) {
                return sonicAudioProcessor.isEnded
            }
        }

    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
        synchronized(lock) {
            sonicAudioProcessor.flush(streamMetadata)
        }
    }

    override fun reset() {
        synchronized(lock) {
            sonicAudioProcessor.reset()
        }
    }
}
