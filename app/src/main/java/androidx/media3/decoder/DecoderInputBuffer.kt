package androidx.media3.decoder

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaLibraryInfo.registerModule

import java.nio.ByteBuffer

open class DecoderInputBuffer  constructor(
    private val bufferReplacementMode: @BufferReplacementMode Int,
    private val paddingSize: Int = 0
) : Buffer() {

    class InsufficientCapacityException
    (
        val currentCapacity: Int,
        val requiredCapacity: Int
    ) : IllegalStateException("Buffer too small (" + currentCapacity + " < " + requiredCapacity + ")")

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [BUFFER_REPLACEMENT_MODE_DISABLED, BUFFER_REPLACEMENT_MODE_NORMAL, BUFFER_REPLACEMENT_MODE_DIRECT
        ]
    )
    annotation class BufferReplacementMode

    var format: Format? = null
    val cryptoInfo: CryptoInfo
    var data: ByteBuffer? = null
    var waitingForKeys: Boolean = false
    var timeUs: Long = 0
    var supplementalData: ByteBuffer? = null

    init {
        this.cryptoInfo = CryptoInfo()
    }

    fun resetSupplementalData(length: Int) {
        if (supplementalData == null || supplementalData!!.capacity() < length) {
            supplementalData = ByteBuffer.allocate(length)
        } else {
            supplementalData!!.clear()
        }
    }

    fun ensureSpaceForWrite(length: Int) {
        var length = length
        length += paddingSize
        val currentData = data
        if (currentData == null) {
            data = createReplacementByteBuffer(length)
            return
        }
        // Check whether the current buffer is sufficient.
        val capacity = currentData.capacity()
        val position = currentData.position()
        val requiredCapacity = position + length
        if (capacity >= requiredCapacity) {
            data = currentData
            return
        }
        // Instantiate a new buffer if possible.
        val newData = createReplacementByteBuffer(requiredCapacity)
        newData.order(currentData.order())
        // Copy data up to the current position from the old buffer to the new one.
        if (position > 0) {
            currentData.flip()
            newData.put(currentData)
        }
        // Set the new buffer.
        data = newData
    }

    val isEncrypted: Boolean
        get() = getFlag(C.BUFFER_FLAG_ENCRYPTED)

    fun flip() {
        if (data != null) {
            data!!.flip()
        }
        if (supplementalData != null) {
            supplementalData!!.flip()
        }
    }

    override fun clear() {
        super.clear()
        if (data != null) {
            data!!.clear()
        }
        if (supplementalData != null) {
            supplementalData!!.clear()
        }
        waitingForKeys = false
    }

    private fun createReplacementByteBuffer(requiredCapacity: Int): ByteBuffer {
        if (bufferReplacementMode == BUFFER_REPLACEMENT_MODE_NORMAL) {
            return ByteBuffer.allocate(requiredCapacity)
        } else if (bufferReplacementMode == BUFFER_REPLACEMENT_MODE_DIRECT) {
            return ByteBuffer.allocateDirect(requiredCapacity)
        } else {
            val currentCapacity = if (data == null) 0 else data!!.capacity()
            throw InsufficientCapacityException(currentCapacity, requiredCapacity)
        }
    }

    companion object {
        init {
            registerModule("media3.decoder")
        }

        const val BUFFER_REPLACEMENT_MODE_DISABLED: Int = 0
        const val BUFFER_REPLACEMENT_MODE_NORMAL: Int = 1
        const val BUFFER_REPLACEMENT_MODE_DIRECT: Int = 2

        fun newNoDataInstance(): DecoderInputBuffer {
            return DecoderInputBuffer(BUFFER_REPLACEMENT_MODE_DISABLED)
        }
    }
}
