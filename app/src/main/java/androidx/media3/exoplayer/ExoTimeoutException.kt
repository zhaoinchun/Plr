
package androidx.media3.exoplayer

import androidx.annotation.IntDef

class ExoTimeoutException(
     val timeoutOperation:  Int
) : RuntimeException(
    getErrorMessage(
        timeoutOperation
    )
) {

    companion object {
        const val TIMEOUT_OPERATION_UNDEFINED: Int = 0
        const val TIMEOUT_OPERATION_RELEASE: Int = 1
        const val TIMEOUT_OPERATION_SET_FOREGROUND_MODE: Int = 2
        const val TIMEOUT_OPERATION_DETACH_SURFACE: Int = 3

        private fun getErrorMessage( timeoutOperation:  Int): String {
            when (timeoutOperation) {
                TIMEOUT_OPERATION_RELEASE -> return "Player release timed out."
                TIMEOUT_OPERATION_SET_FOREGROUND_MODE -> return "Setting foreground mode timed out."
                TIMEOUT_OPERATION_DETACH_SURFACE -> return "Detaching surface timed out."
                TIMEOUT_OPERATION_UNDEFINED -> return "Undefined timeout."
                else -> return "Undefined timeout."
            }
        }
    }
}
