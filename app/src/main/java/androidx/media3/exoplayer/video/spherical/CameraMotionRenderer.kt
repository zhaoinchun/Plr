package androidx.media3.exoplayer.video.spherical

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult
import java.lang.Float
import java.nio.ByteBuffer
import kotlin.Any
import kotlin.Boolean
import kotlin.FloatArray
import kotlin.Int
import kotlin.Long
import kotlin.Throws

class CameraMotionRenderer : BaseRenderer(C.TRACK_TYPE_CAMERA_MOTION) {
    private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private val scratch = ParsableByteArray()

    private var listener: CameraMotionListener? = null
    private var lastTimestampUs: Long = 0
    override val name = TAG

    override fun supportsFormat(format: Format): @RendererCapabilities.Capabilities Int {
        return if (MimeTypes.APPLICATION_CAMERA_MOTION == format.sampleMimeType)
            RendererCapabilities.create(C.FORMAT_HANDLED)
        else
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
    }

    @Throws(ExoPlaybackException::class)
    override fun handleMessage(messageType: @Renderer.MessageType Int, message: Any?) {
        if (messageType == Renderer.MSG_SET_CAMERA_MOTION_LISTENER) {
            listener = message as CameraMotionListener?
        } else {
            super.handleMessage(messageType, message)
        }
    }

    override fun onPositionReset(
        positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean
    ) {
        lastTimestampUs = Long.MIN_VALUE
        resetListener()
    }

    override fun onDisabled() {
        resetListener()
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        // Keep reading available samples as long as the sample time is not too far into the future.
        while (!hasReadStreamToEnd() && lastTimestampUs < positionUs + SAMPLE_WINDOW_DURATION_US) {
            buffer.clear()
            val formatHolder = getFormatHolder()
            val result: @ReadDataResult Int = readSource(formatHolder, buffer,  0)
            if (result != C.RESULT_BUFFER_READ || buffer.isEndOfStream) {
                return
            }

            lastTimestampUs = buffer.timeUs
            val isDecodeOnly = lastTimestampUs < lastResetPositionUs
            if (listener == null || isDecodeOnly) {
                continue
            }

            buffer.flip()
            val rotation = parseMetadata(buffer.data!!)
            if (rotation == null) {
                continue
            }

            listener!!.onCameraMotion(lastTimestampUs - streamOffsetUs, rotation)
        }
    }

    override val isEnded: Boolean
        get() = hasReadStreamToEnd()

    override val isReady: Boolean
        get() = true

    private fun parseMetadata(data: ByteBuffer): FloatArray? {
        if (data.remaining() != 16) {
            return null
        }
        scratch.reset(data.array(), data.limit())
        scratch.setPosition(data.arrayOffset() + 4) // skip reserved bytes too.
        val result = FloatArray(3)
        for (i in 0..2) {
            result[i] = Float.intBitsToFloat(scratch.readLittleEndianInt())
        }
        return result
    }

    private fun resetListener() {
        listener?.onCameraMotionReset()
    }

    companion object {
        private const val TAG = "CameraMotionRenderer"

        // The amount of time to read samples ahead of the current time.
        private const val SAMPLE_WINDOW_DURATION_US = 100000
    }
}
