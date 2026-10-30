
package androidx.media3.extractor.text.webvtt

import android.text.TextUtils
import androidx.media3.common.text.TextAnnotation
import androidx.media3.common.util.ColorParser
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import com.google.common.base.Ascii
import com.google.common.base.Preconditions
import java.util.Arrays
import java.util.regex.Matcher
import java.util.regex.Pattern

internal class WebvttCssParser {
    private val styleInput: ParsableByteArray
    private val stringBuilder: StringBuilder

    init {
        styleInput = ParsableByteArray()
        stringBuilder = StringBuilder()
    }

    fun parseBlock(input: ParsableByteArray): MutableList<WebvttCssStyle> {
        stringBuilder.setLength(0)
        val initialInputPosition: Int = input.getPosition()
        skipStyleBlock(input)
        styleInput.reset(input.data, input.getPosition())
        styleInput.setPosition(initialInputPosition)

        val styles: MutableList<WebvttCssStyle> = ArrayList<WebvttCssStyle>()
        var selector: String?
        while ((parseSelector(styleInput, stringBuilder).also { selector = it }) != null) {
            if (RULE_START != parseNextToken(styleInput, stringBuilder)) {
                return styles
            }
            val style = WebvttCssStyle()
            applySelectorToStyle(style, selector!!)
            var token: String? = null
            var blockEndFound = false
            while (!blockEndFound) {
                val position = styleInput.getPosition()
                token = parseNextToken(styleInput, stringBuilder)
                blockEndFound = token == null || RULE_END == token
                if (!blockEndFound) {
                    styleInput.setPosition(position)
                    parseStyleDeclaration(styleInput, style, stringBuilder)
                }
            }
            // Check that the style rule ended correctly.
            if (RULE_END == token) {
                styles.add(style)
            }
        }
        return styles
    }

    private fun applySelectorToStyle(style: WebvttCssStyle, selector: String) {
        var selector = selector
        if (selector.isEmpty()) {
            return  // Universal selector.
        }
        val voiceStartIndex = selector.indexOf('[')
        if (voiceStartIndex != -1) {
            val matcher: Matcher = VOICE_NAME_PATTERN.matcher(selector.substring(voiceStartIndex))
            if (matcher.matches()) {
                style.setTargetVoice(matcher.group(1)!!)
            }
            selector = selector.substring(0, voiceStartIndex)
        }
        val classDivision = Util.split(selector, "\\.")
        val tagAndIdDivision = classDivision[0]
        val idPrefixIndex = tagAndIdDivision.indexOf('#')
        if (idPrefixIndex != -1) {
            style.setTargetTagName(tagAndIdDivision.substring(0, idPrefixIndex))
            style.setTargetId(tagAndIdDivision.substring(idPrefixIndex + 1)) // We discard the '#'.
        } else {
            style.setTargetTagName(tagAndIdDivision)
        }
        if (classDivision.size > 1) {
            style.setTargetClasses(
                Arrays.copyOfRange(
                    classDivision,
                    1,
                    classDivision.size
                )
            )
        }
    }

