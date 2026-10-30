package androidx.media3.extractor

import androidx.media3.common.DataReader
import java.io.IOException

interface ExtractorInput : DataReader {
    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int

    @Throws(IOException::class)
    fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean

    @Throws(IOException::class)
    fun readFully(target: ByteArray, offset: Int, length: Int)

    @Throws(IOException::class)
    fun skip(length: Int): Int

    @Throws(IOException::class)
    fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean

    @Throws(IOException::class)
    fun skipFully(length: Int)

    @Throws(IOException::class)
    fun peek(target: ByteArray, offset: Int, length: Int): Int

    @Throws(IOException::class)
    fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean

    @Throws(IOException::class)
    fun peekFully(target: ByteArray, offset: Int, length: Int)

    @Throws(IOException::class)
    fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean

    @Throws(IOException::class)
    fun advancePeekPosition(length: Int)

    fun resetPeekPosition()

    val peekPosition: Long

    val position: Long

    val length: Long

    @Throws(Exception::class)
    fun <E : Throwable?> setRetryPosition(position: Long, e: E?)
}
