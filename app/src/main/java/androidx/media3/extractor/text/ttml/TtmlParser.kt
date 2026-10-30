
package androidx.media3.extractor.text.ttml

import android.text.Layout
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.text.TextAnnotation
import androidx.media3.common.util.ColorParser
import androidx.media3.common.util.Log
import androidx.media3.common.util.Util
import androidx.media3.common.util.XmlPullParserUtil
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.LegacySubtitleUtil
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleDecoderException
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.base.Ascii
import com.google.common.base.Preconditions
import org.checkerframework.checker.nullness.qual.PolyNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

@Suppress("MISSING_DEPENDENCY_IN_INFERRED_TYPE_ANNOTATION_WARNING")
class TtmlParser : SubtitleParser {
    private val xmlParserFactory: XmlPullParserFactory

    init {
        try {
            xmlParserFactory = XmlPullParserFactory.newInstance()
            xmlParserFactory.setNamespaceAware(true)
        } catch (e: XmlPullParserException) {
            throw RuntimeException("Couldn't create XmlPullParserFactory instance", e)
        }
    }

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: ((CuesWithTiming)-> Unit)
    ) {
        val subtitle = parseToLegacySubtitle(data, offset, length)
        LegacySubtitleUtil.toCuesWithTiming(subtitle, outputOptions, output)
    }

    override fun parseToLegacySubtitle(data: ByteArray, offset: Int, length: Int): Subtitle {
        try {
            val xmlParser = xmlParserFactory.newPullParser()
            val globalStyles: MutableMap<String, TtmlStyle> = HashMap<String, TtmlStyle>()
            val regionMap: MutableMap<String, TtmlRegion> = HashMap<String, TtmlRegion>()
            val imageMap: MutableMap<String, String> = HashMap<String, String>()
            regionMap.put(
                TtmlNode.ANONYMOUS_REGION_ID,
                TtmlRegion(TtmlNode.ANONYMOUS_REGION_ID)
            )
            val inputStream = ByteArrayInputStream(data, offset, length)
            xmlParser.setInput(inputStream, null)
            var ttmlSubtitle: TtmlSubtitle? = null
            val nodeStack = ArrayDeque<TtmlNode?>()
            var unsupportedNodeDepth = 0
            var eventType = xmlParser.getEventType()
            var frameAndTickRate: FrameAndTickRate = DEFAULT_FRAME_AND_TICK_RATE
            var cellRows: Int = DEFAULT_CELL_ROWS
            var ttsExtent: TtsExtent? = null
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val parent = nodeStack.peek()
                if (unsupportedNodeDepth == 0) {
                    val name = xmlParser.getName()
                    if (eventType == XmlPullParser.START_TAG) {
                        if (TtmlNode.TAG_TT == name) {
                            frameAndTickRate = parseFrameAndTickRates(xmlParser)
                            cellRows = parseCellRows(xmlParser, DEFAULT_CELL_ROWS)
                            ttsExtent = parseTtsExtent(xmlParser)
                        }
                        if (!isSupportedTag(name)) {
                            Log.i(TAG, "Ignoring unsupported tag: " + xmlParser.getName())
                            unsupportedNodeDepth++
                        } else if (TtmlNode.TAG_HEAD == name) {
                            parseHeader(
                                xmlParser,
                                globalStyles,
                                cellRows,
                                ttsExtent,
                                regionMap,
                                imageMap
                            )
                        } else {
                            try {
                                val node: TtmlNode =
                                    parseNode(xmlParser, parent, regionMap, frameAndTickRate)
                                nodeStack.push(node)
                                if (parent != null) {
                                    parent.addChild(node)
                                }
                            } catch (e: SubtitleDecoderException) {
                                Log.w(TAG, "Suppressing parser error", e)
                                // Treat the node (and by extension, all of its children) as unsupported.
                                unsupportedNodeDepth++
                            }
                        }
                    } else if (eventType == XmlPullParser.TEXT) {
                        parent!!.addChild(TtmlNode.buildTextNode(xmlParser.getText()))
                    } else if (eventType == XmlPullParser.END_TAG) {
                        if (xmlParser.getName() == TtmlNode.TAG_TT) {
                            ttmlSubtitle =
                                TtmlSubtitle(
                                    nodeStack.peek()!!,
                                    globalStyles,
                                    regionMap,
                                    imageMap
                                )
                        }
                        nodeStack.pop()
                    }
                } else {
                    if (eventType == XmlPullParser.START_TAG) {
                        unsupportedNodeDepth++
                    } else if (eventType == XmlPullParser.END_TAG) {
                        unsupportedNodeDepth--
                    }
                }
                xmlParser.next()
                eventType = xmlParser.getEventType()
            }
            return ttmlSubtitle!!
        } catch (xppe: XmlPullParserException) {
            throw IllegalStateException("Unable to decode source", xppe)
        } catch (e: IOException) {
            throw IllegalStateException("Unexpected error when reading input.", e)
        }
    }

    private class FrameAndTickRate(
        val effectiveFrameRate: Float,
        val subFrameRate: Int,
        val tickRate: Int
    )

    override val cueReplacementBehavior: Int
        get() = Format.CUE_REPLACEMENT_BEHAVIOR_MERGE

    private class TtsExtent(val width: Int, val height: Int)
    companion object {
        
        val cueReplacementBehavior: @Format.CueReplacementBehavior Int =
            Format.CUE_REPLACEMENT_BEHAVIOR_MERGE

        private const val TAG = "TtmlParser"

        private const val TTP = "http://www.w3.org/ns/ttml#parameter"

        private const val ATTR_BEGIN = "begin"
        private const val ATTR_DURATION = "dur"
        private const val ATTR_END = "end"
        private const val ATTR_STYLE = "style"
        private const val ATTR_REGION = "region"
        private const val ATTR_IMAGE = "backgroundImage"

        private val CLOCK_TIME: Pattern = Pattern.compile(
            "^([0-9][0-9]+):([0-9][0-9]):([0-9][0-9])"
                    + "(?:(\\.[0-9]+)|:([0-9][0-9])(?:\\.([0-9]+))?)?$"
        )
        private val OFFSET_TIME: Pattern = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)(h|m|s|ms|f|t)$")
        private val FONT_SIZE: Pattern = Pattern.compile("^(([0-9]*.)?[0-9]+)(px|em|%)$")
        val SIGNED_PERCENTAGE: Pattern = Pattern.compile("^([-+]?\\d+\\.?\\d*?)%$")
        val PERCENTAGE_COORDINATES: Pattern =
            Pattern.compile("^([-+]?\\d+\\.?\\d*?)% ([-+]?\\d+\\.?\\d*?)%$")
        private val PIXEL_COORDINATES: Pattern =
            Pattern.compile("^([-+]?\\d+\\.?\\d*?)px ([-+]?\\d+\\.?\\d*?)px$")
        private val CELL_RESOLUTION: Pattern = Pattern.compile("^(\\d+) (\\d+)$")

        private const val DEFAULT_FRAME_RATE = 30

        private val DEFAULT_FRAME_AND_TICK_RATE =
            FrameAndTickRate(DEFAULT_FRAME_RATE.toFloat(), 1, 1)
        private const val DEFAULT_CELL_ROWS = 15

        private fun parseFrameAndTickRates(xmlParser: XmlPullParser): FrameAndTickRate {
            var frameRate: Int = DEFAULT_FRAME_RATE
            val frameRateString = xmlParser.getAttributeValue(TTP, "frameRate")
            if (frameRateString != null) {
                frameRate = frameRateString.toInt()
            }

            var frameRateMultiplier = 1f
            val frameRateMultiplierString = xmlParser.getAttributeValue(TTP, "frameRateMultiplier")
            if (frameRateMultiplierString != null) {
                val parts: Array<String> = Util.split(frameRateMultiplierString, " ")
                Preconditions.checkArgument(
                    parts.size == 2,
                    "frameRateMultiplier doesn't have 2 parts"
                )
                val numerator = parts[0]!!.toInt().toFloat()
                val denominator = parts[1]!!.toInt().toFloat()
                frameRateMultiplier = numerator / denominator
            }

            var subFrameRate: Int = DEFAULT_FRAME_AND_TICK_RATE.subFrameRate
            val subFrameRateString = xmlParser.getAttributeValue(TTP, "subFrameRate")
            if (subFrameRateString != null) {
                subFrameRate = subFrameRateString.toInt()
            }

            var tickRate: Int = DEFAULT_FRAME_AND_TICK_RATE.tickRate
            val tickRateString = xmlParser.getAttributeValue(TTP, "tickRate")
            if (tickRateString != null) {
                tickRate = tickRateString.toInt()
            }
            return FrameAndTickRate(frameRate * frameRateMultiplier, subFrameRate, tickRate)
        }

        private fun parseCellRows(xmlParser: XmlPullParser, defaultValue: Int): Int {
            val cellResolution = xmlParser.getAttributeValue(TTP, "cellResolution")
            if (cellResolution == null) {
                return defaultValue
            }

            val cellResolutionMatcher: Matcher = CELL_RESOLUTION.matcher(cellResolution)
            if (!cellResolutionMatcher.matches()) {
                Log.w(TAG, "Ignoring malformed cell resolution: " + cellResolution)
                return defaultValue
            }
            try {
                val columns =cellResolutionMatcher.group(1)!!.toInt()
                val rows =cellResolutionMatcher.group(2)!!.toInt()
                Preconditions.checkArgument(
                    columns != 0 && rows != 0,
                    "Invalid cell resolution %s %s",
                    columns,
                    rows
                )
                return rows
            } catch (e: NumberFormatException) {
                Log.w(TAG, "Ignoring malformed cell resolution: " + cellResolution)
                return defaultValue
            }
        }

        private fun parseTtsExtent(xmlParser: XmlPullParser): TtsExtent? {
            val ttsExtent: String? =
                XmlPullParserUtil.getAttributeValue(xmlParser, TtmlNode.ATTR_TTS_EXTENT)
            if (ttsExtent == null) {
                return null
            }

            val extentMatcher: Matcher = PIXEL_COORDINATES.matcher(ttsExtent)
            if (!extentMatcher.matches()) {
                Log.w(TAG, "Ignoring non-pixel tts extent: " + ttsExtent)
                return null
            }
            try {
                val width = extentMatcher.group(1)!!.toInt()
                val height = extentMatcher.group(2)!!.toInt()
                return TtsExtent(width, height)
            } catch (e: NumberFormatException) {
                Log.w(TAG, "Ignoring malformed tts extent: " + ttsExtent)
                return null
            }
        }

        @Throws(IOException::class, XmlPullParserException::class)
        private fun parseHeader(
            xmlParser: XmlPullParser,
            globalStyles: MutableMap<String, TtmlStyle>,
            cellRows: Int,
            ttsExtent: TtsExtent?,
            globalRegions: MutableMap<String, TtmlRegion>,
            imageMap: MutableMap<String, String>
        ): MutableMap<String, TtmlStyle> {
            do {
                xmlParser.next()
                if (XmlPullParserUtil.isStartTag(xmlParser, TtmlNode.TAG_STYLE)) {
                    val parentStyleId: String? =
                        XmlPullParserUtil.getAttributeValue(xmlParser, ATTR_STYLE)
                    val style= parseStyleAttributes(xmlParser, TtmlStyle())!!
                    if (parentStyleId != null) {
                        for (id in parseStyleIds(parentStyleId)) {
                            style.chain(globalStyles.get(id))
                        }
                    }
                    val styleId = style.id
                    if (styleId != null) {
                        globalStyles.put(styleId, style)
                    }
                } else if (XmlPullParserUtil.isStartTag(xmlParser, TtmlNode.TAG_REGION)) {
                    val ttmlRegion: TtmlRegion? =
                        parseRegionAttributes(xmlParser, cellRows, ttsExtent, globalStyles)
                    if (ttmlRegion != null) {
                        globalRegions.put(ttmlRegion.id!!, ttmlRegion)
                    }
                } else if (XmlPullParserUtil.isStartTag(
                        xmlParser,
                        TtmlNode.TAG_METADATA
                    )
                ) {
                    parseMetadata(xmlParser, imageMap)
                }
            } while (!XmlPullParserUtil.isEndTag(xmlParser, TtmlNode.TAG_HEAD))
            return globalStyles
        }

        @Throws(IOException::class, XmlPullParserException::class)
        private fun parseMetadata(
            xmlParser: XmlPullParser,
            imageMap: MutableMap<String, String>
        ) {
            do {
                xmlParser.next()
                if (XmlPullParserUtil.isStartTag(xmlParser, TtmlNode.TAG_IMAGE)) {
                    val id: String? = XmlPullParserUtil.getAttributeValue(xmlParser, "id")
                    if (id != null) {
                        val encodedBitmapData = xmlParser.nextText()
                        imageMap.put(id, encodedBitmapData)
                    }
                }
            } while (!XmlPullParserUtil.isEndTag(xmlParser, TtmlNode.TAG_METADATA))
        }

        private fun parseRegionAttributes(
            xmlParser: XmlPullParser,
            cellRows: Int,
            ttsExtent: TtsExtent?,
            globalStyles: MutableMap<String, TtmlStyle>
        ): TtmlRegion? {
            val regionId: String? =
                XmlPullParserUtil.getAttributeValue(xmlParser, TtmlNode.ATTR_ID)
            if (regionId == null) {
                return null
            }

            val position: Float
            var line: Float

            var regionOrigin: String? =
                XmlPullParserUtil.getAttributeValue(xmlParser, TtmlNode.ATTR_TTS_ORIGIN)
            if (regionOrigin == null) {
                val styleId: String? =
                    XmlPullParserUtil.getAttributeValue(xmlParser, TtmlNode.ATTR_STYLE)
                if (styleId != null) {
                    val style = globalStyles.get(styleId)
                    if (style != null) {
                        regionOrigin = style.origin
                    }
                }
            }
            if (regionOrigin != null) {
                val originPercentageMatcher: Matcher = PERCENTAGE_COORDINATES.matcher(regionOrigin)
                val originPixelMatcher: Matcher = PIXEL_COORDINATES.matcher(regionOrigin)
                if (originPercentageMatcher.matches()) {
                    try {
                        position =originPercentageMatcher.group(1)!!.toFloat() / 100f
                        line = originPercentageMatcher.group(2)!!.toFloat() / 100f
                    } catch (e: NumberFormatException) {
                        Log.w(TAG, "Ignoring region with malformed origin: " + regionOrigin)
                        return null
                    }
                } else if (originPixelMatcher.matches()) {
                    if (ttsExtent == null) {
                        Log.w(TAG, "Ignoring region with missing tts:extent: " + regionOrigin)
                        return null
                    }
                    try {
                        val width =originPixelMatcher.group(1)!!.toInt()
                        val height =originPixelMatcher.group(2)!!.toInt()
                        // Convert pixel values to fractions.
                        position = width / ttsExtent.width.toFloat()
                        line = height / ttsExtent.height.toFloat()
                    } catch (e: NumberFormatException) {
                        Log.w(TAG, "Ignoring region with malformed origin: " + regionOrigin)
                        return null
                    }
                } else {
                    Log.w(TAG, "Ignoring region with unsupported origin: " + regionOrigin)
                    return null
                }
            } else {
                // Origin is omitted. Default to top left.
                position = 0f
                line = 0f
            }

            val width: Float
            val height: Float
            var regionExtent: String? =
                XmlPullParserUtil.getAttributeValue(xmlParser, TtmlNode.ATTR_TTS_EXTENT)
            if (regionExtent == null) {
                val styleId: String? =
                    XmlPullParserUtil.getAttributeValue(xmlParser, TtmlNode.ATTR_STYLE)
                if (styleId != null) {
                    val style = globalStyles.get(styleId)
                    if (style != null) {
                        regionExtent = style.extent
                    }
                }
            }
            if (regionExtent != null) {
                val extentPercentageMatcher: Matcher = PERCENTAGE_COORDINATES.matcher(regionExtent)
                val extentPixelMatcher: Matcher = PIXEL_COORDINATES.matcher(regionExtent)
                if (extentPercentageMatcher.matches()) {
                    try {
                        width =extentPercentageMatcher.group(1)!!.toFloat() / 100f
                        height =extentPercentageMatcher.group(2)!!.toFloat() / 100f
                    } catch (e: NumberFormatException) {
                        Log.w(TAG, "Ignoring region with malformed extent: " + regionOrigin)
                        return null
                    }
                } else if (extentPixelMatcher.matches()) {
                    if (ttsExtent == null) {
                        Log.w(TAG, "Ignoring region with missing tts:extent: " + regionOrigin)
                        return null
                    }
                    try {
                        val extentWidth =extentPixelMatcher.group(1)!!.toInt()
                        val extentHeight =extentPixelMatcher.group(2)!!.toInt()
                        // Convert pixel values to fractions.
                        width = extentWidth / ttsExtent.width.toFloat()
                        height = extentHeight / ttsExtent.height.toFloat()
                    } catch (e: NumberFormatException) {
                        Log.w(TAG, "Ignoring region with malformed extent: " + regionOrigin)
                        return null
                    }
                } else {
                    Log.w(TAG, "Ignoring region with unsupported extent: " + regionOrigin)
                    return null
                }
            } else {
                // Extent is omitted. Default to extent of parent.
                width = 1f
                height = 1f
            }

            @Cue.AnchorType var lineAnchor: @Cue.AnchorType Int = Cue.ANCHOR_TYPE_START
            var displayAlign: String? =
                XmlPullParserUtil.getAttributeValue(
                    xmlParser,
                    TtmlNode.ATTR_TTS_DISPLAY_ALIGN
                )
            if (displayAlign == null) {
                val styleId: String? =
                    XmlPullParserUtil.getAttributeValue(xmlParser, TtmlNode.ATTR_STYLE)
                if (styleId != null) {
                    val style = globalStyles.get(styleId)
                    if (style != null) {
                        displayAlign = style.displayAlign
                    }
                }
            }
            if (displayAlign != null) {
                when (Ascii.toLowerCase(displayAlign)) {
                    "center" -> {
                        lineAnchor = Cue.ANCHOR_TYPE_MIDDLE
                        line += height / 2
                    }

                    "after" -> {
                        lineAnchor = Cue.ANCHOR_TYPE_END
                        line += height
                    }

                    else -> {}
                }
            }

            val regionTextHeight = 1.0f / cellRows

            @Cue.VerticalType var verticalType: @Cue.VerticalType Int = Cue.TYPE_UNSET
            val writingDirection: String? =
                XmlPullParserUtil.getAttributeValue(
                    xmlParser,
                    TtmlNode.ATTR_TTS_WRITING_MODE
                )
            if (writingDirection != null) {
                when (Ascii.toLowerCase(writingDirection)) {
                    TtmlNode.VERTICAL, TtmlNode.VERTICAL_LR -> verticalType =
                        Cue.VERTICAL_TYPE_LR

                    TtmlNode.VERTICAL_RL -> verticalType = Cue.VERTICAL_TYPE_RL
                    else -> {}
                }
            }
            return TtmlRegion(
                regionId,
                position,
                line,  
                Cue.LINE_TYPE_FRACTION,
                lineAnchor,
                width,
                height,  
                Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING,  
                regionTextHeight,
                verticalType
            )
        }

        private fun parseStyleIds(parentStyleIds: String): Array<String?> {
            var parentStyleIds = parentStyleIds
            parentStyleIds = parentStyleIds.trim { it <= ' ' }
            return (if (parentStyleIds.isEmpty()) arrayOfNulls<String>(0) else Util.split(
                parentStyleIds,
                "\\s+"
            )) as Array<String?>
        }

        private fun parseStyleAttributes(
            parser: XmlPullParser, style: @PolyNull TtmlStyle?
        ): @PolyNull TtmlStyle? {
            var style = style
            val attributeCount = parser.getAttributeCount()
            for (i in 0..<attributeCount) {
                val attributeValue = parser.getAttributeValue(i)
                when (parser.getAttributeName(i)) {
                    TtmlNode.ATTR_ID -> if (TtmlNode.TAG_STYLE == parser.getName()) {
                        style = createIfNull(style).setId(attributeValue)
                    }

                    TtmlNode.ATTR_TTS_BACKGROUND_COLOR -> {
                        style = createIfNull(style)
                        try {
                            style.setBackgroundColor(ColorParser.parseTtmlColor(attributeValue))
                        } catch (e: IllegalArgumentException) {
                            Log.w(TAG, "Failed parsing background value: " + attributeValue)
                        }
                    }

                    TtmlNode.ATTR_TTS_COLOR -> {
                        style = createIfNull(style)
                        try {
                            style.setFontColor(ColorParser.parseTtmlColor(attributeValue))
                        } catch (e: IllegalArgumentException) {
                            Log.w(TAG, "Failed parsing color value: " + attributeValue)
                        }
                    }

                    TtmlNode.ATTR_TTS_FONT_FAMILY -> style =
                        createIfNull(style).setFontFamily(attributeValue)

                    TtmlNode.ATTR_TTS_FONT_SIZE -> try {
                        style = createIfNull(style)
                        parseFontSize(attributeValue, style)
                    } catch (e: SubtitleDecoderException) {
                        Log.w(TAG, "Failed parsing fontSize value: " + attributeValue)
                    }

                    TtmlNode.ATTR_TTS_FONT_WEIGHT -> style = createIfNull(style).setBold(
                        TtmlNode.BOLD.equals(
                            attributeValue,
                            ignoreCase = true
                        )
                    )

                    TtmlNode.ATTR_TTS_FONT_STYLE -> style = createIfNull(style).setItalic(
                        TtmlNode.ITALIC.equals(
                            attributeValue,
                            ignoreCase = true
                        )
                    )

                    TtmlNode.ATTR_TTS_TEXT_ALIGN -> style =
                        createIfNull(style).setTextAlign(
                            parseAlignment(attributeValue)
                        )

                    TtmlNode.ATTR_EBUTTS_MULTI_ROW_ALIGN -> style =
                        createIfNull(style).setMultiRowAlign(
                            parseAlignment(attributeValue)
                        )

                    TtmlNode.ATTR_TTS_TEXT_COMBINE -> when (Ascii.toLowerCase(
                        attributeValue
                    )) {
                        TtmlNode.COMBINE_NONE -> style =
                            createIfNull(style).setTextCombine(false)

                        TtmlNode.COMBINE_ALL -> style =
                            createIfNull(style).setTextCombine(true)

                        else -> {}
                    }

                    TtmlNode.ATTR_TTS_RUBY -> when (Ascii.toLowerCase(attributeValue)) {
                        TtmlNode.RUBY_CONTAINER -> style =
                            createIfNull(style).setRubyType(TtmlStyle.RUBY_TYPE_CONTAINER)

                        TtmlNode.RUBY_BASE, TtmlNode.RUBY_BASE_CONTAINER -> style =
                            createIfNull(style).setRubyType(TtmlStyle.RUBY_TYPE_BASE)

                        TtmlNode.RUBY_TEXT, TtmlNode.RUBY_TEXT_CONTAINER -> style =
                            createIfNull(style).setRubyType(TtmlStyle.RUBY_TYPE_TEXT)

                        TtmlNode.RUBY_DELIMITER -> style =
                            createIfNull(style).setRubyType(TtmlStyle.RUBY_TYPE_DELIMITER)

                        else -> {}
                    }

                    TtmlNode.ATTR_TTS_RUBY_POSITION -> when (Ascii.toLowerCase(
                        attributeValue
                    )) {
                        TtmlNode.ANNOTATION_POSITION_BEFORE -> style =
                            createIfNull(style).setRubyPosition(TextAnnotation.POSITION_BEFORE)

                        TtmlNode.ANNOTATION_POSITION_AFTER -> style =
                            createIfNull(style).setRubyPosition(TextAnnotation.POSITION_AFTER)

                        else -> {}
                    }

                    TtmlNode.ATTR_TTS_TEXT_DECORATION -> when (Ascii.toLowerCase(
                        attributeValue
                    )) {
                        TtmlNode.LINETHROUGH -> style =
                            createIfNull(style).setLinethrough(true)

                        TtmlNode.NO_LINETHROUGH -> style =
                            createIfNull(style).setLinethrough(false)

                        TtmlNode.UNDERLINE -> style =
                            createIfNull(style).setUnderline(true)

                        TtmlNode.NO_UNDERLINE -> style =
                            createIfNull(style).setUnderline(false)
                    }

                    TtmlNode.ATTR_TTS_TEXT_EMPHASIS -> style =
                        createIfNull(style).setTextEmphasis(
                            TextEmphasis.parse(attributeValue)
                        )

                    TtmlNode.ATTR_TTS_SHEAR -> style =
                        createIfNull(style).setShearPercentage(
                            parseShear(attributeValue)
                        )

                    TtmlNode.ATTR_TTS_ORIGIN -> style =
                        createIfNull(style).setOrigin(attributeValue)

                    TtmlNode.ATTR_TTS_EXTENT -> style =
                        createIfNull(style).setExtent(attributeValue)

                    TtmlNode.ATTR_TTS_DISPLAY_ALIGN -> style =
                        createIfNull(style).setDisplayAlign(attributeValue)

                    else -> {}
                }
            }
            return style
        }

        private fun createIfNull(style: TtmlStyle?): TtmlStyle {
            return if (style == null) TtmlStyle() else style
        }

        private fun parseAlignment(alignment: String): Layout.Alignment? {
            when (Ascii.toLowerCase(alignment)) {
                TtmlNode.LEFT, TtmlNode.START -> return Layout.Alignment.ALIGN_NORMAL
                TtmlNode.RIGHT, TtmlNode.END -> return Layout.Alignment.ALIGN_OPPOSITE
                TtmlNode.CENTER -> return Layout.Alignment.ALIGN_CENTER
                else -> return null
            }
        }

        @Throws(SubtitleDecoderException::class)
        private fun parseNode(
            parser: XmlPullParser,
            parent: TtmlNode?,
            regionMap: MutableMap<String, TtmlRegion>,
            frameAndTickRate: FrameAndTickRate
        ): TtmlNode {
            var duration = C.TIME_UNSET
            var startTime = C.TIME_UNSET
            var endTime = C.TIME_UNSET
            var regionId: String? = TtmlNode.ANONYMOUS_REGION_ID
            var imageId: String? = null
            var styleIds: Array<String?>? = null
            val attributeCount = parser.getAttributeCount()
            val style: TtmlStyle? = Companion.parseStyleAttributes(parser, null)
            for (i in 0..<attributeCount) {
                val attr = parser.getAttributeName(i)
                val value = parser.getAttributeValue(i)
                when (attr) {
                    ATTR_BEGIN -> startTime = parseTimeExpression(value, frameAndTickRate)
                    ATTR_END -> endTime = parseTimeExpression(value, frameAndTickRate)
                    ATTR_DURATION -> duration = parseTimeExpression(value, frameAndTickRate)
                    ATTR_STYLE -> {
                        // IDREFS: potentially multiple space delimited ids
                        val ids: Array<String?> = parseStyleIds(value)
                        if (ids.size > 0) {
                            styleIds = ids
                        }
                    }

                    ATTR_REGION -> if (regionMap.containsKey(value)) {
                        // If the region has not been correctly declared or does not define a position, we use
                        // the anonymous region.
                        regionId = value
                    }

                    ATTR_IMAGE ->           // Parse URI reference only if refers to an element in the same document (it must start
                        // with '#'). Resolving URIs from external sources is not supported.
                        if (value.startsWith("#")) {
                            imageId = value.substring(1)
                        }

                    else -> {}
                }
            }
            if (parent != null && parent.startTimeUs != C.TIME_UNSET) {
                if (startTime != C.TIME_UNSET) {
                    startTime += parent.startTimeUs
                } else {
                    // Per TTML2 §12.2.1 and SMIL 3.0 §5.4.3, when `begin` is absent on a timed element
                    // inside a parallel or sequential time container, the implicit value is 0s, which
                    // resolves to the parent's start time. Without this, an element whose timing is
                    // carried by a wrapping ancestor (e.g. a `<p>` inside a `<div begin="…">`) would
                    // keep an unset startTimeUs and never be emitted as a cue.
                    startTime = parent.startTimeUs
                }
                if (endTime != C.TIME_UNSET) {
                    endTime += parent.startTimeUs
                }
            }
            if (endTime == C.TIME_UNSET) {
                if (duration != C.TIME_UNSET) {
                    // Infer the end time from the duration.
                    endTime = startTime + duration
                } else if (parent != null && parent.endTimeUs != C.TIME_UNSET) {
                    // If the end time remains unspecified, then it should be inherited from the parent.
                    endTime = parent.endTimeUs
                }
            }

            return TtmlNode.buildNode(
                parser.getName(), startTime, endTime, style, styleIds, regionId, imageId, parent
            )
        }

        private fun isSupportedTag(tag: String): Boolean {
            return tag == TtmlNode.TAG_TT
                    || tag == TtmlNode.TAG_HEAD
                    || tag == TtmlNode.TAG_BODY
                    || tag == TtmlNode.TAG_DIV
                    || tag == TtmlNode.TAG_P
                    || tag == TtmlNode.TAG_SPAN
                    || tag == TtmlNode.TAG_BR
                    || tag == TtmlNode.TAG_STYLE
                    || tag == TtmlNode.TAG_STYLING
                    || tag == TtmlNode.TAG_LAYOUT
                    || tag == TtmlNode.TAG_REGION
                    || tag == TtmlNode.TAG_METADATA
                    || tag == TtmlNode.TAG_IMAGE
                    || tag == TtmlNode.TAG_DATA
                    || tag == TtmlNode.TAG_INFORMATION
        }

        @Throws(SubtitleDecoderException::class)
        private fun parseFontSize(expression: String, out: TtmlStyle) {
            val expressions: Array<String> = Util.split(expression, "\\s+")
            val matcher: Matcher?
            if (expressions.size == 1) {
                matcher = FONT_SIZE.matcher(expression)
            } else if (expressions.size == 2) {
                matcher = FONT_SIZE.matcher(expressions[1])
                Log.w(
                    TAG,
                    "Multiple values in fontSize attribute. Picking the second value for vertical font"
                            + " size and ignoring the first."
                )
            } else {
                throw SubtitleDecoderException(
                    "Invalid number of entries for fontSize: " + expressions.size + "."
                )
            }

            if (matcher.matches()) {
                val unit = matcher.group(3)!!
                when (unit) {
                    "px" -> out.setFontSizeUnit(TtmlStyle.FONT_SIZE_UNIT_PIXEL)
                    "em" -> out.setFontSizeUnit(TtmlStyle.FONT_SIZE_UNIT_EM)
                    "%" -> out.setFontSizeUnit(TtmlStyle.FONT_SIZE_UNIT_PERCENT)
                    else -> throw SubtitleDecoderException("Invalid unit for fontSize: '" + unit + "'.")
                }
                out.setFontSize(matcher.group(1)!!.toFloat())
            } else {
                throw SubtitleDecoderException("Invalid expression for fontSize: '" + expression + "'.")
            }
        }

        private fun parseShear(expression: String): Float {
            val matcher: Matcher = SIGNED_PERCENTAGE.matcher(expression)
            if (!matcher.matches()) {
                Log.w(TAG, "Invalid value for shear: " + expression)
                return TtmlStyle.UNSPECIFIED_SHEAR
            }
            try {
                val percentage = matcher.group(1)!!
                var value = percentage.toFloat()
                // https://www.w3.org/TR/2018/REC-ttml2-20181108/#semantics-style-procedures-shear
                // If the absolute value of the specified percentage is greater than 100%, then it must be
                // interpreted as if 100% were specified with the appropriate sign.
                value = max(-100f, value)
                value = min(100f, value)
                return value
            } catch (e: NumberFormatException) {
                Log.w(TAG, "Failed to parse shear: " + expression, e)
                return TtmlStyle.UNSPECIFIED_SHEAR
            }
        }

        @Throws(SubtitleDecoderException::class)
        private fun parseTimeExpression(time: String, frameAndTickRate: FrameAndTickRate): Long {
            var matcher: Matcher = CLOCK_TIME.matcher(time)
            if (matcher.matches()) {
                val hours = matcher.group(1)!!
                var durationSeconds = (hours.toLong() * 3600).toDouble()
                val minutes = matcher.group(2)!!
                durationSeconds += (minutes.toLong() * 60).toDouble()
                val seconds = matcher.group(3)!!
                durationSeconds += seconds.toLong().toDouble()
                val fraction = matcher.group(4)
                durationSeconds += if (fraction != null) fraction.toDouble() else 0.0
                val frames = matcher.group(5)
                durationSeconds +=
                    (
                            if (frames != null) frames.toLong() / frameAndTickRate.effectiveFrameRate else 0f).toDouble()
                val subframes = matcher.group(6)
                durationSeconds +=
                    if (subframes != null)
                        ((subframes.toLong().toDouble())
                                / frameAndTickRate.subFrameRate
                                / frameAndTickRate.effectiveFrameRate)
                    else
                        0.0
                return (durationSeconds * C.MICROS_PER_SECOND).toLong()
            }
            matcher = OFFSET_TIME.matcher(time)
            if (matcher.matches()) {
                val timeValue = matcher.group(1)!!
                var offsetSeconds = timeValue.toDouble()
                val unit =matcher.group(2)!!
                when (unit) {
                    "h" -> offsetSeconds *= 3600.0
                    "m" -> offsetSeconds *= 60.0
                    "s" -> {}
                    "ms" -> offsetSeconds /= 1000.0
                    "f" -> offsetSeconds /= frameAndTickRate.effectiveFrameRate.toDouble()
                    "t" -> offsetSeconds /= frameAndTickRate.tickRate.toDouble()
                }
                return (offsetSeconds * C.MICROS_PER_SECOND).toLong()
            }
            throw SubtitleDecoderException("Malformed time expression: " + time)
        }
    }
}
