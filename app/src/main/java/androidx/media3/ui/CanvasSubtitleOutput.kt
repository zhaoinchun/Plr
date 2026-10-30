package androidx.media3.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.media3.common.text.Cue

internal class CanvasSubtitleOutput(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SubtitleView.Output {
    private val painters = mutableListOf<SubtitlePainter>()
    private var cues = listOf<Cue>()
    private var textSizeType: @Cue.TextSizeType Int = Cue.TEXT_SIZE_TYPE_FRACTIONAL
    private var textSize: Float = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION
    private var style: CaptionStyleCompat = CaptionStyleCompat.DEFAULT
    private var bottomPaddingFraction: Float = SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION

    override fun update(
        cues: List<Cue>,
        style: CaptionStyleCompat,
        textSize: Float,
        textSizeType: @Cue.TextSizeType Int,
        bottomPaddingFraction: Float
    ) {
        this.cues = cues
        this.style = style
        this.textSize = textSize
        this.textSizeType = textSizeType
        this.bottomPaddingFraction = bottomPaddingFraction
        // Ensure we have sufficient painters.
        while (painters.size < cues.size) {
            painters.add(SubtitlePainter(context))
        }
        // Invalidate to trigger drawing.
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val cues = this.cues
        if (cues.isEmpty()) {
            return
        }

        val rawViewHeight = height

        // Calculate the cue box bounds relative to the canvas after padding is taken into account.
        val left = getPaddingLeft()
        val top = paddingTop
        val right = width - getPaddingRight()
        val bottom = rawViewHeight - paddingBottom
        if (bottom <= top || right <= left) {
            // No space to draw subtitles.
            return
        }
        val viewHeightMinusPadding = bottom - top

        val defaultViewTextSizePx =
            SubtitleViewUtils.resolveTextSize(
                textSizeType, textSize, rawViewHeight, viewHeightMinusPadding
            )
        if (defaultViewTextSizePx <= 0) {
            // Text has no height.
            return
        }

        val cueCount = cues.size
        for (i in 0..<cueCount) {
            var cue = cues[i]
            if (cue.verticalType != Cue.TYPE_UNSET) {
                cue = repositionVerticalCue(cue)
            }
            val cueTextSizePx =
                SubtitleViewUtils.resolveTextSize(
                    cue.textSizeType, cue.textSize, rawViewHeight, viewHeightMinusPadding
                )
            val painter = painters[i]
            painter.draw(
                cue,
                style,
                defaultViewTextSizePx,
                cueTextSizePx,
                bottomPaddingFraction,
                canvas,
                left,
                top,
                right,
                bottom
            )
        }
    }

    companion object {

        private fun repositionVerticalCue(cue: Cue): Cue {
            val cueBuilder =
                cue.buildUpon()
                    .setPosition(Cue.DIMEN_UNSET)
                    .setPositionAnchor(Cue.TYPE_UNSET)
                    .setTextAlignment(null)

            if (cue.lineType == Cue.LINE_TYPE_FRACTION) {
                cueBuilder.setLine(1.0f - cue.line, Cue.LINE_TYPE_FRACTION)
            } else {
                cueBuilder.setLine(-cue.line - 1f, Cue.LINE_TYPE_NUMBER)
            }
            when (cue.lineAnchor) {
                Cue.ANCHOR_TYPE_END -> cueBuilder.setLineAnchor(Cue.ANCHOR_TYPE_START)
                Cue.ANCHOR_TYPE_START -> cueBuilder.setLineAnchor(Cue.ANCHOR_TYPE_END)
                Cue.ANCHOR_TYPE_MIDDLE, Cue.TYPE_UNSET -> {}
                else -> {}
            }
            return cueBuilder.build()
        }
    }
}
