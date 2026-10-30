package androidx.media3.common.audio

import androidx.media3.common.C
import androidx.media3.common.C.PcmEncoding
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util
import com.google.common.base.Preconditions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Objects

interface AudioProcessor {
    
    class AudioFormat(
        val sampleRate: Int,
        val channelCount: Int,
        val encoding: @PcmEncoding Int
    ) {
        val bytesPerFrame: Int
        constructor(format: Format) : this(
            format.sampleRate,
            format.channelCount,
            format.pcmEncoding
        )

        init {
            bytesPerFrame =
                if (Util.isEncodingLinearPcm(encoding))
                    Util.getPcmFrameSize(encoding, channelCount)
                else
                    Format.NO_VALUE
        }

        override fun toString(): String {
            return ("AudioFormat["
                    + "sampleRate="
                    + sampleRate
                    + ", channelCount="
                    + channelCount
                    + ", encoding="
                    + encoding
                    + ']')
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is AudioFormat) {
                return false
            }
            val that = o
            return sampleRate == that.sampleRate && channelCount == that.channelCount && encoding == that.encoding
        }

        override fun hashCode(): Int {
            return Objects.hash(sampleRate, channelCount, encoding)
        }

        companion object {
            val NOT_SET: AudioFormat = AudioFormat( 
                Format.NO_VALUE,  
                Format.NO_VALUE,  
                Format.NO_VALUE
            )
        }
    }

    class UnhandledAudioFormatException(message: String?, val inputAudioFormat: AudioFormat?) :
        Exception(message + " " + inputAudioFormat) {
        constructor(inputAudioFormat: AudioFormat?) : this(
            "Unhandled input format:",
            inputAudioFormat
        )
    }

    class StreamMetadata private constructor(builder: Builder) {
        class Builder {
            internal var positionOffsetUs: Long
            internal var timeline: Timeline
            internal var periodUid: Any?

            constructor() {
                positionOffsetUs = 0
                timeline = Timeline.EMPTY
                periodUid = null
            }

            internal constructor(streamMetadata: StreamMetadata) {
                positionOffsetUs = streamMetadata.positionOffsetUs
                timeline = streamMetadata.timeline
                periodUid = streamMetadata.periodUid
            }

            fun setPositionOffsetUs(positionOffsetUs: Long): Builder {
                this.positionOffsetUs = positionOffsetUs
                return this
            }

            fun setTimeline(timeline: Timeline): Builder {
                this.timeline = timeline
                return this
            }

            fun setPeriodUid(periodUid: Any?): Builder {
                this.periodUid = periodUid
                return this
            }

            fun build(): StreamMetadata {
                val periodUid = periodUid
                if (!timeline.isEmpty && periodUid != null) {
                    Preconditions.checkArgument(timeline.getIndexOfPeriod(periodUid) != C.INDEX_UNSET)
                }
                return StreamMetadata(this)
            }
        }

        val positionOffsetUs: Long
        val timeline: Timeline
        val periodUid: Any?

        @Deprecated("Use {@link Builder} instead.")
        constructor(positionOffsetUs: Long) : this(Builder().setPositionOffsetUs(positionOffsetUs))

        init {
            positionOffsetUs = builder.positionOffsetUs
            timeline = builder.timeline
            periodUid = builder.periodUid
        }

        fun buildUpon(): Builder {
            return StreamMetadata.Builder(this)
        }

        companion object {
            val DEFAULT: StreamMetadata = Builder().build()
        }
    }

    fun getDurationAfterProcessorApplied(durationUs: Long): Long {
        return durationUs
    }

    @Throws(UnhandledAudioFormatException::class)
    fun configure(inputAudioFormat: AudioFormat): AudioFormat?

    val isActive: Boolean

    fun queueInput(inputBuffer: ByteBuffer)

    fun queueEndOfStream()
    val output: ByteBuffer?
    val isEnded: Boolean

    @Deprecated("Use {@link #flush(StreamMetadata)} instead.")
    fun flush() {
        throw IllegalStateException(
            "AudioProcessor must implement at least one #flush() overload."
        )
    }

    fun flush(streamMetadata: StreamMetadata) {
        flush()
    }

    fun reset()

    companion object {
        val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}