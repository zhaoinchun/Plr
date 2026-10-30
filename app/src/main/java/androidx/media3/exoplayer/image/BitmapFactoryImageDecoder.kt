package androidx.media3.exoplayer.image

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes.isImage
import androidx.media3.common.ParserException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Util.getCurrentDisplayModeSize
import androidx.media3.common.util.Util.isBitmapFactorySupportedMimeType
import androidx.media3.datasource.BitmapUtil.decode
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.exoplayer.RendererCapabilities
import com.google.common.base.Preconditions
import java.io.IOException
import kotlin.math.max

class BitmapFactoryImageDecoder
private constructor(private val context: Context?, private val maxOutputSize: Int) :
    SimpleDecoder<DecoderInputBuffer, ImageOutputBuffer, ImageDecoderException>(
        1, 1
    ), ImageDecoder {
    
    class Factory : ImageDecoder.Factory {
        private val context: Context?
        private var maxOutputSize: Int

        @Deprecated("Use {@link Factory#Factory(Context)} instead.")
        constructor() {
            this.context = null
            this.maxOutputSize = C.LENGTH_UNSET
        }

        constructor(context: Context?) {
            this.context = context!!
            this.maxOutputSize = C.LENGTH_UNSET
        }

        fun setMaxOutputSize(maxOutputSize: Int): Factory {
            Preconditions.checkArgument(maxOutputSize == C.LENGTH_UNSET || maxOutputSize > 0)
            this.maxOutputSize = maxOutputSize
            return this
        }

        override fun supportsFormat(format: Format): @RendererCapabilities.Capabilities Int {
            if (format.sampleMimeType == null || !isImage(format.sampleMimeType)) {
                return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
            }
            return if (isBitmapFactorySupportedMimeType(format.sampleMimeType))
                RendererCapabilities.create(C.FORMAT_HANDLED)
            else
                RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }

        override fun createImageDecoder(): BitmapFactoryImageDecoder {
            return BitmapFactoryImageDecoder(context, maxOutputSize)
        }
    }

    override val name: String
        get() = "BitmapFactoryImageDecoder"

    override fun createInputBuffer(): DecoderInputBuffer {
        return DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    }

    override fun createOutputBuffer(): ImageOutputBuffer {
        return object : ImageOutputBuffer() {
            override fun release() {
                this@BitmapFactoryImageDecoder.releaseOutputBuffer(this)
            }
        }
    }

    override fun createUnexpectedDecodeException(error: Throwable?): ImageDecoderException {
        return ImageDecoderException("Unexpected decode error", error)
    }

    override fun decode(
        inputBuffer: DecoderInputBuffer, outputBuffer: ImageOutputBuffer, reset: Boolean
    ): ImageDecoderException? {
        val inputData = inputBuffer.data!!
        Preconditions.checkState(inputData.hasArray())
        Preconditions.checkArgument(inputData.arrayOffset() == 0)
        try {
            val maxSize: Int
            if (this.maxOutputSize != C.LENGTH_UNSET) {
                maxSize = maxOutputSize
            } else if (context != null) {
                val currentDisplayModeSize = getCurrentDisplayModeSize(context)
                var maxWidth = currentDisplayModeSize.x
                var maxHeight = currentDisplayModeSize.y
                if (inputBuffer.format != null) {
                    if (inputBuffer.format!!.tileCountHorizontal != Format.NO_VALUE) {
                        maxWidth *= inputBuffer.format!!.tileCountHorizontal
                    }
                    if (inputBuffer.format!!.tileCountVertical != Format.NO_VALUE) {
                        maxHeight *= inputBuffer.format!!.tileCountVertical
                    }
                }
                // BitmapUtil.decode can only downscale in powers of 2, so nearly doubling the max size
                // ensures that an image is never downscaled to be smaller than the max size.
                maxSize = max(maxWidth, maxHeight) * 2 - 1
            } else {
                // If we can't get the display size, fallback to a sensible default.
                maxSize = GlUtil.MAX_BITMAP_DECODING_SIZE
            }

            outputBuffer.bitmap =
                decode(
                    inputData.array(),
                    inputData.remaining(),
                    null,  
                    maxSize
                )
        } catch (e: ParserException) {
            return ImageDecoderException("Could not decode image data with BitmapFactory.", e)
        } catch (e: IOException) {
            return ImageDecoderException(e)
        }
        outputBuffer.timeUs = inputBuffer.timeUs
        return null
    }
}
