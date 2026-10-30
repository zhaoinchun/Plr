
package androidx.media3.exoplayer.drm

import androidx.annotation.IntDef

class UnsupportedDrmException : Exception {
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
    @IntDef(value = [REASON_UNSUPPORTED_SCHEME, REASON_INSTANTIATION_ERROR])
    annotation class Reason

    @Reason
    val reason: @Reason Int

    constructor(@Reason reason: @Reason Int) {
        this.reason = reason
    }

    constructor(@Reason reason: @Reason Int, cause: Exception?) : super(cause) {
        this.reason = reason
    }

    companion object {
        const val REASON_UNSUPPORTED_SCHEME: Int = 1
        const val REASON_INSTANTIATION_ERROR: Int = 2
    }
}
