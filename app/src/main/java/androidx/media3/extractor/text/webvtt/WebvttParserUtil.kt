package androidx.media3.extractor.text.webvtt

import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.splitAtFirst
import java.util.regex.Matcher
import java.util.regex.Pattern

object WebvttParserUtil {
    private val COMMENT: Pattern = Pattern.compile("^NOTE([ \t].*)?$")
    private const val WEBVTT_HEADER = "WEBVTT"

    @Throws(ParserException::class)
    fun validateWebvttHeaderLine(input: ParsableByteArray) {
        val startPosition = input.getPosition()
        if (!isWebvttHeaderLine(input)) {
            input.setPosition(startPosition)
            throw ParserException.createForMalformedContainer(
                "Expected WEBVTT. Got " + input.readLine(),  null
            )
        }
    }

    fun isWebvttHeaderLine(input: ParsableByteArray): Boolean {
        val line = input.readLine()
        return line != null && line.startsWith(WEBVTT_HEADER)
    }

    fun parseTimestampUs(timestamp: String): Long {
        var value: Long = 0
        val parts = splitAtFirst(timestamp, "\\.")
        val subparts = Util.split(parts[0]!!, ":")
        for (subpart in subparts) {
            value = (value * 60) + subpart.toLong()
        }
        value *= 1000
        if (parts.size == 2) {
            val millis = parts[1]!!.trim { it <= ' ' }
            if (millis.length == 3) {
                value += millis.toLong()
            } else {
                throw IllegalArgumentException("Expected 3 decimal places, got: " + millis)
            }
        }
        return value * 1000
    }

    @Throws(NumberFormatException::class)
    fun parsePercentage(s: String): Float {
        if (!s.endsWith("%")) {
            throw NumberFormatException("Percentages must end with %")
        }
        return s.substring(0, s.length - 1).toFloat() / 100
    }

    fun findNextCueHeader(input: ParsableByteArray): Matcher? {
        var line: String?
        while ((input.readLine().also { line = it }) != null) {
            if (COMMENT.matcher(line).matches()) {
                // Skip until the end of the comment block.
                while ((input.readLine().also { line = it }) != null && !line!!.isEmpty()) {
                }
            } else {
                val cueHeaderMatcher = WebvttCueParser.CUE_HEADER_PATTERN.matcher(line)
                if (cueHeaderMatcher.matches()) {
                    return cueHeaderMatcher
                }
            }
        }
        return null
    }
}
