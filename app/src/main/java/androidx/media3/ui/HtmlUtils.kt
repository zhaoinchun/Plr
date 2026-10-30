package androidx.media3.ui

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.media3.common.util.Util.formatInvariant

internal object HtmlUtils {
    fun toCssRgba(@ColorInt color: Int): String {
        return formatInvariant(
            "rgba(%d,%d,%d,%.3f)",
            Color.red(color), Color.green(color), Color.blue(color), Color.alpha(color) / 255.0
        )
    }

    fun cssAllClassDescendantsSelector(className: String): String {
        return ".$className,.$className *"
    }
}
