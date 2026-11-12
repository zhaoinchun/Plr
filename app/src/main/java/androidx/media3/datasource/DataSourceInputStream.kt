package androidx.media3.datasource

import androidx.media3.common.C
import com.google.common.base.Preconditions
import java.io.IOException
import java.io.InputStream

class DataSourceInputStream(private val dataSource: DataSource, private val dataSpec: DataSpec) :
    InputStream() {
    private val singleByteArray = ByteArray(1)

    private var opened = false
    private var closed = false
    private var totalBytesRead: Long = 0

    fun bytesRead(): Long {
        return totalBytesRead
    }

    fun open() {
        checkOpened()
    }

    override fun read(): Int {
        val length = read(singleByteArray)
        return if (length == -1) -1 else (singleByteArray[0].toInt() and 0xFF)
    }

    override fun read(buffer: ByteArray): Int {
        return read(buffer, 0, buffer.size)
    }

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

    override fun close() {
        if (!closed) {
            dataSource.close()
            closed = true
        }
    }

    private fun checkOpened() {
        if (!opened) {
            dataSource.open(dataSpec)
            opened = true
        }
    }
}
