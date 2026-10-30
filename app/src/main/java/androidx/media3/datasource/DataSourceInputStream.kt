package androidx.media3.datasource

import androidx.media3.common.C
import com.google.common.base.Preconditions
import java.io.IOException
import java.io.InputStream

class DataSourceInputStream(private val dataSource: DataSource, private val dataSpec: DataSpec) :
    InputStream() {
    private val singleByteArray: ByteArray

    private var opened = false
    private var closed = false
    private var totalBytesRead: Long = 0

    init {
        singleByteArray = ByteArray(1)
    }

    fun bytesRead(): Long {
        return totalBytesRead
    }

    @Throws(IOException::class)
    fun open() {
        checkOpened()
    }

    @Throws(IOException::class)
    override fun read(): Int {
        val length = read(singleByteArray)
        return if (length == -1) -1 else (singleByteArray[0].toInt() and 0xFF)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray): Int {
        return read(buffer, 0, buffer.size)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        Preconditions.checkState(!closed)
        checkOpened()
        val bytesRead: Int = dataSource.read(buffer, offset, length)
        if (bytesRead == C.RESULT_END_OF_INPUT) {
            return -1
        } else {
            totalBytesRead += bytesRead.toLong()
            return bytesRead
        }
    }

    @Throws(IOException::class)
    override fun close() {
        if (!closed) {
            dataSource.close()
            closed = true
        }
    }

    @Throws(IOException::class)
    private fun checkOpened() {
        if (!opened) {
            dataSource.open(dataSpec)
            opened = true
        }
    }
}
