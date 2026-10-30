package androidx.media3.common.text

import androidx.annotation.IntDef

object TextAnnotation {
    const val POSITION_UNKNOWN: Int = -1

    const val POSITION_BEFORE: Int = 1
    const val POSITION_AFTER: Int = 2

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [POSITION_UNKNOWN, POSITION_BEFORE, POSITION_AFTER])
    annotation class Position
}
