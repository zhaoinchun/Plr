
package androidx.media3.exoplayer

import androidx.annotation.IntDef

class ExoTimeoutException(
    @field:TimeoutOperation @param:TimeoutOperation val timeoutOperation: @TimeoutOperation Int
) : RuntimeException(
    getErrorMessage(
        timeoutOperation
    )
) {
    // @Target list includes both 'default' targets and TYPE_USE, to ensure backwards compatibility
    // with Kotlin usages from before TYPE_USE was added.
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        value = [TIMEOUT_OPERATION_UNDEFINED, TIMEOUT_OPERATION_RELEASE, TIMEOUT_OPERATION_SET_FOREGROUND_MODE, TIMEOUT_OPERATION_DETACH_SURFACE
        ]
    )
    annotation class TimeoutOperation

    companion object {
        const val TIMEOUT_OPERATION_UNDEFINED: Int = 0
        const val TIMEOUT_OPERATION_RELEASE: Int = 1
        const val TIMEOUT_OPERATION_SET_FOREGROUND_MODE: Int = 2
        const val TIMEOUT_OPERATION_DETACH_SURFACE: Int = 3

        private fun getErrorMessage(@TimeoutOperation timeoutOperation: @TimeoutOperation Int): String {
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
