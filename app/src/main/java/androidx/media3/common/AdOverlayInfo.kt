package androidx.media3.common

import android.view.View
import androidx.annotation.IntDef
import com.google.errorprone.annotations.CanIgnoreReturnValue

class AdOverlayInfo @Deprecated("Use {@link Builder} instead.") constructor(
    
    val view: View?,
    
    @field:Purpose @param:Purpose val purpose: @Purpose Int,
    
    val reasonDetail: String?
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

    class Builder
    (private val view: View?, @field:Purpose @param:Purpose private val purpose: @Purpose Int) {
        private var detailedReason: String? = null

        fun setDetailedReason(detailedReason: String?): Builder {
            this.detailedReason = detailedReason
            return this
        }

         // Using deprecated constructor while it still exists.
        @Suppress("deprecation")
        fun build(): AdOverlayInfo {
            return AdOverlayInfo(view, purpose, detailedReason)
        }
    }

    @Suppress("deprecation") // Intentionally using deprecated constructor
    @Deprecated("Use {@link Builder} instead.")
    constructor(view: View?, @Purpose purpose: @Purpose Int) : this(
        view,
        purpose,  
        null
    )

    companion object {
        
        const val PURPOSE_CONTROLS: Int = 1

        const val PURPOSE_CLOSE_AD: Int = 2

        const val PURPOSE_OTHER: Int = 3

        const val PURPOSE_NOT_VISIBLE: Int = 4
    }
}
