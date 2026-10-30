
package androidx.media3.extractor

import androidx.media3.common.Format
import androidx.media3.common.Metadata

import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import com.google.common.base.Predicate
import java.util.regex.Matcher
import java.util.regex.Pattern

class GaplessInfoHolder {
    var encoderDelay: Int
    var encoderPadding: Int
    init {
        encoderDelay = Format.NO_VALUE
        encoderPadding = Format.NO_VALUE
    }

    fun setFromMetadata(metadata: Metadata): Boolean {
        for (gaplessCommentFrame in metadata.getMatchingEntries<CommentFrame>(
            CommentFrame::class.java,
            Predicate { commentFrame: CommentFrame -> commentFrame.description == GAPLESS_DESCRIPTION })) {
            if (setFromComment(gaplessCommentFrame!!.text!!)) {
                return true
            }
        }
        for (gaplessInternalFrame in metadata.getMatchingEntries<InternalFrame>(
            InternalFrame::class.java,
            Predicate { internalFrame: InternalFrame? ->
                internalFrame!!.domain == GAPLESS_DOMAIN
                        && internalFrame.description == GAPLESS_DESCRIPTION
            })) {
            if (setFromComment(gaplessInternalFrame!!.text!!)) {
                return true
            }
        }
        return false
    }

    private fun setFromComment(data: String): Boolean {
        val matcher: Matcher = GAPLESS_COMMENT_PATTERN.matcher(data)
        if (matcher.find()) {
            try {
                val encoderDelay = matcher.group(1)!!.toInt(16)
                val encoderPadding = matcher.group(2)!!.toInt(16)
                if (encoderDelay > 0 || encoderPadding > 0) {
                    this.encoderDelay = encoderDelay
                    this.encoderPadding = encoderPadding
                    return true
                }
            } catch (e: NumberFormatException) {
                // Ignore incorrectly formatted comments.
            }
        }
        return false
    }

    fun hasGaplessInfo(): Boolean {
        return encoderDelay != Format.NO_VALUE && encoderPadding != Format.NO_VALUE
    }

    companion object {
        private const val GAPLESS_DOMAIN = "com.apple.iTunes"
        private const val GAPLESS_DESCRIPTION = "iTunSMPB"
        private val GAPLESS_COMMENT_PATTERN: Pattern =
            Pattern.compile("^ [0-9a-fA-F]{8} ([0-9a-fA-F]{8}) ([0-9a-fA-F]{8})")
    }
}
