package androidx.media3.extractor.text.ssa

import android.text.TextUtils
import androidx.media3.common.C
import com.google.common.base.Ascii
import com.google.common.base.Preconditions

 class SsaDialogueFormat private constructor(
    val layerIndex: Int,
    val startTimeIndex: Int,
    val endTimeIndex: Int,
    val styleIndex: Int,
    val textIndex: Int,
    val length: Int
) {
    companion object {

        fun fromFormatLine(formatLine: String): SsaDialogueFormat? {
            var layerIndex = C.INDEX_UNSET
            var startTimeIndex = C.INDEX_UNSET
            var endTimeIndex = C.INDEX_UNSET
            var styleIndex = C.INDEX_UNSET
            var textIndex = C.INDEX_UNSET
            Preconditions.checkArgument(formatLine.startsWith(SsaParser.FORMAT_LINE_PREFIX))
            val keys = TextUtils.split(
                formatLine.substring(SsaParser.FORMAT_LINE_PREFIX.length),
                ","
            )
            for (i in keys.indices) {
                when (Ascii.toLowerCase(keys[i]!!.trim { it <= ' ' })) {
                    "layer" -> layerIndex = i
                    "start" -> startTimeIndex = i
                    "end" -> endTimeIndex = i
                    "style" -> styleIndex = i
                    "text" -> textIndex = i
                }
            }
            return if (startTimeIndex != C.INDEX_UNSET && endTimeIndex != C.INDEX_UNSET && textIndex != C.INDEX_UNSET)
                SsaDialogueFormat(
                    layerIndex, startTimeIndex, endTimeIndex, styleIndex, textIndex, keys.size
                )
            else
                null
        }
    }
}
