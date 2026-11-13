package androidx.media3.exoplayer

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.Clock

interface SuitableOutputChecker {
    fun interface Callback {
        fun onSelectedOutputSuitabilityChanged(isSelectedOutputSuitableForPlayback: Boolean)
    }

    fun enable(
        callback: Callback,
        context: Context,
        callbackLooper: Looper,
        backgroundLooper: Looper,
        clock: Clock
    )

    fun disable()

    val isSelectedOutputSuitableForPlayback: Boolean
}
