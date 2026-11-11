package androidx.media3.common.util

import androidx.annotation.IntDef
import androidx.media3.common.Player

object RepeatModeUtil {
    const val REPEAT_TOGGLE_MODE_NONE: Int = 0
    const val REPEAT_TOGGLE_MODE_ONE: Int = 1
    const val REPEAT_TOGGLE_MODE_ALL: Int = 1 shl 1 // 2

    
    fun getNextRepeatMode(
        currentMode: Int,
         enabledModes:  Int
    ): Int {
        for (offset in 1..2) {
            val proposedMode: Int = (currentMode + offset) % 3
            if (isRepeatModeEnabled(proposedMode, enabledModes)) {
                return proposedMode
            }
        }
        return currentMode
    }

    fun isRepeatModeEnabled(
        repeatMode: Int,
         enabledModes:  Int
    ): Boolean {
        when (repeatMode) {
            Player.REPEAT_MODE_OFF -> return true
            Player.REPEAT_MODE_ONE -> return (enabledModes and REPEAT_TOGGLE_MODE_ONE) != 0
            Player.REPEAT_MODE_ALL -> return (enabledModes and REPEAT_TOGGLE_MODE_ALL) != 0
            else -> return false
        }
    }

}
