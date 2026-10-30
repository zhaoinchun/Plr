package androidx.media3.extractor.text.ssa

import android.graphics.Color
import android.graphics.PointF
import android.text.TextUtils
import androidx.annotation.ColorInt
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Log.i
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.formatInvariant
import com.google.common.base.Ascii
import com.google.common.base.Preconditions
import com.google.common.primitives.Ints
import java.util.regex.Matcher
import java.util.regex.Pattern

internal class SsaStyle private constructor(
    val name: String?,
    val alignment: @SsaAlignment Int,
    @field:ColorInt @param:ColorInt val primaryColor: Int?,
    @field:ColorInt @param:ColorInt val outlineColor: Int?,
    val fontSize: Float,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strikeout: Boolean,
    val borderStyle: @SsaBorderStyle Int
) {
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [SSA_ALIGNMENT_UNKNOWN, SSA_ALIGNMENT_BOTTOM_LEFT, SSA_ALIGNMENT_BOTTOM_CENTER, SSA_ALIGNMENT_BOTTOM_RIGHT, SSA_ALIGNMENT_MIDDLE_LEFT, SSA_ALIGNMENT_MIDDLE_CENTER, SSA_ALIGNMENT_MIDDLE_RIGHT, SSA_ALIGNMENT_TOP_LEFT, SSA_ALIGNMENT_TOP_CENTER, SSA_ALIGNMENT_TOP_RIGHT
        ]
    )
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    annotation class SsaAlignment

    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [SSA_BORDER_STYLE_UNKNOWN, SSA_BORDER_STYLE_OUTLINE, SSA_BORDER_STYLE_BOX
        ]
    )
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    annotation class SsaBorderStyle

    internal class Format private constructor(
        val nameIndex: Int,
        val alignmentIndex: Int,
        val primaryColorIndex: Int,
        val outlineColorIndex: Int,
        val fontSizeIndex: Int,
        val boldIndex: Int,
        val italicIndex: Int,
        val underlineIndex: Int,
        val strikeoutIndex: Int,
        val borderStyleIndex: Int,
        val length: Int
    ) {
        companion object {
            
            fun fromFormatLine(styleFormatLine: String): Format? {
                var nameIndex = C.INDEX_UNSET
                var alignmentIndex = C.INDEX_UNSET
                var primaryColorIndex = C.INDEX_UNSET
                var outlineColorIndex = C.INDEX_UNSET
                var fontSizeIndex = C.INDEX_UNSET
                var boldIndex = C.INDEX_UNSET
                var italicIndex = C.INDEX_UNSET
                var underlineIndex = C.INDEX_UNSET
                var strikeoutIndex = C.INDEX_UNSET
                var borderStyleIndex = C.INDEX_UNSET
                val keys =
                    TextUtils.split(
                        styleFormatLine.substring(SsaParser.FORMAT_LINE_PREFIX.length),
                        ","
                    )
                for (i in keys.indices) {
                    when (Ascii.toLowerCase(keys[i]!!.trim { it <= ' ' })) {
                        "name" -> nameIndex = i
                        "alignment" -> alignmentIndex = i
                        "primarycolour" -> primaryColorIndex = i
                        "outlinecolour" -> outlineColorIndex = i
                        "fontsize" -> fontSizeIndex = i
                        "bold" -> boldIndex = i
                        "italic" -> italicIndex = i
                        "underline" -> underlineIndex = i
                        "strikeout" -> strikeoutIndex = i
                        "borderstyle" -> borderStyleIndex = i
                    }
                }
                return if (nameIndex != C.INDEX_UNSET)
                    Format(
                        nameIndex,
                        alignmentIndex,
                        primaryColorIndex,
                        outlineColorIndex,
                        fontSizeIndex,
                        boldIndex,
                        italicIndex,
                        underlineIndex,
                        strikeoutIndex,
                        borderStyleIndex,
                        keys.size
                    )
                else
                    null
            }
        }
    }

    internal class Overrides private constructor(
        val alignment: @SsaAlignment Int,
        val position: PointF?
    ) {
        companion object {
            private const val TAG = "SsaStyle.Overrides"

             // Warning that \\} can be replaced with } is bogus [internal: b/144480183].
            private val BRACES_PATTERN: Pattern = Pattern.compile("\\{([^}]*)\\}")

            private const val PADDED_DECIMAL_PATTERN = "\\s*\\d+(?:\\.\\d+)?\\s*"

            private val POSITION_PATTERN: Pattern = Pattern.compile(
                formatInvariant(
                    "\\\\pos\\((%1\$s),(%1\$s)\\)",
                    PADDED_DECIMAL_PATTERN
                )
            )

            private val MOVE_PATTERN: Pattern = Pattern.compile(
                formatInvariant(
                    "\\\\move\\(%1\$s,%1\$s,(%1\$s),(%1\$s)(?:,%1\$s,%1\$s)?\\)",
                    PADDED_DECIMAL_PATTERN
                )
            )

            private val ALIGNMENT_OVERRIDE_PATTERN: Pattern = Pattern.compile("\\\\an(\\d+)")

            fun parseFromDialogue(text: String): Overrides {
                var alignment: @SsaAlignment Int = SSA_ALIGNMENT_UNKNOWN
                var position: PointF? = null
                val matcher: Matcher = BRACES_PATTERN.matcher(text)
                while (matcher.find()) {
                    val braceContents = matcher.group(1)!!
                    try {
                        val parsedPosition: PointF? = parsePosition(braceContents)
                        if (parsedPosition != null) {
                            position = parsedPosition
                        }
                    } catch (e: RuntimeException) {
                        // Ignore invalid \pos() or \move() function.
                    }
                    try {
                        val parsedAlignment: @SsaAlignment Int =
                            parseAlignmentOverride(braceContents)
                        if (parsedAlignment != SSA_ALIGNMENT_UNKNOWN) {
                            alignment = parsedAlignment
                        }
                    } catch (e: RuntimeException) {
                        // Ignore invalid \an alignment override.
                    }
                }
                return Overrides(alignment, position)
            }

            fun stripStyleOverrides(dialogueLine: String): String {
                return BRACES_PATTERN.matcher(dialogueLine).replaceAll("")
            }

            private fun parsePosition(styleOverride: String): PointF? {
                val positionMatcher: Matcher = POSITION_PATTERN.matcher(styleOverride)
                val moveMatcher: Matcher = MOVE_PATTERN.matcher(styleOverride)
                val hasPosition = positionMatcher.find()
                val hasMove = moveMatcher.find()

                val x: String?
                val y: String?
                if (hasPosition) {
                    if (hasMove) {
                        i(
                            TAG,
                            ("Override has both \\pos(x,y) and \\move(x1,y1,x2,y2); using \\pos values. override='"
                                    + styleOverride
                                    + "'")
                        )
                    }
                    x = positionMatcher.group(1)
                    y = positionMatcher.group(2)
                } else if (hasMove) {
                    x = moveMatcher.group(1)
                    y = moveMatcher.group(2)
                } else {
                    return null
                }
                return PointF(
                    x!!.trim { it <= ' ' }.toFloat(),
                    y!!.trim { it <= ' ' }.toFloat()
                )
            }

            private fun parseAlignmentOverride(braceContents: String): @SsaAlignment Int {
                val matcher: Matcher = ALIGNMENT_OVERRIDE_PATTERN.matcher(braceContents)
                return if (matcher.find())
                    parseAlignment(matcher.group(1)!!)
                else
                    SSA_ALIGNMENT_UNKNOWN
            }
        }
    }

    companion object {
        private const val TAG = "SsaStyle"

        // The numbering follows the ASS (v4+) spec (i.e. the points on the number pad).
        const val SSA_ALIGNMENT_UNKNOWN: Int = -1
        const val SSA_ALIGNMENT_BOTTOM_LEFT: Int = 1
        const val SSA_ALIGNMENT_BOTTOM_CENTER: Int = 2
        const val SSA_ALIGNMENT_BOTTOM_RIGHT: Int = 3
        const val SSA_ALIGNMENT_MIDDLE_LEFT: Int = 4
        const val SSA_ALIGNMENT_MIDDLE_CENTER: Int = 5
        const val SSA_ALIGNMENT_MIDDLE_RIGHT: Int = 6
        const val SSA_ALIGNMENT_TOP_LEFT: Int = 7
        const val SSA_ALIGNMENT_TOP_CENTER: Int = 8
        const val SSA_ALIGNMENT_TOP_RIGHT: Int = 9

        // The numbering follows the ASS (v4+) spec.
        const val SSA_BORDER_STYLE_UNKNOWN: Int = -1
        const val SSA_BORDER_STYLE_OUTLINE: Int = 1
        const val SSA_BORDER_STYLE_BOX: Int = 3

        fun fromStyleLine(styleLine: String, format: Format): SsaStyle? {
            Preconditions.checkArgument(styleLine.startsWith(SsaParser.STYLE_LINE_PREFIX))
            val styleValues =
                TextUtils.split(styleLine.substring(SsaParser.STYLE_LINE_PREFIX.length), ",")
            if (styleValues.size != format.length) {
                w(
                    TAG,
                    formatInvariant(
                        "Skipping malformed 'Style:' line (expected %s values, found %s): '%s'",
                        format.length, styleValues.size, styleLine
                    )
                )
                return null
            }
            try {
                return SsaStyle(
                    styleValues[format.nameIndex]!!.trim { it <= ' ' },
                    if (format.alignmentIndex != C.INDEX_UNSET)
                        parseAlignment(styleValues[format.alignmentIndex]!!.trim { it <= ' ' })
                    else
                        SSA_ALIGNMENT_UNKNOWN,
                    if (format.primaryColorIndex != C.INDEX_UNSET)
                        parseColor(styleValues[format.primaryColorIndex]!!.trim { it <= ' ' })
                    else
                        null,
                    if (format.outlineColorIndex != C.INDEX_UNSET)
                        parseColor(styleValues[format.outlineColorIndex]!!.trim { it <= ' ' })
                    else
                        null,
                    if (format.fontSizeIndex != C.INDEX_UNSET)
                        parseFontSize(styleValues[format.fontSizeIndex]!!.trim { it <= ' ' })
                    else
                        Cue.DIMEN_UNSET,
                    format.boldIndex != C.INDEX_UNSET
                            && parseBooleanValue(styleValues[format.boldIndex]!!.trim { it <= ' ' }),
                    format.italicIndex != C.INDEX_UNSET
                            && parseBooleanValue(styleValues[format.italicIndex]!!.trim { it <= ' ' }),
                    format.underlineIndex != C.INDEX_UNSET
                            && parseBooleanValue(styleValues[format.underlineIndex]!!.trim { it <= ' ' }),
                    format.strikeoutIndex != C.INDEX_UNSET
                            && parseBooleanValue(styleValues[format.strikeoutIndex]!!.trim { it <= ' ' }),
                    if (format.borderStyleIndex != C.INDEX_UNSET)
                        parseBorderStyle(styleValues[format.borderStyleIndex]!!.trim { it <= ' ' })
                    else
                        SSA_BORDER_STYLE_UNKNOWN
                )
            } catch (e: RuntimeException) {
                w(TAG, "Skipping malformed 'Style:' line: '" + styleLine + "'", e)
                return null
            }
        }

        private fun parseAlignment(alignmentStr: String): @SsaAlignment Int {
            try {
                val alignment: @SsaAlignment Int = alignmentStr.trim { it <= ' ' }.toInt()
                if (isValidAlignment(alignment)) {
                    return alignment
                }
            } catch (e: NumberFormatException) {
                // Swallow the exception and return UNKNOWN below.
            }
            w(TAG, "Ignoring unknown alignment: " + alignmentStr)
            return SSA_ALIGNMENT_UNKNOWN
        }

        private fun isValidAlignment(alignment: @SsaAlignment Int): Boolean {
            when (alignment) {
                SSA_ALIGNMENT_BOTTOM_CENTER, SSA_ALIGNMENT_BOTTOM_LEFT, SSA_ALIGNMENT_BOTTOM_RIGHT, SSA_ALIGNMENT_MIDDLE_CENTER, SSA_ALIGNMENT_MIDDLE_LEFT, SSA_ALIGNMENT_MIDDLE_RIGHT, SSA_ALIGNMENT_TOP_CENTER, SSA_ALIGNMENT_TOP_LEFT, SSA_ALIGNMENT_TOP_RIGHT -> return true
                SSA_ALIGNMENT_UNKNOWN -> return false
                else -> return false
            }
        }

        private fun parseBorderStyle(borderStyleStr: String): @SsaBorderStyle Int {
            try {
                val borderStyle: @SsaBorderStyle Int = borderStyleStr.trim { it <= ' ' }.toInt()
                if (isValidBorderStyle(borderStyle)) {
                    return borderStyle
                }
            } catch (e: NumberFormatException) {
                // Swallow the exception and return UNKNOWN below.
            }
            w(TAG, "Ignoring unknown BorderStyle: " + borderStyleStr)
            return SSA_BORDER_STYLE_UNKNOWN
        }

        private fun isValidBorderStyle(alignment: @SsaBorderStyle Int): Boolean {
            when (alignment) {
                SSA_BORDER_STYLE_OUTLINE, SSA_BORDER_STYLE_BOX -> return true
                SSA_BORDER_STYLE_UNKNOWN -> return false
                else -> return false
            }
        }

        @ColorInt
        fun parseColor(ssaColorExpression: String): Int? {
            // We use a long because the value is an unsigned 32-bit number, so can be larger than
            // Integer.MAX_VALUE.
            val abgr: Long
            try {
                abgr =
                    if (ssaColorExpression.startsWith("&H") // Parse color from hex format (&HAABBGGRR).
                    ) ssaColorExpression.substring(2)
                        .toLong( 16) else ssaColorExpression.toLong()
                // Ensure only the bottom 4 bytes of abgr are set.
                Preconditions.checkArgument(abgr <= 0xFFFFFFFFL)
            } catch (e: IllegalArgumentException) {
                w(TAG, "Failed to parse color expression: '" + ssaColorExpression + "'", e)
                return null
            }
            // Convert ABGR to ARGB.
            val a = Ints.checkedCast(((abgr shr 24) and 0xFFL) xor 0xFFL) // Flip alpha.
            val b = Ints.checkedCast((abgr shr 16) and 0xFFL)
            val g = Ints.checkedCast((abgr shr 8) and 0xFFL)
            val r = Ints.checkedCast(abgr and 0xFFL)
            return Color.argb(a, r, g, b)
        }

        private fun parseFontSize(fontSize: String): Float {
            try {
                return fontSize.toFloat()
            } catch (e: NumberFormatException) {
                w(TAG, "Failed to parse font size: '" + fontSize + "'", e)
                return Cue.DIMEN_UNSET
            }
        }

        private fun parseBooleanValue(booleanValue: String): Boolean {
            try {
                val value = booleanValue.toInt()
                return value == 1 || value == -1
            } catch (e: NumberFormatException) {
                w(TAG, "Failed to parse boolean value: '" + booleanValue + "'", e)
                return false
            }
        }
    }
}
