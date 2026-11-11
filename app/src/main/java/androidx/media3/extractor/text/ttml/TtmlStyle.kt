
package androidx.media3.extractor.text.ttml

import android.graphics.Typeface
import android.text.Layout
import androidx.annotation.IntDef
import androidx.media3.common.text.TextAnnotation
import com.google.errorprone.annotations.CanIgnoreReturnValue

class TtmlStyle {
    var fontFamily: String? = null
        private set
    private var fontColor = 0
    private var hasFontColor = false
    private var backgroundColor = 0
    private var hasBackgroundColor = false
    private var linethrough:  Int
    private var underline:  Int
    private var bold:  Int
    private var italic:  Int
    var fontSizeUnit:  Int
        private set
    var fontSize: Float = 0f
        private set
    var id: String? = null
        private set
    var rubyType:  Int
        private set
    var rubyPosition:  Int
        private set
    var textAlign: Layout.Alignment? = null
        private set
    var multiRowAlign: Layout.Alignment? = null
        private set
    private var textCombine:  Int
    var textEmphasis: TextEmphasis? = null
        private set
    var shearPercentage: Float
        private set
    var origin: String? = null
        private set
    var extent: String? = null
        private set
    var displayAlign: String? = null
        private set

    init {
        linethrough = UNSPECIFIED
        underline = UNSPECIFIED
        bold = UNSPECIFIED
        italic = UNSPECIFIED
        fontSizeUnit = UNSPECIFIED
        rubyType = UNSPECIFIED
        rubyPosition = TextAnnotation.POSITION_UNKNOWN
        textCombine = UNSPECIFIED
        shearPercentage = UNSPECIFIED_SHEAR
    }

    val style:  Int
        
        get() {
            if (bold == UNSPECIFIED && italic == UNSPECIFIED) {
                return UNSPECIFIED
            }
            return (if (bold == ON) STYLE_BOLD else STYLE_NORMAL) or (if (italic == ON) STYLE_ITALIC else STYLE_NORMAL)
        }

    fun isLinethrough(): Boolean {
        return linethrough == ON
    }

    fun setLinethrough(linethrough: Boolean): TtmlStyle {
        this.linethrough = if (linethrough) ON else OFF
        return this
    }

    fun isUnderline(): Boolean {
        return underline == ON
    }

    fun setUnderline(underline: Boolean): TtmlStyle {
        this.underline = if (underline) ON else OFF
        return this
    }

    fun setBold(bold: Boolean): TtmlStyle {
        this.bold = if (bold) ON else OFF
        return this
    }

    fun setItalic(italic: Boolean): TtmlStyle {
        this.italic = if (italic) ON else OFF
        return this
    }

    fun setFontFamily(fontFamily: String?): TtmlStyle {
        this.fontFamily = fontFamily
        return this
    }

    fun getFontColor(): Int {
        check(hasFontColor) { "Font color has not been defined." }
        return fontColor
    }

    fun setFontColor(fontColor: Int): TtmlStyle {
        this.fontColor = fontColor
        hasFontColor = true
        return this
    }

    fun hasFontColor(): Boolean {
        return hasFontColor
    }

    fun getBackgroundColor(): Int {
        check(hasBackgroundColor) { "Background color has not been defined." }
        return backgroundColor
    }

    fun setBackgroundColor(backgroundColor: Int): TtmlStyle {
        this.backgroundColor = backgroundColor
        hasBackgroundColor = true
        return this
    }

    fun hasBackgroundColor(): Boolean {
        return hasBackgroundColor
    }

    fun setShearPercentage(shearPercentage: Float): TtmlStyle {
        this.shearPercentage = shearPercentage
        return this
    }

    fun chain(ancestor: TtmlStyle?): TtmlStyle {
        return inherit(ancestor, true)
    }

    fun inherit(ancestor: TtmlStyle?): TtmlStyle {
        return inherit(ancestor, false)
    }

