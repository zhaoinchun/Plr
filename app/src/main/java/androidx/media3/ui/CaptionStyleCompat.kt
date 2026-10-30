package androidx.media3.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.accessibility.CaptioningManager.CaptionStyle
import androidx.annotation.IntDef

class CaptionStyleCompat(
    val foregroundColor: Int,
    val backgroundColor: Int,
    val windowColor: Int,
    val edgeType: @EdgeType Int,
    val edgeColor: Int,
    val typeface: Typeface?
) {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [EDGE_TYPE_NONE, EDGE_TYPE_OUTLINE, EDGE_TYPE_DROP_SHADOW, EDGE_TYPE_RAISED, EDGE_TYPE_DEPRESSED]
    )
    annotation class EdgeType

    companion object {
        const val EDGE_TYPE_NONE: Int = 0
        const val EDGE_TYPE_OUTLINE: Int = 1
        const val EDGE_TYPE_DROP_SHADOW: Int = 2
        const val EDGE_TYPE_RAISED: Int = 3
        const val EDGE_TYPE_DEPRESSED: Int = 4
        const val USE_TRACK_COLOR_SETTINGS: Int = 1

        val DEFAULT: CaptionStyleCompat = CaptionStyleCompat(
            Color.WHITE,
            Color.BLACK,
            Color.TRANSPARENT,
            EDGE_TYPE_NONE,
            Color.WHITE,
            null
        )

        fun createFromCaptionStyle(
            captionStyle: CaptionStyle
        ): CaptionStyleCompat {
            return CaptionStyleCompat(
                if (captionStyle.hasForegroundColor()) captionStyle.foregroundColor else DEFAULT.foregroundColor,
                if (captionStyle.hasBackgroundColor()) captionStyle.backgroundColor else DEFAULT.backgroundColor,
                if (captionStyle.hasWindowColor()) captionStyle.windowColor else DEFAULT.windowColor,
                if (captionStyle.hasEdgeType()) captionStyle.edgeType else DEFAULT.edgeType,
                if (captionStyle.hasEdgeColor()) captionStyle.edgeColor else DEFAULT.edgeColor,
                captionStyle.getTypeface()
            )
        }
    }
}
