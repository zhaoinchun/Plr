
package androidx.media3.extractor.text.ttml

import android.annotation.SuppressLint
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.media3.common.text.Cue
import androidx.media3.common.text.HorizontalTextInVerticalContextSpan
import androidx.media3.common.text.RubySpan
import androidx.media3.common.text.SpanUtil
import androidx.media3.common.text.TextAnnotation
import androidx.media3.common.text.TextEmphasisSpan
import androidx.media3.common.util.Log
import androidx.media3.common.util.Util
import com.google.common.base.Preconditions
import java.util.ArrayDeque
import java.util.Deque

internal object TtmlRenderUtil {
    private const val TAG = "TtmlRenderUtil"

    fun resolveStyle(
        style: TtmlStyle?, styleIds: Array<String?>?, globalStyles: MutableMap<String, TtmlStyle>
    ): TtmlStyle? {
        if (style == null) {
            if (styleIds == null) {
                // No styles at all.
                return null
            } else if (styleIds.size == 1) {
                // Only one single referential style present.
                return globalStyles.get(styleIds[0])
            } else if (styleIds.size > 1) {
                // Only multiple referential styles present.
                val chainedStyle = TtmlStyle()
                for (id in styleIds) {
                    chainedStyle.chain(globalStyles.get(id))
                }
                return chainedStyle
            }
        } else   {
            if (styleIds != null && styleIds.size == 1) {
                // Merge a single referential style into inline style.
                return style.chain(globalStyles.get(styleIds[0]))
            } else if (styleIds != null && styleIds.size > 1) {
                // Merge multiple referential styles into inline style.
                for (id in styleIds) {
                    style.chain(globalStyles.get(id))
                }
                return style
            }
        }
        // Only inline styles available.
        return style
    }