    companion object {
        private const val TAG = "WebvttCssParser"

        private const val RULE_START = "{"
        private const val RULE_END = "}"
        private const val PROPERTY_COLOR = "color"
        private const val PROPERTY_BGCOLOR = "background-color"
        private const val PROPERTY_FONT_FAMILY = "font-family"
        private const val PROPERTY_FONT_WEIGHT = "font-weight"
        private const val PROPERTY_FONT_SIZE = "font-size"
        private const val PROPERTY_RUBY_POSITION = "ruby-position"
        private const val VALUE_OVER = "over"
        private const val VALUE_UNDER = "under"
        private const val PROPERTY_TEXT_COMBINE_UPRIGHT = "text-combine-upright"
        private const val VALUE_ALL = "all"
        private const val VALUE_DIGITS = "digits"
        private const val PROPERTY_TEXT_DECORATION = "text-decoration"
        private const val VALUE_BOLD = "bold"
        private const val VALUE_UNDERLINE = "underline"
        private const val PROPERTY_FONT_STYLE = "font-style"
        private const val VALUE_ITALIC = "italic"

        private val VOICE_NAME_PATTERN: Pattern = Pattern.compile("\\[voice=\"([^\"]*)\"\\]")
        private val FONT_SIZE_PATTERN: Pattern =
            Pattern.compile("^((?:[0-9]*\\.)?[0-9]+)(px|em|%)$")

        private fun parseSelector(input: ParsableByteArray, stringBuilder: StringBuilder): String? {
            skipWhitespaceAndComments(input)
            if (input.bytesLeft() < 5) {
                return null
            }
            val cueSelector: String = input.readString(5)
            if ("::cue" != cueSelector) {
                return null
            }
            val position: Int = input.getPosition()
            var token: String? = parseNextToken(input, stringBuilder)
            if (token == null) {
                return null
            }
            if (RULE_START == token) {
                input.setPosition(position)
                return ""
            }
            var target: String? = null
            if ("(" == token) {
                target = readCueTarget(input)
            }
            token = parseNextToken(input, stringBuilder)
            if (")" != token) {
                return null
            }
            return target
        }

        private fun readCueTarget(input: ParsableByteArray): String {
            var position: Int = input.getPosition()
            val limit: Int = input.limit()
            var cueTargetEndFound = false
            while (position < limit && !cueTargetEndFound) {
                val c = Char(input.data[position++].toUShort())
                cueTargetEndFound = c == ')'
            }
            return input.readString(--position - input.getPosition()).trim { it <= ' ' }
            // --offset to return ')' to the input.
        }

        private fun parseStyleDeclaration(
            input: ParsableByteArray, style: WebvttCssStyle, stringBuilder: StringBuilder
        ) {
            skipWhitespaceAndComments(input)
            val property: String = parseIdentifier(input, stringBuilder)
            if (property.isEmpty()) {
                return
            }
            if (":" != parseNextToken(input, stringBuilder)) {
                return
            }
            skipWhitespaceAndComments(input)
            val value: String? = parsePropertyValue(input, stringBuilder)
            if (value == null || value.isEmpty()) {
                return
            }
            val position: Int = input.getPosition()
            val token: String? = parseNextToken(input, stringBuilder)
            if (";" == token) {
                // The style declaration is well formed.
            } else if (RULE_END == token) {
                // The style declaration is well formed and we can go on, but the closing bracket had to be
                // fed back.
                input.setPosition(position)
            } else {
                // The style declaration is not well formed.
                return
            }
            // At this point we have a presumably valid declaration, we need to parse it and fill the style.
            if (PROPERTY_COLOR == property) {
                style.setFontColor(ColorParser.parseCssColor(value))
            } else if (PROPERTY_BGCOLOR == property) {
                style.setBackgroundColor(ColorParser.parseCssColor(value))
            } else if (PROPERTY_RUBY_POSITION == property) {
                if (VALUE_OVER == value) {
                    style.setRubyPosition(TextAnnotation.POSITION_BEFORE)
                } else if (VALUE_UNDER == value) {
                    style.setRubyPosition(TextAnnotation.POSITION_AFTER)
                }
            } else if (PROPERTY_TEXT_COMBINE_UPRIGHT == property) {
                style.setCombineUpright(VALUE_ALL == value || value.startsWith(VALUE_DIGITS))
            } else if (PROPERTY_TEXT_DECORATION == property) {
                if (VALUE_UNDERLINE == value) {
                    style.setUnderline(true)
                }
            } else if (PROPERTY_FONT_FAMILY == property) {
                style.setFontFamily(value)
            } else if (PROPERTY_FONT_WEIGHT == property) {
                if (VALUE_BOLD == value) {
                    style.setBold(true)
                }
            } else if (PROPERTY_FONT_STYLE == property) {
                if (VALUE_ITALIC == value) {
                    style.setItalic(true)
                }
            } else if (PROPERTY_FONT_SIZE == property) {
                parseFontSize(value, style)
            }
            // TODO: Fill remaining supported styles.
        }

        // Visible for testing.
        
        fun skipWhitespaceAndComments(input: ParsableByteArray) {
            var skipping = true
            while (input.bytesLeft() > 0 && skipping) {
                skipping = maybeSkipWhitespace(input) || maybeSkipComment(input)
            }
        }

        // Visible for testing.
        fun parseNextToken(input: ParsableByteArray, stringBuilder: StringBuilder): String? {
            skipWhitespaceAndComments(input)
            if (input.bytesLeft() == 0) {
                return null
            }
            val identifier: String = parseIdentifier(input, stringBuilder)
            if (!identifier.isEmpty()) {
                return identifier
            }
            // We found a delimiter.
            return "" + input.readUnsignedByte().toChar()
        }

        private fun maybeSkipWhitespace(input: ParsableByteArray): Boolean {
            when (peekCharAtPosition(input, input.getPosition())) {
                '\t', '\r', '\n', '\u000C', ' ' -> {
                    input.skipBytes(1)
                    return true
                }

                else -> return false
            }
        }

        // Visible for testing.
        
        fun skipStyleBlock(input: ParsableByteArray) {
            // The style block cannot contain empty lines, so we assume the input ends when a empty line
            // is found.
            var line: String?
            do {
                line = input.readLine()
            } while (!TextUtils.isEmpty(line))
        }

        private fun peekCharAtPosition(input: ParsableByteArray, position: Int): Char {
            return Char(input.data[position].toUShort())
        }

        private fun parsePropertyValue(
            input: ParsableByteArray,
            stringBuilder: StringBuilder
        ): String? {
            val expressionBuilder = StringBuilder()
            var token: String?
            var position: Int
            var expressionEndFound = false
            // TODO: Add support for "Strings in quotes with spaces".
            while (!expressionEndFound) {
                position = input.getPosition()
                token = parseNextToken(input, stringBuilder)
                if (token == null) {
                    // Syntax error.
                    return null
                }
                if (RULE_END == token || ";" == token) {
                    input.setPosition(position)
                    expressionEndFound = true
                } else {
                    expressionBuilder.append(token)
                }
            }
            return expressionBuilder.toString()
        }

        private fun maybeSkipComment(input: ParsableByteArray): Boolean {
            var position: Int = input.getPosition()
            var limit: Int = input.limit()
            val data: ByteArray = input.data
            if (position + 2 <= limit && data[position++] == '/'.code.toByte() && data[position++] == '*'.code.toByte()) {
                while (position + 1 < limit) {
                    val skippedChar = Char(data[position++].toUShort())
                    if (skippedChar == '*') {
                        if ((Char(data[position].toUShort())) == '/') {
                            position++
                            limit = position
                        }
                    }
                }
                input.skipBytes(limit - input.getPosition())
                return true
            }
            return false
        }

        private fun parseIdentifier(
            input: ParsableByteArray,
            stringBuilder: StringBuilder
        ): String {
            stringBuilder.setLength(0)
            var position: Int = input.getPosition()
            val limit: Int = input.limit()
            var identifierEndFound = false
            while (position < limit && !identifierEndFound) {
                val c = Char(input.data[position].toUShort())
                if ((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '#' || c == '-' || c == '.' || c == '_'
                ) {
                    position++
                    stringBuilder.append(c)
                } else {
                    identifierEndFound = true
                }
            }
            input.skipBytes(position - input.getPosition())
            return stringBuilder.toString()
        }

        private fun parseFontSize(fontSize: String, style: WebvttCssStyle) {
            val matcher: Matcher = FONT_SIZE_PATTERN.matcher(Ascii.toLowerCase(fontSize))
            if (!matcher.matches()) {
                Log.w(TAG, "Invalid font-size: '" + fontSize + "'.")
                return
            }
            val unit = matcher.group(2)!!
            when (unit) {
                "px" -> style.setFontSizeUnit(WebvttCssStyle.FONT_SIZE_UNIT_PIXEL)
                "em" -> style.setFontSizeUnit(WebvttCssStyle.FONT_SIZE_UNIT_EM)
                "%" -> style.setFontSizeUnit(WebvttCssStyle.FONT_SIZE_UNIT_PERCENT)
                else ->         // this line should never be reached because when the fontSize matches the FONT_SIZE_PATTERN
                    // unit must be one of: px, em, %
                    throw IllegalStateException()
            }
            style.setFontSize(matcher.group(1)!!.toFloat())
        }
    }
}
