package androidx.media3.datasource.cache

import androidx.media3.common.C
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.closeQuietly
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache.CacheException
import com.google.common.base.Preconditions
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.math.min

class CacheDataSink(
    private val cache: Cache,
    private var fragmentSize: Long,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE
) : DataSink {
    private var dataSpec: DataSpec? = null
    private var dataSpecFragmentSize: Long = 0
    private var file: File? = null
    private var outputStream: OutputStream? = null
    private var outputStreamBytesWritten: Long = 0
    private var dataSpecBytesWritten: Long = 0
    private var bufferedOutputStream: ReusableBufferedOutputStream? = null

    init {
        Preconditions.checkState(
            fragmentSize > 0 || fragmentSize == C.LENGTH_UNSET.toLong(),
            "fragmentSize must be positive or C.LENGTH_UNSET."
        )
        if (fragmentSize != C.LENGTH_UNSET.toLong() && fragmentSize < MIN_RECOMMENDED_FRAGMENT_SIZE) {
            w(
                TAG,
                ("fragmentSize is below the minimum recommended value of "
                        + MIN_RECOMMENDED_FRAGMENT_SIZE
                        + ". This may cause poor cache performance.")
            )
        }
        this.fragmentSize =
            if (fragmentSize == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else fragmentSize
    }

    class Factory(
        var cache: Cache,
        var fragmentSize: Long = DEFAULT_FRAGMENT_SIZE,
        var bufferSize: Int = DEFAULT_BUFFER_SIZE
    ) : DataSink.Factory {
        override fun createDataSink(): DataSink {
            return CacheDataSink(
                cache,
                fragmentSize,
                bufferSize
            )
        }
    }

    class CacheDataSinkException(cause: IOException?) : CacheException(cause)

    override fun open(dataSpec: DataSpec) {
        dataSpec.key!!
        if (dataSpec.length == C.LENGTH_UNSET.toLong()
            && dataSpec.isFlagSet(DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN)
        ) {
            this.dataSpec = null
            return
        }
        this.dataSpec = dataSpec
        this.dataSpecFragmentSize =
            if (dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)) fragmentSize else Long.MAX_VALUE
        dataSpecBytesWritten = 0
        try {
            openNextOutputStream(dataSpec)
        } catch (e: IOException) {
            throw CacheDataSinkException(e)
        }
    }
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val dataSpec = this.dataSpec ?: return
        try {
            var bytesWritten = 0
            while (bytesWritten < length) {
                if (outputStreamBytesWritten == dataSpecFragmentSize) {
                    closeCurrentOutputStream()
                    openNextOutputStream(dataSpec)
                }
                val bytesToWrite = min(
                    (length - bytesWritten).toLong(),
                    dataSpecFragmentSize - outputStreamBytesWritten
                ).toInt()
                outputStream!!.write(buffer, offset + bytesWritten, bytesToWrite)
                bytesWritten += bytesToWrite
                outputStreamBytesWritten += bytesToWrite.toLong()
                dataSpecBytesWritten += bytesToWrite.toLong()
            }
        } catch (e: IOException) {
            throw CacheDataSinkException(e)
        }
    }

    override fun close() {
        if (dataSpec == null) {
            return
        }
        try {
            closeCurrentOutputStream()
        } catch (e: IOException) {
            throw CacheDataSinkException(e)
        }
    }

    private fun openNextOutputStream(dataSpec: DataSpec) {
        val length =
            if (dataSpec.length == C.LENGTH_UNSET.toLong())
                C.LENGTH_UNSET
                    .toLong()
            else
                min(dataSpec.length - dataSpecBytesWritten, dataSpecFragmentSize)
        file =
            cache.startFile(
                dataSpec.key!!, dataSpec.position + dataSpecBytesWritten, length
            )
        val underlyingFileOutputStream = FileOutputStream(file)
        if (bufferSize > 0) {
            if (bufferedOutputStream == null) {
                bufferedOutputStream =
                    ReusableBufferedOutputStream(underlyingFileOutputStream, bufferSize)
            } else {
                bufferedOutputStream!!.reset(underlyingFileOutputStream)
            }
            outputStream = bufferedOutputStream
        } else {
            outputStream = underlyingFileOutputStream
        }
        outputStreamBytesWritten = 0
    }

    private fun closeCurrentOutputStream() {
        val outputStream = outputStream ?: return
        var success = false
        try {
            outputStream.flush()
            success = true
        } finally {
            closeQuietly(outputStream)
            this.outputStream = null
            val fileToCommit: File = file!!
            file = null
            if (success) {
                cache.commitFile(fileToCommit, outputStreamBytesWritten)
            } else {
                fileToCommit.delete()
            }
        }
    }

    companion object {
        const val DEFAULT_FRAGMENT_SIZE: Long = (5 * 1024 * 1024).toLong()
        const val DEFAULT_BUFFER_SIZE: Int = 20 * 1024
        private const val MIN_RECOMMENDED_FRAGMENT_SIZE = (2 * 1024 * 1024).toLong()
        private const val TAG = "CacheDataSink"
    }
}
