package androidx.media3.decoder

import androidx.annotation.CallSuper
import androidx.media3.common.C
import androidx.media3.common.C.BufferFlags

abstract class Buffer {
    private var flags: @BufferFlags Int = 0

    @CallSuper
    open fun clear() {
        flags = 0
    }

    val isFirstSample: Boolean
        get() = getFlag(C.BUFFER_FLAG_FIRST_SAMPLE)

    val isEndOfStream: Boolean
        get() = getFlag(C.BUFFER_FLAG_END_OF_STREAM)

    val isKeyFrame: Boolean
        get() = getFlag(C.BUFFER_FLAG_KEY_FRAME)

    val isLastSample: Boolean
        get() = getFlag(C.BUFFER_FLAG_LAST_SAMPLE)

    fun hasSupplementalData(): Boolean {
        return getFlag(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
    }

    fun notDependedOn(): Boolean {
        return getFlag(C.BUFFER_FLAG_NOT_DEPENDED_ON)
    }

    fun setFlags(flags: @BufferFlags Int) {
        this.flags = flags
    }

    fun addFlag(flag: @BufferFlags Int) {
        flags = flags or flag
    }

    fun clearFlag(flag: @BufferFlags Int) {
        flags = flags and flag.inv()
    }

    protected fun getFlag(flag: @BufferFlags Int): Boolean {
        return (flags and flag) == flag
    }
}
