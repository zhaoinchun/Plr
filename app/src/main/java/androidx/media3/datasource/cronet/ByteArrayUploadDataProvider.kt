package androidx.media3.datasource.cronet

import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min

/** A [UploadDataProvider] implementation that provides data from a `byte[]`.  */ /* package */
internal class ByteArrayUploadDataProvider(private val data: ByteArray) : UploadDataProvider() {
    private var position = 0

    override fun getLength(): Long {
        return data.size.toLong()
    }

    @Throws(IOException::class)
    override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
        val readLength = min(byteBuffer.remaining(), data.size - position)
        byteBuffer.put(data, position, readLength)
        position += readLength
        uploadDataSink.onReadSucceeded(false)
    }

    @Throws(IOException::class)
    override fun rewind(uploadDataSink: UploadDataSink) {
        position = 0
        uploadDataSink.onRewindSucceeded()
    }
}
