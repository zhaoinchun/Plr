package androidx.media3.extractor.text.webvtt

import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.annotation.IntDef
import androidx.annotation.VisibleForTesting
import androidx.media3.common.text.Cue
import androidx.media3.common.text.HorizontalTextInVerticalContextSpan
import androidx.media3.common.text.RubySpan
import androidx.media3.common.text.SpanUtil
import androidx.media3.common.text.TextAnnotation
import androidx.media3.common.text.VoiceSpan
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util.split
import androidx.media3.common.util.Util.splitAtFirst
import androidx.media3.extractor.text.webvtt.WebvttParserUtil.parsePercentage
import androidx.media3.extractor.text.webvtt.WebvttParserUtil.parseTimestampUs
import com.google.common.base.Preconditions

import java.util.ArrayDeque
import java.util.Collections
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.min

object WebvttCueParser {

    private const val TEXT_ALIGNMENT_START = 1

    private const val TEXT_ALIGNMENT_CENTER = 2

    private const val TEXT_ALIGNMENT_END = 3

    private const val TEXT_ALIGNMENT_LEFT = 4

    private const val TEXT_ALIGNMENT_RIGHT = 5

    val CUE_HEADER_PATTERN: Pattern = Pattern.compile("^(\\S+)\\s+-->\\s+(\\S+)((?:.|\\f)*+)?$")
    private val CUE_SETTING_PATTERN: Pattern = Pattern.compile("(\\S+?):(\\S+)")

    private const val CHAR_LESS_THAN = '<'
    private const val CHAR_GREATER_THAN = '>'
    private const val CHAR_SLASH = '/'
    private const val CHAR_AMPERSAND = '&'
    private const val CHAR_SEMI_COLON = ';'
    private const val CHAR_SPACE = ' '

    private const val ENTITY_LESS_THAN = "lt"
    private const val ENTITY_GREATER_THAN = "gt"
    private const val ENTITY_AMPERSAND = "amp"
    private const val ENTITY_NON_BREAK_SPACE = "nbsp"

    private const val TAG_BOLD = "b"
    private const val TAG_CLASS = "c"
    private const val TAG_ITALIC = "i"
    private const val TAG_LANG = "lang"
    private const val TAG_RUBY = "ruby"
    private const val TAG_RUBY_TEXT = "rt"
    private const val TAG_UNDERLINE = "u"
    private const val TAG_VOICE = "v"

    private val STYLE_BOLD = Typeface.BOLD
    private val STYLE_ITALIC = Typeface.ITALIC

    const val DEFAULT_POSITION: Float = 0.5f

    private const val TAG = "WebvttCueParser"

    private var DEFAULT_TEXT_COLORS: MutableMap<String, Int>? = null

    init {
        val defaultColors: MutableMap<String?, Int?> = HashMap<String?, Int?>()
        defaultColors.put("white", Color.rgb(255, 255, 255))
        defaultColors.put("lime", Color.rgb(0, 255, 0))
        defaultColors.put("cyan", Color.rgb(0, 255, 255))
        defaultColors.put("red", Color.rgb(255, 0, 0))
        defaultColors.put("yellow", Color.rgb(255, 255, 0))
        defaultColors.put("magenta", Color.rgb(255, 0, 255))
        defaultColors.put("blue", Color.rgb(0, 0, 255))
        defaultColors.put("black", Color.rgb(0, 0, 0))
        DEFAULT_TEXT_COLORS = Collections.unmodifiableMap<String, Int>(defaultColors)
    }

    private var DEFAULT_BACKGROUND_COLORS: MutableMap<String, Int>? = null

