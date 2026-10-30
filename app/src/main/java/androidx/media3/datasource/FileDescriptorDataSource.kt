package androidx.media3.datasource

import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import com.google.common.base.Preconditions
import com.google.common.collect.Sets
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.min

class FileDescriptorDataSource(
    private val fileDescriptor: FileDescriptor,
    private val offset: Long,
    private val length: Long
) : BaseDataSource( false) {

    override var uri: Uri? = null
    private var inputStream: FileInputStream? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    @Throws(DataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {
        try {
            uri = dataSpec.uri
            transferInitializing(dataSpec)

            if (!inUseFileDescriptors.add(fileDescriptor)) {
                throw DataSourceException(
                    IllegalStateException("Attempted to re-use an already in-use file descriptor"),
                    PlaybackException.ERROR_CODE_INVALID_STATE
                )
            }

            if (length != C.LENGTH_UNSET.toLong() && dataSpec.position > length) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }

            seekFileDescriptor(fileDescriptor, offset + dataSpec.position)
            inputStream = FileInputStream(fileDescriptor)

            if (length == C.LENGTH_UNSET.toLong()) {
                // The asset must extend to the end of the file. We can try and resolve the length with
                // FileInputStream.getChannel().size().
                val channel = inputStream!!.getChannel()
                val channelSize = channel.size()
                if (channelSize == 0L) {
                    bytesRemaining = C.LENGTH_UNSET.toLong()
                } else {
                    bytesRemaining = channelSize - channel.position()
                    if (bytesRemaining < 0) {
                        // The seek above was successful, but the new position is beyond the end of the file.
                        throw DataSourceException(
                            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                        )
                    }
                }
            } else {
                bytesRemaining = length - dataSpec.position
                if (bytesRemaining < 0) {
                    throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                }
            }
        } catch (e: DataSourceException) {
            throw e
        } catch (e: IOException) {
            throw DataSourceException(
                e,
                if (e is FileNotFoundException)
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                else
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        }

        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            bytesRemaining =
                if (bytesRemaining == C.LENGTH_UNSET.toLong()) dataSpec.length else min(
                    bytesRemaining,
                    dataSpec.length
                )
        }

        opened = true
        transferStarted(dataSpec)
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else bytesRemaining
    }

    @Throws(DataSourceException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        } else if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else min(
            bytesRemaining,
            length.toLong()
        ).toInt()
        val bytesRead: Int
        try {
            bytesRead = inputStream!!.read(buffer, offset, bytesToRead)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead.toLong()
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    @Throws(DataSourceException::class)
    override fun close() {
        uri = null
        inUseFileDescriptors.remove(fileDescriptor)
        try {
            if (inputStream != null) {
                inputStream!!.close()
            }
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        } finally {
            inputStream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        // Track file descriptors currently in use to fail fast if an attempt is made to re-use one.
        private val inUseFileDescriptors: MutableSet<FileDescriptor?> =
            Sets.newConcurrentHashSet<FileDescriptor?>()

        @Throws(DataSourceException::class)
        private fun seekFileDescriptor(fileDescriptor: FileDescriptor?, position: Long) {
            try {
                Os.lseek(fileDescriptor, position,  OsConstants.SEEK_SET)
            } catch (e: ErrnoException) {
                throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            }
        }
    }
}
