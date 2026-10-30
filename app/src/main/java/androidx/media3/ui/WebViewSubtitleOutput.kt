package androidx.media3.ui

import android.content.Context
import android.graphics.Color
import android.text.Layout
import android.util.AttributeSet
import android.util.Base64
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Util.formatInvariant
import com.google.common.base.Preconditions
import java.nio.charset.StandardCharsets

internal class WebViewSubtitleOutput  constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), SubtitleView.Output {
    
    private val canvasSubtitleOutput: CanvasSubtitleOutput

    private val webView: WebView

    private var textCues: MutableList<Cue>
    private var style: CaptionStyleCompat
    private var defaultTextSize: Float

    @Cue.TextSizeType
    private var defaultTextSizeType: @Cue.TextSizeType Int
    private var bottomPaddingFraction: Float

    init {
        textCues = mutableListOf<Cue>()
        style = CaptionStyleCompat.DEFAULT
        defaultTextSize = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION
        defaultTextSizeType = Cue.TEXT_SIZE_TYPE_FRACTIONAL
        bottomPaddingFraction = SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION

        canvasSubtitleOutput = CanvasSubtitleOutput(context, attrs)
        webView =
            object : WebView(context, attrs) {
                override fun onTouchEvent(event: MotionEvent?): Boolean {
                    super.onTouchEvent(event)
                    // Return false so that touch events are allowed down into @id/exo_content_frame below.
                    return false
                }

                override fun performClick(): Boolean {
                    super.performClick()
                    // Return false so that clicks are allowed down into @id/exo_content_frame below.
                    return false
                }
            }
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.getSettings().setAllowContentAccess(false)

        addView(canvasSubtitleOutput)
        addView(webView)
    }