    init {
        val defaultBackgroundColors: MutableMap<String?, Int?> = HashMap<String?, Int?>()
        defaultBackgroundColors.put("bg_white", Color.rgb(255, 255, 255))
        defaultBackgroundColors.put("bg_lime", Color.rgb(0, 255, 0))
        defaultBackgroundColors.put("bg_cyan", Color.rgb(0, 255, 255))
        defaultBackgroundColors.put("bg_red", Color.rgb(255, 0, 0))
        defaultBackgroundColors.put("bg_yellow", Color.rgb(255, 255, 0))
        defaultBackgroundColors.put("bg_magenta", Color.rgb(255, 0, 255))
        defaultBackgroundColors.put("bg_blue", Color.rgb(0, 0, 255))
        defaultBackgroundColors.put("bg_black", Color.rgb(0, 0, 0))
        DEFAULT_BACKGROUND_COLORS =
            Collections.unmodifiableMap<String, Int>(defaultBackgroundColors)
    }

    fun parseCue(
        webvttData: ParsableByteArray,
        styles: MutableList<WebvttCssStyle>
    ): WebvttCueInfo? {
        val firstLine = webvttData.readLine()
        if (firstLine == null) {
            return null
        }
        var cueHeaderMatcher = CUE_HEADER_PATTERN.matcher(firstLine)
        if (cueHeaderMatcher.matches()) {
            // We have found the timestamps in the first line. No id present.
            return parseCue(null, cueHeaderMatcher, webvttData, styles)
        }
        // The first line is not the timestamps, but could be the cue id.
        val secondLine = webvttData.readLine()
        if (secondLine == null) {
            return null
        }
        cueHeaderMatcher = CUE_HEADER_PATTERN.matcher(secondLine)
        if (cueHeaderMatcher.matches()) {
            // We can do the rest of the parsing, including the id.
            return parseCue(firstLine.trim { it <= ' ' }, cueHeaderMatcher, webvttData, styles)
        }
        return null
    }

