package androidx.media3.extractor

import androidx.media3.common.DataReader
import java.io.IOException

interface ExtractorInput : DataReader {
    
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int

    
    fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean

    
    fun readFully(target: ByteArray, offset: Int, length: Int)

    
    fun skip(length: Int): Int

    
    fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean

    
    fun skipFully(length: Int)

    
    fun peek(target: ByteArray, offset: Int, length: Int): Int

    
    fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean

    
    fun peekFully(target: ByteArray, offset: Int, length: Int)

    
    fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean

    
    fun advancePeekPosition(length: Int)

    fun resetPeekPosition()

    val peekPosition: Long

    val position: Long

    val length: Long
    fun <E : Throwable?> setRetryPosition(position: Long, e: E?)
}
