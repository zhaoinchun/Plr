/*
 * Copyright 2021 The Android Open Source Project
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
 */
package androidx.media3.extractor.text.ttml

import android.text.TextUtils
import androidx.annotation.IntDef
import androidx.media3.common.text.TextAnnotation
import androidx.media3.common.text.TextEmphasisSpan
import androidx.media3.common.text.TextEmphasisSpan.MarkFill
import com.google.common.base.Ascii
import com.google.common.collect.Iterables
import com.google.common.collect.Sets
import java.util.regex.Pattern

internal class TextEmphasis private constructor(
    
    val markShape: @MarkShape Int,
    
    val markFill: @MarkFill Int,
    
    val position: @TextAnnotation.Position Int
) {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [TextEmphasisSpan.MARK_SHAPE_NONE, TextEmphasisSpan.MARK_SHAPE_CIRCLE, TextEmphasisSpan.MARK_SHAPE_DOT, TextEmphasisSpan.MARK_SHAPE_SESAME, MARK_SHAPE_AUTO
        ]
    )
    internal annotation class MarkShape

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [TextAnnotation.POSITION_UNKNOWN, TextAnnotation.POSITION_BEFORE, TextAnnotation.POSITION_AFTER, POSITION_OUTSIDE
        ]
    )
    annotation class Position

    companion object {
        
        const val MARK_SHAPE_AUTO: Int = -1

        const  val POSITION_OUTSIDE: Int = -2

        private val WHITESPACE_PATTERN: Pattern = Pattern.compile("\\s+")

        private val SINGLE_STYLE_VALUES: Set<String?> = setOf<String?>(
            TtmlNode.TEXT_EMPHASIS_AUTO,
            TtmlNode.TEXT_EMPHASIS_NONE
        )

        private val MARK_SHAPE_VALUES: Set<String?> = setOf<String?>(
            TtmlNode.TEXT_EMPHASIS_MARK_DOT,
            TtmlNode.TEXT_EMPHASIS_MARK_SESAME,
            TtmlNode.TEXT_EMPHASIS_MARK_CIRCLE
        )

        private val MARK_FILL_VALUES: Set<String?> = setOf<String?>(
            TtmlNode.TEXT_EMPHASIS_MARK_FILLED,
            TtmlNode.TEXT_EMPHASIS_MARK_OPEN
        )

        private val POSITION_VALUES: Set<String?> = setOf<String?>(
            TtmlNode.ANNOTATION_POSITION_AFTER,
            TtmlNode.ANNOTATION_POSITION_BEFORE,
            TtmlNode.ANNOTATION_POSITION_OUTSIDE
        )

        fun parse(value: String?): TextEmphasis? {
            if (value == null) {
                return null
            }

            val parsingValue = Ascii.toLowerCase(value.trim { it <= ' ' })
            if (parsingValue.isEmpty()) {
                return null
            }

            return parseWords(
                TextUtils.split(
                    parsingValue,
                    WHITESPACE_PATTERN
                ).toSet()
            )
        }

        private fun parseWords(nodes: Set<String?>): TextEmphasis {
            val matchingPositions: MutableSet<String?> =
                Sets.intersection<String?>(POSITION_VALUES, nodes)
            // If no emphasis position is specified, then the emphasis position must be interpreted as if
            // a position of outside were specified:
            // https://www.w3.org/TR/2018/REC-ttml2-20181108/#style-attribute-textEmphasis
            val position: @Position Int
            when (Iterables.getFirst<String?>(
                matchingPositions,
                TtmlNode.ANNOTATION_POSITION_OUTSIDE
            )) {
                TtmlNode.ANNOTATION_POSITION_AFTER -> position =
                    TextAnnotation.POSITION_AFTER

                TtmlNode.ANNOTATION_POSITION_OUTSIDE -> position = POSITION_OUTSIDE
                TtmlNode.ANNOTATION_POSITION_BEFORE ->         // If an implementation does not recognize or otherwise distinguish an annotation position
                    // value, then it must be interpreted as if a position of 'before' were specified:
                    // https://www.w3.org/TR/2018/REC-ttml2-20181108/#style-attribute-textEmphasis
                    position = TextAnnotation.POSITION_BEFORE

                else ->
                    position = TextAnnotation.POSITION_BEFORE
            }

            val matchingSingleStyles: MutableSet<String?> = Sets.intersection<String?>(
                SINGLE_STYLE_VALUES, nodes
            )
            if (!matchingSingleStyles.isEmpty()) {
                // If "none" or "auto" are found in the description, ignore the other style (fill, shape)
                // attributes.
                val markShape: @MarkShape Int
                when (matchingSingleStyles.iterator().next()) {
                    TtmlNode.TEXT_EMPHASIS_NONE -> markShape =
                        TextEmphasisSpan.MARK_SHAPE_NONE

                    TtmlNode.TEXT_EMPHASIS_AUTO -> markShape = MARK_SHAPE_AUTO
                    else -> markShape = MARK_SHAPE_AUTO
                }
                // markFill is ignored when markShape is NONE or AUTO
                return TextEmphasis(markShape, TextEmphasisSpan.MARK_FILL_UNKNOWN, position)
            }

            val matchingFills: MutableSet<String?> =
                Sets.intersection<String?>(MARK_FILL_VALUES, nodes)
            val matchingShapes: MutableSet<String?> =
                Sets.intersection<String?>(MARK_SHAPE_VALUES, nodes)
            if (matchingFills.isEmpty() && matchingShapes.isEmpty()) {
                // If an implementation does not recognize or otherwise distinguish an emphasis style value,
                // then it must be interpreted as if a style of auto were specified; as such, an
                // implementation that supports text emphasis marks must minimally support the auto value.
                // https://www.w3.org/TR/ttml2/#style-value-emphasis-style.
                //
                // markFill is ignored when markShape is NONE or AUTO.
                return TextEmphasis(MARK_SHAPE_AUTO, TextEmphasisSpan.MARK_FILL_UNKNOWN, position)
            }

            val markFill: @MarkFill Int
            when (Iterables.getFirst<String?>(
                matchingFills,
                TtmlNode.TEXT_EMPHASIS_MARK_FILLED
            )) {
                TtmlNode.TEXT_EMPHASIS_MARK_OPEN -> markFill =
                    TextEmphasisSpan.MARK_FILL_OPEN

                TtmlNode.TEXT_EMPHASIS_MARK_FILLED -> markFill =
                    TextEmphasisSpan.MARK_FILL_FILLED

                else -> markFill = TextEmphasisSpan.MARK_FILL_FILLED
            }

            val markShape: @MarkShape Int
            when (Iterables.getFirst<String?>(
                matchingShapes,
                TtmlNode.TEXT_EMPHASIS_MARK_CIRCLE
            )) {
                TtmlNode.TEXT_EMPHASIS_MARK_DOT -> markShape =
                    TextEmphasisSpan.MARK_SHAPE_DOT

                TtmlNode.TEXT_EMPHASIS_MARK_SESAME -> markShape =
                    TextEmphasisSpan.MARK_SHAPE_SESAME

                TtmlNode.TEXT_EMPHASIS_MARK_CIRCLE -> markShape =
                    TextEmphasisSpan.MARK_SHAPE_CIRCLE

                else -> markShape = TextEmphasisSpan.MARK_SHAPE_CIRCLE
            }

            return TextEmphasis(markShape, markFill, position)
        }
    }
}
