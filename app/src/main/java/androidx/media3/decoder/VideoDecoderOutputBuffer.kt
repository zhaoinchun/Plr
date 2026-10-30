package androidx.media3.decoder

import androidx.media3.common.C
import androidx.media3.common.C.VideoOutputMode
import androidx.media3.common.Format

import java.nio.ByteBuffer

class VideoDecoderOutputBuffer
(private val owner: Owner<VideoDecoderOutputBuffer>) : DecoderOutputBuffer() {
    // LINT.ThenChange(
    //     ../../../../../../../decoder_av1/src/main/jni/dav1d_jni.cc,
    //     ../../../../../../../decoder_vp9/src/main/jni/vpx_jni.cc
    // )
    var decoderPrivate: Long = 0
    var mode: @VideoOutputMode Int = 0
    var data: ByteBuffer? = null

    var width: Int = 0
    var height: Int = 0

    var format: Format? = null

    var yuvPlanes: Array<ByteBuffer?>? = null

    var yuvStrides: IntArray? = null
    var colorspace: Int = 0
    var yStride: Int = 0
    var uvStride: Int = 0

    var supplementalData: ByteBuffer? = null

    override fun release() {
        owner.releaseOutputBuffer(this)
    }

    fun init(
        timeUs: Long, mode: @VideoOutputMode Int, supplementalData: ByteBuffer?
    ) {
        this.timeUs = timeUs
        this.mode = mode
        if (supplementalData != null && supplementalData.hasRemaining()) {
            addFlag(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
            val size = supplementalData.limit()
            if (this.supplementalData == null || this.supplementalData!!.capacity() < size) {
                this.supplementalData = ByteBuffer.allocate(size)
            } else {
                this.supplementalData!!.clear()
            }
            this.supplementalData!!.put(supplementalData)
            this.supplementalData!!.flip()
            supplementalData.position(0)
        } else {
            this.supplementalData = null
        }
    }

    fun initForYuvFrame(
        width: Int,
        height: Int,
        yStride: Int,
        uvStride: Int,
        colorspace: Int
    ): Boolean {
        this.width = width
        this.height = height
        this.colorspace = colorspace
        this.yStride = yStride
        this.uvStride = uvStride
        val uvHeight = ((height.toLong() + 1) / 2).toInt()
        if (!isSafeToMultiply(yStride, height) || !isSafeToMultiply(uvStride, uvHeight)) {
            return false
        }
        val yLength = yStride * height
        val uvLength = uvStride * uvHeight
        val minimumYuvSize = yLength + (uvLength * 2)
        if (!isSafeToMultiply(uvLength, 2) || minimumYuvSize < yLength) {
            return false
        }

        // Initialize data.
        if (data == null || data!!.capacity() < minimumYuvSize) {
            data = ByteBuffer.allocateDirect(minimumYuvSize)
        } else {
            data!!.position(0)
            data!!.limit(minimumYuvSize)
        }

        if (yuvPlanes == null) {
            yuvPlanes = Array<ByteBuffer?>(3){null}
        }

        val data = this.data
        val yuvPlanes = this.yuvPlanes

        // Rewrapping has to be done on every frame since the stride might have changed.
        yuvPlanes!![0] = data!!.slice()
        yuvPlanes[0]!!.limit(yLength)
        data.position(yLength)
        yuvPlanes[1] = data.slice()
        yuvPlanes[1]!!.limit(uvLength)
        data.position(yLength + uvLength)
        yuvPlanes[2] = data.slice()
        yuvPlanes[2]!!.limit(uvLength)
        if (yuvStrides == null) {
            yuvStrides = IntArray(3)
        }
        yuvStrides!![0] = yStride
        yuvStrides!![1] = uvStride
        yuvStrides!![2] = uvStride
        return true
    }

    fun initForOffsetFrames(
        offset: Int,
        width: Int,
        height: Int,
        yStride: Int,
        uvStride: Int,
        colorspace: Int,
        alignedHeight: Int
    ): Boolean {
        if (yuvPlanes == null) {
            yuvPlanes = arrayOfNulls<ByteBuffer>(3)
        }

        // Data should be allocated in the native code.
        if (data == null) {
            return false
        }

        this.width = width
        this.height = height
        this.colorspace = colorspace
        val yuvPlanes = this.yuvPlanes
        val data = this.data

        val yLength = yStride * height
        val uvLength = uvStride * (height shr 1)
        val alignedYLength = yStride * alignedHeight
        val alignedUvLength = uvStride * (alignedHeight shr 1)
        data!!.position(offset)
        yuvPlanes!![0] = data.slice()
        yuvPlanes[0]!!.limit(yLength)
        data.position(alignedYLength + offset)
        yuvPlanes[1] = data.slice()
        yuvPlanes[1]!!.limit(uvLength)
        data.position(alignedYLength + alignedUvLength + offset)
        yuvPlanes[2] = data.slice()
        yuvPlanes[2]!!.limit(uvLength)

        if (yuvStrides == null) {
            yuvStrides = IntArray(3)
        }
        yuvStrides!![0] = yStride
        yuvStrides!![1] = uvStride
        yuvStrides!![2] = uvStride
        return true
    }

    fun initForPrivateFrame(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    companion object {
        // LINT.IfChange
        const val COLORSPACE_UNKNOWN: Int = 0
        const val COLORSPACE_BT601: Int = 1
        const val COLORSPACE_BT709: Int = 2
        const val COLORSPACE_BT2020: Int = 3

        private fun isSafeToMultiply(a: Int, b: Int): Boolean {
            return a >= 0 && b >= 0 && !(b > 0 && a >= Int.MAX_VALUE / b)
        }
    }
}
