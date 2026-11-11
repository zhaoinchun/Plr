package androidx.media3.common

data class AuxEffectInfo(val effectId: Int, val sendLevel: Float) {
    companion object {
        const val NO_AUX_EFFECT_ID: Int = 0
    }
}
