package androidx.media3.common.audio

import androidx.media3.common.C

interface SpeedProvider {
    fun getSpeed(timeUs: Long): Float
    fun getNextSpeedChangeTimeUs(timeUs: Long): Long

    companion object {
        val DEFAULT: SpeedProvider = object : SpeedProvider {
            override fun getSpeed(timeUs: Long): Float {
                return 1f
            }

            override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
                return C.TIME_UNSET
            }
        }
    }
}
