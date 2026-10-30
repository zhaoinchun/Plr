
package androidx.media3.ui

import android.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.Join
import android.graphics.Rect
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.DisplayMetrics
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Log.w
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal class SubtitlePainter(context: Context) {
    // Styled dimensions.
    private val outlineWidth: Float
    private val shadowRadius: Float
    private val shadowOffset: Float
    private val spacingMult: Float
    private val spacingAdd: Float

    private val textPaint: TextPaint
    private val windowPaint: Paint
    private val bitmapPaint: Paint

    // Previous input variables.
    private var cueText: CharSequence? = null
    private var cueTextAlignment: Layout.Alignment? = null
    private var cueBitmap: Bitmap? = null
    private var cueLine = 0f

    @Cue.LineType
    private var cueLineType: @Cue.LineType Int = 0

    @Cue.AnchorType
    private var cueLineAnchor: @Cue.AnchorType Int = 0
    private var cuePosition = 0f

    @Cue.AnchorType
    private var cuePositionAnchor: @Cue.AnchorType Int = 0
    private var cueSize = 0f
    private var cueBitmapHeight = 0f
    private var foregroundColor = 0
    private var backgroundColor = 0
    private var windowColor = 0
    private var edgeColor = 0
    private var edgeType: @CaptionStyleCompat.EdgeType Int = 0
    private var defaultTextSizePx = 0f
    private var cueTextSizePx = 0f
    private var bottomPaddingFraction = 0f
    private var parentLeft = 0
    private var parentTop = 0
    private var parentRight = 0
    private var parentBottom = 0

    // Derived drawing variables.
    private var textLayout:StaticLayout? = null
    private var edgeLayout:StaticLayout? = null
    private var textLeft = 0
    private var textTop = 0
    private var textPaddingX = 0
    private var bitmapRect:Rect? = null

    init {
        val viewAttr = intArrayOf(R.attr.lineSpacingExtra, R.attr.lineSpacingMultiplier)
        val styledAttributes = context.obtainStyledAttributes(null, viewAttr, 0, 0)
        spacingAdd = styledAttributes.getDimensionPixelSize(0, 0).toFloat()
        spacingMult = styledAttributes.getFloat(1, 1f)
        styledAttributes.recycle()

        val resources = context.getResources()
        val displayMetrics = resources.getDisplayMetrics()
        val twoDpInPx =
            Math.round((2f * displayMetrics.densityDpi) / DisplayMetrics.DENSITY_DEFAULT)
        outlineWidth = twoDpInPx.toFloat()
        shadowRadius = twoDpInPx.toFloat()
        shadowOffset = twoDpInPx.toFloat()

        textPaint = TextPaint()
        textPaint.setAntiAlias(true)
        textPaint.setSubpixelText(true)

        windowPaint = Paint()
        windowPaint.setAntiAlias(true)
        windowPaint.setStyle(Paint.Style.FILL)

        bitmapPaint = Paint()
        bitmapPaint.setAntiAlias(true)
        bitmapPaint.setFilterBitmap(true)
    }

    fun draw(
        cue: Cue,
        style: CaptionStyleCompat,
        defaultTextSizePx: Float,
        cueTextSizePx: Float,
        bottomPaddingFraction: Float,
        canvas: Canvas,
        cueBoxLeft: Int,
        cueBoxTop: Int,
        cueBoxRight: Int,
        cueBoxBottom: Int
    ) {
        val isTextCue = cue.bitmap == null
        var windowColor = Color.BLACK
        if (isTextCue) {
            if (TextUtils.isEmpty(cue.text)) {
                // Nothing to draw.
                return
            }
            windowColor = if (cue.windowColorSet) cue.windowColor else style.windowColor
        }
        if (areCharSequencesEqual(this.cueText, cue.text)
            && this.cueTextAlignment == cue.textAlignment
            && this.cueBitmap == cue.bitmap && this.cueLine == cue.line && this.cueLineType == cue.lineType && this.cueLineAnchor == cue.lineAnchor
            && this.cuePosition == cue.position && this.cuePositionAnchor == cue.positionAnchor
            && this.cueSize == cue.size && this.cueBitmapHeight == cue.bitmapHeight && this.foregroundColor == style.foregroundColor && this.backgroundColor == style.backgroundColor && this.windowColor == windowColor && this.edgeType == style.edgeType && this.edgeColor == style.edgeColor && this.textPaint.getTypeface() == style.typeface
            && this.defaultTextSizePx == defaultTextSizePx && this.cueTextSizePx == cueTextSizePx && this.bottomPaddingFraction == bottomPaddingFraction && this.parentLeft == cueBoxLeft && this.parentTop == cueBoxTop && this.parentRight == cueBoxRight && this.parentBottom == cueBoxBottom
        ) {
            // We can use the cached layout.
            drawLayout(canvas, isTextCue)
            return
        }

        this.cueText =
            if (BidiUtils.containsRtl(cue.text)) BidiUtils.wrapText(cue.text!!) else cue.text
        this.cueTextAlignment = cue.textAlignment
        this.cueBitmap = cue.bitmap
        this.cueLine = cue.line
        this.cueLineType = cue.lineType
        this.cueLineAnchor = cue.lineAnchor
        this.cuePosition = cue.position
        this.cuePositionAnchor = cue.positionAnchor
        this.cueSize = cue.size
        this.cueBitmapHeight = cue.bitmapHeight
        this.foregroundColor = style.foregroundColor
        this.backgroundColor = style.backgroundColor
        this.windowColor = windowColor
        this.edgeType = style.edgeType
        this.edgeColor = style.edgeColor
        this.textPaint.setTypeface(style.typeface)
        this.defaultTextSizePx = defaultTextSizePx
        this.cueTextSizePx = cueTextSizePx
        this.bottomPaddingFraction = bottomPaddingFraction
        this.parentLeft = cueBoxLeft
        this.parentTop = cueBoxTop
        this.parentRight = cueBoxRight
        this.parentBottom = cueBoxBottom

        if (isTextCue) {
            cueText!!
            setupTextLayout()
        } else {
            cueBitmap!!
            setupBitmapLayout()
        }
        drawLayout(canvas, isTextCue)
    }

    @RequiresNonNull("cueText")
    private fun setupTextLayout() {
        val cueText =
            if (this.cueText is SpannableStringBuilder)
                this.cueText as SpannableStringBuilder
            else
                SpannableStringBuilder(this.cueText)
        val parentWidth = parentRight - parentLeft
        val parentHeight = parentBottom - parentTop

        textPaint.setTextSize(defaultTextSizePx)
        val textPaddingX = (defaultTextSizePx * INNER_PADDING_RATIO + 0.5f).toInt()

        var availableWidth = parentWidth - textPaddingX * 2
        if (cueSize != Cue.DIMEN_UNSET) {
            availableWidth = (availableWidth * cueSize).toInt()
        }
        if (availableWidth <= 0) {
            w(TAG, "Skipped drawing subtitle cue (insufficient space)")
            return
        }

        if (cueTextSizePx > 0) {
            // Use an AbsoluteSizeSpan encompassing the whole text to apply the default cueTextSizePx.
            cueText.setSpan(
                AbsoluteSizeSpan(cueTextSizePx.toInt()),  
                0,  
                cueText.length,
                Spanned.SPAN_PRIORITY
            )
        }

        // Remove embedded font color to not destroy edges, otherwise it overrides edge color.
        val cueTextEdge = SpannableStringBuilder(cueText)
        if (edgeType == CaptionStyleCompat.EDGE_TYPE_OUTLINE) {
            val foregroundColorSpans =
                cueTextEdge.getSpans<ForegroundColorSpan?>(
                    0,
                    cueTextEdge.length,
                    ForegroundColorSpan::class.java
                )
            for (foregroundColorSpan in foregroundColorSpans) {
                cueTextEdge.removeSpan(foregroundColorSpan)
            }
        }

        // EDGE_TYPE_NONE & EDGE_TYPE_DROP_SHADOW both paint in one pass, they ignore cueTextEdge.
        // In other cases we use two painters and we need to apply the background in the first one only,
        // otherwise the background color gets drawn in front of the edge color
        // (https://github.com/google/ExoPlayer/pull/6724#issuecomment-564650572).
        if (Color.alpha(backgroundColor) > 0) {
            if (edgeType == CaptionStyleCompat.EDGE_TYPE_NONE
                || edgeType == CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            ) {
                cueText.setSpan(
                    BackgroundColorSpan(backgroundColor), 0, cueText.length, Spanned.SPAN_PRIORITY
                )
            } else {
                cueTextEdge.setSpan(
                    BackgroundColorSpan(backgroundColor),
                    0,
                    cueTextEdge.length,
                    Spanned.SPAN_PRIORITY
                )
            }
        }

        val textAlignment: Layout.Alignment =
            (if (cueTextAlignment == null) android.text.Layout.Alignment.ALIGN_CENTER else cueTextAlignment)!!
        textLayout =
            StaticLayout(
                cueText, textPaint, availableWidth, textAlignment, spacingMult, spacingAdd, true
            )
        val textHeight = textLayout!!.getHeight()
        var textWidth = 0
        val lineCount = textLayout!!.getLineCount()
        for (i in 0..<lineCount) {
            textWidth = max(ceil(textLayout!!.getLineWidth(i).toDouble()).toInt(), textWidth)
        }
        if (cueSize != Cue.DIMEN_UNSET && textWidth < availableWidth) {
            textWidth = availableWidth
        }
        textWidth += textPaddingX * 2

        var textLeft: Int
        val textRight: Int
        if (cuePosition != Cue.DIMEN_UNSET) {
            val anchorPosition = Math.round(parentWidth * cuePosition) + parentLeft
            when (cuePositionAnchor) {
                Cue.ANCHOR_TYPE_END -> textLeft = anchorPosition - textWidth
                Cue.ANCHOR_TYPE_MIDDLE -> textLeft = (anchorPosition * 2 - textWidth) / 2
                Cue.ANCHOR_TYPE_START, Cue.TYPE_UNSET -> textLeft = anchorPosition
                else -> textLeft = anchorPosition
            }

            textLeft = max(textLeft, parentLeft)
            textRight = min(textLeft + textWidth, parentRight)
        } else {
            textLeft = (parentWidth - textWidth) / 2 + parentLeft
            textRight = textLeft + textWidth
        }

        textWidth = textRight - textLeft
        if (textWidth <= 0) {
            w(TAG, "Skipped drawing subtitle cue (invalid horizontal positioning)")
            return
        }

        var textTop: Int
        if (cueLine != Cue.DIMEN_UNSET) {
            if (cueLineType == Cue.LINE_TYPE_FRACTION) {
                val anchorPosition = Math.round(parentHeight * cueLine) + parentTop
                textTop =
                    if (cueLineAnchor == Cue.ANCHOR_TYPE_END)
                        anchorPosition - textHeight
                    else
                        if (cueLineAnchor == Cue.ANCHOR_TYPE_MIDDLE)
                            (anchorPosition * 2 - textHeight) / 2
                        else
                            anchorPosition
            } else {
                // cueLineType == Cue.LINE_TYPE_NUMBER
                val firstLineHeight = textLayout!!.getLineBottom(0) - textLayout!!.getLineTop(0)
                if (cueLine >= 0) {
                    textTop = Math.round(cueLine * firstLineHeight) + parentTop
                } else {
                    textTop =
                        Math.round((cueLine + 1) * firstLineHeight) + parentBottom - textHeight
                }
            }

            if (textTop + textHeight > parentBottom) {
                textTop = parentBottom - textHeight
            } else if (textTop < parentTop) {
                textTop = parentTop
            }
        } else {
            textTop = parentBottom - textHeight - (parentHeight * bottomPaddingFraction).toInt()
        }

        // Update the derived drawing variables.
        this.textLayout =
            StaticLayout(
                cueText, textPaint, textWidth, textAlignment, spacingMult, spacingAdd, true
            )
        this.edgeLayout =
            StaticLayout(
                cueTextEdge, textPaint, textWidth, textAlignment, spacingMult, spacingAdd, true
            )
        this.textLeft = textLeft
        this.textTop = textTop
        this.textPaddingX = textPaddingX
    }

    @RequiresNonNull("cueBitmap")
    private fun setupBitmapLayout() {
        val cueBitmap = this.cueBitmap
        val parentWidth = parentRight - parentLeft
        val parentHeight = parentBottom - parentTop
        val anchorX = parentLeft + (parentWidth * cuePosition)
        val anchorY = parentTop + (parentHeight * cueLine)
        val width = Math.round(parentWidth * cueSize)
        val height =
            if (cueBitmapHeight != Cue.DIMEN_UNSET)
                Math.round(parentHeight * cueBitmapHeight)
            else
                Math.round(width * (cueBitmap!!.getHeight().toFloat() / cueBitmap.getWidth()))
        val x =
            Math.round(
                if (cuePositionAnchor == Cue.ANCHOR_TYPE_END)
                    (anchorX - width)
                else
                    if (cuePositionAnchor == Cue.ANCHOR_TYPE_MIDDLE) (anchorX - (width / 2)) else anchorX
            )
        val y =
            Math.round(
                if (cueLineAnchor == Cue.ANCHOR_TYPE_END)
                    (anchorY - height)
                else
                    if (cueLineAnchor == Cue.ANCHOR_TYPE_MIDDLE) (anchorY - (height / 2)) else anchorY
            )
        bitmapRect = Rect(x, y, x + width, y + height)
    }

    private fun drawLayout(canvas: Canvas, isTextCue: Boolean) {
        if (isTextCue) {
            drawTextLayout(canvas)
        } else {
            bitmapRect!!
            cueBitmap!!
            drawBitmapLayout(canvas)
        }
    }

    private fun drawTextLayout(canvas: Canvas) {
        val textLayout = this.textLayout
        val edgeLayout = this.edgeLayout
        if (textLayout == null || edgeLayout == null) {
            // Nothing to draw.
            return
        }

        val saveCount = canvas.save()
        canvas.translate(textLeft.toFloat(), textTop.toFloat())

        if (Color.alpha(windowColor) > 0) {
            windowPaint.setColor(windowColor)
            canvas.drawRect(
                -textPaddingX.toFloat(),
                0f,
                (textLayout.getWidth() + textPaddingX).toFloat(),
                textLayout.getHeight().toFloat(),
                windowPaint
            )
        }

        if (edgeType == CaptionStyleCompat.EDGE_TYPE_OUTLINE) {
            textPaint.setStrokeJoin(Join.ROUND)
            textPaint.setStrokeWidth(outlineWidth)
            textPaint.setColor(edgeColor)
            textPaint.setStyle(Paint.Style.FILL_AND_STROKE)
            edgeLayout.draw(canvas)
        } else if (edgeType == CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW) {
            textPaint.setShadowLayer(shadowRadius, shadowOffset, shadowOffset, edgeColor)
        } else if (edgeType == CaptionStyleCompat.EDGE_TYPE_RAISED
            || edgeType == CaptionStyleCompat.EDGE_TYPE_DEPRESSED
        ) {
            val raised = edgeType == CaptionStyleCompat.EDGE_TYPE_RAISED
            val colorUp = if (raised) Color.WHITE else edgeColor
            val colorDown = if (raised) edgeColor else Color.WHITE
            val offset = shadowRadius / 2f
            textPaint.setColor(foregroundColor)
            textPaint.setStyle(Paint.Style.FILL)
            textPaint.setShadowLayer(shadowRadius, -offset, -offset, colorUp)
            edgeLayout.draw(canvas)
            textPaint.setShadowLayer(shadowRadius, offset, offset, colorDown)
        }

        textPaint.setColor(foregroundColor)
        textPaint.setStyle(Paint.Style.FILL)
        textLayout.draw(canvas)
        textPaint.setShadowLayer(0f, 0f, 0f, 0)

        canvas.restoreToCount(saveCount)
    }

    @RequiresNonNull("cueBitmap", "bitmapRect")
    private fun drawBitmapLayout(canvas: Canvas) {
        canvas.drawBitmap(cueBitmap!!,  null, bitmapRect!!, bitmapPaint)
    }

    companion object {
        private const val TAG = "SubtitlePainter"

        private const val INNER_PADDING_RATIO = 0.125f

        private fun areCharSequencesEqual(
            first: CharSequence?, second: CharSequence?
        ): Boolean {
            // Some CharSequence implementations don't perform a cheap referential equality check in their
            // equals methods, so we perform one explicitly here.
            return first === second || (first != null && first == second)
        }
    }
}
