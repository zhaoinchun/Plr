package androidx.media3.common

import android.os.Bundle
import androidx.annotation.IntDef
import androidx.annotation.IntRange
import androidx.media3.common.util.Util
import com.google.common.base.Preconditions

data class DeviceInfo(
    val playbackType: @PlaybackType Int,
    var minVolume: Int = 0,
    var maxVolume: Int = 0,
    var routingControllerId: String? = null,
) {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [PLAYBACK_TYPE_LOCAL, PLAYBACK_TYPE_REMOTE])
    annotation class PlaybackType

    fun toBundle(): Bundle {
        val bundle = Bundle()
        if (playbackType != PLAYBACK_TYPE_LOCAL) {
            bundle.putInt(FIELD_PLAYBACK_TYPE, playbackType)
        }
        if (minVolume != 0) {
            bundle.putInt(FIELD_MIN_VOLUME, minVolume)
        }
        if (maxVolume != 0) {
            bundle.putInt(FIELD_MAX_VOLUME, maxVolume)
        }
        if (routingControllerId != null) {
            bundle.putString(FIELD_ROUTING_CONTROLLER_ID, routingControllerId)
        }
        return bundle
    }

    companion object {
        const val PLAYBACK_TYPE_LOCAL: Int = 0
        const val PLAYBACK_TYPE_REMOTE: Int = 1
        val UNKNOWN: DeviceInfo = DeviceInfo(PLAYBACK_TYPE_LOCAL)
        private val FIELD_PLAYBACK_TYPE: String = Util.intToStringMaxRadix(0)
        private val FIELD_MIN_VOLUME: String = Util.intToStringMaxRadix(1)
        private val FIELD_MAX_VOLUME: String = Util.intToStringMaxRadix(2)
        private val FIELD_ROUTING_CONTROLLER_ID: String = Util.intToStringMaxRadix(3)

        fun fromBundle(bundle: Bundle): DeviceInfo {
            val playbackType = bundle.getInt(
                FIELD_PLAYBACK_TYPE,
                PLAYBACK_TYPE_LOCAL
            )
            val minVolume = bundle.getInt(FIELD_MIN_VOLUME, 0)
            val maxVolume = bundle.getInt(FIELD_MAX_VOLUME, 0)
            val routingControllerId = bundle.getString(FIELD_ROUTING_CONTROLLER_ID)
            return DeviceInfo(
                playbackType,
                minVolume = minVolume,
                maxVolume = maxVolume,
                routingControllerId = routingControllerId
            )
        }
    }
}