package androidx.media3.datasource

import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import java.io.IOException
import kotlin.math.min

class MediaDataSourceAdapter(private val mediaDataSource: MediaDataSource, isNetwork: Boolean) : BaseDataSource(isNetwork) {
    override var uri: Uri? = null
    private var position: Long = 0
    private var bytesRemaining: Long = 0
    private var opened = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        position = dataSpec.position
        transferInitializing(dataSpec)

        if (mediaDataSource.getSize() != C.LENGTH_UNSET.toLong() && position > mediaDataSource.getSize()) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }

        if (mediaDataSource.getSize() == C.LENGTH_UNSET.toLong()) {
            bytesRemaining = C.LENGTH_UNSET.toLong()
        } else {
            bytesRemaining = mediaDataSource.getSize() - position
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
            bytesRead = mediaDataSource.readAt(position, buffer, offset, bytesToRead)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }

        if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT
        }

        position += bytesRead.toLong()
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead.toLong()
        }

        bytesTransferred(bytesRead)
        return bytesRead
    }

    @Throws(IOException::class)
    override fun close() {
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
