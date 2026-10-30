package androidx.media3.common.text

import android.os.Bundle
import androidx.annotation.IntDef
import androidx.media3.common.util.Util.intToStringMaxRadix

class TextEmphasisSpan(
    var markShape: @MarkShape Int,
    var markFill: @MarkFill Int,
    val position: @TextAnnotation.Position Int
) : LanguageFeatureSpan {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [MARK_SHAPE_NONE, MARK_SHAPE_CIRCLE, MARK_SHAPE_DOT, MARK_SHAPE_SESAME])
    annotation class MarkShape

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [MARK_FILL_UNKNOWN, MARK_FILL_FILLED, MARK_FILL_OPEN])
    annotation class MarkFill

    fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putInt(FIELD_MARK_SHAPE, markShape)
        bundle.putInt(FIELD_MARK_FILL, markFill)
        bundle.putInt(FIELD_POSITION, position)
        return bundle
    }

    companion object {
        const val MARK_SHAPE_NONE: Int = 0
        const val MARK_SHAPE_CIRCLE: Int = 1
        const val MARK_SHAPE_DOT: Int = 2
        const val MARK_SHAPE_SESAME: Int = 3

        const val MARK_FILL_UNKNOWN: Int = 0
        const val MARK_FILL_FILLED: Int = 1
        const val MARK_FILL_OPEN: Int = 2

        private val FIELD_MARK_SHAPE = intToStringMaxRadix(0)
        private val FIELD_MARK_FILL = intToStringMaxRadix(1)
        private val FIELD_POSITION = intToStringMaxRadix(2)

            fun fromBundle(bundle: Bundle): TextEmphasisSpan {
            return TextEmphasisSpan( 
                bundle.getInt(FIELD_MARK_SHAPE),  
                bundle.getInt(FIELD_MARK_FILL),  
                bundle.getInt(FIELD_POSITION)
            )
        }
    }
}
