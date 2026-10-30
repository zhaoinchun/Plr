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
 */
package androidx.media3.common.text

import android.text.Spannable
import android.text.style.RelativeSizeSpan

object SpanUtil {
    
    fun addOrReplaceSpan(
        spannable: Spannable, span: Any, start: Int, end: Int, spanFlags: Int
    ) {
        val existingSpans = spannable.getSpans(start, end, span.javaClass)
        for (existingSpan in existingSpans) {
            removeIfStartEndAndFlagsMatch(spannable, existingSpan, start, end, spanFlags)
        }
        spannable.setSpan(span, start, end, spanFlags)
    }

    fun addInheritedRelativeSizeSpan(
        spannable: Spannable, size: Float, start: Int, end: Int, spanFlags: Int
    ) {
        var size = size
        for (existingSpan in spannable.getSpans<RelativeSizeSpan>(
            start,
            end,
            RelativeSizeSpan::class.java
        )) {
            if (spannable.getSpanStart(existingSpan) <= start
                && spannable.getSpanEnd(existingSpan) >= end
            ) {
                size *= existingSpan.getSizeChange()
            }
            removeIfStartEndAndFlagsMatch(spannable, existingSpan, start, end, spanFlags)
        }
        spannable.setSpan(RelativeSizeSpan(size), start, end, spanFlags)
    }

    private fun removeIfStartEndAndFlagsMatch(
        spannable: Spannable, span: Any?, start: Int, end: Int, spanFlags: Int
    ) {
        if (spannable.getSpanStart(span) == start && spannable.getSpanEnd(span) == end && spannable.getSpanFlags(
                span
            ) == spanFlags
        ) {
            spannable.removeSpan(span)
        }
    }
}
