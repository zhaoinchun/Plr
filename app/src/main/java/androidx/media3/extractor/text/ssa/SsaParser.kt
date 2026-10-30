
package androidx.media3.extractor.text.ssa

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.base.Ascii
import com.google.common.base.Preconditions

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.regex.Matcher
import java.util.regex.Pattern

class SsaParser  constructor(initializationData: List<ByteArray>? = null) :
    SubtitleParser {
    private val haveInitializationData: Boolean
    private val dialogueFormatFromInitializationData: SsaDialogueFormat?
    private val parsableByteArray: ParsableByteArray

    private var styles:MutableMap<String?, SsaStyle?>? = null

    private var screenWidth: Float

    private var screenHeight: Float

    init {
        screenWidth = Cue.DIMEN_UNSET
        screenHeight = Cue.DIMEN_UNSET
        parsableByteArray = ParsableByteArray()

        if (initializationData != null && !initializationData.isEmpty()) {
            haveInitializationData = true
            // Currently, construction with initialization data is only relevant to SSA subtitles muxed
            // in a MKV. According to https://www.matroska.org/technical/subtitles.html, these muxed
            // subtitles are always encoded in UTF-8.
            val formatLine = Util.fromUtf8Bytes(initializationData[0])
            Preconditions.checkArgument(formatLine.startsWith(FORMAT_LINE_PREFIX))
            dialogueFormatFromInitializationData = SsaDialogueFormat.fromFormatLine(formatLine)!!
            parseHeader(ParsableByteArray(initializationData.get(1)), StandardCharsets.UTF_8)
        } else {
            haveInitializationData = false
            dialogueFormatFromInitializationData = null
        }
    }

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: (CuesWithTiming) -> Unit
    ) {
        val cues: MutableList<MutableList<Cue>> = ArrayList<MutableList<Cue>>()
        val startTimesUs: MutableList<Long?> = ArrayList<Long?>()

        parsableByteArray.reset(data,  offset + length)
        parsableByteArray.setPosition(offset)
        val charset = detectUtfCharset(parsableByteArray)

        if (!haveInitializationData) {
            parseHeader(parsableByteArray, charset)
        }
        parseEventBody(parsableByteArray, cues, startTimesUs, charset)

        val cuesWithTimingBeforeRequestedStartTimeUs: MutableList<CuesWithTiming>? =
            if (outputOptions.startTimeUs != C.TIME_UNSET && outputOptions.outputAllCues)
                ArrayList<CuesWithTiming>()
            else
                null
        for (i in cues.indices) {
            val cuesForThisStartTime: MutableList<Cue> = cues[i]
            if (cuesForThisStartTime.isEmpty() && i != 0) {
                // An empty cue list has already been implicitly encoded in the duration of the previous
                // sample (unless there was no previous sample).
                continue
            } else check(i != cues.size - 1)
            val startTimeUs: Long = startTimesUs[i]!!
            // It's safe to inspect element i+1, because we already exited the loop above if i=size()-1.
            val endTimeUs: Long = startTimesUs.get(i + 1)!!
            val cuesWithTiming: CuesWithTiming =
                CuesWithTiming(
                    cuesForThisStartTime, startTimeUs,  endTimeUs - startTimeUs
                )
            if (outputOptions.startTimeUs == C.TIME_UNSET || endTimeUs >= outputOptions.startTimeUs) {
                output(cuesWithTiming)
            } else if (cuesWithTimingBeforeRequestedStartTimeUs != null) {
                cuesWithTimingBeforeRequestedStartTimeUs.add(cuesWithTiming)
            }
        }
        if (cuesWithTimingBeforeRequestedStartTimeUs != null) {
            for (cuesWithTiming in cuesWithTimingBeforeRequestedStartTimeUs) {
                output(cuesWithTiming)
            }
        }
    }

    private fun detectUtfCharset(data: ParsableByteArray): Charset {
        val charset: Charset? = data.readUtfCharsetFromBom()
        return if (charset != null) charset else StandardCharsets.UTF_8
    }

    private fun parseHeader(data: ParsableByteArray, charset: Charset) {
        var currentLine: String?
        while ((data.readLine(charset).also { currentLine = it }) != null) {
            if ("[Script Info]".equals(currentLine, ignoreCase = true)) {
                parseScriptInfo(data, charset)
            } else if ("[V4+ Styles]".equals(currentLine, ignoreCase = true)) {
                styles = parseStyles(data, charset)
            } else if ("[V4 Styles]".equals(currentLine, ignoreCase = true)) {
                Log.i(TAG, "[V4 Styles] are not supported")
            } else if ("[Events]".equals(currentLine, ignoreCase = true)) {
                // We've reached the [Events] section, so the header is over.
                return
            }
        }
    }

    private fun parseScriptInfo(data: ParsableByteArray, charset: Charset) {
        var currentLine: String?
        while ((data.readLine(charset).also { currentLine = it }) != null
            && (data.bytesLeft() == 0 || data.peekCodePoint(charset) != '['.code)
        ) {
            val infoNameAndValue: Array<String?> =
                currentLine!!.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (infoNameAndValue.size != 2) {
                continue
            }
            when (Ascii.toLowerCase(infoNameAndValue[0]!!.trim { it <= ' ' })) {
                "playresx" -> try {
                    screenWidth = infoNameAndValue[1]!!.trim { it <= ' ' }.toFloat()
                } catch (e: NumberFormatException) {
                    // Ignore invalid PlayResX value.
                }

                "playresy" -> try {
                    screenHeight = infoNameAndValue[1]!!.trim { it <= ' ' }.toFloat()
                } catch (e: NumberFormatException) {
                    // Ignore invalid PlayResY value.
                }
            }
        }
    }

    private fun parseEventBody(
        data: ParsableByteArray,
        cues: MutableList<MutableList<Cue>>,
        cueTimesUs: MutableList<Long?>,
        charset: Charset
    ) {
        var format = if (haveInitializationData) dialogueFormatFromInitializationData else null
        var currentLine: String?
        while ((data.readLine(charset).also { currentLine = it }) != null) {
            if (currentLine!!.startsWith(FORMAT_LINE_PREFIX)) {
                format = SsaDialogueFormat.fromFormatLine(currentLine)
            } else if (currentLine.startsWith(DIALOGUE_LINE_PREFIX)) {
                if (format == null) {
                    Log.w(TAG, "Skipping dialogue line before complete format: " + currentLine)
                    continue
                }
                parseDialogueLine(currentLine, format, cues, cueTimesUs)
            }
        }
    }

    private fun parseDialogueLine(
        dialogueLine: String,
        format: SsaDialogueFormat,
        cues: MutableList<MutableList<Cue>>,
        cueTimesUs: MutableList<Long?>
    ) {
        Preconditions.checkArgument(dialogueLine.startsWith(DIALOGUE_LINE_PREFIX))
        val lineValues: Array<String> =
            dialogueLine.substring(DIALOGUE_LINE_PREFIX.length)
                .split(",".toRegex(), format.length.coerceAtLeast(0)).toTypedArray()
        if (lineValues.size != format.length) {
            Log.w(TAG, "Skipping dialogue line with fewer columns than format: " + dialogueLine)
            return
        }

        var layer = 0
        if (format.layerIndex != C.INDEX_UNSET) {
            try {
                layer = lineValues[format.layerIndex]!!.trim { it <= ' ' }.toInt()
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Fail to parse layer: " + lineValues[format.layerIndex])
            }
        }

        val startTimeUs: Long = Companion.parseTimecodeUs(lineValues[format.startTimeIndex]!!)
        if (startTimeUs == C.TIME_UNSET) {
            Log.w(TAG, "Skipping invalid timing: " + dialogueLine)
            return
        }

        val endTimeUs: Long = Companion.parseTimecodeUs(lineValues[format.endTimeIndex]!!)
        if (endTimeUs == C.TIME_UNSET || endTimeUs <= startTimeUs) {
            Log.w(TAG, "Skipping invalid timing: " + dialogueLine)
            return
        }

        val style: SsaStyle? =
            if (styles != null && format.styleIndex != C.INDEX_UNSET)
                styles!!.get(lineValues[format.styleIndex]!!.trim { it <= ' ' })
            else
                null
        val rawText = lineValues[format.textIndex]
        val styleOverrides: SsaStyle.Overrides = SsaStyle.Overrides.parseFromDialogue(rawText)
        val text: String? =
            SsaStyle.Overrides.stripStyleOverrides(rawText)
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", "\u00A0")
        val cue: Cue = createCue(text, layer, style, styleOverrides, screenWidth, screenHeight)

        val startTimeIndex: Int = addCuePlacerholderByTime(startTimeUs, cueTimesUs, cues)
        val endTimeIndex: Int = addCuePlacerholderByTime(endTimeUs, cueTimesUs, cues)
        // Iterate on cues from startTimeIndex until endTimeIndex, adding the current cue.
        for (i in startTimeIndex..<endTimeIndex) {
            cues[i].add(cue)
        }
    }

    override val cueReplacementBehavior: Int
        get() = Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
    companion object {
        val cueReplacementBehavior: @Format.CueReplacementBehavior Int =
            Format.CUE_REPLACEMENT_BEHAVIOR_MERGE

        private const val TAG = "SsaParser"

        private val SSA_TIMECODE_PATTERN: Pattern =
            Pattern.compile("(?:(\\d+):)?(\\d+):(\\d+)[:.](\\d+)")

        const val FORMAT_LINE_PREFIX: String = "Format:"

        const val STYLE_LINE_PREFIX: String = "Style:"
        private const val DIALOGUE_LINE_PREFIX = "Dialogue:"

        private const val DEFAULT_MARGIN = 0.05f

        private fun parseStyles(
            data: ParsableByteArray,
            charset: Charset
        ): MutableMap<String?, SsaStyle?> {
            val styles: MutableMap<String?, SsaStyle?> = LinkedHashMap<String?, SsaStyle?>()
            var formatInfo: SsaStyle.Format? = null
            var currentLine: String?
            while ((data.readLine(charset).also { currentLine = it }) != null
                && (data.bytesLeft() == 0 || data.peekCodePoint(charset) != '['.code)
            ) {
                if (currentLine!!.startsWith(FORMAT_LINE_PREFIX)) {
                    formatInfo = SsaStyle.Format.fromFormatLine(currentLine)
                } else if (currentLine.startsWith(STYLE_LINE_PREFIX)) {
                    if (formatInfo == null) {
                        Log.w(TAG, "Skipping 'Style:' line before 'Format:' line: " + currentLine)
                        continue
                    }
                    val style: SsaStyle? = SsaStyle.fromStyleLine(currentLine, formatInfo)
                    if (style != null) {
                        styles.put(style.name, style)
                    }
                }
            }
            return styles
        }

        private fun parseTimecodeUs(timeString: String): Long {
            val matcher: Matcher = SSA_TIMECODE_PATTERN.matcher(timeString.trim { it <= ' ' })
            if (!matcher.matches()) {
                return C.TIME_UNSET
            }
            var timestampUs =
                matcher.group(1)!!.toLong() * 60 * 60 * C.MICROS_PER_SECOND
            timestampUs +=matcher.group(2)!!.toLong() * 60 * C.MICROS_PER_SECOND
            timestampUs += matcher.group(3)!!.toLong() * C.MICROS_PER_SECOND
            timestampUs += matcher.group(4)!!.toLong() * 10000 // 100ths of a second.
            return timestampUs
        }

        private fun createCue(
            text: String?,
            layer: Int,
            style: SsaStyle?,
            styleOverrides: SsaStyle.Overrides,
            screenWidth: Float,
            screenHeight: Float
        ): Cue {
            val spannableText: SpannableString = SpannableString(text)
            val cue = Cue.Builder().setText(spannableText).setZIndex(layer)

            if (style != null) {
                if (style.primaryColor != null) {
                    spannableText.setSpan(
                        ForegroundColorSpan(style.primaryColor),  
                        0,  
                        spannableText.length,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (style.borderStyle === SsaStyle.SSA_BORDER_STYLE_BOX && style.outlineColor != null) {
                    spannableText.setSpan(
                        BackgroundColorSpan(style.outlineColor),  
                        0,  
                        spannableText.length,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (style.fontSize !== Cue.DIMEN_UNSET && screenHeight != Cue.DIMEN_UNSET) {
                    cue.setTextSize(
                        style.fontSize / screenHeight, Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING
                    )
                }
                if (style.bold && style.italic) {
                    spannableText.setSpan(
                        StyleSpan(Typeface.BOLD_ITALIC),  
                        0,  
                        spannableText.length,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else if (style.bold) {
                    spannableText.setSpan(
                        StyleSpan(Typeface.BOLD),  
                        0,  
                        spannableText.length,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else if (style.italic) {
                    spannableText.setSpan(
                        StyleSpan(Typeface.ITALIC),  
                        0,  
                        spannableText.length,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (style.underline) {
                    spannableText.setSpan(
                        UnderlineSpan(),  
                        0,  
                        spannableText.length,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (style.strikeout) {
                    spannableText.setSpan(
                        StrikethroughSpan(),  
                        0,  
                        spannableText.length,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

 val alignment: Int
            if (styleOverrides.alignment !== SsaStyle.SSA_ALIGNMENT_UNKNOWN) {
                alignment = styleOverrides.alignment
            } else if (style != null) {
                alignment = style.alignment
            } else {
                alignment = SsaStyle.SSA_ALIGNMENT_UNKNOWN
            }
            cue.setTextAlignment(toTextAlignment(alignment))
                .setPositionAnchor(toPositionAnchor(alignment))
                .setLineAnchor(toLineAnchor(alignment))

            if (styleOverrides.position != null && screenHeight != Cue.DIMEN_UNSET && screenWidth != Cue.DIMEN_UNSET) {
                cue.setPosition(styleOverrides.position.x / screenWidth)
                cue.setLine(
                    styleOverrides.position.y / screenHeight,
                    Cue.LINE_TYPE_FRACTION
                )
            } else {
                // TODO: Read the MarginL, MarginR and MarginV values from the Style & Dialogue lines.
                cue.setPosition(computeDefaultLineOrPosition(cue.positionAnchor))
                cue.setLine(
                    computeDefaultLineOrPosition(cue.lineAnchor),
                    Cue.LINE_TYPE_FRACTION
                )
            }

            return cue.build()
        }

        private fun toTextAlignment( alignment: Int): Layout.Alignment? {
            when (alignment) {
                SsaStyle.SSA_ALIGNMENT_BOTTOM_LEFT, SsaStyle.SSA_ALIGNMENT_MIDDLE_LEFT, SsaStyle.SSA_ALIGNMENT_TOP_LEFT -> return Layout.Alignment.ALIGN_NORMAL
                SsaStyle.SSA_ALIGNMENT_BOTTOM_CENTER, SsaStyle.SSA_ALIGNMENT_MIDDLE_CENTER, SsaStyle.SSA_ALIGNMENT_TOP_CENTER -> return Layout.Alignment.ALIGN_CENTER
                SsaStyle.SSA_ALIGNMENT_BOTTOM_RIGHT, SsaStyle.SSA_ALIGNMENT_MIDDLE_RIGHT, SsaStyle.SSA_ALIGNMENT_TOP_RIGHT -> return Layout.Alignment.ALIGN_OPPOSITE
                SsaStyle.SSA_ALIGNMENT_UNKNOWN -> return null
                else -> {
                    Log.w(TAG, "Unknown alignment: " + alignment)
                    return null
                }
            }
        }

        @Cue.AnchorType
        private fun toLineAnchor(alignment: Int): @Cue.AnchorType Int {
            when (alignment) {
                SsaStyle.SSA_ALIGNMENT_BOTTOM_LEFT, SsaStyle.SSA_ALIGNMENT_BOTTOM_CENTER, SsaStyle.SSA_ALIGNMENT_BOTTOM_RIGHT -> return Cue.ANCHOR_TYPE_END
                SsaStyle.SSA_ALIGNMENT_MIDDLE_LEFT, SsaStyle.SSA_ALIGNMENT_MIDDLE_CENTER, SsaStyle.SSA_ALIGNMENT_MIDDLE_RIGHT -> return Cue.ANCHOR_TYPE_MIDDLE
                SsaStyle.SSA_ALIGNMENT_TOP_LEFT, SsaStyle.SSA_ALIGNMENT_TOP_CENTER, SsaStyle.SSA_ALIGNMENT_TOP_RIGHT -> return Cue.ANCHOR_TYPE_START
                SsaStyle.SSA_ALIGNMENT_UNKNOWN -> return Cue.TYPE_UNSET
                else -> {
                    Log.w(TAG, "Unknown alignment: " + alignment)
                    return Cue.TYPE_UNSET
                }
            }
        }

        @Cue.AnchorType
        private fun toPositionAnchor(alignment: Int): @Cue.AnchorType Int {
            when (alignment) {
                SsaStyle.SSA_ALIGNMENT_BOTTOM_LEFT, SsaStyle.SSA_ALIGNMENT_MIDDLE_LEFT, SsaStyle.SSA_ALIGNMENT_TOP_LEFT -> return Cue.ANCHOR_TYPE_START
                SsaStyle.SSA_ALIGNMENT_BOTTOM_CENTER, SsaStyle.SSA_ALIGNMENT_MIDDLE_CENTER, SsaStyle.SSA_ALIGNMENT_TOP_CENTER -> return Cue.ANCHOR_TYPE_MIDDLE
                SsaStyle.SSA_ALIGNMENT_BOTTOM_RIGHT, SsaStyle.SSA_ALIGNMENT_MIDDLE_RIGHT, SsaStyle.SSA_ALIGNMENT_TOP_RIGHT -> return Cue.ANCHOR_TYPE_END
                SsaStyle.SSA_ALIGNMENT_UNKNOWN -> return Cue.TYPE_UNSET
                else -> {
                    Log.w(TAG, "Unknown alignment: " + alignment)
                    return Cue.TYPE_UNSET
                }
            }
        }

        private fun computeDefaultLineOrPosition(@Cue.AnchorType anchor: @Cue.AnchorType Int): Float {
            when (anchor) {
                Cue.ANCHOR_TYPE_START -> return DEFAULT_MARGIN
                Cue.ANCHOR_TYPE_MIDDLE -> return 0.5f
                Cue.ANCHOR_TYPE_END -> return 1.0f - DEFAULT_MARGIN
                Cue.TYPE_UNSET -> return Cue.DIMEN_UNSET
                else -> return Cue.DIMEN_UNSET
            }
        }

        private fun addCuePlacerholderByTime(
            timeUs: Long, sortedCueTimesUs: MutableList<Long?>, cues: MutableList<MutableList<Cue>>
        ): Int {
            var insertionIndex = 0
            for (i in sortedCueTimesUs.indices.reversed()) {
                if (sortedCueTimesUs[i] == timeUs) {
                    return i
                }

                if (sortedCueTimesUs[i]!! < timeUs) {
                    insertionIndex = i + 1
                    break
                }
            }
            sortedCueTimesUs.add(insertionIndex, timeUs)
            // Copy over cues from left, or use an empty list if we're inserting at the beginning.
            cues.add(
                insertionIndex,
                if (insertionIndex == 0) ArrayList<Cue>() else ArrayList<Cue>(
                    cues.get(
                        insertionIndex - 1
                    )
                )
            )
            return insertionIndex
        }
    }
}
