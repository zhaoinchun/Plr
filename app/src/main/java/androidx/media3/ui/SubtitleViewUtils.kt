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

import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.RelativeSizeSpan
import androidx.media3.common.text.Cue
import androidx.media3.common.text.LanguageFeatureSpan
import com.google.common.base.Preconditions
import com.google.common.base.Predicate

internal object SubtitleViewUtils {
    
    fun resolveTextSize(
        @Cue.TextSizeType textSizeType: @Cue.TextSizeType Int,
        textSize: Float,
        rawViewHeight: Int,
        viewHeightMinusPadding: Int
    ): Float {
        if (textSize == Cue.DIMEN_UNSET) {
            return Cue.DIMEN_UNSET
        }
        when (textSizeType) {
            Cue.TEXT_SIZE_TYPE_ABSOLUTE -> return textSize
            Cue.TEXT_SIZE_TYPE_FRACTIONAL -> return textSize * viewHeightMinusPadding
            Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING -> return textSize * rawViewHeight
            Cue.TYPE_UNSET -> return Cue.DIMEN_UNSET
            else -> return Cue.DIMEN_UNSET
        }
    }

    fun removeAllEmbeddedStyling(cue: Cue.Builder) {
        cue.clearWindowColor()
        if (cue.text is Spanned) {
            if (cue.text !is Spannable) {
                cue.setText(SpannableString.valueOf(cue.text))
            }
            removeSpansIf(
                cue.text as Spannable,
                Predicate { span: Any? -> span !is LanguageFeatureSpan })
        }
        removeEmbeddedFontSizes(cue)
    }

    fun removeEmbeddedFontSizes(cue: Cue.Builder) {
        cue.setTextSize(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
        if (cue.text is Spanned) {
            if (cue.text !is Spannable) {
                cue.setText(SpannableString.valueOf(cue.text))
            }
            removeSpansIf(
                cue.text as Spannable,
                Predicate { span: Any? -> span is AbsoluteSizeSpan || span is RelativeSizeSpan })
        }
    }

    private fun removeSpansIf(spannable: Spannable, removeFilter: Predicate<Any?>) {
        val spans = spannable.getSpans<Any?>(0, spannable.length, Any::class.java)
        for (span in spans) {
            if (removeFilter.apply(span)) {
                spannable.removeSpan(span)
            }
        }
    }
}
