package androidx.media3.exoplayer

import android.content.Context
import android.os.Looper
import androidx.annotation.RestrictTo
import androidx.media3.common.util.Clock

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
interface SuitableOutputChecker {
    fun interface Callback {
        fun onSelectedOutputSuitabilityChanged(isSelectedOutputSuitableForPlayback: Boolean)
    }

    fun enable(
        callback: SuitableOutputChecker.Callback,
        context: Context,
        callbackLooper: Looper,
        backgroundLooper: Looper,
        clock: Clock
    )

    fun disable()

    val isSelectedOutputSuitableForPlayback: Boolean
}
