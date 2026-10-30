/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package androidx.media3.ui

import android.graphics.Typeface
import android.text.Html
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.SparseArray
import androidx.media3.common.text.HorizontalTextInVerticalContextSpan
import androidx.media3.common.text.RubySpan
import androidx.media3.common.text.TextAnnotation
import androidx.media3.common.text.TextEmphasisSpan
import androidx.media3.common.util.Util.formatInvariant
import java.util.Collections
import java.util.regex.Pattern

internal object SpannedToHtmlConverter {
    // Matches /n and /r/n in ampersand-encoding (returned from Html.escapeHtml).
    private val NEWLINE_PATTERN: Pattern = Pattern.compile("(&#13;)?&#10;")

    fun convert(text: CharSequence?, displayDensity: Float): HtmlAndCss {
        if (text == null) {
            return HtmlAndCss("",  mapOf<String, String>())
        }
        if (text !is Spanned) {
            return HtmlAndCss(
                escapeHtml(text),  
                mapOf()
            )
        }
        val spanned = text

        // Use CSS inheritance to ensure BackgroundColorSpans affect all inner elements
        val backgroundColors: MutableSet<Int?> = HashSet<Int?>()
        for (backgroundColorSpan in spanned.getSpans<BackgroundColorSpan>(
            0,
            spanned.length,
            BackgroundColorSpan::class.java
        )) {
            backgroundColors.add(backgroundColorSpan.getBackgroundColor())
        }
        val cssRuleSets = HashMap<String, String>()
        for (backgroundColor in backgroundColors) {
            cssRuleSets.put(
                HtmlUtils.cssAllClassDescendantsSelector("bg_" + backgroundColor),
                formatInvariant("background-color:%s;", HtmlUtils.toCssRgba(backgroundColor!!))
            )
        }

        val spanTransitions = findSpanTransitions(spanned, displayDensity)
        val html = StringBuilder(spanned.length)
        var previousTransition = 0
        for (i in 0..<spanTransitions.size()) {
            val index = spanTransitions.keyAt(i)
            html.append(escapeHtml(spanned.subSequence(previousTransition, index)))

            val transition = spanTransitions.get(index)
            Collections.sort<SpanInfo>(
                transition.spansRemoved,
                SpanInfo.FOR_CLOSING_TAGS
            )
            for (spanInfo in transition.spansRemoved) {
                html.append(spanInfo.closingTag)
            }
            Collections.sort<SpanInfo?>(transition.spansAdded, SpanInfo.FOR_OPENING_TAGS)
            for (spanInfo in transition.spansAdded) {
                html.append(spanInfo.openingTag)
            }
            previousTransition = index
        }

        html.append(escapeHtml(spanned.subSequence(previousTransition, spanned.length)))

        return HtmlAndCss(html.toString(), cssRuleSets)
    }

    private fun findSpanTransitions(
        spanned: Spanned, displayDensity: Float
    ): SparseArray<Transition> {
        val spanTransitions = SparseArray<Transition>()

        for (span in spanned.getSpans<Any?>(0, spanned.length, Any::class.java)) {
            val openingTag = getOpeningTag(span, displayDensity)
            val closingTag = getClosingTag(span)
            val spanStart = spanned.getSpanStart(span)
            val spanEnd = spanned.getSpanEnd(span)
            if (openingTag != null) {
                val spanInfo = SpanInfo(spanStart, spanEnd, openingTag, closingTag!!)
                getOrCreate(spanTransitions, spanStart).spansAdded.add(spanInfo)
                getOrCreate(spanTransitions, spanEnd).spansRemoved.add(spanInfo)
            }
        }

        return spanTransitions
    }

    private fun getOpeningTag(span: Any?, displayDensity: Float): String? {
        if (span is StrikethroughSpan) {
            return "<span style='text-decoration:line-through;'>"
        } else if (span is ForegroundColorSpan) {
            val colorSpan = span
            return formatInvariant(
                "<span style='color:%s;'>", HtmlUtils.toCssRgba(colorSpan.getForegroundColor())
            )
        } else if (span is BackgroundColorSpan) {
            val colorSpan = span
            return formatInvariant("<span class='bg_%s'>", colorSpan.getBackgroundColor())
        } else if (span is HorizontalTextInVerticalContextSpan) {
            return "<span style='text-combine-upright:all;'>"
        } else if (span is AbsoluteSizeSpan) {
            val absoluteSizeSpan = span
            val sizeCssPx =
                if (absoluteSizeSpan.getDip())
                    absoluteSizeSpan.getSize()
                        .toFloat()
                else
                    absoluteSizeSpan.getSize() / displayDensity
            return formatInvariant("<span style='font-size:%.2fpx;'>", sizeCssPx)
        } else if (span is RelativeSizeSpan) {
            return formatInvariant(
                "<span style='font-size:%.2f%%;'>", span.getSizeChange() * 100
            )
        } else if (span is TypefaceSpan) {
            val fontFamily = span.getFamily()
            return if (fontFamily != null)
                formatInvariant("<span style='font-family:\"%s\";'>", fontFamily)
            else
                null
        } else if (span is StyleSpan) {
            when (span.getStyle()) {
                Typeface.BOLD -> return "<b>"
                Typeface.ITALIC -> return "<i>"
                Typeface.BOLD_ITALIC -> return "<b><i>"
                else -> return null
            }
        } else if (span is RubySpan) {
            val rubySpan = span
            when (rubySpan.position) {
                TextAnnotation.POSITION_BEFORE -> return "<ruby style='ruby-position:over;'>"
                TextAnnotation.POSITION_AFTER -> return "<ruby style='ruby-position:under;'>"
                TextAnnotation.POSITION_UNKNOWN -> return "<ruby style='ruby-position:unset;'>"
                else -> return null
            }
        } else if (span is UnderlineSpan) {
            return "<u>"
        } else if (span is TextEmphasisSpan) {
            val textEmphasisSpan = span
            val style = getTextEmphasisStyle(textEmphasisSpan.markShape, textEmphasisSpan.markFill)
            val position = getTextEmphasisPosition(textEmphasisSpan.position)
            return formatInvariant(
                ("<span style='-webkit-text-emphasis-style:%1\$s;text-emphasis-style:%1\$s;"
                        + "-webkit-text-emphasis-position:%2\$s;text-emphasis-position:%2\$s;"
                        + "display:inline-block;'>"),
                style, position
            )
        } else {
            return null
        }
    }

