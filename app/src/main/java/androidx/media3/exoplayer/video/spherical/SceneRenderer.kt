package androidx.media3.exoplayer.video.spherical

import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.opengl.GLES20
import android.opengl.Matrix
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.GlUtil.GlException
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.TimedValueQueue
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile

internal class SceneRenderer : VideoFrameMetadataListener, CameraMotionListener {
    private val frameAvailable = AtomicBoolean()
    private val resetRotationAtNextFrame = AtomicBoolean(true)
    private val projectionRenderer = ProjectionRenderer()
    private val frameRotationQueue = FrameRotationQueue()
    private val sampleTimestampQueue = TimedValueQueue<Long>()
    private val projectionQueue = TimedValueQueue<Projection>()
    private val rotationMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    // Used by GL thread only
    private var textureId = 0
    private var surfaceTexture:SurfaceTexture? = null

    // Used by other threads only
    @Volatile
    private var defaultStereoMode: @C.StereoMode Int = C.STEREO_MODE_MONO
    private var lastStereoMode: @C.StereoMode Int = Format.NO_VALUE
    private var lastProjectionData: ByteArray? = null

    fun setDefaultStereoMode(stereoMode: @C.StereoMode Int) {
        defaultStereoMode = stereoMode
    }

    fun init(): SurfaceTexture {
        try {
            // Set the background frame color. This is only visible if the display mesh isn't a full
            // sphere.
            GLES20.glClearColor(0.5f, 0.5f, 0.5f, 1.0f)
            GlUtil.checkGlError()

            projectionRenderer.init()
            GlUtil.checkGlError()

            textureId = GlUtil.createExternalTexture()
        } catch (e: GlException) {
            e(TAG, "Failed to initialize the renderer", e)
        }
        surfaceTexture = SurfaceTexture(textureId).also {
            it.setOnFrameAvailableListener { surfaceTexture: SurfaceTexture? ->
                frameAvailable.set(true)
            }
        }
        return surfaceTexture!!
    }

    fun drawFrame(viewProjectionMatrix: FloatArray, rightEye: Boolean) {
        // glClear isn't strictly necessary when rendering fully spherical panoramas, but it can improve
        // performance on tiled renderers by causing the GPU to discard previous data.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        try {
            GlUtil.checkGlError()
        } catch (e: GlException) {
            e(TAG, "Failed to draw a frame", e)
        }

        if (frameAvailable.compareAndSet(true, false)) {
            surfaceTexture!!.updateTexImage()
            try {
                GlUtil.checkGlError()
            } catch (e: GlException) {
                e(TAG, "Failed to draw a frame", e)
            }
            if (resetRotationAtNextFrame.compareAndSet(true, false)) {
                GlUtil.setToIdentity(rotationMatrix)
            }
            val lastFrameTimestampNs = surfaceTexture!!.timestamp
            val sampleTimestampUs = sampleTimestampQueue.poll(lastFrameTimestampNs)
            if (sampleTimestampUs != null) {
                frameRotationQueue.pollRotationMatrix(rotationMatrix, sampleTimestampUs)
            }
            val projection = projectionQueue.pollFloor(lastFrameTimestampNs)
            if (projection != null) {
                projectionRenderer.setProjection(projection)
            }
        }
        Matrix.multiplyMM(tempMatrix, 0, viewProjectionMatrix, 0, rotationMatrix, 0)
        projectionRenderer.draw(textureId, tempMatrix, rightEye)
    }

    fun shutdown() {
        projectionRenderer.shutdown()
    }

    // Methods called on playback thread.
    // VideoFrameMetadataListener implementation.
    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?
    ) {
        sampleTimestampQueue.add(releaseTimeNs, presentationTimeUs)
        setProjection(format.projectionData, format.stereoMode, releaseTimeNs)
    }

    // CameraMotionListener implementation.
    override fun onCameraMotion(timeUs: Long, rotation: FloatArray) {
        frameRotationQueue.setRotation(timeUs, rotation)
    }

    override fun onCameraMotionReset() {
        sampleTimestampQueue.clear()
        frameRotationQueue.reset()
        resetRotationAtNextFrame.set(true)
    }

    private fun setProjection(
        projectionData: ByteArray?, stereoMode: @C.StereoMode Int, timeNs: Long
    ) {
        val oldProjectionData = lastProjectionData
        val oldStereoMode = lastStereoMode
        lastProjectionData = projectionData
        lastStereoMode = if (stereoMode == Format.NO_VALUE) defaultStereoMode else stereoMode
        if (oldStereoMode == lastStereoMode && oldProjectionData.contentEquals(lastProjectionData)) {
            return
        }

        var projectionFromData: Projection? = null
        if (lastProjectionData != null) {
            projectionFromData = ProjectionDecoder.decode(lastProjectionData!!, lastStereoMode)
        }
        val projection =
            if (projectionFromData != null && ProjectionRenderer.isSupported(
                    projectionFromData
                )
            )
                projectionFromData
            else
                Projection.createEquirectangular(lastStereoMode)
        projectionQueue.add(timeNs, projection)
    }

    companion object {
        private const val TAG = "SceneRenderer"
    }
}
