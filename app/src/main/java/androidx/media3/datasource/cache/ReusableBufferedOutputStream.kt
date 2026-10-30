package androidx.media3.datasource.cache

import androidx.media3.common.util.Util
import com.google.common.base.Preconditions
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream

internal class ReusableBufferedOutputStream : BufferedOutputStream {
    private var closed = false

    constructor(out: OutputStream?) : super(out)

    constructor(out: OutputStream?, size: Int) : super(out, size)

    @Throws(IOException::class)
    override fun close() {
        closed = true

        var thrown: Throwable? = null
        try {
            flush()
        } catch (e: Throwable) {
            thrown = e
        }
        try {
            out.close()
        } catch (e: Throwable) {
            if (thrown == null) {
                thrown = e
            }
        }
        if (thrown != null) {
            throw thrown
        }
    }

    fun reset(out: OutputStream?) {
        Preconditions.checkState(closed)
        this.out = out
        count = 0
        closed = false
    }
}
