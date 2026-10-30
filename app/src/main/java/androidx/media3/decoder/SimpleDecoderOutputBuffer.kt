package androidx.media3.decoder

import com.google.common.base.Preconditions
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SimpleDecoderOutputBuffer(private val owner: Owner<SimpleDecoderOutputBuffer>) :
    DecoderOutputBuffer() {
    var data: ByteBuffer? = null

    fun init(timeUs: Long, size: Int): ByteBuffer? {
        this.timeUs = timeUs
        if (data == null || data!!.capacity() < size) {
            data = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }
        data!!.position(0)
        data!!.limit(size)
        return data
    }

    fun grow(newSize: Int): ByteBuffer {
        val oldData = this.data!!
        Preconditions.checkArgument(newSize >= oldData.limit())
        val newData = ByteBuffer.allocateDirect(newSize).order(ByteOrder.nativeOrder())
        val restorePosition = oldData.position()
        oldData.position(0)
        newData.put(oldData)
        newData.position(restorePosition)
        newData.limit(newSize)
        this.data = newData
        return newData
    }

    override fun clear() {
        super.clear()
        if (data != null) {
            data!!.clear()
        }
    }

    override fun release() {
        owner.releaseOutputBuffer(this)
    }
}
