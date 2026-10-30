package androidx.media3.datasource

import android.net.http.UploadDataProvider
import android.net.http.UploadDataSink
import android.os.Build
import androidx.annotation.RequiresExtension
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)

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
