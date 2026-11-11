package androidx.media3.common

import android.os.Bundle
import androidx.annotation.FloatRange
import androidx.media3.common.util.Util.formatInvariant
import androidx.media3.common.util.Util.intToStringMaxRadix
import com.google.common.base.Preconditions
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.math.roundToInt

data class PlaybackParameters(
    @FloatRange(from = 0.0, fromInclusive = false) val speed: Float,
    @FloatRange(from = 0.0, fromInclusive = false) val pitch: Float = 1f
) {
    private val scaledUsPerMs: Int = (speed * 1000f).roundToInt()

    init {
        Preconditions.checkArgument(speed > 0)
        Preconditions.checkArgument(pitch > 0)
    }

    fun getMediaTimeUsForPlayoutTimeMs(timeMs: Long): Long {
        return timeMs * scaledUsPerMs
    }

    fun withSpeed(@FloatRange(from = 0.0, fromInclusive = false) speed: Float): PlaybackParameters {
        return PlaybackParameters(speed, pitch)
    }

    fun withPitch(@FloatRange(from = 0.0, fromInclusive = false) pitch: Float): PlaybackParameters {
        return PlaybackParameters(speed, pitch)
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putFloat(FIELD_SPEED, speed)
        bundle.putFloat(FIELD_PITCH, pitch)
        return bundle
    }

    companion object {
        val DEFAULT: PlaybackParameters = PlaybackParameters(1f)
        private val FIELD_SPEED = intToStringMaxRadix(0)
        private val FIELD_PITCH = intToStringMaxRadix(1)

        fun fromBundle(bundle: Bundle): PlaybackParameters {
            val speed = bundle.getFloat(FIELD_SPEED, 1f)
            val pitch = bundle.getFloat(FIELD_PITCH, 1f)
            return PlaybackParameters(speed, pitch)
        }
    }
}
