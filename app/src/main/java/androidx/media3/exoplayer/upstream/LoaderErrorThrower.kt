package androidx.media3.exoplayer.upstream

import java.io.IOException

interface LoaderErrorThrower {
    
    fun maybeThrowError()

    
    fun maybeThrowError(minRetryCount: Int)

    class Placeholder : LoaderErrorThrower {
        override fun maybeThrowError() {
            // Do nothing.
        }

        override fun maybeThrowError(minRetryCount: Int) {
            // Do nothing.
        }
    }
}
