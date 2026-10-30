package androidx.media3.common.util

import androidx.annotation.IntDef
import androidx.media3.common.Player
import androidx.media3.common.Player.RepeatMode

object RepeatModeUtil {
    const val REPEAT_TOGGLE_MODE_NONE: Int = 0
    const val REPEAT_TOGGLE_MODE_ONE: Int = 1
    const val REPEAT_TOGGLE_MODE_ALL: Int = 1 shl 1 // 2

    @RepeatMode
    fun getNextRepeatMode(
        @RepeatMode currentMode: @RepeatMode Int,
        @RepeatToggleModes enabledModes: @RepeatToggleModes Int
    ): @RepeatMode Int {
        for (offset in 1..2) {
            @RepeatMode val proposedMode: @RepeatMode Int = (currentMode + offset) % 3
            if (isRepeatModeEnabled(proposedMode, enabledModes)) {
                return proposedMode
            }
        }
        return currentMode
    }

    fun isRepeatModeEnabled(
        @RepeatMode repeatMode: @RepeatMode Int,
        @RepeatToggleModes enabledModes: @RepeatToggleModes Int
    ): Boolean {
        when (repeatMode) {
            Player.REPEAT_MODE_OFF -> return true
            Player.REPEAT_MODE_ONE -> return (enabledModes and REPEAT_TOGGLE_MODE_ONE) != 0
            Player.REPEAT_MODE_ALL -> return (enabledModes and REPEAT_TOGGLE_MODE_ALL) != 0
            else -> return false
        }
    }

    // LINT.IfChange
    // @Target list includes both 'default' targets and TYPE_USE, to ensure backwards compatibility
    // with Kotlin usages from before TYPE_USE was added.
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        flag = true,
        value = [REPEAT_TOGGLE_MODE_NONE, REPEAT_TOGGLE_MODE_ONE, REPEAT_TOGGLE_MODE_ALL]
    )
    annotation class RepeatToggleModes
}