    @SuppressLint("WrongConstant")
    fun applyStylesToSpan(
        builder: Spannable,
        start: Int,
        end: Int,
        style: TtmlStyle,
        parent: TtmlNode?,
        globalStyles: MutableMap<String, TtmlStyle>,
        @Cue.VerticalType verticalType: @Cue.VerticalType Int
    ) {
        if (style.style != TtmlStyle.UNSPECIFIED) {
            builder.setSpan(
                StyleSpan(style.style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (style.isLinethrough()) {
            builder.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (style.isUnderline()) {
            builder.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (style.hasFontColor()) {
            SpanUtil.addOrReplaceSpan(
                builder,
                ForegroundColorSpan(style.getFontColor()),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (style.hasBackgroundColor()) {
            SpanUtil.addOrReplaceSpan(
                builder,
                BackgroundColorSpan(style.getBackgroundColor()),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (style.fontFamily != null) {
            SpanUtil.addOrReplaceSpan(
                builder,
                TypefaceSpan(style.fontFamily),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (style.textEmphasis != null) {
            val textEmphasis = style.textEmphasis!!
            val markShape: @TextEmphasisSpan.MarkShape Int
            val markFill: @TextEmphasisSpan.MarkFill Int
            if (textEmphasis.markShape == TextEmphasis.MARK_SHAPE_AUTO) {
                // If a vertical writing mode applies, then 'auto' is equivalent to 'filled sesame';
                // otherwise, it's equivalent to 'filled circle':
                // https://www.w3.org/TR/ttml2/#style-value-emphasis-style
                markShape =
                    if (verticalType == Cue.VERTICAL_TYPE_LR || verticalType == Cue.VERTICAL_TYPE_RL)
                        TextEmphasisSpan.MARK_SHAPE_SESAME
                    else
                        TextEmphasisSpan.MARK_SHAPE_CIRCLE
                markFill = TextEmphasisSpan.MARK_FILL_FILLED
            } else {
                markShape = textEmphasis.markShape
                markFill = textEmphasis.markFill
            }

            val position: @TextEmphasis.Position Int
            if (textEmphasis.position == TextEmphasis.POSITION_OUTSIDE) {
                // 'outside' is not supported by TextEmphasisSpan, so treat it as 'before':
                // https://www.w3.org/TR/ttml2/#style-value-annotation-position
                position = TextAnnotation.POSITION_BEFORE
            } else {
                position = textEmphasis.position
            }

            SpanUtil.addOrReplaceSpan(
                builder,
                TextEmphasisSpan(markShape, markFill, position),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        when (style.rubyType) {
            l@
            TtmlStyle.RUBY_TYPE_BASE -> {
                // look for the sibling RUBY_TEXT and add it as span between start & end.
                val containerNode = findRubyContainerNode(parent, globalStyles)
                if (containerNode != null) {
                    val textNode = findRubyTextNode(containerNode, globalStyles)
                    if (textNode != null) {
                        val rubyText: String?
                        if (textNode.childCount == 1 && textNode.getChild(0)?.text != null) {
                            rubyText = textNode.getChild(0)?.text!!
                            val textStyle = resolveStyle(textNode.style, textNode.styleIds, globalStyles)

                            // Use position from ruby text node if defined.
                            var rubyPosition: @TextAnnotation.Position Int =
                                if (textStyle != null) textStyle.rubyPosition else TextAnnotation.POSITION_UNKNOWN

                            if (rubyPosition == TextAnnotation.POSITION_UNKNOWN) {
                                // If ruby position is not defined, use position info from container node.
                                val containerStyle =
                                    resolveStyle(containerNode.style, containerNode.styleIds, globalStyles)
                                rubyPosition =
                                    if (containerStyle != null) containerStyle.rubyPosition else rubyPosition
                            }

                            builder.setSpan(
                                RubySpan(rubyText, rubyPosition), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        } else {
                            Log.i(TAG, "Skipping rubyText node without exactly one text child.")
                        }
                    }
                }
            }

            TtmlStyle.RUBY_TYPE_DELIMITER, TtmlStyle.RUBY_TYPE_TEXT ->         // We can't just remove the text directly from `builder` here because TtmlNode has fixed
                // ideas of where every node starts and ends (nodeStartsByRegion and nodeEndsByRegion) so
                // all these indices become invalid if we mutate the underlying string at this point.
                // Instead we add a special span that's then handled in TtmlNode#cleanUpText.
                builder.setSpan(DeleteTextSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            TtmlStyle.RUBY_TYPE_CONTAINER, TtmlStyle.UNSPECIFIED -> {}
            else -> {}
        }
        if (style.getTextCombine()) {
            SpanUtil.addOrReplaceSpan(
                builder,
                HorizontalTextInVerticalContextSpan(),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        when (style.fontSizeUnit) {
            TtmlStyle.FONT_SIZE_UNIT_PIXEL -> SpanUtil.addOrReplaceSpan(
                builder,
                AbsoluteSizeSpan(style.fontSize.toInt(), true),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            TtmlStyle.FONT_SIZE_UNIT_EM -> SpanUtil.addOrReplaceSpan(
                builder,
                RelativeSizeSpan(style.fontSize),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            TtmlStyle.FONT_SIZE_UNIT_PERCENT -> SpanUtil.addInheritedRelativeSizeSpan(
                builder, style.fontSize / 100, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            TtmlStyle.UNSPECIFIED -> {}
        }
    }

    private fun findRubyTextNode(
        rubyContainerNode: TtmlNode?, globalStyles: MutableMap<String, TtmlStyle>
    ): TtmlNode? {
        val childNodesStack: Deque<TtmlNode> = ArrayDeque<TtmlNode>()
        childNodesStack.push(rubyContainerNode)
        while (!childNodesStack.isEmpty()) {
            val childNode = childNodesStack.pop()
            val style = resolveStyle(childNode.style, childNode.styleIds, globalStyles)
            if (style != null && style.rubyType == TtmlStyle.RUBY_TYPE_TEXT) {
                return childNode
            }
            for (i in childNode.childCount - 1 downTo 0) {
                childNodesStack.push(childNode.getChild(i))
            }
        }

        return null
    }

    private fun findRubyContainerNode(
        node: TtmlNode?, globalStyles: MutableMap<String, TtmlStyle>
    ): TtmlNode? {
        var node = node
        while (node != null) {
            val style = resolveStyle(node.style, node.styleIds, globalStyles)
            if (style != null && style.rubyType == TtmlStyle.RUBY_TYPE_CONTAINER) {
                return node
            }
            node = node.parent
        }
        return null
    }

    fun endParagraph(builder: SpannableStringBuilder) {
        var position: Int = builder.length - 1
        while (position >= 0 && builder.get(position) == ' ') {
            position--
        }
        if (position >= 0 && builder.get(position) != '\n') {
            builder.append('\n')
        }
    }

    fun applyTextElementSpacePolicy(`in`: String): String {
        // Removes carriage return followed by line feed. See: http://www.w3.org/TR/xml/#sec-line-ends
        var out = `in`.replace("\r\n".toRegex(), "\n")
        // Apply suppress-at-line-break="auto" and
        // white-space-treatment="ignore-if-surrounding-linefeed"
        out = out.replace(" *\n *".toRegex(), "\n")
        // Apply linefeed-treatment="treat-as-space"
        out = out.replace("\n".toRegex(), " ")
        // Apply white-space-collapse="true"
        out = out.replace("[ \t\\x0B\u000c\r]+".toRegex(), " ")
        return out
    }
}
