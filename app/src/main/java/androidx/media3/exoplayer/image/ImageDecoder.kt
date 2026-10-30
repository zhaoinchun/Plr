package androidx.media3.exoplayer.image

import androidx.media3.common.Format

import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.RendererCapabilities

interface ImageDecoder : Decoder<DecoderInputBuffer, ImageOutputBuffer, ImageDecoderException> {
    interface Factory {
        fun supportsFormat(format: Format): @RendererCapabilities.Capabilities Int
        fun createImageDecoder(): ImageDecoder

        companion object {
            @Deprecated("Instantiate a {@link BitmapFactoryImageDecoder.Factory} directly.")
            val DEFAULT: Factory = BitmapFactoryImageDecoder.Factory()
        }
    }

    @Throws(ImageDecoderException::class)
    override fun queueInputBuffer(inputBuffer: DecoderInputBuffer)

    @Throws(ImageDecoderException::class)
    override fun dequeueOutputBuffer(): ImageOutputBuffer?
}
