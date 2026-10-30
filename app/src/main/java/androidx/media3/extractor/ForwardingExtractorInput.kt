package androidx.media3.extractor

import java.io.IOException

open class ForwardingExtractorInput(private val input: ExtractorInput) : ExtractorInput {
    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return input.read(buffer!!, offset, length)
    }

    @Throws(IOException::class)
    override fun readFully(
        target: ByteArray,
        offset: Int,
        length: Int,
        allowEndOfInput: Boolean
    ): Boolean {
        return input.readFully(target, offset, length, allowEndOfInput)
    }

    @Throws(IOException::class)
    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        input.readFully(target, offset, length)
    }

    @Throws(IOException::class)
    override fun skip(length: Int): Int {
        return input.skip(length)
    }

    @Throws(IOException::class)
    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        return input.skipFully(length, allowEndOfInput)
    }

    @Throws(IOException::class)
    override fun skipFully(length: Int) {
        input.skipFully(length)
    }

    @Throws(IOException::class)
    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        return input.peek(target, offset, length)
    }

    @Throws(IOException::class)
    override fun peekFully(
        target: ByteArray,
        offset: Int,
        length: Int,
        allowEndOfInput: Boolean
    ): Boolean {
        return input.peekFully(target, offset, length, allowEndOfInput)
    }

    @Throws(IOException::class)
    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        input.peekFully(target, offset, length)
    }

    @Throws(IOException::class)
    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        return input.advancePeekPosition(length, allowEndOfInput)
    }

    @Throws(IOException::class)
    override fun advancePeekPosition(length: Int) {
        input.advancePeekPosition(length)
    }

    override fun resetPeekPosition() {
        input.resetPeekPosition()
    }

    override val peekPosition: Long
        get() = input.peekPosition
    override val position: Long
        get() = input.position
    override val length: Long
        get() = input.length

    @Throws(Throwable::class)
    override fun <E : Throwable?> setRetryPosition(position: Long, e: E?) {
        input.setRetryPosition<E?>(position, e)
    }
}
