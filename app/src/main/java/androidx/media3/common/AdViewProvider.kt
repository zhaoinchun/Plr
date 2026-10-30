package androidx.media3.common

import android.view.ViewGroup

interface AdViewProvider {
    val adViewGroup: ViewGroup?
    fun getAdOverlayInfos(): MutableList<AdOverlayInfo>
}
