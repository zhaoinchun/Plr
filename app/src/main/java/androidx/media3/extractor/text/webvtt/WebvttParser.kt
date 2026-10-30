package androidx.media3.extractor.text.webvtt

import android.text.TextUtils
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.LegacySubtitleUtil
import androidx.media3.extractor.text.SubtitleParser

class WebvttParser : SubtitleParser {
    private val parsableWebvttData: ParsableByteArray
    private val cssParser: WebvttCssParser

    init {
        parsableWebvttData = ParsableByteArray()
        cssParser = WebvttCssParser()
    }

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: ((CuesWithTiming)-> Unit)
    ) {
        parsableWebvttData.reset(data,  offset + length)
        parsableWebvttData.setPosition(offset)
        val definedStyles: MutableList<WebvttCssStyle> = ArrayList<WebvttCssStyle>()

        // Validate the first line of the header, and skip the remainder.
        try {
            WebvttParserUtil.validateWebvttHeaderLine(parsableWebvttData)
        } catch (e: ParserException) {
            throw IllegalArgumentException(e)
        }
        while (!TextUtils.isEmpty(parsableWebvttData.readLine())) {
        }

        var event: Int
        val cueInfos: MutableList<WebvttCueInfo> = ArrayList<WebvttCueInfo>()
        while ((getNextEvent(parsableWebvttData).also { event = it }) != EVENT_END_OF_FILE) {
            if (event == EVENT_COMMENT) {
                skipComment(parsableWebvttData)
            } else if (event == EVENT_STYLE_BLOCK) {
                require(cueInfos.isEmpty()) { "A style block was found after the first cue." }
                parsableWebvttData.readLine() // Consume the "STYLE" header.
                definedStyles.addAll(cssParser.parseBlock(parsableWebvttData))
            } else if (event == EVENT_CUE) {
                val cueInfo = WebvttCueParser.parseCue(parsableWebvttData, definedStyles)
                if (cueInfo != null) {
                    cueInfos.add(cueInfo)
                }
            }
        }
        val subtitle = WebvttSubtitle(cueInfos)
        LegacySubtitleUtil.toCuesWithTiming(subtitle, outputOptions, output)
    }

    override val cueReplacementBehavior: Int
        get() = Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
    companion object {
        val cueReplacementBehavior: @Format.CueReplacementBehavior Int =
            Format.CUE_REPLACEMENT_BEHAVIOR_MERGE

        private val EVENT_NONE = -1
        private const val EVENT_END_OF_FILE = 0
        private const val EVENT_COMMENT = 1
        private const val EVENT_STYLE_BLOCK = 2
        private const val EVENT_CUE = 3

        private const val COMMENT_START = "NOTE"
        private const val STYLE_START = "STYLE"

        private fun getNextEvent(parsableWebvttData: ParsableByteArray): Int {
            var foundEvent: Int = EVENT_NONE
            var currentInputPosition = 0
            while (foundEvent == EVENT_NONE) {
                currentInputPosition = parsableWebvttData.getPosition()
                val line: String? = parsableWebvttData.readLine()
                if (line == null) {
                    foundEvent = EVENT_END_OF_FILE
                } else if (STYLE_START == line) {
                    foundEvent = EVENT_STYLE_BLOCK
                } else if (line.startsWith(COMMENT_START)) {
                    foundEvent = EVENT_COMMENT
                } else {
                    foundEvent = EVENT_CUE
                }
            }
            parsableWebvttData.setPosition(currentInputPosition)
            return foundEvent
        }

        private fun skipComment(parsableWebvttData: ParsableByteArray) {
            while (!TextUtils.isEmpty(parsableWebvttData.readLine())) {
            }
        }
    }
}
