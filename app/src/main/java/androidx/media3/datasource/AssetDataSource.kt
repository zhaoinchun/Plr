package androidx.media3.datasource

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import com.google.common.base.Preconditions
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

class AssetDataSource(context: Context) : BaseDataSource( false) {
    class AssetDataSourceException : DataSourceException {
        @Deprecated("Use {@link #AssetDataSourceException(Throwable, int)}.")
        constructor(cause: IOException?) : super(cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

        constructor(
            cause: Throwable?,
            @PlaybackException.ErrorCode errorCode: @PlaybackException.ErrorCode Int
        ) : super(cause, errorCode)
    }

    private val assetManager: AssetManager = context.assets
    override var uri: Uri? = null
    private var inputStream: InputStream? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    @Throws(AssetDataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {
        try {
            uri = dataSpec.uri
            var path = uri!!.path!!
            if (path.startsWith("/android_asset/")) {
                path = path.substring(15)
            } else if (path.startsWith("/")) {
                path = path.substring(1)
            }
            transferInitializing(dataSpec)
            inputStream = assetManager.open(path, AssetManager.ACCESS_RANDOM)
            val skipped = inputStream!!.skip(dataSpec.position)
            if (skipped < dataSpec.position) {
                // assetManager.open() returns an AssetInputStream, whose skip() implementation only skips
                // fewer bytes than requested if the skip is beyond the end of the asset's data.
                throw AssetDataSourceException( 
                    null, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                )
            }
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                bytesRemaining = dataSpec.length
            } else {
                bytesRemaining = inputStream!!.available().toLong()
                if (bytesRemaining == Int.MAX_VALUE.toLong()) {
                    // assetManager.open() returns an AssetInputStream, whose available() implementation
                    // returns Integer.MAX_VALUE if the remaining length is greater than (or equal to)
                    // Integer.MAX_VALUE. We don't know the true length in this case, so treat as unbounded.
                    bytesRemaining = C.LENGTH_UNSET.toLong()
                }
            }
        } catch (e: AssetDataSourceException) {
            throw e
        } catch (e: IOException) {
            throw AssetDataSourceException(
                e,
                if (e is FileNotFoundException)
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                else
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    @Throws(AssetDataSourceException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        } else if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }

        val bytesRead: Int
        try {
            val bytesToRead =
                if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else min(
                    bytesRemaining,
                    length.toLong()
                ).toInt()
            bytesRead = inputStream!!.read(buffer, offset, bytesToRead)
        } catch (e: IOException) {
            throw AssetDataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
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

    @Throws(AssetDataSourceException::class)
    override fun close() {
        uri = null
        try {
            if (inputStream != null) {
                inputStream!!.close()
            }
        } catch (e: IOException) {
            throw AssetDataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        } finally {
            inputStream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
