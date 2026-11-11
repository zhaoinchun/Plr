package androidx.media3.common

import android.view.View
import androidx.annotation.IntDef

class AdOverlayInfo(
    val view: View?,
    val purpose: Int,
    val reasonDetail: String? = null
) {

    companion object {
        const val PURPOSE_CONTROLS: Int = 1
        const val PURPOSE_CLOSE_AD: Int = 2
        const val PURPOSE_OTHER: Int = 3
        const val PURPOSE_NOT_VISIBLE: Int = 4
    }
}
