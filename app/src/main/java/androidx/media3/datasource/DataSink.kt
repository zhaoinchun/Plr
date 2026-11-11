package androidx.media3.datasource

import java.io.IOException

interface DataSink {
    interface Factory {
        fun createDataSink(): DataSink
    }

    
    fun open(dataSpec: DataSpec)

    
    fun write(buffer: ByteArray, offset: Int, length: Int)

    
    fun close()
}