    private fun inherit(ancestor: TtmlStyle?, chaining: Boolean): TtmlStyle {
        if (ancestor != null) {
            if (!hasFontColor && ancestor.hasFontColor) {
                setFontColor(ancestor.fontColor)
            }
            if (bold == UNSPECIFIED) {
                bold = ancestor.bold
            }
            if (italic == UNSPECIFIED) {
                italic = ancestor.italic
            }
            if (fontFamily == null && ancestor.fontFamily != null) {
                fontFamily = ancestor.fontFamily
            }
            if (linethrough == UNSPECIFIED) {
                linethrough = ancestor.linethrough
            }
            if (underline == UNSPECIFIED) {
                underline = ancestor.underline
            }
            if (rubyPosition == TextAnnotation.POSITION_UNKNOWN) {
                rubyPosition = ancestor.rubyPosition
            }
            if (textAlign == null && ancestor.textAlign != null) {
                textAlign = ancestor.textAlign
            }
            if (multiRowAlign == null && ancestor.multiRowAlign != null) {
                multiRowAlign = ancestor.multiRowAlign
            }
            if (textCombine == UNSPECIFIED) {
                textCombine = ancestor.textCombine
            }
            if (fontSizeUnit == UNSPECIFIED) {
                fontSizeUnit = ancestor.fontSizeUnit
                fontSize = ancestor.fontSize
            }
            if (textEmphasis == null) {
                textEmphasis = ancestor.textEmphasis
            }
            if (shearPercentage == UNSPECIFIED_SHEAR) {
                shearPercentage = ancestor.shearPercentage
            }
            if (origin == null) {
                origin = ancestor.origin
            }
            if (extent == null) {
                extent = ancestor.extent
            }
            if (displayAlign == null) {
                displayAlign = ancestor.displayAlign
            }
            // attributes not inherited as of http://www.w3.org/TR/ttml1/
            if (chaining && !hasBackgroundColor && ancestor.hasBackgroundColor) {
                setBackgroundColor(ancestor.backgroundColor)
            }
            if (chaining && rubyType == UNSPECIFIED && ancestor.rubyType != UNSPECIFIED) {
                rubyType = ancestor.rubyType
            }
        }
        return this
    }

    fun setId(id: String?): TtmlStyle {
        this.id = id
        return this
    }

    fun setRubyType(rubyType:  Int): TtmlStyle {
        this.rubyType = rubyType
        return this
    }

    fun setRubyPosition(position:  Int): TtmlStyle {
        this.rubyPosition = position
        return this
    }

    fun setTextAlign(textAlign: Layout.Alignment?): TtmlStyle {
        this.textAlign = textAlign
        return this
    }

    fun setMultiRowAlign(multiRowAlign: Layout.Alignment?): TtmlStyle {
        this.multiRowAlign = multiRowAlign
        return this
    }

    fun getTextCombine(): Boolean {
        return textCombine == ON
    }

    fun setTextCombine(combine: Boolean): TtmlStyle {
        this.textCombine = if (combine) ON else OFF
        return this
    }

    fun setTextEmphasis(textEmphasis: TextEmphasis?): TtmlStyle {
        this.textEmphasis = textEmphasis
        return this
    }

    fun setFontSize(fontSize: Float): TtmlStyle {
        this.fontSize = fontSize
        return this
    }

    fun setFontSizeUnit(fontSizeUnit: Int): TtmlStyle {
        this.fontSizeUnit = fontSizeUnit
        return this
    }

    fun setOrigin(origin: String?): TtmlStyle {
        this.origin = origin
        return this
    }

    fun setExtent(extent: String?): TtmlStyle {
        this.extent = extent
        return this
    }

    fun setDisplayAlign(displayAlign: String?): TtmlStyle {
        this.displayAlign = displayAlign
        return this
    }

    companion object {
        const val UNSPECIFIED: Int = -1
        const  val UNSPECIFIED_SHEAR: Float = Float.MAX_VALUE

        const  val STYLE_NORMAL: Int = Typeface.NORMAL
        const  val STYLE_BOLD: Int = Typeface.BOLD
        const  val STYLE_ITALIC: Int = Typeface.ITALIC
        const  val STYLE_BOLD_ITALIC: Int = Typeface.BOLD_ITALIC

        const val FONT_SIZE_UNIT_PIXEL: Int = 1
        const val FONT_SIZE_UNIT_EM: Int = 2
        const val FONT_SIZE_UNIT_PERCENT: Int = 3

        private const val OFF = 0
        private const val ON = 1

        const val RUBY_TYPE_CONTAINER: Int = 1
        const val RUBY_TYPE_BASE: Int = 2
        const val RUBY_TYPE_TEXT: Int = 3
        const val RUBY_TYPE_DELIMITER: Int = 4
    }
}
