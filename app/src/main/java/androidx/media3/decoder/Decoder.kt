package androidx.media3.decoder

import java.util.concurrent.Executor

interface Decoder<I, O, E : DecoderException> {
    val name: String

    fun setOutputStartTimeUs(outputStartTimeUs: Long)

    
    fun dequeueInputBuffer(): I?

    
    fun queueInputBuffer(inputBuffer: I)

    
    fun dequeueOutputBuffer(): O?

    fun flush()

    fun release()

    interface Callback {
        fun onInputBufferAvailable()

        fun onOutputBufferAvailable()
    }

    fun setCallback(callback: Callback?, executor: Executor?) {
        // Do nothing by default.
    }
}
