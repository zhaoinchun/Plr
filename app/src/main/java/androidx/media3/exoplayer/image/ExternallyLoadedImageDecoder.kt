package androidx.media3.exoplayer.image

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.isImage
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import com.google.common.base.Preconditions
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import androidx.core.net.toUri

class ExternallyLoadedImageDecoder private constructor(private val bitmapResolver: BitmapResolver) :
    ImageDecoder {
    class ExternalImageRequest(val uri: Uri?)

    interface BitmapResolver {
        fun resolve(request: ExternalImageRequest?): ListenableFuture<Bitmap?>?
    }

    class Factory
        (private val bitmapResolver: BitmapResolver) : ImageDecoder.Factory {
        override fun supportsFormat(format: Format): @RendererCapabilities.Capabilities Int {
            val isExternallyLoadedImage =
                format.sampleMimeType == MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE
            return RendererCapabilities.create(
                if (isExternallyLoadedImage)
                    C.FORMAT_HANDLED
                else
                    if (isImage(format.sampleMimeType))
                        C.FORMAT_UNSUPPORTED_SUBTYPE
                    else
                        C.FORMAT_UNSUPPORTED_TYPE
            )
        }

        override fun createImageDecoder(): ExternallyLoadedImageDecoder {
            return ExternallyLoadedImageDecoder(bitmapResolver)
        }
    }

    private val inputBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private val outputBuffer = object : ImageOutputBuffer() {
        override fun release() {
            clear()
        }
    }

    private var pendingDecode: ListenableFuture<Bitmap?>? = null
    private var pendingDecodeTimeUs: Long = 0
    private var pendingEndOfStream = false

    override val name: String
        get() = "externallyLoadedImageDecoder"

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        // Intentionally unused to render images that start earlier than the intended start time.
    }

    override fun dequeueInputBuffer(): DecoderInputBuffer? {
        return if (pendingDecode == null) inputBuffer else null
    }

    override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
        if (inputBuffer.isEndOfStream) {
            pendingEndOfStream = true
            inputBuffer.clear()
            return
        }
        val inputData = inputBuffer.data!!
        Preconditions.checkState(inputData.hasArray())
        val imageUri = String(
            inputData.array(),
            inputData.arrayOffset(),
            inputData.remaining(),
            StandardCharsets.UTF_8
        ).toUri()
        pendingDecode = bitmapResolver.resolve(ExternalImageRequest(imageUri))
        pendingDecodeTimeUs = inputBuffer.timeUs
        inputBuffer.clear()
    }

    @Throws(ImageDecoderException::class)
    override fun dequeueOutputBuffer(): ImageOutputBuffer? {
        if (pendingEndOfStream) {
            outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            pendingEndOfStream = false
            return outputBuffer
        }
        val pendingDecode = pendingDecode
        if (pendingDecode == null || !pendingDecode.isDone) {
            return null
        }
        try {
            outputBuffer.bitmap = Futures.getDone(pendingDecode)
            outputBuffer.timeUs = pendingDecodeTimeUs
            return outputBuffer
        } catch (e: ExecutionException) {
            throw ImageDecoderException(e.cause)
        } catch (e: CancellationException) {
            throw ImageDecoderException(e)
        } finally {
            this.pendingDecode = null
        }
    }

    override fun flush() {
        resetState()
    }

    override fun release() {
        resetState()
    }

    private fun resetState() {
        if (pendingDecode != null) {
            pendingDecode!!.cancel(false)
            pendingDecode = null
        }
        pendingEndOfStream = false
        inputBuffer.clear()
        outputBuffer.release()
    }
}
