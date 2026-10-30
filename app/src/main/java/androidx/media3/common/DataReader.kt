package androidx.media3.common

import java.io.IOException

interface DataReader {
    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}
