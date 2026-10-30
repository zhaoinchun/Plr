package androidx.media3.extractor

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.MediaLibraryInfo.registerModule

import androidx.media3.common.util.Util.constrainValue
import com.google.common.base.Preconditions
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.math.min

class DefaultExtractorInput(
    val dataReader: DataReader, override var position: Long, private val streamLength: Long
) : ExtractorInput {
    private val scratchSpace: ByteArray

    private var peekBuffer: ByteArray
    private var peekBufferPosition = 0
    private var peekBufferLength = 0

    init {
        peekBuffer = ByteArray(PEEK_MIN_FREE_SPACE_AFTER_RESIZE)
        scratchSpace = ByteArray(SCRATCH_SPACE_SIZE)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        var bytesRead = readFromPeekBuffer(buffer, offset, length)
        if (bytesRead == 0) {
            bytesRead =
                readFromUpstream(
                    buffer, offset, length,  0,  true
                )
        }
        commitBytesRead(bytesRead)
        return bytesRead
    }

    @Throws(IOException::class)
    override fun readFully(
        target: ByteArray,
        offset: Int,
        length: Int,
        allowEndOfInput: Boolean
    ): Boolean {
        var bytesRead = readFromPeekBuffer(target, offset, length)
        while (bytesRead < length && bytesRead != C.RESULT_END_OF_INPUT) {
            bytesRead = readFromUpstream(target, offset, length, bytesRead, allowEndOfInput)
        }
        commitBytesRead(bytesRead)
        return bytesRead != C.RESULT_END_OF_INPUT
    }

    @Throws(IOException::class)
    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        readFully(target, offset, length, false)
    }

    @Throws(IOException::class)
    override fun skip(length: Int): Int {
        var bytesSkipped = skipFromPeekBuffer(length)
        if (bytesSkipped == 0) {
            bytesSkipped =
                readFromUpstream(scratchSpace, 0, min(length, scratchSpace.size), 0, true)
        }
        commitBytesRead(bytesSkipped)
        return bytesSkipped
    }

    @Throws(IOException::class)
    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        var bytesSkipped = skipFromPeekBuffer(length)
        while (bytesSkipped < length && bytesSkipped != C.RESULT_END_OF_INPUT) {
            val minLength = min(length, bytesSkipped + scratchSpace.size)
            bytesSkipped =
                readFromUpstream(
                    scratchSpace,
                    -bytesSkipped,
                    minLength,
                    bytesSkipped,
                    allowEndOfInput
                )
        }
        commitBytesRead(bytesSkipped)
        return bytesSkipped != C.RESULT_END_OF_INPUT
    }

    @Throws(IOException::class)
    override fun skipFully(length: Int) {
        skipFully(length, false)
    }

    @Throws(IOException::class)
    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        ensureSpaceForPeek(length)
        val peekBufferRemainingBytes = peekBufferLength - peekBufferPosition
        val bytesPeeked: Int
        if (peekBufferRemainingBytes == 0) {
            bytesPeeked =
                readFromUpstream(
                    peekBuffer,
                    peekBufferPosition,
                    length,  
                    0,  
                    true
                )
            if (bytesPeeked == C.RESULT_END_OF_INPUT) {
                return C.RESULT_END_OF_INPUT
            }
            peekBufferLength += bytesPeeked
        } else {
            bytesPeeked = min(length, peekBufferRemainingBytes)
        }
        System.arraycopy(peekBuffer, peekBufferPosition, target, offset, bytesPeeked)
        peekBufferPosition += bytesPeeked
        return bytesPeeked
    }

    @Throws(IOException::class)
    override fun peekFully(
        target: ByteArray,
        offset: Int,
        length: Int,
        allowEndOfInput: Boolean
    ): Boolean {
        if (!advancePeekPosition(length, allowEndOfInput)) {
            return false
        }
        System.arraycopy(peekBuffer, peekBufferPosition - length, target, offset, length)
        return true
    }

    @Throws(IOException::class)
    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        peekFully(target, offset, length, false)
    }

    @Throws(IOException::class)
    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        ensureSpaceForPeek(length)
        var bytesPeeked = peekBufferLength - peekBufferPosition
        while (bytesPeeked < length) {
            bytesPeeked =
                readFromUpstream(
                    peekBuffer,
                    peekBufferPosition,
                    length,
                    bytesPeeked,
                    allowEndOfInput
                )
            if (bytesPeeked == C.RESULT_END_OF_INPUT) {
                return false
            }
            peekBufferLength = peekBufferPosition + bytesPeeked
        }
        peekBufferPosition += length
        return true
    }

    @Throws(IOException::class)
    override fun advancePeekPosition(length: Int) {
        advancePeekPosition(length, false)
    }

    override fun resetPeekPosition() {
        peekBufferPosition = 0
    }

    override val peekPosition: Long
        get() = position + peekBufferPosition

    override val length: Long
        get() = streamLength

    @Throws(Throwable::class)
    override fun <E : Throwable?> setRetryPosition(position: Long, e: E?) {
        Preconditions.checkArgument(position >= 0)
        this.position = position
        if (e != null) {
            throw e
        }
    }

    private fun ensureSpaceForPeek(length: Int) {
        val requiredLength = peekBufferPosition + length
        if (requiredLength > peekBuffer.size) {
            val newPeekCapacity =
                constrainValue(
                    peekBuffer.size * 2,
                    requiredLength + PEEK_MIN_FREE_SPACE_AFTER_RESIZE,
                    requiredLength + PEEK_MAX_FREE_SPACE
                )
            peekBuffer = peekBuffer.copyOf(newPeekCapacity)
        }
    }

    private fun skipFromPeekBuffer(length: Int): Int {
        val bytesSkipped = min(peekBufferLength, length)
        updatePeekBuffer(bytesSkipped)
        return bytesSkipped
    }

    private fun readFromPeekBuffer(target: ByteArray, offset: Int, length: Int): Int {
        if (peekBufferLength == 0) {
            return 0
        }
        val peekBytes = min(peekBufferLength, length)
        System.arraycopy(peekBuffer, 0, target, offset, peekBytes)
        updatePeekBuffer(peekBytes)
        return peekBytes
    }

    private fun updatePeekBuffer(bytesConsumed: Int) {
        peekBufferLength -= bytesConsumed
        peekBufferPosition = 0
        var newPeekBuffer = peekBuffer
        if (peekBufferLength < peekBuffer.size - PEEK_MAX_FREE_SPACE) {
            newPeekBuffer = ByteArray(peekBufferLength + PEEK_MIN_FREE_SPACE_AFTER_RESIZE)
        }
        System.arraycopy(peekBuffer, bytesConsumed, newPeekBuffer, 0, peekBufferLength)
        peekBuffer = newPeekBuffer
    }

    @Throws(IOException::class)
    private fun readFromUpstream(
        target: ByteArray, offset: Int, length: Int, bytesAlreadyRead: Int, allowEndOfInput: Boolean
    ): Int {
        if (Thread.interrupted()) {
            throw InterruptedIOException()
        }
        val bytesRead =
            dataReader.read(target, offset + bytesAlreadyRead, length - bytesAlreadyRead)
        if (bytesRead == C.RESULT_END_OF_INPUT) {
            if (bytesAlreadyRead == 0 && allowEndOfInput) {
                return C.RESULT_END_OF_INPUT
            }
            throw EOFException()
        }
        return bytesAlreadyRead + bytesRead
    }

    private fun commitBytesRead(bytesRead: Int) {
        if (bytesRead != C.RESULT_END_OF_INPUT) {
            position += bytesRead.toLong()
        }
    }

    companion object {
        init {
            registerModule("media3.extractor")
        }

        private val PEEK_MIN_FREE_SPACE_AFTER_RESIZE = 64 * 1024
        private val PEEK_MAX_FREE_SPACE = 512 * 1024
        private const val SCRATCH_SPACE_SIZE = 4096
    }
}
