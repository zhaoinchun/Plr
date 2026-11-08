package androidx.media3.common

import android.view.View
import androidx.annotation.IntDef

class AdOverlayInfo(
    val view: View?,
    val purpose: @Purpose Int,
    val reasonDetail: String? = null
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
    @IntDef(value = [PURPOSE_CONTROLS, PURPOSE_CLOSE_AD, PURPOSE_OTHER, PURPOSE_NOT_VISIBLE])
    annotation class Purpose

    companion object {
        const val PURPOSE_CONTROLS: Int = 1
        const val PURPOSE_CLOSE_AD: Int = 2
        const val PURPOSE_OTHER: Int = 3
        const val PURPOSE_NOT_VISIBLE: Int = 4
    }
}
