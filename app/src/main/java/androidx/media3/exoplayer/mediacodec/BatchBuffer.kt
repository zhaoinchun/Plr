package androidx.media3.exoplayer.mediacodec

import androidx.annotation.IntRange
import androidx.media3.common.C
import androidx.media3.decoder.DecoderInputBuffer
import com.google.common.base.Preconditions

internal class BatchBuffer : DecoderInputBuffer(BUFFER_REPLACEMENT_MODE_DIRECT) {
    var lastSampleTimeUs: Long = 0
        private set
    var sampleCount: Int = 0
        private set
    private var maxSampleCount= DEFAULT_MAX_SAMPLE_COUNT

    override fun clear() {
        super.clear()
        sampleCount = 0
    }

    fun setMaxSampleCount(@IntRange(from = 1) maxSampleCount: Int) {
        Preconditions.checkArgument(maxSampleCount > 0)
        this.maxSampleCount = maxSampleCount
    }

    val firstSampleTimeUs: Long
        get() = timeUs

    fun hasSamples(): Boolean {
        return sampleCount > 0
    }

    fun append(buffer: DecoderInputBuffer): Boolean {
        Preconditions.checkArgument(!buffer.isEncrypted)
        Preconditions.checkArgument(!buffer.hasSupplementalData())
        Preconditions.checkArgument(!buffer.isEndOfStream)
        if (!canAppendSampleBuffer(buffer)) {
            return false
        }
        if (sampleCount++ == 0) {
            timeUs = buffer.timeUs
            if (buffer.isKeyFrame) {
                setFlags(C.BUFFER_FLAG_KEY_FRAME)
            }
        }
        val bufferData = buffer.data
        if (bufferData != null) {
            ensureSpaceForWrite(bufferData.remaining())
            data!!.put(bufferData)
        }
        lastSampleTimeUs = buffer.timeUs
        return true
    }

    private fun canAppendSampleBuffer(buffer: DecoderInputBuffer): Boolean {
        if (!hasSamples()) {
            // Always allow appending when the buffer is empty, else no progress can be made.
            return true
        }
        if (sampleCount >= maxSampleCount) {
            return false
        }
        val bufferData = buffer.data
        return !(bufferData != null && data != null && data!!.position() + bufferData.remaining() > MAX_SIZE_BYTES)
    }

    companion object {
        const val DEFAULT_MAX_SAMPLE_COUNT: Int = 32
        const val MAX_SIZE_BYTES: Int = 3 * 1000 * 1024
    }
}
