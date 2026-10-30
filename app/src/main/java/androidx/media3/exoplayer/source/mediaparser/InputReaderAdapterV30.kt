package androidx.media3.exoplayer.source.mediaparser

import android.media.MediaParser.SeekableInputReader
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.DataReader
import java.io.IOException

@RequiresApi(30)
class InputReaderAdapterV30 : SeekableInputReader {
    private var dataReader: DataReader? = null
    private var resourceLength: Long = 0
    private var currentPosition: Long = 0
    private var lastSeekPosition: Long = 0

    fun setDataReader(dataReader: DataReader?, length: Long) {
        this.dataReader = dataReader
        resourceLength = length
        lastSeekPosition = C.INDEX_UNSET.toLong()
    }

    fun setCurrentPosition(position: Long) {
        currentPosition = position
    }

    fun getAndResetSeekPosition(): Long {
        val lastSeekPosition = this.lastSeekPosition
        this.lastSeekPosition = C.INDEX_UNSET.toLong()
        return lastSeekPosition
    }

    // SeekableInputReader implementation.
    override fun seekToPosition(position: Long) {
        lastSeekPosition = position
    }

    @Throws(IOException::class)
    override fun read(bytes: ByteArray, offset: Int, readLength: Int): Int {
        val bytesRead: Int = dataReader!!.read(bytes, offset, readLength)
        currentPosition += bytesRead.toLong()
        return bytesRead
    }

    override fun getPosition(): Long {
        return currentPosition
    }

    override fun getLength(): Long {
        return resourceLength
    }
}
