package androidx.media3.common

import kotlin.Any
import kotlin.Boolean
import kotlin.Int

class AuxEffectInfo
(
    val effectId: Int,
    val sendLevel: Float
) {
    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || javaClass != o.javaClass) {
            return false
        }
        val auxEffectInfo = o as AuxEffectInfo
        return effectId == auxEffectInfo.effectId
                && auxEffectInfo.sendLevel.compareTo(sendLevel) == 0
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + effectId
        result = 31 * result + sendLevel.toBits()
        return result
    }

    companion object {
        
        const val NO_AUX_EFFECT_ID: Int = 0
    }
}
