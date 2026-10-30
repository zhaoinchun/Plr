package androidx.media3.datasource

import androidx.media3.common.C
import com.google.common.base.Preconditions

import java.io.ByteArrayOutputStream
import java.io.IOException

class ByteArrayDataSink : DataSink {
    private var stream:ByteArrayOutputStream? = null

    override fun open(dataSpec: DataSpec) {
        if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            stream = ByteArrayOutputStream()
        } else {
            Preconditions.checkArgument(dataSpec.length <= Int.MAX_VALUE)
            stream = ByteArrayOutputStream(dataSpec.length.toInt())
        }
    }

    @Throws(IOException::class)
    override fun close() {
        stream?.close()
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        stream?.write(buffer, offset, length)
    }

    val data: ByteArray?
        get() = if (stream == null) null else stream!!.toByteArray()
}
