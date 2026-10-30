/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.accessibility.CaptioningManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.annotation.Dimension
import androidx.annotation.IntDef
import androidx.media3.common.text.Cue

class SubtitleView  constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    internal interface Output {
        fun update(
            cues: List<Cue>,
            style: CaptionStyleCompat,
            defaultTextSize: Float,
            @Cue.TextSizeType defaultTextSizeType: @Cue.TextSizeType Int,
            bottomPaddingFraction: Float
        )
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [VIEW_TYPE_CANVAS, VIEW_TYPE_WEB])
    annotation class ViewType

    private var cues: List<Cue> = listOf<Cue>()
    private var style: CaptionStyleCompat = CaptionStyleCompat.DEFAULT

    @Cue.TextSizeType
    private var defaultTextSizeType: @Cue.TextSizeType Int
    private var defaultTextSize: Float
    private var bottomPaddingFraction: Float
    private var applyEmbeddedStyles = true
    private var applyEmbeddedFontSizes = true

    private var viewType: @ViewType Int
    private var innerSubtitleView: View = CanvasSubtitleOutput(context)
    private var output: Output = innerSubtitleView as CanvasSubtitleOutput

    init {
        defaultTextSizeType = Cue.TEXT_SIZE_TYPE_FRACTIONAL
        defaultTextSize = DEFAULT_TEXT_SIZE_FRACTION
        bottomPaddingFraction = DEFAULT_BOTTOM_PADDING_FRACTION

        addView(innerSubtitleView)
        viewType = VIEW_TYPE_CANVAS
    }

    fun setCues(cues: List<Cue>?) {
        this.cues = (if (cues != null) cues else listOf<Cue>())
        updateOutput()
    }

    fun setViewType(viewType: @ViewType Int) {
        if (this.viewType == viewType) {
            return
        }
        when (viewType) {
            VIEW_TYPE_CANVAS -> setView<CanvasSubtitleOutput>(CanvasSubtitleOutput(getContext()))
            VIEW_TYPE_WEB -> setView<WebViewSubtitleOutput>(WebViewSubtitleOutput(getContext()))
            else -> throw IllegalArgumentException()
        }
        this.viewType = viewType
    }

    private fun <T> setView(view: T) where T : View, T : Output {
        removeView(innerSubtitleView)
        if (innerSubtitleView is WebViewSubtitleOutput) {
            (innerSubtitleView as WebViewSubtitleOutput).destroy()
        }
        innerSubtitleView = view
        output = view!!
        addView(view)
    }

    fun setFixedTextSize(@Dimension unit: Int, size: Float) {
        val context = getContext()
        val resources: Resources
        if (context == null) {
            resources = Resources.getSystem()
        } else {
            resources = context.getResources()
        }
        setTextSize(
            Cue.TEXT_SIZE_TYPE_ABSOLUTE,
            TypedValue.applyDimension(unit, size, resources.getDisplayMetrics())
        )
    }

    fun setUserDefaultTextSize() {
        setFractionalTextSize(DEFAULT_TEXT_SIZE_FRACTION * this.userCaptionFontScale)
    }

    fun setFractionalTextSize(fractionOfHeight: Float) {
        setFractionalTextSize(fractionOfHeight, false)
    }

    fun setFractionalTextSize(fractionOfHeight: Float, ignorePadding: Boolean) {
        setTextSize(
            if (ignorePadding)
                Cue.TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING
            else
                Cue.TEXT_SIZE_TYPE_FRACTIONAL,
            fractionOfHeight
        )
    }

    private fun setTextSize(
        @Cue.TextSizeType textSizeType: @Cue.TextSizeType Int,
        textSize: Float
    ) {
        this.defaultTextSizeType = textSizeType
        this.defaultTextSize = textSize
        updateOutput()
    }

    fun setApplyEmbeddedStyles(applyEmbeddedStyles: Boolean) {
        this.applyEmbeddedStyles = applyEmbeddedStyles
        updateOutput()
    }

    fun setApplyEmbeddedFontSizes(applyEmbeddedFontSizes: Boolean) {
        this.applyEmbeddedFontSizes = applyEmbeddedFontSizes
        updateOutput()
    }

    fun setUserDefaultStyle() {
        setStyle(this.userCaptionStyle)
    }

    fun setStyle(style: CaptionStyleCompat) {
        this.style = style
        updateOutput()
    }

    fun setBottomPaddingFraction(bottomPaddingFraction: Float) {
        this.bottomPaddingFraction = bottomPaddingFraction
        updateOutput()
    }

    private val userCaptionFontScale: Float
        get() {
            if (isInEditMode()) {
                return 1f
            }
            val captioningManager =
                getContext().getSystemService(Context.CAPTIONING_SERVICE) as CaptioningManager?
            return if (captioningManager != null && captioningManager.isEnabled())
                captioningManager.getFontScale()
            else
                1f
        }

    private val userCaptionStyle: CaptionStyleCompat
        get() {
            if (isInEditMode()) {
                return CaptionStyleCompat.DEFAULT
            }
            val captioningManager =
                getContext().getSystemService(Context.CAPTIONING_SERVICE) as CaptioningManager?
            return if (captioningManager != null && captioningManager.isEnabled())
                CaptionStyleCompat.createFromCaptionStyle(captioningManager.getUserStyle())
            else
                CaptionStyleCompat.DEFAULT
        }

    private fun updateOutput() {
        output.update(
            this.cuesWithStylingPreferencesApplied,
            style,
            defaultTextSize,
            defaultTextSizeType,
            bottomPaddingFraction
        )
    }

    private val cuesWithStylingPreferencesApplied: List<Cue>
        get() {
            if (applyEmbeddedStyles && applyEmbeddedFontSizes) {
                return cues
            }
            val strippedCues: MutableList<Cue> =
                ArrayList<Cue>(cues.size)
            for (i in cues.indices) {
                strippedCues.add(removeEmbeddedStyling(cues[i]!!))
            }
            return strippedCues
        }

    private fun removeEmbeddedStyling(cue: Cue): Cue {
        val strippedCue = cue.buildUpon()
        if (!applyEmbeddedStyles) {
            SubtitleViewUtils.removeAllEmbeddedStyling(strippedCue)
        } else if (!applyEmbeddedFontSizes) {
            SubtitleViewUtils.removeEmbeddedFontSizes(strippedCue)
        }
        return strippedCue.build()
    }

    companion object {

        const val DEFAULT_TEXT_SIZE_FRACTION: Float = 0.0533f

        const val DEFAULT_BOTTOM_PADDING_FRACTION: Float = 0.08f

        const val VIEW_TYPE_CANVAS: Int = 1

        const val VIEW_TYPE_WEB: Int = 2
    }
}
