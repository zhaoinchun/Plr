
package androidx.media3.extractor.text.subrip

import android.text.Html
import android.text.Spanned
import android.text.TextUtils
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.regex.Matcher
import java.util.regex.Pattern

class SubripParser : SubtitleParser {
    private val textBuilder: StringBuilder
    private val tags: ArrayList<String>
    private val parsableByteArray: ParsableByteArray

    init {
        textBuilder = StringBuilder()
        tags = ArrayList<String>()
        parsableByteArray = ParsableByteArray()
    }

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: (CuesWithTiming)-> Unit    ) {
        parsableByteArray.reset(data,  offset + length)
        parsableByteArray.setPosition(offset)
        val charset = detectUtfCharset(parsableByteArray)

        val cuesWithTimingBeforeRequestedStartTimeUs: MutableList<CuesWithTiming>? =
            if (outputOptions.startTimeUs != C.TIME_UNSET && outputOptions.outputAllCues)
                ArrayList<CuesWithTiming>()
            else
                null
        var currentLine: String?
        while ((parsableByteArray.readLine(charset).also { currentLine = it }) != null) {
            if (currentLine!!.isEmpty()) {
                // Skip blank lines.
                continue
            }

            // Parse and check the index line.
            try {
                currentLine.toInt()
            } catch (e: NumberFormatException) {
                Log.w(TAG, "Skipping invalid index: " + currentLine)
                continue
            }

            // Read and parse the timing line.
            currentLine = parsableByteArray.readLine(charset)
            if (currentLine == null) {
                Log.w(TAG, "Unexpected end")
                break
            }

            val startTimeUs: Long
            val endTimeUs: Long
            val matcher: Matcher = SUBRIP_TIMING_LINE.matcher(currentLine)
            if (matcher.matches()) {
                startTimeUs = parseTimecode(matcher,  1)
                endTimeUs = parseTimecode(matcher,  6)
            } else {
                Log.w(TAG, "Skipping invalid timing: " + currentLine)
                continue
            }

            // Read and parse the text and tags.
            textBuilder.setLength(0)
            tags.clear()
            currentLine = parsableByteArray.readLine(charset)
            while (!TextUtils.isEmpty(currentLine)) {
                if (textBuilder.length > 0) {
                    textBuilder.append("<br>")
                }
                textBuilder.append(processLine(currentLine!!, tags))
                currentLine = parsableByteArray.readLine(charset)
            }

            val text: Spanned? = Html.fromHtml(textBuilder.toString())

            var alignmentTag: String? = null
            for (i in tags.indices) {
                val tag = tags[i]
                if (tag.matches(SUBRIP_ALIGNMENT_TAG.toRegex())) {
                    alignmentTag = tag
                    // Subsequent alignment tags should be ignored.
                    break
                }
            }
            if (outputOptions.startTimeUs == C.TIME_UNSET || endTimeUs >= outputOptions.startTimeUs) {
                output(
                    CuesWithTiming(
                        listOf(buildCue(text, alignmentTag)),
                        startTimeUs,  
                        endTimeUs - startTimeUs
                    )
                )
            } else if (cuesWithTimingBeforeRequestedStartTimeUs != null) {
                cuesWithTimingBeforeRequestedStartTimeUs.add(
                    CuesWithTiming(
                        listOf(buildCue(text, alignmentTag)),
                        startTimeUs,  
                        endTimeUs - startTimeUs
                    )
                )
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
        return charset ?: StandardCharsets.UTF_8
    }

    private fun processLine(line: String, tags: ArrayList<String>): String {
        var line = line
        line = line.trim { it <= ' ' }

        var removedCharacterCount = 0
        val processedLine = StringBuilder(line)
        val matcher: Matcher = SUBRIP_TAG_PATTERN.matcher(line)
        while (matcher.find()) {
            val tag = matcher.group()
            tags.add(tag)
            val start = matcher.start() - removedCharacterCount
            val tagLength = tag.length
            processedLine.replace(start,  start + tagLength,  "")
            removedCharacterCount += tagLength
        }

        return processedLine.toString()
    }

    private fun buildCue(text: Spanned?, alignmentTag: String?): Cue {
        val cue = Cue.Builder().setText(text)
        if (alignmentTag == null) {
            return cue.build()
        }

        // Horizontal alignment.
        when (alignmentTag) {
            ALIGN_BOTTOM_LEFT, ALIGN_MID_LEFT, ALIGN_TOP_LEFT -> cue.setPositionAnchor(Cue.ANCHOR_TYPE_START)
            ALIGN_BOTTOM_RIGHT, ALIGN_MID_RIGHT, ALIGN_TOP_RIGHT -> cue.setPositionAnchor(Cue.ANCHOR_TYPE_END)
            ALIGN_BOTTOM_MID, ALIGN_MID_MID, ALIGN_TOP_MID -> cue.setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            else -> cue.setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        }

        // Vertical alignment.
        when (alignmentTag) {
            ALIGN_BOTTOM_LEFT, ALIGN_BOTTOM_MID, ALIGN_BOTTOM_RIGHT -> cue.setLineAnchor(Cue.ANCHOR_TYPE_END)
            ALIGN_TOP_LEFT, ALIGN_TOP_MID, ALIGN_TOP_RIGHT -> cue.setLineAnchor(Cue.ANCHOR_TYPE_START)
            ALIGN_MID_LEFT, ALIGN_MID_MID, ALIGN_MID_RIGHT -> cue.setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            else -> cue.setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        }

        return cue.setPosition(getFractionalPositionForAnchorType(cue.positionAnchor))
            .setLine(getFractionalPositionForAnchorType(cue.lineAnchor), Cue.LINE_TYPE_FRACTION)
            .build()
    }

    override val cueReplacementBehavior: Int
        get() = Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
    companion object {
        
        val cueReplacementBehavior: @Format.CueReplacementBehavior Int =
            Format.CUE_REPLACEMENT_BEHAVIOR_MERGE

        // Fractional positions for use when alignment tags are present.
        private const val START_FRACTION = 0.08f
        private val END_FRACTION: Float = 1 - START_FRACTION
        private const val MID_FRACTION = 0.5f

        private const val TAG = "SubripParser"

        // Some SRT files don't include hours or milliseconds in the timecode, so we use optional groups.
        private const val SUBRIP_TIMECODE = "(?:(\\d+):)?(\\d+):(\\d+)(?:,(\\d{3}))?"
        private val SUBRIP_TIMING_LINE: Pattern =
            Pattern.compile("\\s*(" + SUBRIP_TIMECODE + ")\\s*-->\\s*(" + SUBRIP_TIMECODE + ")\\s*")

        // NOTE: Android Studio's suggestion to simplify '\\}' is incorrect [internal: b/144480183].
        private val SUBRIP_TAG_PATTERN: Pattern = Pattern.compile("\\{\\\\.*?\\}")
        private const val SUBRIP_ALIGNMENT_TAG = "\\{\\\\an[1-9]\\}"

        // Alignment tags for SSA V4+.
        private const val ALIGN_BOTTOM_LEFT = "{\\an1}"
        private const val ALIGN_BOTTOM_MID = "{\\an2}"
        private const val ALIGN_BOTTOM_RIGHT = "{\\an3}"
        private const val ALIGN_MID_LEFT = "{\\an4}"
        private const val ALIGN_MID_MID = "{\\an5}"
        private const val ALIGN_MID_RIGHT = "{\\an6}"
        private const val ALIGN_TOP_LEFT = "{\\an7}"
        private const val ALIGN_TOP_MID = "{\\an8}"
        private const val ALIGN_TOP_RIGHT = "{\\an9}"

        private fun parseTimecode(matcher: Matcher, groupOffset: Int): Long {
            val hours = matcher.group(groupOffset + 1)
            var timestampMs = if (hours != null) hours.toLong() * 60 * 60 * 1000 else 0
            timestampMs += matcher.group(groupOffset + 2)!!
                .toLong() * 60 * 1000
            timestampMs += matcher.group(groupOffset + 3)!!
                .toLong() * 1000
            val millis = matcher.group(groupOffset + 4)
            if (millis != null) {
                timestampMs += millis.toLong()
            }
            return timestampMs * 1000
        }

        // TODO(b/289983417): Make package-private again, once it is no longer needed in
        // DelegatingSubtitleDecoderWithSubripParserTest.java (i.e. legacy subtitle flow is removed)
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        fun getFractionalPositionForAnchorType(@Cue.AnchorType anchorType: @Cue.AnchorType Int): Float {
            when (anchorType) {
                Cue.ANCHOR_TYPE_START -> return START_FRACTION
                Cue.ANCHOR_TYPE_MIDDLE -> return MID_FRACTION
                Cue.ANCHOR_TYPE_END -> return END_FRACTION
                Cue.TYPE_UNSET ->         // Should never happen.
                    throw IllegalArgumentException()

                else ->
                    throw IllegalArgumentException()
            }
        }
    }
}
