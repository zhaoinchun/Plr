package androidx.media3.datasource

import java.io.IOException

interface DataSink {
    interface Factory {
        fun createDataSink(): DataSink
    }

    @Throws(IOException::class)
    fun open(dataSpec: DataSpec)

    @Throws(IOException::class)
    fun write(buffer: ByteArray, offset: Int, length: Int)

    @Throws(IOException::class)
    fun close()
}
