
package androidx.media3.ui

import android.text.BidiFormatter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextDirectionHeuristics
import androidx.media3.common.util.Log.w
import com.google.common.base.Joiner
import com.google.common.base.Splitter
import java.util.Arrays

internal object BidiUtils {
    private const val TAG = "BidiUtils"
    private val LF_SPLITTER: Splitter = Splitter.on("\n")
    private val CRLF_SPLITTER: Splitter = Splitter.on("\r\n")
    private val LF_JOINER: Joiner = Joiner.on("\n")

    fun containsRtl(input: CharSequence?): Boolean {
        if (input == null) {
            return false
        }
        val length = input.length
        var offset = 0
        while (offset < length) {
            val codePoint = Character.codePointAt(input, offset)
            val dir = Character.getDirectionality(codePoint)
            if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
                return true
            }
            offset += Character.charCount(codePoint)
        }
        return false
    }

    fun wrapText(input: CharSequence): CharSequence {
        val bidiFormatter = BidiFormatter.getInstance()
        var spannedInput: Spanned? = null
        var spans: Array<Any?>? = null
        var spanStarts: IntArray? = null
        var spanEnds: IntArray? = null

        if (input is Spanned) {
            // Preserve span in the input text.
            spannedInput = input
            spans = spannedInput.getSpans<Any?>(0, input.length, Any::class.java)
            // Create arrays to track the start and end of each span after wrapping.
            spanStarts = IntArray(spans.size)
            spanEnds = IntArray(spans.size)
            Arrays.fill(spanStarts, -1)
            Arrays.fill(spanEnds, -1)
        }

        // Determine the end-of-line (EOL) sequence for splitting the input text.
        val lines: MutableList<String>?
        val eolLength: Int
        if (input.toString().contains("\r\n")) {
            lines = CRLF_SPLITTER.splitToList(input)
            eolLength = 2
        } else {
            lines = LF_SPLITTER.splitToList(input)
            eolLength = 1
        }

        val wrappedLines: MutableList<String?> = ArrayList<String?>(lines.size)

        // Calculate the offset of each span after wrapping
        var spanUpdate = 0
        var lineStart = 0
        for (line in lines) {
            // According to unicodeWrap documentation, this will either add 2 more characters or none
            val wrappedLine = bidiFormatter.unicodeWrap(line, TextDirectionHeuristics.LTR)
            if (spans != null) {
                val diff = wrappedLine.length - line.length
                if (diff > 0) {
                    spanUpdate++
                }
                for (j in spans.indices) {
                    // Each span start or end is updated only once
                    if ((spanStarts!![j] < 0)
                        && (spannedInput!!.getSpanStart(spans[j]) >= lineStart)
                        && (spannedInput.getSpanStart(spans[j]) < lineStart + line.length)
                    ) {
                        spanStarts[j] = spanUpdate
                    }
                    if ((spanEnds!![j] < 0)
                        && ((spannedInput!!.getSpanEnd(spans[j]) - 1) >= lineStart)
                        && ((spannedInput.getSpanEnd(spans[j]) - 1) < lineStart + line.length)
                    ) {
                        spanEnds[j] = spanUpdate
                    }
                }
                lineStart += line.length + eolLength
                if (diff > 0) {
                    spanUpdate++
                }
            }
            wrappedLines.add(wrappedLine)
        }

        // Create a new SpannableStringBuilder with the wrapped lines.
        val wrapped = SpannableStringBuilder(LF_JOINER.join(wrappedLines))

        if (spans != null) {
            // Reapply original spans to the wrapped lines.
            for (i in spans.indices) {
                val start = spannedInput!!.getSpanStart(spans[i]) + spanStarts!![i]
                val end = spannedInput.getSpanEnd(spans[i]) + spanEnds!![i]
                val flags = spannedInput.getSpanFlags(spans[i])
                if ((start >= 0) && (start < wrapped.length) && (end >= 0) && (end <= wrapped.length)) {
                    // Only set the span if the start and end are within bounds of the wrapped text.
                    wrapped.setSpan(spans[i], start, end, flags)
                } else {
                    w(
                        TAG,
                        "Span out of bounds: start=" + start + ",end=" + end + ",len=" + wrapped.length
                    )
                }
            }
        }

        return wrapped
    }
}