    private fun getClosingTag(span: Any?): String? {
        if (span is StrikethroughSpan
            || span is ForegroundColorSpan
            || span is BackgroundColorSpan
            || span is HorizontalTextInVerticalContextSpan
            || span is AbsoluteSizeSpan
            || span is RelativeSizeSpan
            || span is TextEmphasisSpan
        ) {
            return "</span>"
        } else if (span is TypefaceSpan) {
            val fontFamily = span.getFamily()
            return if (fontFamily != null) "</span>" else null
        } else if (span is StyleSpan) {
            when (span.getStyle()) {
                Typeface.BOLD -> return "</b>"
                Typeface.ITALIC -> return "</i>"
                Typeface.BOLD_ITALIC -> return "</i></b>"
            }
        } else if (span is RubySpan) {
            val rubySpan = span
            return "<rt>" + escapeHtml(rubySpan.rubyText) + "</rt></ruby>"
        } else if (span is UnderlineSpan) {
            return "</u>"
        }
        return null
    }

    private fun getTextEmphasisStyle(
        shape: @TextEmphasisSpan.MarkShape Int, fill: @TextEmphasisSpan.MarkFill Int
    ): String {
        val builder = StringBuilder()
        when (fill) {
            TextEmphasisSpan.MARK_FILL_FILLED -> builder.append("filled ")
            TextEmphasisSpan.MARK_FILL_OPEN -> builder.append("open ")
            TextEmphasisSpan.MARK_FILL_UNKNOWN -> {}
            else -> {}
        }

        when (shape) {
            TextEmphasisSpan.MARK_SHAPE_CIRCLE -> builder.append("circle")
            TextEmphasisSpan.MARK_SHAPE_DOT -> builder.append("dot")
            TextEmphasisSpan.MARK_SHAPE_SESAME -> builder.append("sesame")
            TextEmphasisSpan.MARK_SHAPE_NONE -> builder.append("none")
            else -> builder.append("unset")
        }
        return builder.toString()
    }

    private fun getTextEmphasisPosition(position: @TextAnnotation.Position Int): String {
        when (position) {
            TextAnnotation.POSITION_AFTER -> return "under left"
            TextAnnotation.POSITION_UNKNOWN, TextAnnotation.POSITION_BEFORE -> return "over right"
            else -> return "over right"
        }
    }

    private fun getOrCreate(transitions: SparseArray<Transition>, key: Int): Transition {
        var transition = transitions.get(key)
        if (transition == null) {
            transition = Transition()
            transitions.put(key, transition)
        }
        return transition
    }

    private fun escapeHtml(text: CharSequence?): String {
        val escaped = Html.escapeHtml(text)
        return NEWLINE_PATTERN.matcher(escaped).replaceAll("<br>")
    }

    class HtmlAndCss  constructor(
        val html: String,
        val cssRuleSets: Map<String, String>
    )

    private class SpanInfo(
        val start: Int,
        val end: Int,
        val openingTag: String,
        val closingTag: String
    ) {
        companion object {
            
             val FOR_OPENING_TAGS: java.util.Comparator<SpanInfo> =
                Comparator { info1: SpanInfo?, info2: SpanInfo? ->
                    var result = Integer.compare(info2!!.end, info1!!.end)
                    if (result != 0) {
                        return@Comparator result
                    }
                    result = info1.openingTag.compareTo(info2.openingTag)
                    if (result != 0) {
                        return@Comparator result
                    }
                    info1.closingTag.compareTo(info2.closingTag)
                }

            
             val FOR_CLOSING_TAGS: Comparator<SpanInfo> =
                Comparator { info1: SpanInfo, info2: SpanInfo ->
                    var result = Integer.compare(info2!!.start, info1!!.start)
                    if (result != 0) {
                        return@Comparator result
                    }
                    result = info2.openingTag.compareTo(info1.openingTag)
                    if (result != 0) {
                        return@Comparator result
                    }
                    info2.closingTag.compareTo(info1.closingTag)
                }
        }
    }

    private class Transition {
         val spansAdded: MutableList<SpanInfo>
         val spansRemoved: MutableList<SpanInfo>

        init {
            this.spansAdded = ArrayList<SpanInfo>()
            this.spansRemoved = ArrayList<SpanInfo>()
        }
    }
}
