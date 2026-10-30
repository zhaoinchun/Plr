
package androidx.media3.extractor.text.webvtt

import android.graphics.Typeface
import android.text.TextUtils
import androidx.annotation.ColorInt
import androidx.annotation.IntDef
import androidx.media3.common.text.TextAnnotation

import com.google.common.base.Ascii
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.Arrays

class WebvttCssStyle {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true,
        value = [UNSPECIFIED, STYLE_NORMAL, STYLE_BOLD, STYLE_ITALIC, STYLE_BOLD_ITALIC]
    )
    annotation class StyleFlags

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [UNSPECIFIED, FONT_SIZE_UNIT_PIXEL, FONT_SIZE_UNIT_EM, FONT_SIZE_UNIT_PERCENT])
    annotation class FontSizeUnit

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [UNSPECIFIED, OFF, ON])
    private annotation class OptionalBoolean

    // Selector properties.
    private var targetId = ""
    private var targetTag = ""
    private var targetClasses: MutableSet<String?>
    private var targetVoice = ""

    // Style properties.
    var fontFamily: String? = null
        private set

    @ColorInt
    private var fontColor = 0
    private var hasFontColor = false
    private var backgroundColor = 0
    private var hasBackgroundColor = false
    private var linethrough: @OptionalBoolean Int
    private var underline: @OptionalBoolean Int
    private var bold: @OptionalBoolean Int
    private var italic: @OptionalBoolean Int
    var fontSizeUnit: @FontSizeUnit Int
        private set
    var fontSize: Float = 0f
        private set
    var rubyPosition: @TextAnnotation.Position Int
        private set
    var combineUpright: Boolean = false
        private set

    init {
        targetClasses = mutableSetOf<String?>()
        linethrough = UNSPECIFIED
        underline = UNSPECIFIED
        bold = UNSPECIFIED
        italic = UNSPECIFIED
        fontSizeUnit = UNSPECIFIED
        rubyPosition = TextAnnotation.POSITION_UNKNOWN
    }

    fun setTargetId(targetId: String) {
        this.targetId = targetId
    }

    fun setTargetTagName(targetTag: String) {
        this.targetTag = targetTag
    }

    fun setTargetClasses(targetClasses: Array<String?>) {
        this.targetClasses = HashSet<String?>(Arrays.asList<String?>(*targetClasses))
    }

    fun setTargetVoice(targetVoice: String) {
        this.targetVoice = targetVoice
    }

    fun getSpecificityScore(
        id: String?, tag: String?, classes: MutableSet<String?>, voice: String?
    ): Int {
        if (targetId.isEmpty()
            && targetTag.isEmpty()
            && targetClasses.isEmpty()
            && targetVoice.isEmpty()
        ) {
            // The selector is universal. It matches with the minimum score if and only if the given
            // element is a whole cue.
            return if (TextUtils.isEmpty(tag)) 1 else 0
        }
        var score = 0
        score = updateScoreForMatch(score, targetId, id, 0x40000000)
        score = updateScoreForMatch(score, targetTag, tag, 2)
        score = updateScoreForMatch(score, targetVoice, voice, 4)
        if (score == -1 || !classes.containsAll(targetClasses)) {
            return 0
        } else {
            score += targetClasses.size * 4
        }
        return score
    }

    val style: @StyleFlags Int
        
        get() {
            if (bold == UNSPECIFIED && italic == UNSPECIFIED) {
                return UNSPECIFIED
            }
            return (if (bold == ON) STYLE_BOLD else STYLE_NORMAL) or (if (italic == ON) STYLE_ITALIC else STYLE_NORMAL)
        }

    fun isLinethrough(): Boolean {
        return linethrough == ON
    }

    fun setLinethrough(linethrough: Boolean): WebvttCssStyle {
        this.linethrough = if (linethrough) ON else OFF
        return this
    }

    fun isUnderline(): Boolean {
        return underline == ON
    }

    fun setUnderline(underline: Boolean): WebvttCssStyle {
        this.underline = if (underline) ON else OFF
        return this
    }

    fun setBold(bold: Boolean): WebvttCssStyle {
        this.bold = if (bold) ON else OFF
        return this
    }

    fun setItalic(italic: Boolean): WebvttCssStyle {
        this.italic = if (italic) ON else OFF
        return this
    }

    fun setFontFamily(fontFamily: String?): WebvttCssStyle {
        this.fontFamily = if (fontFamily == null) null else Ascii.toLowerCase(fontFamily)
        return this
    }

    fun getFontColor(): Int {
        check(hasFontColor) { "Font color not defined" }
        return fontColor
    }

    fun setFontColor(color: Int): WebvttCssStyle {
        this.fontColor = color
        hasFontColor = true
        return this
    }

    fun hasFontColor(): Boolean {
        return hasFontColor
    }

    fun getBackgroundColor(): Int {
        check(hasBackgroundColor) { "Background color not defined." }
        return backgroundColor
    }

    fun setBackgroundColor(backgroundColor: Int): WebvttCssStyle {
        this.backgroundColor = backgroundColor
        hasBackgroundColor = true
        return this
    }

    fun hasBackgroundColor(): Boolean {
        return hasBackgroundColor
    }

    fun setFontSize(fontSize: Float): WebvttCssStyle {
        this.fontSize = fontSize
        return this
    }

    fun setFontSizeUnit(unit: @FontSizeUnit Int): WebvttCssStyle {
        this.fontSizeUnit = unit
        return this
    }

    fun setRubyPosition(rubyPosition: @TextAnnotation.Position Int): WebvttCssStyle {
        this.rubyPosition = rubyPosition
        return this
    }

    fun setCombineUpright(enabled: Boolean): WebvttCssStyle {
        this.combineUpright = enabled
        return this
    }

    companion object {
        const    val UNSPECIFIED: Int = -1

        const  val STYLE_NORMAL: Int = Typeface.NORMAL
        const   val STYLE_BOLD: Int = Typeface.BOLD
        const   val STYLE_ITALIC: Int = Typeface.ITALIC
        const   val STYLE_BOLD_ITALIC: Int = Typeface.BOLD_ITALIC

        const val FONT_SIZE_UNIT_PIXEL: Int = 1
        const val FONT_SIZE_UNIT_EM: Int = 2
        const val FONT_SIZE_UNIT_PERCENT: Int = 3

        private const val OFF = 0
        private const val ON = 1

        private fun updateScoreForMatch(
            currentScore: Int, target: String, actual: String?, score: Int
        ): Int {
            if (target.isEmpty() || currentScore == -1) {
                return currentScore
            }
            return if (target == actual) currentScore + score else -1
        }
    }
}
