package androidx.media3.exoplayer.mediacodec

import android.os.Bundle
import androidx.media3.decoder.CryptoInfo

internal interface MediaCodecBufferEnqueuer {
    fun start()
    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)
    fun queueSecureInputBuffer(
        index: Int, offset: Int, info: CryptoInfo, presentationTimeUs: Long, flags: Int
    )
    fun setParameters(parameters: Bundle)
    fun flush()
    fun shutdown()

    @Throws(InterruptedException::class)
    fun waitUntilQueueingComplete()
    fun maybeThrowException()
}