    override fun update(
        cues: List<Cue>,
        style: CaptionStyleCompat,
        textSize: Float,
        @Cue.TextSizeType textSizeType: @Cue.TextSizeType Int,
        bottomPaddingFraction: Float
    ) {
        this.style = style
        this.defaultTextSize = textSize
        this.defaultTextSizeType = textSizeType
        this.bottomPaddingFraction = bottomPaddingFraction

        val bitmapCues: MutableList<Cue> = ArrayList<Cue>()
        val textCues: MutableList<Cue> = ArrayList<Cue>()
        for (i in cues.indices) {
            val cue = cues[i]
            if (cue.bitmap != null) {
                bitmapCues.add(cue)
            } else {
                textCues.add(cue)
            }
        }

        if (!this.textCues.isEmpty() || !textCues.isEmpty()) {
            this.textCues = textCues
            // Skip updating if this is a transition from empty-cues to empty-cues (i.e. only positioning
            // info has changed) since a positional-only change with no cues is a visual no-op. The new
            // position info will be used when we get non-empty cue data in a future update() call.
            updateWebView()
        }
        canvasSubtitleOutput.update(
            bitmapCues,
            style,
            textSize,
            textSizeType,
            bottomPaddingFraction
        )
        // Invalidate to trigger canvasSubtitleOutput to draw.
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed && !textCues.isEmpty()) {
            // A positional change with no cues is a visual no-op. The new layout info will be used
            // automatically next time update() is called.
            updateWebView()
        }
    }

    fun destroy() {
        webView.destroy()
    }

    private fun updateWebView() {
        val html = StringBuilder()
        html.append(
            formatInvariant(
                ("<body><div style='"
                        + "-webkit-user-select:none;"
                        + "position:fixed;"
                        + "top:0;"
                        + "bottom:0;"
                        + "left:0;"
                        + "right:0;"
                        + "color:%s;"
                        + "font-size:%s;"
                        + "line-height:%.2f;"
                        + "text-shadow:%s;"
                        + "'>"),
                HtmlUtils.toCssRgba(style.foregroundColor),
                convertTextSizeToCss(defaultTextSizeType, defaultTextSize),
                CSS_LINE_HEIGHT,
                convertCaptionStyleToCssTextShadow(style)
            )
        )

        val cssRuleSets: MutableMap<String?, String?> = HashMap<String?, String?>()
        cssRuleSets.put(
            HtmlUtils.cssAllClassDescendantsSelector(DEFAULT_BACKGROUND_CSS_CLASS),
            formatInvariant("background-color:%s;", HtmlUtils.toCssRgba(style.backgroundColor))
        )
        for (i in textCues.indices) {
            val cue = textCues[i]
            val positionPercent = if (cue.position != Cue.DIMEN_UNSET) (cue.position * 100) else 50f
            val positionAnchorTranslatePercent: Int =
                anchorTypeToTranslatePercent(cue.positionAnchor)

            val lineValue: String?
            var lineMeasuredFromEnd = false
            var lineAnchorTranslatePercent = 0
            if (cue.line != Cue.DIMEN_UNSET) {
                when (cue.lineType) {
                    Cue.LINE_TYPE_NUMBER -> if (cue.line >= 0) {
                        lineValue = formatInvariant("%.2fem", cue.line * CSS_LINE_HEIGHT)
                    } else {
                        lineValue = formatInvariant("%.2fem", (-cue.line - 1) * CSS_LINE_HEIGHT)
                        lineMeasuredFromEnd = true
                    }

                    Cue.LINE_TYPE_FRACTION, Cue.TYPE_UNSET -> {
                        lineValue = formatInvariant("%.2f%%", cue.line * 100)

                        lineAnchorTranslatePercent =
                            if (cue.verticalType == Cue.VERTICAL_TYPE_RL)
                                -anchorTypeToTranslatePercent(cue.lineAnchor)
                            else
                                anchorTypeToTranslatePercent(cue.lineAnchor)
                    }

                    else -> {
                        lineValue = formatInvariant("%.2f%%", cue.line * 100)

                        lineAnchorTranslatePercent =
                            if (cue.verticalType == Cue.VERTICAL_TYPE_RL)
                                -anchorTypeToTranslatePercent(cue.lineAnchor)
                            else
                                anchorTypeToTranslatePercent(cue.lineAnchor)
                    }
                }
            } else {
                lineValue = formatInvariant("%.2f%%", (1.0f - bottomPaddingFraction) * 100)
                lineAnchorTranslatePercent = -100
            }

            val size =
                if (cue.size != Cue.DIMEN_UNSET)
                    formatInvariant("%.2f%%", cue.size * 100)
                else
                    "fit-content"

            val textAlign: String = convertAlignmentToCss(cue.textAlignment)
            val writingMode: String = convertVerticalTypeToCss(cue.verticalType)
            val cueTextSizeCssPx = convertTextSizeToCss(cue.textSizeType, cue.textSize)
            val windowCssColor =
                HtmlUtils.toCssRgba(if (cue.windowColorSet) cue.windowColor else style.windowColor)

            val positionProperty: String?
            val lineProperty: String?
            when (cue.verticalType) {
                Cue.VERTICAL_TYPE_LR -> {
                    lineProperty = if (lineMeasuredFromEnd) "right" else "left"
                    positionProperty = "top"
                }

                Cue.VERTICAL_TYPE_RL -> {
                    lineProperty = if (lineMeasuredFromEnd) "left" else "right"
                    positionProperty = "top"
                }

                Cue.TYPE_UNSET -> {
                    lineProperty = if (lineMeasuredFromEnd) "bottom" else "top"
                    positionProperty = "left"
                }

                else -> {
                    lineProperty = if (lineMeasuredFromEnd) "bottom" else "top"
                    positionProperty = "left"
                }
            }

            val sizeProperty: String?
            val horizontalTranslatePercent: Int
            val verticalTranslatePercent: Int
            if (cue.verticalType == Cue.VERTICAL_TYPE_LR || cue.verticalType == Cue.VERTICAL_TYPE_RL) {
                sizeProperty = "height"
                horizontalTranslatePercent = lineAnchorTranslatePercent
                verticalTranslatePercent = positionAnchorTranslatePercent
            } else {
                sizeProperty = "width"
                horizontalTranslatePercent = positionAnchorTranslatePercent
                verticalTranslatePercent = lineAnchorTranslatePercent
            }

            val htmlAndCss =
                SpannedToHtmlConverter.convert(
                    cue.text, getContext().getResources().getDisplayMetrics().density
                )
            for (cssSelector in cssRuleSets.keys) {
                val previousCssDeclarationBlock =
                    cssRuleSets.put(cssSelector, cssRuleSets.get(cssSelector))
                Preconditions.checkState(
                    previousCssDeclarationBlock == null
                            || previousCssDeclarationBlock == cssRuleSets.get(cssSelector)
                )
            }

            html.append(
                formatInvariant(
                    ("<div style='"
                            + "position:absolute;"
                            + "z-index:%s;"
                            + "%s:%.2f%%;"
                            + "%s:%s;"
                            + "%s:%s;"
                            + "text-align:%s;"
                            + "writing-mode:%s;"
                            + "font-size:%s;"
                            + "background-color:%s;"
                            + "transform:translate(%s%%,%s%%)"
                            + "%s;"
                            + "'>"),  
                    i,
                    positionProperty,
                    positionPercent,
                    lineProperty,
                    lineValue,
                    sizeProperty,
                    size,
                    textAlign,
                    writingMode,
                    cueTextSizeCssPx,
                    windowCssColor,
                    horizontalTranslatePercent,
                    verticalTranslatePercent,
                    getBlockShearTransformFunction(cue)
                )
            )
                .append(formatInvariant("<span class='%s'>", DEFAULT_BACKGROUND_CSS_CLASS))

            if (cue.multiRowAlignment != null) {
                html.append(
                    formatInvariant(
                        "<span style='display:inline-block; text-align:%s;'>",
                        convertAlignmentToCss(cue.multiRowAlignment)
                    )
                )
                    .append(htmlAndCss.html)
                    .append("</span>")
            } else {
                html.append(htmlAndCss.html)
            }

            html.append("</span>").append("</div>")
        }

        html.append("</div></body></html>")

        val htmlHead = StringBuilder()
        htmlHead.append("<html><head><style>")
        for (cssSelector in cssRuleSets.keys) {
            htmlHead.append(cssSelector).append("{").append(cssRuleSets.get(cssSelector))
                .append("}")
        }
        htmlHead.append("</style></head>")
        html.insert(0, htmlHead)

        webView.loadData(
            Base64.encodeToString(
                html.toString().toByteArray(StandardCharsets.UTF_8),
                Base64.NO_PADDING
            ),
            "text/html",
            "base64"
        )
    }

    private fun convertTextSizeToCss(
        @Cue.TextSizeType type: @Cue.TextSizeType Int,
        size: Float
    ): String {
        val sizePx =
            SubtitleViewUtils.resolveTextSize(
                type, size, getHeight(), getHeight() - getPaddingTop() - getPaddingBottom()
            )
        if (sizePx == Cue.DIMEN_UNSET) {
            return "unset"
        }
        val sizeDp = sizePx / getContext().getResources().getDisplayMetrics().density
        return formatInvariant("%.2fpx", sizeDp)
    }

    companion object {
        
        private const val CSS_LINE_HEIGHT = 1.2f

        private const val DEFAULT_BACKGROUND_CSS_CLASS = "default_bg"

        private fun getBlockShearTransformFunction(cue: Cue): String {
            if (cue.shearDegrees != 0.0f) {
                val direction =
                    if (cue.verticalType == Cue.VERTICAL_TYPE_LR || cue.verticalType == Cue.VERTICAL_TYPE_RL)
                        "skewY"
                    else
                        "skewX"
                return formatInvariant("%s(%.2fdeg)", direction, cue.shearDegrees)
            }
            return ""
        }

        private fun convertCaptionStyleToCssTextShadow(style: CaptionStyleCompat): String {
            when (style.edgeType) {
                CaptionStyleCompat.EDGE_TYPE_DEPRESSED -> return formatInvariant(
                    "-0.05em -0.05em 0.15em %s", HtmlUtils.toCssRgba(style.edgeColor)
                )

                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW -> return formatInvariant(
                    "0.1em 0.12em 0.15em %s",
                    HtmlUtils.toCssRgba(style.edgeColor)
                )

                CaptionStyleCompat.EDGE_TYPE_OUTLINE ->         // -webkit-text-stroke makes the underlying text appear too narrow, so we 'fake' an edge
                    // outline using 4 text-shadows each offset by 1px in different directions.
                    return formatInvariant(
                        "1px 1px 0 %1\$s, 1px -1px 0 %1\$s, -1px 1px 0 %1\$s, -1px -1px 0 %1\$s",
                        HtmlUtils.toCssRgba(style.edgeColor)
                    )

                CaptionStyleCompat.EDGE_TYPE_RAISED -> return formatInvariant(
                    "0.06em 0.08em 0.15em %s", HtmlUtils.toCssRgba(style.edgeColor)
                )

                CaptionStyleCompat.EDGE_TYPE_NONE -> return "unset"
                else -> return "unset"
            }
        }

        private fun convertVerticalTypeToCss(@Cue.VerticalType verticalType: @Cue.VerticalType Int): String {
            when (verticalType) {
                Cue.VERTICAL_TYPE_LR -> return "vertical-lr"
                Cue.VERTICAL_TYPE_RL -> return "vertical-rl"
                Cue.TYPE_UNSET -> return "horizontal-tb"
                else -> return "horizontal-tb"
            }
        }

        private fun convertAlignmentToCss(alignment: Layout.Alignment?): String {
            if (alignment == null) {
                return "center"
            }
            when (alignment) {
                Layout.Alignment.ALIGN_NORMAL -> return "start"
                Layout.Alignment.ALIGN_OPPOSITE -> return "end"
                Layout.Alignment.ALIGN_CENTER -> return "center"
                else -> return "center"
            }
        }

        private fun anchorTypeToTranslatePercent(@Cue.AnchorType anchorType: @Cue.AnchorType Int): Int {
            when (anchorType) {
                Cue.ANCHOR_TYPE_END -> return -100
                Cue.ANCHOR_TYPE_MIDDLE -> return -50
                Cue.ANCHOR_TYPE_START, Cue.TYPE_UNSET -> return 0
                else -> return 0
            }
        }
    }
}