    fun parseCueSettingsList(cueSettingsList: String): Cue.Builder {
        val builder = WebvttCueInfoBuilder()
        parseCueSettingsList(cueSettingsList, builder)
        return builder.toCueBuilder()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    fun newCueForText(text: CharSequence?): Cue {
        val infoBuilder = WebvttCueInfoBuilder()
        infoBuilder.text = text
        return infoBuilder.toCueBuilder().build()
    }

    fun parseCueText(
        id: String?, markup: String, styles: MutableList<WebvttCssStyle>
    ): SpannedString? {
        val spannedText = SpannableStringBuilder()
        val startTagStack = ArrayDeque<StartTag>()
        var pos = 0
        val nestedElements: MutableList<Element> = ArrayList<Element>()
        while (pos < markup.length) {
            val curr = markup.get(pos)
            when (curr) {
                CHAR_LESS_THAN -> {
                    if (pos + 1 >= markup.length) {
                        pos++
                        break // avoid ArrayOutOfBoundsException
                    }
                    val ltPos = pos
                    val isClosingTag = markup.get(ltPos + 1) == CHAR_SLASH
                    pos = findEndOfTag(markup, ltPos + 1)
                    val isVoidTag = markup.get(pos - 2) == CHAR_SLASH
                    val fullTagExpression =
                        markup.substring(
                            ltPos + (if (isClosingTag) 2 else 1),
                            if (isVoidTag) pos - 2 else pos - 1
                        )
                    if (fullTagExpression.trim { it <= ' ' }.isEmpty()) {
                        continue
                    }
                    val tagName = getTagName(fullTagExpression)
                    if (!isSupportedTag(tagName)) {
                        continue
                    }
                    if (isClosingTag) {
                        var startTag: StartTag
                        do {
                            if (startTagStack.isEmpty()) {
                                break
                            }
                            startTag = startTagStack.pop()
                            applySpansForTag(id, startTag, nestedElements, spannedText, styles)
                            if (!startTagStack.isEmpty()) {
                                nestedElements.add(Element(startTag, spannedText.length))
                            } else {
                                nestedElements.clear()
                            }
                        } while (startTag.name != tagName)
                    } else if (!isVoidTag) {
                        startTagStack.push(
                            StartTag.buildStartTag(
                                fullTagExpression,
                                spannedText.length
                            )
                        )
                    }
                }

                CHAR_AMPERSAND -> {
                    val semiColonEndIndex = markup.indexOf(CHAR_SEMI_COLON, pos + 1)
                    val spaceEndIndex = markup.indexOf(CHAR_SPACE, pos + 1)
                    val entityEndIndex =
                        if (semiColonEndIndex == -1)
                            spaceEndIndex
                        else
                            (if (spaceEndIndex == -1)
                                semiColonEndIndex
                            else
                                min(semiColonEndIndex, spaceEndIndex))
                    if (entityEndIndex != -1) {
                        applyEntity(markup.substring(pos + 1, entityEndIndex), spannedText)
                        if (entityEndIndex == spaceEndIndex) {
                            spannedText.append(" ")
                        }
                        pos = entityEndIndex + 1
                    } else {
                        spannedText.append(curr)
                        pos++
                    }
                }

                else -> {
                    spannedText.append(curr)
                    pos++
                }
            }
        }
        // apply unclosed tags
        while (!startTagStack.isEmpty()) {
            applySpansForTag(id, startTagStack.pop(), nestedElements, spannedText, styles)
        }
        applySpansForTag(
            id,
            StartTag.buildWholeCueVirtualTag(),  
            mutableListOf<Element>(),
            spannedText,
            styles
        )
        return SpannedString.valueOf(spannedText)
    }

    // Internal methods
    private fun parseCue(
        id: String?,
        cueHeaderMatcher: Matcher,
        webvttData: ParsableByteArray,
        styles: MutableList<WebvttCssStyle>
    ): WebvttCueInfo? {
        val builder = WebvttCueInfoBuilder()
        try {
            // Parse the cue start and end times.
            builder.startTimeUs =
                parseTimestampUs(cueHeaderMatcher.group(1)!!)
            builder.endTimeUs =
                parseTimestampUs(cueHeaderMatcher.group(2)!!)
        } catch (e: IllegalArgumentException) {
            w(TAG, "Skipping cue with bad header: " + cueHeaderMatcher.group())
            return null
        }

        parseCueSettingsList(
            cueHeaderMatcher.group(3)!!,
            builder
        )

        // Parse the cue text.
        val textBuilder = StringBuilder()
        var line = webvttData.readLine()
        while (!TextUtils.isEmpty(line)
        ) {
            if (textBuilder.length > 0) {
                textBuilder.append("\n")
            }
            textBuilder.append(line!!.trim { it <= ' ' })
            line = webvttData.readLine()
        }
        builder.text = parseCueText(id, textBuilder.toString(), styles)
        return builder.build()
    }

    private fun parseCueSettingsList(cueSettingsList: String, builder: WebvttCueInfoBuilder) {
        // Parse the cue settings list.
        val cueSettingMatcher = CUE_SETTING_PATTERN.matcher(cueSettingsList)

        while (cueSettingMatcher.find()) {
            val name = cueSettingMatcher.group(1)!!
            val value = cueSettingMatcher.group(2)!!
            try {
                if ("line" == name) {
                    parseLineAttribute(value, builder)
                } else if ("align" == name) {
                    builder.textAlignment = parseTextAlignment(value)
                } else if ("position" == name) {
                    parsePositionAttribute(value, builder)
                } else if ("size" == name) {
                    builder.size = parsePercentage(value)
                } else if ("vertical" == name) {
                    builder.verticalType = parseVerticalAttribute(value)
                } else {
                    w(TAG, "Unknown cue setting " + name + ":" + value)
                }
            } catch (e: NumberFormatException) {
                w(TAG, "Skipping bad cue setting: " + cueSettingMatcher.group())
            }
        }
    }

    private fun parseLineAttribute(s: String, builder: WebvttCueInfoBuilder) {
        var s = s
        val commaIndex = s.indexOf(',')
        if (commaIndex != -1) {
            builder.lineAnchor = parseLineAnchor(s.substring(commaIndex + 1))
            s = s.substring(0, commaIndex)
        }
        if (s.endsWith("%")) {
            builder.line = parsePercentage(s)
            builder.lineType = Cue.LINE_TYPE_FRACTION
        } else {
            builder.line = s.toInt().toFloat()
            builder.lineType = Cue.LINE_TYPE_NUMBER
        }
    }

    @Cue.AnchorType
    private fun parseLineAnchor(s: String): @Cue.AnchorType Int {
        when (s) {
            "start" -> return Cue.ANCHOR_TYPE_START
            "center", "middle" -> return Cue.ANCHOR_TYPE_MIDDLE
            "end" -> return Cue.ANCHOR_TYPE_END
            else -> {
                w(TAG, "Invalid anchor value: " + s)
                return Cue.TYPE_UNSET
            }
        }
    }

    private fun parsePositionAttribute(s: String, builder: WebvttCueInfoBuilder) {
        var s = s
        val commaIndex = s.indexOf(',')
        if (commaIndex != -1) {
            builder.positionAnchor = parsePositionAnchor(s.substring(commaIndex + 1))
            s = s.substring(0, commaIndex)
        }
        builder.position = parsePercentage(s)
    }

    @Cue.AnchorType
    private fun parsePositionAnchor(s: String): @Cue.AnchorType Int {
        when (s) {
            "line-left", "start" -> return Cue.ANCHOR_TYPE_START
            "center", "middle" -> return Cue.ANCHOR_TYPE_MIDDLE
            "line-right", "end" -> return Cue.ANCHOR_TYPE_END
            else -> {
                w(TAG, "Invalid anchor value: " + s)
                return Cue.TYPE_UNSET
            }
        }
    }

    @Cue.VerticalType
    private fun parseVerticalAttribute(s: String): @Cue.VerticalType Int {
        when (s) {
            "rl" -> return Cue.VERTICAL_TYPE_RL
            "lr" -> return Cue.VERTICAL_TYPE_LR
            else -> {
                w(TAG, "Invalid 'vertical' value: " + s)
                return Cue.TYPE_UNSET
            }
        }
    }

    private fun parseTextAlignment(s: String): @TextAlignment Int {
        when (s) {
            "start" -> return TEXT_ALIGNMENT_START
            "left" -> return TEXT_ALIGNMENT_LEFT
            "center", "middle" -> return TEXT_ALIGNMENT_CENTER
            "end" -> return TEXT_ALIGNMENT_END
            "right" -> return TEXT_ALIGNMENT_RIGHT
            else -> {
                w(TAG, "Invalid alignment value: " + s)
                // Default value: https://www.w3.org/TR/webvtt1/#webvtt-cue-text-alignment
                return TEXT_ALIGNMENT_CENTER
            }
        }
    }

    private fun findEndOfTag(markup: String, startPos: Int): Int {
        val index = markup.indexOf(CHAR_GREATER_THAN, startPos)
        return if (index == -1) markup.length else index + 1
    }

    private fun applyEntity(entity: String, spannedText: SpannableStringBuilder) {
        when (entity) {
            ENTITY_LESS_THAN -> spannedText.append('<')
            ENTITY_GREATER_THAN -> spannedText.append('>')
            ENTITY_NON_BREAK_SPACE -> spannedText.append(' ')
            ENTITY_AMPERSAND -> spannedText.append('&')
            else -> w(TAG, "ignoring unsupported entity: '&" + entity + ";'")
        }
    }

    private fun isSupportedTag(tagName: String): Boolean {
        when (tagName) {
            TAG_BOLD, TAG_CLASS, TAG_ITALIC, TAG_LANG, TAG_RUBY, TAG_RUBY_TEXT, TAG_UNDERLINE, TAG_VOICE -> return true
            else -> return false
        }
    }

    private fun applySpansForTag(
        cueId: String?,
        startTag: StartTag,
        nestedElements: MutableList<Element>,
        text: SpannableStringBuilder,
        styles: MutableList<WebvttCssStyle>
    ) {
        val start = startTag.position
        val end = text.length

        when (startTag.name) {
            TAG_BOLD -> text.setSpan(
                StyleSpan(STYLE_BOLD),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            TAG_ITALIC -> text.setSpan(
                StyleSpan(STYLE_ITALIC),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            TAG_RUBY -> applyRubySpans(text, cueId, startTag, nestedElements, styles)
            TAG_UNDERLINE -> text.setSpan(
                UnderlineSpan(),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            TAG_CLASS -> applyDefaultColors(text, startTag.classes, start, end)
            TAG_VOICE -> applyVoiceSpan(text, startTag.voice, start, end)
            TAG_LANG, "" -> {}
            else -> return
        }

        val applicableStyles: MutableList<StyleMatch> =
            getApplicableStyles(styles, cueId, startTag)
        for (i in applicableStyles.indices) {
            applyStyleToText(text, applicableStyles[i]!!.style, start, end)
        }
    }

    private fun applyRubySpans(
        text: SpannableStringBuilder,
        cueId: String?,
        startTag: StartTag,
        nestedElements: MutableList<Element>,
        styles: MutableList<WebvttCssStyle>
    ) {
        val rubyTagPosition: @TextAnnotation.Position Int = getRubyPosition(styles, cueId, startTag)
        val sortedNestedElements: MutableList<Element> = ArrayList<Element>(nestedElements.size)
        sortedNestedElements.addAll(nestedElements)
        Collections.sort<Element?>(sortedNestedElements, Element.BY_START_POSITION_ASC)
        var deletedCharCount = 0
        var lastRubyTextEnd = startTag.position
        for (i in sortedNestedElements.indices) {
            if (TAG_RUBY_TEXT != sortedNestedElements[i].startTag.name) {
                continue
            }
            val rubyTextElement = sortedNestedElements[i]
            // Use the <rt> element's ruby-position if set, otherwise the <ruby> element's and otherwise
            // default to OVER.
            val rubyPosition: @TextAnnotation.Position Int =
                firstKnownRubyPosition(
                    getRubyPosition(styles, cueId, rubyTextElement.startTag),
                    rubyTagPosition,
                    TextAnnotation.POSITION_BEFORE
                )
            // Move the rubyText from spannedText into the RubySpan.
            val adjustedRubyTextStart = rubyTextElement.startTag.position - deletedCharCount
            val adjustedRubyTextEnd = rubyTextElement.endPosition - deletedCharCount
            val rubyText = text.subSequence(adjustedRubyTextStart, adjustedRubyTextEnd)
            text.delete(adjustedRubyTextStart, adjustedRubyTextEnd)
            text.setSpan(
                RubySpan(rubyText.toString(), rubyPosition),
                lastRubyTextEnd,
                adjustedRubyTextStart,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            deletedCharCount += rubyText.length
            // The ruby text has been deleted, so new-start == old-end.
            lastRubyTextEnd = adjustedRubyTextStart
        }
    }

    private fun getRubyPosition(
        styles: MutableList<WebvttCssStyle>, cueId: String?, startTag: StartTag
    ): @TextAnnotation.Position Int {
        val styleMatches: MutableList<StyleMatch> = getApplicableStyles(styles, cueId, startTag)
        for (i in styleMatches.indices) {
            val style = styleMatches[i]!!.style
            if (style.rubyPosition != TextAnnotation.POSITION_UNKNOWN) {
                return style.rubyPosition
            }
        }
        return TextAnnotation.POSITION_UNKNOWN
    }

    private fun firstKnownRubyPosition(
        position1: @TextAnnotation.Position Int,
        position2: @TextAnnotation.Position Int,
        position3: @TextAnnotation.Position Int
    ): @TextAnnotation.Position Int {
        if (position1 != TextAnnotation.POSITION_UNKNOWN) {
            return position1
        }
        if (position2 != TextAnnotation.POSITION_UNKNOWN) {
            return position2
        }
        if (position3 != TextAnnotation.POSITION_UNKNOWN) {
            return position3
        }
        throw IllegalArgumentException()
    }

    private fun applyDefaultColors(
        text: SpannableStringBuilder, classes: MutableSet<String?>, start: Int, end: Int
    ) {
        for (className in classes) {
            if (DEFAULT_TEXT_COLORS!!.containsKey(className)) {
                val color: Int = DEFAULT_TEXT_COLORS!!.get(className)!!
                text.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else if (DEFAULT_BACKGROUND_COLORS!!.containsKey(className)) {
                val color: Int = DEFAULT_BACKGROUND_COLORS!!.get(className)!!
                text.setSpan(
                    BackgroundColorSpan(color),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun applyVoiceSpan(
        text: SpannableStringBuilder, voice: String?, start: Int, end: Int
    ) {
        text.setSpan(VoiceSpan(voice), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun applyStyleToText(
        spannedText: SpannableStringBuilder, style: WebvttCssStyle?, start: Int, end: Int
    ) {
        if (style == null) {
            return
        }
        if (style.style != WebvttCssStyle.UNSPECIFIED) {
            SpanUtil.addOrReplaceSpan(
                spannedText,
                StyleSpan(style.style),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (style.isLinethrough()) {
            spannedText.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (style.isUnderline()) {
            spannedText.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (style.hasFontColor()) {
            SpanUtil.addOrReplaceSpan(
                spannedText,
                ForegroundColorSpan(style.getFontColor()),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (style.hasBackgroundColor()) {
            SpanUtil.addOrReplaceSpan(
                spannedText,
                BackgroundColorSpan(style.getBackgroundColor()),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (style.fontFamily != null) {
            SpanUtil.addOrReplaceSpan(
                spannedText,
                TypefaceSpan(style.fontFamily),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        when (style.fontSizeUnit) {
            WebvttCssStyle.FONT_SIZE_UNIT_PIXEL -> SpanUtil.addOrReplaceSpan(
                spannedText,
                AbsoluteSizeSpan(style.fontSize.toInt(), true),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            WebvttCssStyle.FONT_SIZE_UNIT_EM -> SpanUtil.addOrReplaceSpan(
                spannedText,
                RelativeSizeSpan(style.fontSize),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            WebvttCssStyle.FONT_SIZE_UNIT_PERCENT -> SpanUtil.addOrReplaceSpan(
                spannedText,
                RelativeSizeSpan(style.fontSize / 100),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            WebvttCssStyle.UNSPECIFIED -> {}
        }
        if (style.combineUpright) {
            spannedText.setSpan(
                HorizontalTextInVerticalContextSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun getTagName(tagExpression: String): String {
        var tagExpression = tagExpression
        tagExpression = tagExpression.trim { it <= ' ' }
        Preconditions.checkArgument(!tagExpression.isEmpty())
        return splitAtFirst(tagExpression, "[ \\.]")[0]!!
    }

    private fun getApplicableStyles(
        declaredStyles: MutableList<WebvttCssStyle>, id: String?, tag: StartTag
    ): MutableList<StyleMatch> {
        val applicableStyles: MutableList<StyleMatch> = ArrayList<StyleMatch>()
        for (i in declaredStyles.indices) {
            val style = declaredStyles[i]
            val score = style.getSpecificityScore(id, tag.name, tag.classes, tag.voice)
            if (score > 0) {
                applicableStyles.add(StyleMatch(score, style))
            }
        }
        Collections.sort<StyleMatch>(applicableStyles)
        return applicableStyles
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [TEXT_ALIGNMENT_START, TEXT_ALIGNMENT_CENTER, TEXT_ALIGNMENT_END, TEXT_ALIGNMENT_LEFT, TEXT_ALIGNMENT_RIGHT
        ]
    )
    private annotation class TextAlignment

    private class WebvttCueInfoBuilder {
        var startTimeUs: Long = 0
        var endTimeUs: Long = 0
        var text:CharSequence? = null
        var textAlignment: @TextAlignment Int
        var line: Float

        // Equivalent to WebVTT's snap-to-lines flag:
        // https://www.w3.org/TR/webvtt1/#webvtt-cue-snap-to-lines-flag
        @Cue.LineType
        var lineType: @Cue.LineType Int

        @Cue.AnchorType
        var lineAnchor: @Cue.AnchorType Int
        var position: Float

        @Cue.AnchorType
        var positionAnchor: @Cue.AnchorType Int
        var size: Float = 1.0f

        @Cue.VerticalType
        var verticalType: @Cue.VerticalType Int

        init {
            // Default: https://www.w3.org/TR/webvtt1/#webvtt-cue-text-alignment
            textAlignment = TEXT_ALIGNMENT_CENTER
            line = Cue.DIMEN_UNSET
            // Defaults to NUMBER (true): https://www.w3.org/TR/webvtt1/#webvtt-cue-snap-to-lines-flag
            lineType = Cue.LINE_TYPE_NUMBER
            // Default: https://www.w3.org/TR/webvtt1/#webvtt-cue-line-alignment
            lineAnchor = Cue.ANCHOR_TYPE_START
            position = Cue.DIMEN_UNSET
            positionAnchor = Cue.TYPE_UNSET
            // Default: https://www.w3.org/TR/webvtt1/#webvtt-cue-size
            verticalType = Cue.TYPE_UNSET
        }

        fun build(): WebvttCueInfo {
            return WebvttCueInfo(toCueBuilder().build(), startTimeUs, endTimeUs)
        }

        fun toCueBuilder(): Cue.Builder {
            val position =
                if (this.position != Cue.DIMEN_UNSET) this.position else derivePosition(
                    textAlignment
                )
            @Cue.AnchorType val positionAnchor: @Cue.AnchorType Int =
                if (this.positionAnchor != Cue.TYPE_UNSET)
                    this.positionAnchor
                else
                    derivePositionAnchor(textAlignment)
            val cueBuilder =
                Cue.Builder()
                    .setTextAlignment(convertTextAlignment(textAlignment))
                    .setLine(computeLine(line, lineType), lineType)
                    .setLineAnchor(lineAnchor)
                    .setPosition(position)
                    .setPositionAnchor(positionAnchor)
                    .setSize(min(size, deriveMaxSize(positionAnchor, position)))
                    .setVerticalType(verticalType)

            if (text != null) {
                cueBuilder.setText(text)
            }

            return cueBuilder
        }

        companion object {
            // https://www.w3.org/TR/webvtt1/#webvtt-cue-line
            private fun computeLine(line: Float, @Cue.LineType lineType: @Cue.LineType Int): Float {
                if (line != Cue.DIMEN_UNSET && lineType == Cue.LINE_TYPE_FRACTION && (line < 0.0f || line > 1.0f)) {
                    return 1.0f // Step 1
                } else if (line != Cue.DIMEN_UNSET) {
                    // Step 2: Do nothing, line is already correct.
                    return line
                } else if (lineType == Cue.LINE_TYPE_FRACTION) {
                    return 1.0f // Step 3
                } else {
                    // Steps 4 - 10 (stacking multiple simultaneous cues) are handled by
                    // WebvttSubtitle.getCues(long) and WebvttSubtitle.isNormal(Cue).
                    return Cue.DIMEN_UNSET
                }
            }

            // https://www.w3.org/TR/webvtt1/#webvtt-cue-position
            private fun derivePosition(textAlignment: @TextAlignment Int): Float {
                when (textAlignment) {
                    TEXT_ALIGNMENT_LEFT -> return 0.0f
                    TEXT_ALIGNMENT_RIGHT -> return 1.0f
                    TEXT_ALIGNMENT_START, TEXT_ALIGNMENT_CENTER, TEXT_ALIGNMENT_END -> return DEFAULT_POSITION
                    else -> return DEFAULT_POSITION
                }
            }

            // https://www.w3.org/TR/webvtt1/#webvtt-cue-position-alignment
            @Cue.AnchorType
            private fun derivePositionAnchor(textAlignment: @TextAlignment Int): @Cue.AnchorType Int {
                when (textAlignment) {
                    TEXT_ALIGNMENT_LEFT, TEXT_ALIGNMENT_START -> return Cue.ANCHOR_TYPE_START
                    TEXT_ALIGNMENT_RIGHT, TEXT_ALIGNMENT_END -> return Cue.ANCHOR_TYPE_END
                    TEXT_ALIGNMENT_CENTER -> return Cue.ANCHOR_TYPE_MIDDLE
                    else -> return Cue.ANCHOR_TYPE_MIDDLE
                }
            }

            private fun convertTextAlignment(textAlignment: @TextAlignment Int): Layout.Alignment? {
                when (textAlignment) {
                    TEXT_ALIGNMENT_START, TEXT_ALIGNMENT_LEFT -> return Layout.Alignment.ALIGN_NORMAL
                    TEXT_ALIGNMENT_CENTER -> return Layout.Alignment.ALIGN_CENTER
                    TEXT_ALIGNMENT_END, TEXT_ALIGNMENT_RIGHT -> return Layout.Alignment.ALIGN_OPPOSITE
                    else -> {
                        w(TAG, "Unknown textAlignment: " + textAlignment)
                        return null
                    }
                }
            }

            // Step 2 here: https://www.w3.org/TR/webvtt1/#processing-cue-settings
            private fun deriveMaxSize(
                @Cue.AnchorType positionAnchor: @Cue.AnchorType Int,
                position: Float
            ): Float {
                when (positionAnchor) {
                    Cue.ANCHOR_TYPE_START -> return 1.0f - position
                    Cue.ANCHOR_TYPE_END -> return position
                    Cue.ANCHOR_TYPE_MIDDLE -> if (position <= 0.5f) {
                        return position * 2
                    } else {
                        return (1.0f - position) * 2
                    }

                    Cue.TYPE_UNSET -> throw IllegalStateException(positionAnchor.toString())
                    else -> throw IllegalStateException(positionAnchor.toString())
                }
            }
        }
    }

    private class StyleMatch(val score: Int, val style: WebvttCssStyle) : Comparable<StyleMatch> {
        override fun compareTo(another: StyleMatch): Int {
            return Integer.compare(this.score, another.score)
        }
    }

    private class StartTag(
        val name: String,
        val position: Int,
        val voice: String?,
        val classes: MutableSet<String?>
    ) {
        companion object {
            fun buildStartTag(fullTagExpression: String, position: Int): StartTag {
                var fullTagExpression = fullTagExpression
                fullTagExpression = fullTagExpression.trim { it <= ' ' }
                Preconditions.checkArgument(!fullTagExpression.isEmpty())
                val voiceStartIndex = fullTagExpression.indexOf(" ")
                val voice: String?
                if (voiceStartIndex == -1) {
                    voice = ""
                } else {
                    voice = fullTagExpression.substring(voiceStartIndex).trim { it <= ' ' }
                    fullTagExpression = fullTagExpression.substring(0, voiceStartIndex)
                }
                val nameAndClasses = split(fullTagExpression, "\\.")
                val name = nameAndClasses[0]
                val classes: MutableSet<String?> = HashSet<String?>()
                for (i in 1..<nameAndClasses.size) {
                    classes.add(nameAndClasses[i])
                }
                return StartTag(name, position, voice, classes)
            }

            fun buildWholeCueVirtualTag(): StartTag {
                return StartTag( 
                    "",  
                    0,  
                    "",  
                    mutableSetOf<String?>()
                )
            }
        }
    }

    private class Element(
        val startTag: StartTag,

        val endPosition: Int
    ) {
        companion object {
            val BY_START_POSITION_ASC = Comparator { e1: Element?, e2: Element? ->
                Integer.compare(
                    e1!!.startTag.position,
                    e2!!.startTag.position
                )
            }
        }
    }
}
