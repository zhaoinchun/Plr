package androidx.media3.datasource

import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import android.text.TextUtils
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import com.google.common.base.Preconditions
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min

class FileDataSource : BaseDataSource( false) {
    class FileDataSourceException : DataSourceException {
        constructor(
            cause: Throwable,
            @PlaybackException.ErrorCode errorCode: @PlaybackException.ErrorCode Int
        ) : super(cause, errorCode)

        constructor(
            message: String?,
            cause: Throwable?,
            @PlaybackException.ErrorCode errorCode: @PlaybackException.ErrorCode Int
        ) : super(message, cause, errorCode)
    }

    class Factory : DataSource.Factory {
        private var listener: TransferListener? = null

        fun setListener(listener: TransferListener?): Factory {
            this.listener = listener
            return this
        }

        override fun createDataSource(): FileDataSource {
            val dataSource = FileDataSource()
            listener?.also {
                dataSource.addTransferListener(it)
            }
            return dataSource
        }
    }

    private var file: RandomAccessFile? = null
    override var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    @Throws(FileDataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        this.uri = uri
        transferInitializing(dataSpec)
        this.file = openLocalFile(uri)
        try {
            file!!.seek(dataSpec.position)
            bytesRemaining =
                if (dataSpec.length == C.LENGTH_UNSET.toLong()) file!!.length() - dataSpec.position else dataSpec.length
        } catch (e: IOException) {
            throw FileDataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (bytesRemaining < 0) {
            throw FileDataSourceException( 
                null,  
                null,
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
            )
        }

        opened = true
        transferStarted(dataSpec)

        return bytesRemaining
    }

    @Throws(FileDataSourceException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        } else if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        } else {
            val bytesRead: Int
            try {
                bytesRead = file!!.read(
                    buffer,
                    offset,
                    min(bytesRemaining, length.toLong()).toInt()
                )
            } catch (e: IOException) {
                throw FileDataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            }

            if (bytesRead > 0) {
                bytesRemaining -= bytesRead.toLong()
                bytesTransferred(bytesRead)
            }

            return bytesRead
        }
    }

    @Throws(FileDataSourceException::class)
    override fun close() {
        uri = null
        try {
            if (file != null) {
                file!!.close()
            }
        } catch (e: IOException) {
            throw FileDataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        @Throws(FileDataSourceException::class)
        private fun openLocalFile(uri: Uri): RandomAccessFile {
            try {
                return RandomAccessFile(uri.path, "r")
            } catch (e: FileNotFoundException) {
                if (!TextUtils.isEmpty(uri.getQuery()) || !TextUtils.isEmpty(uri.getFragment())) {
                    throw FileDataSourceException(
                        String.format(
                            ("uri has query and/or fragment, which are not supported. Did you call Uri.parse()"
                                    + " on a string containing '?' or '#'? Use Uri.fromFile(new File(path)) to"
                                    + " avoid this. path=%s,query=%s,fragment=%s"),
                            uri.getPath(), uri.getQuery(), uri.getFragment()
                        ),
                        e,
                        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
                    )
                }

                // TODO(internal b/193503588): Add tests to ensure the correct error codes are assigned under
                // different SDK versions.
                throw FileDataSourceException(
                    e,
                    if (e.cause is ErrnoException
                        && (e.cause as ErrnoException).errno == OsConstants.EACCES
                    )
                        PlaybackException.ERROR_CODE_IO_NO_PERMISSION
                    else
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                )
            } catch (e: SecurityException) {
                throw FileDataSourceException(e, PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
            } catch (e: RuntimeException) {
                throw FileDataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            }
        }
    }
}
