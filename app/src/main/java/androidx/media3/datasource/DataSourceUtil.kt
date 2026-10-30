package androidx.media3.datasource

import androidx.media3.common.C
import java.io.IOException

object DataSourceUtil {
    @Throws(IOException::class)
    fun readToEnd(dataSource: DataSource): ByteArray {
        var data = ByteArray(1024)
        var position = 0
        var bytesRead = 0
        while (bytesRead != C.RESULT_END_OF_INPUT) {
            if (position == data.size) {
                data = data.copyOf(data.size * 2)
            }
            bytesRead = dataSource.read(data, position, data.size - position)
            if (bytesRead != C.RESULT_END_OF_INPUT) {
                position += bytesRead
            }
        }
        return data.copyOf(position)
    }

    @Throws(IOException::class)
    fun readExactly(dataSource: DataSource, length: Int): ByteArray {
        val data = ByteArray(length)
        var position = 0
        while (position < length) {
            val bytesRead: Int = dataSource.read(data, position, data.size - position)
            check(bytesRead != C.RESULT_END_OF_INPUT) { "Not enough data could be read: " + position + " < " + length }
            position += bytesRead
        }
        return data
    }

    fun closeQuietly(dataSource: DataSource?) {
        try {
            if (dataSource != null) {
                dataSource.close()
            }
        } catch (e: IOException) {
            // Ignore.
        }
    }
}
