package androidx.media3.exoplayer.mediacodec

import android.media.MediaCodec
import android.os.Bundle
import androidx.media3.decoder.CryptoInfo

internal class SynchronousMediaCodecBufferEnqueuer(private val codec: MediaCodec) :
    MediaCodecBufferEnqueuer {
    override fun start() {
        // Do nothing.
    }

    override fun queueInputBuffer(
        index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int
    ) {
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    }

    override fun queueSecureInputBuffer(
        index: Int, offset: Int, info: CryptoInfo, presentationTimeUs: Long, flags: Int
    ) {
        codec.queueSecureInputBuffer(
            index, offset, info.frameworkCryptoInfo, presentationTimeUs, flags
        )
    }

    override fun setParameters(parameters: Bundle) {
        codec.setParameters(parameters)
    }

    override fun flush() {
        // Do nothing.
    }

    override fun shutdown() {
        // Do nothing.
    }

    override fun waitUntilQueueingComplete() {
        // Do nothing.
    }

    override fun maybeThrowException() {
        // Do nothing.
    }
}
