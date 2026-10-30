
package androidx.media3.exoplayer.drm

import android.util.Pair
import androidx.media3.common.C

object WidevineUtil {
    const val PROPERTY_LICENSE_DURATION_REMAINING: String = "LicenseDurationRemaining"
    const val PROPERTY_PLAYBACK_DURATION_REMAINING: String = "PlaybackDurationRemaining"

    fun getLicenseDurationRemainingSec(drmSession: DrmSession): Pair<Long, Long>? {
        val keyStatus = drmSession.queryKeyStatus() ?: return null
        return Pair<Long, Long>(
            getDurationRemainingSec(keyStatus, PROPERTY_LICENSE_DURATION_REMAINING),
            getDurationRemainingSec(keyStatus, PROPERTY_PLAYBACK_DURATION_REMAINING)
        )
    }

    private fun getDurationRemainingSec(
        keyStatus: Map<String, String>?,
        property: String?
    ): Long {
        if (keyStatus != null) {
            try {
                val value = keyStatus[property]
                if (value != null) {
                    return value.toLong()
                }
            } catch (e: NumberFormatException) {
                // do nothing.
            }
        }
        return C.TIME_UNSET
    }
}
