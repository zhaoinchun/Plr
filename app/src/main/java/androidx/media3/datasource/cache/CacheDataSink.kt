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

class CacheDataSink  constructor(
    cache: Cache?,
    fragmentSize: Long,
    bufferSize: Int = DEFAULT_BUFFER_SIZE
) : DataSink {
    class Factory : DataSink.Factory {
        private var cache:Cache? = null
        private var fragmentSize: Long
        private var bufferSize: Int

        init {
            fragmentSize = DEFAULT_FRAGMENT_SIZE
            bufferSize = DEFAULT_BUFFER_SIZE
        }

        fun setCache(cache: Cache?): Factory {
            this.cache = cache
            return this
        }

        fun setFragmentSize(fragmentSize: Long): Factory {
            this.fragmentSize = fragmentSize
            return this
        }

        fun setBufferSize(bufferSize: Int): Factory {
            this.bufferSize = bufferSize
            return this
        }

        override fun createDataSink(): DataSink {
            return CacheDataSink(
                cache,
                fragmentSize,
                bufferSize
            )
        }
    }

    class CacheDataSinkException(cause: IOException?) : CacheException(cause)

    private val cache: Cache
    private val fragmentSize: Long
    private val bufferSize: Int

    private var dataSpec: DataSpec? = null
    private var dataSpecFragmentSize: Long = 0
    private var file: File? = null
    private var outputStream: OutputStream? = null
    private var outputStreamBytesWritten: Long = 0
    private var dataSpecBytesWritten: Long = 0
    private var bufferedOutputStream:ReusableBufferedOutputStream? = null

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
        this.cache = cache!!
        this.fragmentSize =
            if (fragmentSize == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else fragmentSize
        this.bufferSize = bufferSize
    }

    @Throws(CacheDataSinkException::class)
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

    @Throws(CacheDataSinkException::class)
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val dataSpec = this.dataSpec
        if (dataSpec == null) {
            return
        }
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

    @Throws(CacheDataSinkException::class)
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

    @Throws(IOException::class)
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

    @Throws(IOException::class)
    private fun closeCurrentOutputStream() {
        if (outputStream == null) {
            return
        }

        var success = false
        try {
            outputStream!!.flush()
            success = true
        } finally {
            closeQuietly(outputStream)
            outputStream = null
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
        val DEFAULT_FRAGMENT_SIZE: Long = (5 * 1024 * 1024).toLong()
        val DEFAULT_BUFFER_SIZE: Int = 20 * 1024

        private val MIN_RECOMMENDED_FRAGMENT_SIZE = (2 * 1024 * 1024).toLong()
        private const val TAG = "CacheDataSink"
    }
}
