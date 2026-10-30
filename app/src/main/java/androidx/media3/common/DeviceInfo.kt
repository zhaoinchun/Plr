package androidx.media3.common

import android.os.Bundle
import androidx.annotation.IntDef
import androidx.annotation.IntRange
import androidx.media3.common.util.Util
import com.google.common.base.Preconditions

class DeviceInfo private constructor(builder: Builder) {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [PLAYBACK_TYPE_LOCAL, PLAYBACK_TYPE_REMOTE])
    annotation class PlaybackType

    class Builder(internal val playbackType: @PlaybackType Int) {
        internal var minVolume = 0
        internal var maxVolume = 0
        internal var routingControllerId: String? = null

        fun setMinVolume(@IntRange(from = 0) minVolume: Int): Builder {
            this.minVolume = minVolume
            return this
        }

        fun setMaxVolume(@IntRange(from = 0) maxVolume: Int): Builder {
            this.maxVolume = maxVolume
            return this
        }

        fun setRoutingControllerId(routingControllerId: String?): Builder {
            Preconditions.checkArgument(playbackType != PLAYBACK_TYPE_LOCAL || routingControllerId == null)
            this.routingControllerId = routingControllerId
            return this
        }

        fun build(): DeviceInfo {
            Preconditions.checkArgument(minVolume <= maxVolume)
            return DeviceInfo(this)
        }
    }

    val playbackType: @PlaybackType Int

    @IntRange(from = 0)
    val minVolume: Int

    @IntRange(from = 0)
    val maxVolume: Int

    val routingControllerId: String?

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is DeviceInfo) {
            return false
        }
        return playbackType == other.playbackType && minVolume == other.minVolume && maxVolume == other.maxVolume && routingControllerId == other.routingControllerId
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + playbackType
        result = 31 * result + minVolume
        result = 31 * result + maxVolume
        result = 31 * result + (routingControllerId?.hashCode() ?: 0)
        return result
    }

    init {
        this.playbackType = builder.playbackType
        this.minVolume = builder.minVolume
        this.maxVolume = builder.maxVolume
        this.routingControllerId = builder.routingControllerId
    }

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
        val UNKNOWN: DeviceInfo = Builder(PLAYBACK_TYPE_LOCAL).build()
        private val FIELD_PLAYBACK_TYPE: String = Util.intToStringMaxRadix(0)
        private val FIELD_MIN_VOLUME: String = Util.intToStringMaxRadix(1)
        private val FIELD_MAX_VOLUME: String = Util.intToStringMaxRadix(2)
        private val FIELD_ROUTING_CONTROLLER_ID: String = Util.intToStringMaxRadix(3)

        fun fromBundle(bundle: Bundle): DeviceInfo {
            val playbackType = bundle.getInt(
                FIELD_PLAYBACK_TYPE,  
                PLAYBACK_TYPE_LOCAL
            )
            val minVolume = bundle.getInt(FIELD_MIN_VOLUME,  0)
            val maxVolume = bundle.getInt(FIELD_MAX_VOLUME,  0)
            val routingControllerId = bundle.getString(FIELD_ROUTING_CONTROLLER_ID)
            return Builder(playbackType)
                .setMinVolume(minVolume)
                .setMaxVolume(maxVolume)
                .setRoutingControllerId(routingControllerId)
                .build()
        }
    }
}