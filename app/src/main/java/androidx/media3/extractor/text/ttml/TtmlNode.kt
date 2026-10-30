
package androidx.media3.extractor.text.ttml

import android.graphics.BitmapFactory
import android.text.SpannableStringBuilder
import android.util.Base64
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.text.Cue.VerticalType
import com.google.common.base.Preconditions

import java.util.TreeMap
import java.util.TreeSet

internal class TtmlNode private constructor(
    val tag: String?,
    val text: String?,
    val startTimeUs: Long,
    val endTimeUs: Long,
    val style: TtmlStyle?,
    val styleIds: Array<String?>?,
    regionId: String?,
    val imageId: String?,
    val parent: TtmlNode?
) {
    val isTextNode: Boolean
    val regionId: String

    private val nodeStartsByRegion: HashMap<String?, Int?>
    private val nodeEndsByRegion: HashMap<String?, Int?>

    private var children:MutableList<TtmlNode?>? = null

    init {
        this.isTextNode = text != null
        this.regionId =regionId!!
        nodeStartsByRegion = HashMap<String?, Int?>()
        nodeEndsByRegion = HashMap<String?, Int?>()
    }

    fun isActive(timeUs: Long): Boolean {
        return (startTimeUs == C.TIME_UNSET && endTimeUs == C.TIME_UNSET)
                || (startTimeUs <= timeUs && endTimeUs == C.TIME_UNSET)
                || (startTimeUs == C.TIME_UNSET && timeUs < endTimeUs)
                || (startTimeUs <= timeUs && timeUs < endTimeUs)
    }

    fun addChild(child: TtmlNode?) {
        if (children == null) {
            children = ArrayList<TtmlNode?>()
        }
        children!!.add(child)
    }

    fun getChild(index: Int): TtmlNode? {
        if (children == null) {
            throw IndexOutOfBoundsException()
        }
        return children!!.get(index)
    }

    val childCount: Int
        get() = if (children == null) 0 else children!!.size

    val eventTimesUs: LongArray
        get() {
            val eventTimeSet = TreeSet<Long?>()
            getEventTimes(eventTimeSet, false)
            val eventTimes = LongArray(eventTimeSet.size)
            var i = 0
            for (eventTimeUs in eventTimeSet) {
                eventTimes[i++] = eventTimeUs!!
            }
            return eventTimes
        }

    private fun getEventTimes(out: TreeSet<Long?>, descendsPNode: Boolean) {
        val isPNode = TAG_P == tag
        val isDivNode = TAG_DIV == tag
        if (descendsPNode || isPNode || (isDivNode && imageId != null)) {
            if (startTimeUs != C.TIME_UNSET) {
                out.add(startTimeUs)
            }
            if (endTimeUs != C.TIME_UNSET) {
                out.add(endTimeUs)
            }
        }
        if (children == null) {
            return
        }
        for (i in children!!.indices) {
            children!![i]!!.getEventTimes(out, descendsPNode || isPNode)
        }
    }

    fun getCues(
        timeUs: Long,
        globalStyles: MutableMap<String, TtmlStyle>,
        regionMap: MutableMap<String, TtmlRegion>,
        imageMap: MutableMap<String, String>
    ): MutableList<Cue> {
        val regionImageOutputs: MutableList<Pair<String?, String?>> =
            ArrayList<Pair<String?, String?>>()
        traverseForImage(timeUs, regionId, regionImageOutputs)

        val regionTextOutputs = TreeMap<String, Cue.Builder>()
        traverseForText(timeUs, false, regionId, regionTextOutputs)
        traverseForStyle(timeUs, globalStyles, regionMap, regionId, regionTextOutputs)

        val cues: MutableList<Cue> = ArrayList<Cue>()

        // Create image based cues.
        for (regionImagePair in regionImageOutputs) {
            val encodedBitmapData = imageMap.get(regionImagePair.second)
            if (encodedBitmapData == null) {
                // Image reference points to an invalid image. Do nothing.
                continue
            }

            val bitmapData = Base64.decode(encodedBitmapData, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bitmapData,  0, bitmapData.size)
            val region =regionMap.get(regionImagePair.first)!!

            cues.add(
                Cue.Builder()
                    .setBitmap(bitmap)
                    .setPosition(region.position)
                    .setPositionAnchor(Cue.ANCHOR_TYPE_START)
                    .setLine(region.line, Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(region.lineAnchor)
                    .setSize(region.width)
                    .setBitmapHeight(region.height)
                    .setVerticalType(region.verticalType)
                    .build()
            )
        }

        // Create text based cues.
        for (entry in regionTextOutputs.entries) {
            val region = regionMap.get(entry.key)!!
            val regionOutput: Cue.Builder = entry.value!!
            cleanUpText(regionOutput.text!! as SpannableStringBuilder)
            regionOutput.setLine(region.line, region.lineType)
            regionOutput.setLineAnchor(region.lineAnchor)
            regionOutput.setPosition(region.position)
            regionOutput.setSize(region.width)
            regionOutput.setTextSize(region.textSize, region.textSizeType)
            regionOutput.setVerticalType(region.verticalType)
            cues.add(regionOutput.build())
        }

        return cues
    }

    private fun traverseForImage(
        timeUs: Long, inheritedRegion: String?, regionImageList: MutableList<Pair<String?, String?>>
    ) {
        val resolvedRegionId = if (ANONYMOUS_REGION_ID == regionId) inheritedRegion else regionId
        if (isActive(timeUs) && TAG_DIV == tag && imageId != null) {
            regionImageList.add(Pair<String?, String?>(resolvedRegionId, imageId))
            return
        }
        for (i in 0..<this.childCount) {
            getChild(i)!!.traverseForImage(timeUs, resolvedRegionId, regionImageList)
        }
    }

    private fun traverseForText(
        timeUs: Long,
        descendsPNode: Boolean,
        inheritedRegion: String?,
        regionOutputs: MutableMap<String, Cue.Builder>
    ) {
        nodeStartsByRegion.clear()
        nodeEndsByRegion.clear()
        if (TAG_METADATA == tag) {
            // Ignore metadata tag.
            return
        }

        val resolvedRegionId = if (ANONYMOUS_REGION_ID == regionId) inheritedRegion else regionId

        if (isTextNode && descendsPNode) {
            getRegionOutputText(
                resolvedRegionId!!,
                regionOutputs
            ).append(text!!)
        } else if (TAG_BR == tag && descendsPNode) {
            getRegionOutputText(resolvedRegionId!!, regionOutputs).append('\n')
        } else if (isActive(timeUs)) {
            // This is a container node, which can contain zero or more children.
            for (entry in regionOutputs.entries) {
                nodeStartsByRegion.put(
                    entry.key,
                    entry.value!!.text!!.length
                )
            }

            val isPNode = TAG_P == tag
            for (i in 0..<this.childCount) {
                getChild(i)!!
                    .traverseForText(
                        timeUs,
                        descendsPNode || isPNode,
                        resolvedRegionId,
                        regionOutputs
                    )
            }
            if (isPNode) {
                TtmlRenderUtil.endParagraph(getRegionOutputText(resolvedRegionId!!, regionOutputs))
            }

            for (entry in regionOutputs.entries) {
                nodeEndsByRegion.put(
                    entry.key,
                    entry.value!!.text!!.length
                )
            }
        }
    }

    private fun traverseForStyle(
        timeUs: Long,
        globalStyles: MutableMap<String, TtmlStyle>,
        regionMaps: MutableMap<String, TtmlRegion>,
        inheritedRegion: String?,
        regionOutputs: MutableMap<String, Cue.Builder>
    ) {
        if (!isActive(timeUs)) {
            return
        }
        val resolvedRegionId = if (ANONYMOUS_REGION_ID == regionId) inheritedRegion else regionId
        for (entry in nodeEndsByRegion.entries) {
            val regionId = entry.key
            val start: Int =
                (if (nodeStartsByRegion.containsKey(regionId)) nodeStartsByRegion.get(regionId) else 0)!!
            val end: Int = entry.value!!
            if (start != end) {
                val regionOutput =regionOutputs.get(regionId)!!
                @VerticalType val verticalType: @VerticalType Int =regionMaps.get(resolvedRegionId)!!.verticalType
                applyStyleToOutput(globalStyles, regionOutput, start, end, verticalType)
            }
        }
        for (i in 0..<this.childCount) {
            getChild(i)!!
                .traverseForStyle(timeUs, globalStyles, regionMaps, resolvedRegionId, regionOutputs)
        }
    }

    private fun applyStyleToOutput(
        globalStyles: MutableMap<String, TtmlStyle>,
        regionOutput: Cue.Builder,
        start: Int,
        end: Int,
        @VerticalType verticalType: @VerticalType Int
    ) {
        val resolvedStyle = TtmlRenderUtil.resolveStyle(style, styleIds, globalStyles)
        var text = regionOutput.text as SpannableStringBuilder?
        if (text == null) {
            text = SpannableStringBuilder()
            regionOutput.setText(text)
        }
        if (resolvedStyle != null) {
            TtmlRenderUtil.applyStylesToSpan(
                text, start, end, resolvedStyle, parent, globalStyles, verticalType
            )
            if (TAG_P == tag) {
                if (resolvedStyle.shearPercentage != TtmlStyle.UNSPECIFIED_SHEAR) {
                    // Shear style should only be applied to P nodes
                    // https://www.w3.org/TR/2018/REC-ttml2-20181108/#style-attribute-shear
                    // The spec doesn't specify the coordinate system to use for block shear
                    // however the spec shows examples of how different values are expected to be rendered.
                    // See: https://www.w3.org/TR/2018/REC-ttml2-20181108/#style-attribute-shear
                    // https://www.w3.org/TR/2018/REC-ttml2-20181108/#style-attribute-fontShear
                    // This maps the shear percentage to shear angle in graphics coordinates
                    regionOutput.setShearDegrees((resolvedStyle.shearPercentage * -90) / 100)
                }
                if (resolvedStyle.textAlign != null) {
                    regionOutput.setTextAlignment(resolvedStyle.textAlign)
                }
                if (resolvedStyle.multiRowAlign != null) {
                    regionOutput.setMultiRowAlignment(resolvedStyle.multiRowAlign)
                }
            }
        }
    }

    companion object {
        const val TAG_TT: String = "tt"
        const val TAG_HEAD: String = "head"
        const val TAG_BODY: String = "body"
        const val TAG_DIV: String = "div"
        const val TAG_P: String = "p"
        const val TAG_SPAN: String = "span"
        const val TAG_BR: String = "br"
        const val TAG_STYLE: String = "style"
        const val TAG_STYLING: String = "styling"
        const val TAG_LAYOUT: String = "layout"
        const val TAG_REGION: String = "region"
        const val TAG_METADATA: String = "metadata"
        const val TAG_IMAGE: String = "image"
        const val TAG_DATA: String = "data"
        const val TAG_INFORMATION: String = "information"

        const val ANONYMOUS_REGION_ID: String = ""
        const val ATTR_ID: String = "id"
        const val ATTR_STYLE: String = "style"
        const val ATTR_TTS_ORIGIN: String = "origin"
        const val ATTR_TTS_EXTENT: String = "extent"
        const val ATTR_TTS_DISPLAY_ALIGN: String = "displayAlign"
        const val ATTR_TTS_BACKGROUND_COLOR: String = "backgroundColor"
        const val ATTR_TTS_FONT_STYLE: String = "fontStyle"
        const val ATTR_TTS_FONT_SIZE: String = "fontSize"
        const val ATTR_TTS_FONT_FAMILY: String = "fontFamily"
        const val ATTR_TTS_FONT_WEIGHT: String = "fontWeight"
        const val ATTR_TTS_COLOR: String = "color"
        const val ATTR_TTS_RUBY: String = "ruby"
        const val ATTR_TTS_RUBY_POSITION: String = "rubyPosition"
        const val ATTR_TTS_TEXT_DECORATION: String = "textDecoration"
        const val ATTR_TTS_TEXT_ALIGN: String = "textAlign"
        const val ATTR_TTS_TEXT_COMBINE: String = "textCombine"
        const val ATTR_TTS_TEXT_EMPHASIS: String = "textEmphasis"
        const val ATTR_TTS_WRITING_MODE: String = "writingMode"
        const val ATTR_TTS_SHEAR: String = "shear"
        const val ATTR_EBUTTS_MULTI_ROW_ALIGN: String = "multiRowAlign"

        // Values for ruby
        const val RUBY_CONTAINER: String = "container"
        const val RUBY_BASE: String = "base"
        const val RUBY_BASE_CONTAINER: String = "baseContainer"
        const val RUBY_TEXT: String = "text"
        const val RUBY_TEXT_CONTAINER: String = "textContainer"
        const val RUBY_DELIMITER: String = "delimiter"

        // Values for text annotation (i.e. ruby, text emphasis) position
        const val ANNOTATION_POSITION_BEFORE: String = "before"
        const val ANNOTATION_POSITION_AFTER: String = "after"
        const val ANNOTATION_POSITION_OUTSIDE: String = "outside"

        // Values for textDecoration
        const val LINETHROUGH: String = "linethrough"
        const val NO_LINETHROUGH: String = "nolinethrough"
        const val UNDERLINE: String = "underline"
        const val NO_UNDERLINE: String = "nounderline"
        const val ITALIC: String = "italic"
        const val BOLD: String = "bold"

        // Values for textAlign
        const val LEFT: String = "left"
        const val CENTER: String = "center"
        const val RIGHT: String = "right"
        const val START: String = "start"
        const val END: String = "end"

        // Values for textCombine
        const val COMBINE_NONE: String = "none"
        const val COMBINE_ALL: String = "all"

        // Values for writingMode
        const val VERTICAL: String = "tb"
        const val VERTICAL_LR: String = "tblr"
        const val VERTICAL_RL: String = "tbrl"

        // Values for textEmphasis
        const val TEXT_EMPHASIS_NONE: String = "none"
        const val TEXT_EMPHASIS_AUTO: String = "auto"
        const val TEXT_EMPHASIS_MARK_DOT: String = "dot"
        const val TEXT_EMPHASIS_MARK_SESAME: String = "sesame"
        const val TEXT_EMPHASIS_MARK_CIRCLE: String = "circle"
        const val TEXT_EMPHASIS_MARK_FILLED: String = "filled"
        const val TEXT_EMPHASIS_MARK_OPEN: String = "open"

        fun buildTextNode(text: String): TtmlNode {
            return TtmlNode( 
                null,
                TtmlRenderUtil.applyTextElementSpacePolicy(text),  
                C.TIME_UNSET,  
                C.TIME_UNSET,  
                null,  
                null,
                ANONYMOUS_REGION_ID,  
                null,  
                null
            )
        }

        fun buildNode(
            tag: String?,
            startTimeUs: Long,
            endTimeUs: Long,
            style: TtmlStyle?,
            styleIds: Array<String?>?,
            regionId: String?,
            imageId: String?,
            parent: TtmlNode?
        ): TtmlNode {
            return TtmlNode(
                tag,  
                null,
                startTimeUs,
                endTimeUs,
                style,
                styleIds,
                regionId,
                imageId,
                parent
            )
        }

        private fun getRegionOutputText(
            resolvedRegionId: String, regionOutputs: MutableMap<String, Cue.Builder>
        ): SpannableStringBuilder {
            if (!regionOutputs.containsKey(resolvedRegionId)) {
                val regionOutput = Cue.Builder()
                regionOutput.setText(SpannableStringBuilder())
                regionOutputs.put(resolvedRegionId, regionOutput)
            }
            return regionOutputs.get(resolvedRegionId)!!.text!! as SpannableStringBuilder
        }

        private fun cleanUpText(builder: SpannableStringBuilder) {
            // Having joined the text elements, we need to do some final cleanup on the result.
            // Remove any text covered by a DeleteTextSpan (e.g. ruby text).
            val deleteTextSpans =
                builder.getSpans<DeleteTextSpan?>(0, builder.length, DeleteTextSpan::class.java)
            for (deleteTextSpan in deleteTextSpans) {
                builder.replace(
                    builder.getSpanStart(deleteTextSpan),
                    builder.getSpanEnd(deleteTextSpan),
                    ""
                )
            }
            // Collapse multiple consecutive spaces into a single space.
            for (i in 0..<builder.length) {
                if (builder[i] == ' ') {
                    var j = i + 1
                    while (j < builder.length && builder.get(j) == ' ') {
                        j++
                    }
                    val spacesToDelete = j - (i + 1)
                    if (spacesToDelete > 0) {
                        builder.delete(i, i + spacesToDelete)
                    }
                }
            }
            // Remove any spaces from the start of each line.
            if (builder.length > 0 && builder[0] == ' ') {
                builder.delete(0, 1)
            }
            for (i in 0..<builder.length - 1) {
                if (builder[i] == '\n' && builder.get(i + 1) == ' ') {
                    builder.delete(i + 1, i + 2)
                }
            }
            // Remove any spaces from the end of each line.
            if (builder.length > 0 && builder.get(builder.length - 1) == ' ') {
                builder.delete(builder.length - 1, builder.length)
            }
            for (i in 0..<builder.length - 1) {
                if (builder[i] == ' ' && builder.get(i + 1) == '\n') {
                    builder.delete(i, i + 1)
                }
            }
            // Trim a trailing newline, if there is one.
            if (builder.length > 0 && builder.get(builder.length - 1) == '\n') {
                builder.delete(builder.length - 1, builder.length)
            }
        }
    }
}
