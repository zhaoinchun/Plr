package androidx.media3.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.getStringForTime
import androidx.media3.ui.TimeBar.OnScrubListener
import com.example.plr.R
import com.google.common.base.Preconditions

import java.util.Formatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.withSave

class DefaultTimeBar  constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    timebarAttrs: AttributeSet? = attrs,
    defStyleRes: Int = 0
) : View(context, attrs, defStyleAttr), TimeBar {
    private val seekBounds: Rect = Rect()
    private val progressBar: Rect = Rect()
    private val bufferedBar: Rect = Rect()
    private val scrubberBar: Rect = Rect()
    private val playedPaint: Paint = Paint()
    private val bufferedPaint: Paint = Paint()
    private val unplayedPaint: Paint = Paint()
    private val adMarkerPaint: Paint = Paint()
    private val playedAdMarkerPaint: Paint = Paint()
    private val scrubberPaint: Paint = Paint().apply {
        isAntiAlias = true
    }
    private val scrubberDrawable: Drawable?
    private val barHeight: Int
    private val touchTargetHeight: Int
    private val barGravity: Int
    private val adMarkerWidth: Int
    private val scrubberEnabledSize: Int
    private val scrubberDisabledSize: Int
    private val scrubberDraggedSize: Int
    private val scrubberPadding: Int
    private val fineScrubYThreshold: Int
    private val formatBuilder = StringBuilder()
    private val formatter: Formatter
    private val stopScrubbingRunnable = Runnable { stopScrubbing(false) }
    private val listeners = CopyOnWriteArraySet<OnScrubListener>()
    private val touchPosition = Point()
    private val density: Float

    private var keyCountIncrement = DEFAULT_INCREMENT_COUNT
    private var keyTimeIncrement = C.TIME_UNSET
    private var lastCoarseScrubXPosition = 0
    private var lastExclusionRectangle:Rect? = null

    private val scrubberScalingAnimator = ValueAnimator()
    private var scrubberScale = 1.0f
    private var scrubberPaddingDisabled = false
    private var scrubbing = false
    private var scrubPosition: Long = 0
    private var duration = C.TIME_UNSET
    private var position: Long = 0
    private var bufferedPosition: Long = 0
    private var adGroupCount = 0
    private var adGroupTimesMs: LongArray? = null
    private var playedAdGroups: BooleanArray? = null

    init {
        val res = context.resources
        val displayMetrics = res.displayMetrics
        density = displayMetrics.density
        fineScrubYThreshold = dpToPx(density, FINE_SCRUB_Y_THRESHOLD_DP)
        val defaultBarHeight: Int = dpToPx(density, DEFAULT_BAR_HEIGHT_DP)
        var defaultTouchTargetHeight: Int = dpToPx(density, DEFAULT_TOUCH_TARGET_HEIGHT_DP)
        val defaultAdMarkerWidth: Int = dpToPx(density, DEFAULT_AD_MARKER_WIDTH_DP)
        val defaultScrubberEnabledSize: Int = dpToPx(density, DEFAULT_SCRUBBER_ENABLED_SIZE_DP)
        val defaultScrubberDisabledSize: Int = dpToPx(density, DEFAULT_SCRUBBER_DISABLED_SIZE_DP)
        val defaultScrubberDraggedSize: Int = dpToPx(density, DEFAULT_SCRUBBER_DRAGGED_SIZE_DP)
        if (timebarAttrs != null) {
            val a = context.theme
                .obtainStyledAttributes(
                    timebarAttrs, R.styleable.DefaultTimeBar, defStyleAttr, defStyleRes
                )
            try {
                scrubberDrawable = a.getDrawable(R.styleable.DefaultTimeBar_scrubber_drawable)
                if (scrubberDrawable != null) {
                    scrubberDrawable.setLayoutDirection(layoutDirection)
                    defaultTouchTargetHeight =
                        max(scrubberDrawable.getMinimumHeight(), defaultTouchTargetHeight)
                }
                barHeight =
                    a.getDimensionPixelSize(R.styleable.DefaultTimeBar_bar_height, defaultBarHeight)
                touchTargetHeight =
                    a.getDimensionPixelSize(
                        R.styleable.DefaultTimeBar_touch_target_height, defaultTouchTargetHeight
                    )
                barGravity = a.getInt(R.styleable.DefaultTimeBar_bar_gravity, BAR_GRAVITY_CENTER)
                adMarkerWidth =
                    a.getDimensionPixelSize(
                        R.styleable.DefaultTimeBar_ad_marker_width, defaultAdMarkerWidth
                    )
                scrubberEnabledSize =
                    a.getDimensionPixelSize(
                        R.styleable.DefaultTimeBar_scrubber_enabled_size, defaultScrubberEnabledSize
                    )
                scrubberDisabledSize =
                    a.getDimensionPixelSize(
                        R.styleable.DefaultTimeBar_scrubber_disabled_size,
                        defaultScrubberDisabledSize
                    )
                scrubberDraggedSize =
                    a.getDimensionPixelSize(
                        R.styleable.DefaultTimeBar_scrubber_dragged_size, defaultScrubberDraggedSize
                    )
                val playedColor =
                    a.getInt(R.styleable.DefaultTimeBar_played_color, DEFAULT_PLAYED_COLOR)
                val scrubberColor =
                    a.getInt(R.styleable.DefaultTimeBar_scrubber_color, DEFAULT_SCRUBBER_COLOR)
                val bufferedColor =
                    a.getInt(R.styleable.DefaultTimeBar_buffered_color, DEFAULT_BUFFERED_COLOR)
                val unplayedColor =
                    a.getInt(R.styleable.DefaultTimeBar_unplayed_color, DEFAULT_UNPLAYED_COLOR)
                val adMarkerColor =
                    a.getInt(R.styleable.DefaultTimeBar_ad_marker_color, DEFAULT_AD_MARKER_COLOR)
                val playedAdMarkerColor =
                    a.getInt(
                        R.styleable.DefaultTimeBar_played_ad_marker_color,
                        DEFAULT_PLAYED_AD_MARKER_COLOR
                    )
                playedPaint.setColor(playedColor)
                scrubberPaint.setColor(scrubberColor)
                bufferedPaint.setColor(bufferedColor)
                unplayedPaint.setColor(unplayedColor)
                adMarkerPaint.setColor(adMarkerColor)
                playedAdMarkerPaint.setColor(playedAdMarkerColor)
            } finally {
                a.recycle()
            }
        } else {
            barHeight = defaultBarHeight
            touchTargetHeight = defaultTouchTargetHeight
            barGravity = BAR_GRAVITY_CENTER
            adMarkerWidth = defaultAdMarkerWidth
            scrubberEnabledSize = defaultScrubberEnabledSize
            scrubberDisabledSize = defaultScrubberDisabledSize
            scrubberDraggedSize = defaultScrubberDraggedSize
            playedPaint.setColor(DEFAULT_PLAYED_COLOR)
            scrubberPaint.setColor(DEFAULT_SCRUBBER_COLOR)
            bufferedPaint.setColor(DEFAULT_BUFFERED_COLOR)
            unplayedPaint.setColor(DEFAULT_UNPLAYED_COLOR)
            adMarkerPaint.setColor(DEFAULT_AD_MARKER_COLOR)
            playedAdMarkerPaint.setColor(DEFAULT_PLAYED_AD_MARKER_COLOR)
            scrubberDrawable = null
        }
        formatter = Formatter(formatBuilder, Locale.getDefault())
        if (scrubberDrawable != null) {
            scrubberPadding = (scrubberDrawable.getMinimumWidth() + 1) / 2
        } else {
            scrubberPadding =
                ((max(scrubberDisabledSize, max(scrubberEnabledSize, scrubberDraggedSize)) + 1)
                        / 2)
        }
        scrubberScalingAnimator.addUpdateListener { animation: ValueAnimator ->
            scrubberScale = animation.getAnimatedValue() as Float
            invalidate(seekBounds)
        }
        setFocusable(true)
        if (importantForAccessibility == IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES)
        }
    }

    fun showScrubber() {
        if (scrubberScalingAnimator.isStarted) {
            scrubberScalingAnimator.cancel()
        }
        scrubberPaddingDisabled = false
        scrubberScale = 1f
        invalidate(seekBounds)
    }

    fun showScrubber(showAnimationDurationMs: Long) {
        if (scrubberScalingAnimator.isStarted) {
            scrubberScalingAnimator.cancel()
        }
        scrubberPaddingDisabled = false
        scrubberScalingAnimator.setFloatValues(scrubberScale, SHOWN_SCRUBBER_SCALE)
        scrubberScalingAnimator.setDuration(showAnimationDurationMs)
        scrubberScalingAnimator.start()
    }

    fun hideScrubber(disableScrubberPadding: Boolean) {
        if (scrubberScalingAnimator.isStarted) {
            scrubberScalingAnimator.cancel()
        }
        scrubberPaddingDisabled = disableScrubberPadding
        scrubberScale = 0f
        invalidate(seekBounds)
    }

    fun hideScrubber(hideAnimationDurationMs: Long) {
        if (scrubberScalingAnimator.isStarted) {
            scrubberScalingAnimator.cancel()
        }
        scrubberScalingAnimator.setFloatValues(scrubberScale, HIDDEN_SCRUBBER_SCALE)
        scrubberScalingAnimator.setDuration(hideAnimationDurationMs)
        scrubberScalingAnimator.start()
    }

    fun setPlayedColor(@ColorInt playedColor: Int) {
        playedPaint.setColor(playedColor)
        invalidate(seekBounds)
    }

    fun setScrubberColor(@ColorInt scrubberColor: Int) {
        scrubberPaint.setColor(scrubberColor)
        invalidate(seekBounds)
    }

    fun setBufferedColor(@ColorInt bufferedColor: Int) {
        bufferedPaint.setColor(bufferedColor)
        invalidate(seekBounds)
    }

    fun setUnplayedColor(@ColorInt unplayedColor: Int) {
        unplayedPaint.setColor(unplayedColor)
        invalidate(seekBounds)
    }

    fun setAdMarkerColor(@ColorInt adMarkerColor: Int) {
        adMarkerPaint.setColor(adMarkerColor)
        invalidate(seekBounds)
    }

    fun setPlayedAdMarkerColor(@ColorInt playedAdMarkerColor: Int) {
        playedAdMarkerPaint.setColor(playedAdMarkerColor)
        invalidate(seekBounds)
    }

    // TimeBar implementation.
    override fun addListener(listener: OnScrubListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: OnScrubListener) {
        listeners.remove(listener)
    }

    override fun setKeyTimeIncrement(time: Long) {
        Preconditions.checkArgument(time > 0)
        keyCountIncrement = C.INDEX_UNSET
        keyTimeIncrement = time
    }

    override fun setKeyCountIncrement(count: Int) {
        Preconditions.checkArgument(count > 0)
        keyCountIncrement = count
        keyTimeIncrement = C.TIME_UNSET
    }

    override fun setPosition(position: Long) {
        if (this.position == position) {
            return
        }
        this.position = position
        setContentDescription(this.progressText)
        update()
    }

    override fun setBufferedPosition(bufferedPosition: Long) {
        if (this.bufferedPosition == bufferedPosition) {
            return
        }
        this.bufferedPosition = bufferedPosition
        update()
    }

    override fun setDuration(duration: Long) {
        if (this.duration == duration) {
            return
        }
        this.duration = duration
        if (scrubbing && duration == C.TIME_UNSET) {
            stopScrubbing( true)
        }
        update()
    }

    override val preferredUpdateDelay: Long
        get() {
            val timeBarWidthDp: Int = pxToDp(density, progressBar.width())
            return if (timeBarWidthDp == 0 || duration == 0L || duration == C.TIME_UNSET) Long.MAX_VALUE else
                duration / timeBarWidthDp
        }

    override fun setAdGroupTimesMs(
        adGroupTimesMs: LongArray?, playedAdGroups: BooleanArray?, adGroupCount: Int
    ) {
        Preconditions.checkArgument(adGroupCount == 0 || (adGroupTimesMs != null && playedAdGroups != null))
        this.adGroupCount = adGroupCount
        this.adGroupTimesMs = adGroupTimesMs
        this.playedAdGroups = playedAdGroups
        update()
    }

    // View methods.
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (scrubbing && !enabled) {
            stopScrubbing( true)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.withSave {
            drawTimeBar(this)
            drawPlayhead(this)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || duration <= 0) {
            return false
        }
        val touchPosition = resolveRelativeTouchPosition(event)
        val x = touchPosition.x
        val y = touchPosition.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> if (isInSeekBar(x.toFloat(), y.toFloat())) {
                positionScrubber(x.toFloat())
                startScrubbing(this.scrubberPosition)
                update()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> if (scrubbing) {
                if (y < fineScrubYThreshold) {
                    val relativeX = x - lastCoarseScrubXPosition
                    positionScrubber((lastCoarseScrubXPosition + relativeX / FINE_SCRUB_RATIO).toFloat())
                } else {
                    lastCoarseScrubXPosition = x
                    positionScrubber(x.toFloat())
                }
                updateScrubbing(this.scrubberPosition)
                update()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (scrubbing) {
                stopScrubbing(event.action == MotionEvent.ACTION_CANCEL)
                return true
            }

            else -> {}
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isEnabled) {
            var positionIncrement = this.positionIncrement
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    positionIncrement = -positionIncrement
                    if (scrubIncrementally(positionIncrement)) {
                        removeCallbacks(stopScrubbingRunnable)
                        postDelayed(stopScrubbingRunnable, STOP_SCRUBBING_TIMEOUT_MS)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> if (scrubIncrementally(positionIncrement)) {
                    removeCallbacks(stopScrubbingRunnable)
                    postDelayed(stopScrubbingRunnable, STOP_SCRUBBING_TIMEOUT_MS)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (scrubbing) {
                    stopScrubbing(false)
                    return true
                }

                else -> {}
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onFocusChanged(
        gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (scrubbing && !gainFocus) {
            stopScrubbing(false)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        updateDrawableState()
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        scrubberDrawable?.jumpToCurrentState()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val height =
            when (heightMode) {
                MeasureSpec.UNSPECIFIED -> touchTargetHeight
                MeasureSpec.EXACTLY -> heightSize
                else -> min(touchTargetHeight, heightSize)
            }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
        updateDrawableState()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val seekLeft = getPaddingLeft()
        val seekRight = width - getPaddingRight()
        val seekBoundsY: Int
        val progressBarY: Int
        val scrubberPadding = if (scrubberPaddingDisabled) 0 else this.scrubberPadding
        if (barGravity == BAR_GRAVITY_BOTTOM) {
            seekBoundsY = height - paddingBottom - touchTargetHeight
            progressBarY =
                height - paddingBottom - barHeight - max(scrubberPadding - (barHeight / 2), 0)
        } else {
            seekBoundsY = (height - touchTargetHeight) / 2
            progressBarY = (height - barHeight) / 2
        }
        seekBounds.set(seekLeft, seekBoundsY, seekRight, seekBoundsY + touchTargetHeight)
        progressBar.set(
            seekBounds.left + scrubberPadding,
            progressBarY,
            seekBounds.right - scrubberPadding,
            progressBarY + barHeight
        )
        if (Build.VERSION.SDK_INT >= 29) {
            setSystemGestureExclusionRectsV29(width, height)
        }
        update()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        if (scrubberDrawable != null && scrubberDrawable.setLayoutDirection(layoutDirection)) {
            invalidate()
        }
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            event.text.add(this.progressText)
        }
        event.setClassName(ACCESSIBILITY_CLASS_NAME)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.setClassName(ACCESSIBILITY_CLASS_NAME)
        info.setContentDescription(this.progressText)
        if (duration <= 0) {
            return
        }
        info.addAction(AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        if (super.performAccessibilityAction(action, args)) {
            return true
        }
        if (duration <= 0) {
            return false
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            if (scrubIncrementally(-this.positionIncrement)) {
                stopScrubbing( false)
            }
        } else if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            if (scrubIncrementally(this.positionIncrement)) {
                stopScrubbing( false)
            }
        } else {
            return false
        }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
        return true
    }

    private fun startScrubbing(scrubPosition: Long) {
        this.scrubPosition = scrubPosition
        scrubbing = true
        setPressed(true)
        val parent = getParent()
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true)
        }
        for (listener in listeners) {
            listener.onScrubStart(this, scrubPosition)
        }
    }

    private fun updateScrubbing(scrubPosition: Long) {
        if (this.scrubPosition == scrubPosition) {
            return
        }
        this.scrubPosition = scrubPosition
        for (listener in listeners) {
            listener.onScrubMove(this, scrubPosition)
        }
    }

    private fun stopScrubbing(canceled: Boolean) {
        removeCallbacks(stopScrubbingRunnable)
        scrubbing = false
        setPressed(false)
        val parent = getParent()
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(false)
        }
        invalidate()
        for (listener in listeners) {
            listener.onScrubStop(this, scrubPosition, canceled)
        }
    }

    private fun scrubIncrementally(positionChange: Long): Boolean {
        if (duration <= 0) {
            return false
        }
        val previousPosition = if (scrubbing) scrubPosition else position
        val scrubPosition = constrainValue(previousPosition + positionChange, 0, duration)
        if (scrubPosition == previousPosition) {
            return false
        }
        if (!scrubbing) {
            startScrubbing(scrubPosition)
        } else {
            updateScrubbing(scrubPosition)
        }
        update()
        return true
    }

    private fun update() {
        bufferedBar.set(progressBar)
        scrubberBar.set(progressBar)
        val newScrubberTime = if (scrubbing) scrubPosition else position
        if (duration > 0) {
            val bufferedPixelWidth = ((progressBar.width() * bufferedPosition) / duration).toInt()
            bufferedBar.right = min(progressBar.left + bufferedPixelWidth, progressBar.right)
            val scrubberPixelPosition = ((progressBar.width() * newScrubberTime) / duration).toInt()
            scrubberBar.right = min(progressBar.left + scrubberPixelPosition, progressBar.right)
        } else {
            bufferedBar.right = progressBar.left
            scrubberBar.right = progressBar.left
        }
        invalidate(seekBounds)
    }

    private fun positionScrubber(xPosition: Float) {
        scrubberBar.right = constrainValue(xPosition.toInt(), progressBar.left, progressBar.right)
    }

    private fun resolveRelativeTouchPosition(motionEvent: MotionEvent): Point {
        touchPosition.set(motionEvent.x.toInt(), motionEvent.y.toInt())
        return touchPosition
    }

    private val scrubberPosition: Long
        get() {
            if (progressBar.width() <= 0 || duration == C.TIME_UNSET) {
                return 0
            }
            return (scrubberBar.width() * duration) / progressBar.width()
        }

    private fun isInSeekBar(x: Float, y: Float): Boolean {
        return seekBounds.contains(x.toInt(), y.toInt())
    }

    private fun drawTimeBar(canvas: Canvas) {
        val progressBarHeight = progressBar.height()
        val barTop = progressBar.centerY() - progressBarHeight / 2
        val barBottom = barTop + progressBarHeight
        if (duration <= 0) {
            canvas.drawRect(
                progressBar.left.toFloat(),
                barTop.toFloat(),
                progressBar.right.toFloat(),
                barBottom.toFloat(),
                unplayedPaint
            )
            return
        }
        var bufferedLeft = bufferedBar.left
        val bufferedRight = bufferedBar.right
        val progressLeft = max(max(progressBar.left, bufferedRight), scrubberBar.right)
        if (progressLeft < progressBar.right) {
            canvas.drawRect(
                progressLeft.toFloat(),
                barTop.toFloat(),
                progressBar.right.toFloat(),
                barBottom.toFloat(),
                unplayedPaint
            )
        }
        bufferedLeft = max(bufferedLeft, scrubberBar.right)
        if (bufferedRight > bufferedLeft) {
            canvas.drawRect(
                bufferedLeft.toFloat(),
                barTop.toFloat(),
                bufferedRight.toFloat(),
                barBottom.toFloat(),
                bufferedPaint
            )
        }
        if (scrubberBar.width() > 0) {
            canvas.drawRect(
                scrubberBar.left.toFloat(),
                barTop.toFloat(),
                scrubberBar.right.toFloat(),
                barBottom.toFloat(),
                playedPaint
            )
        }
        if (adGroupCount == 0) {
            return
        }
        val adGroupTimesMs = adGroupTimesMs!!
        val playedAdGroups = playedAdGroups!!
        val adMarkerOffset = adMarkerWidth / 2
        for (i in 0..<adGroupCount) {
            val adGroupTimeMs = constrainValue(adGroupTimesMs[i], 0, duration)
            val markerPositionOffset =
                (progressBar.width() * adGroupTimeMs / duration).toInt() - adMarkerOffset
            val markerLeft =
                (progressBar.left
                        + min(progressBar.width() - adMarkerWidth, max(0, markerPositionOffset)))
            val paint = if (playedAdGroups[i]) playedAdMarkerPaint else adMarkerPaint
            canvas.drawRect(
                markerLeft.toFloat(),
                barTop.toFloat(),
                (markerLeft + adMarkerWidth).toFloat(),
                barBottom.toFloat(),
                paint
            )
        }
    }

    private fun drawPlayhead(canvas: Canvas) {
        if (duration <= 0) {
            return
        }
        val playheadX = constrainValue(scrubberBar.right, scrubberBar.left, progressBar.right)
        val playheadY = scrubberBar.centerY()
        if (scrubberDrawable == null) {
            val scrubberSize =
                if (scrubbing || isFocused)
                    scrubberDraggedSize
                else
                    (if (isEnabled) scrubberEnabledSize else scrubberDisabledSize)
            val playheadRadius = ((scrubberSize * scrubberScale) / 2).toInt()
            canvas.drawCircle(
                playheadX.toFloat(),
                playheadY.toFloat(),
                playheadRadius.toFloat(),
                scrubberPaint
            )
        } else {
            val scrubberDrawableWidth =
                (scrubberDrawable.intrinsicWidth * scrubberScale).toInt()
            val scrubberDrawableHeight =
                (scrubberDrawable.intrinsicHeight * scrubberScale).toInt()
            scrubberDrawable.setBounds(
                playheadX - scrubberDrawableWidth / 2,
                playheadY - scrubberDrawableHeight / 2,
                playheadX + scrubberDrawableWidth / 2,
                playheadY + scrubberDrawableHeight / 2
            )
            scrubberDrawable.draw(canvas)
        }
    }

    private fun updateDrawableState() {
        if (scrubberDrawable != null && scrubberDrawable.isStateful
            && scrubberDrawable.setState(drawableState)
        ) {
            invalidate()
        }
    }

    @RequiresApi(29)
    private fun setSystemGestureExclusionRectsV29(width: Int, height: Int) {
        if (lastExclusionRectangle != null && lastExclusionRectangle!!.width() == width && lastExclusionRectangle!!.height() == height) {
            // Allocating inside onLayout is considered a DrawAllocation lint error, so avoid if possible.
            return
        }
        lastExclusionRectangle = Rect( 0,  0, width, height)
        setSystemGestureExclusionRects(mutableListOf<Rect?>(lastExclusionRectangle))
    }

    private val progressText: String
        get() = getStringForTime(
            formatBuilder,
            formatter,
            position
        )

    private val positionIncrement: Long
        get() = if (keyTimeIncrement == C.TIME_UNSET)
            (if (duration == C.TIME_UNSET) 0 else (duration / keyCountIncrement))
        else
            keyTimeIncrement

    companion object {
        const val DEFAULT_BAR_HEIGHT_DP: Int = 4
        const val DEFAULT_TOUCH_TARGET_HEIGHT_DP: Int = 26
        const val DEFAULT_AD_MARKER_WIDTH_DP: Int = 4
        const val DEFAULT_SCRUBBER_ENABLED_SIZE_DP: Int = 12
        const val DEFAULT_SCRUBBER_DISABLED_SIZE_DP: Int = 0
        const val DEFAULT_SCRUBBER_DRAGGED_SIZE_DP: Int = 16
        const val DEFAULT_PLAYED_COLOR: Int = -0x1
        const val DEFAULT_UNPLAYED_COLOR: Int = 0x33FFFFFF
        const val DEFAULT_BUFFERED_COLOR: Int = -0x33000001
        const val DEFAULT_SCRUBBER_COLOR: Int = -0x1
        const val DEFAULT_AD_MARKER_COLOR: Int = -0x4d000100
        const val DEFAULT_PLAYED_AD_MARKER_COLOR: Int = 0x33FFFF00
        const val BAR_GRAVITY_CENTER: Int = 0
        const val BAR_GRAVITY_BOTTOM: Int = 1
        private const val FINE_SCRUB_Y_THRESHOLD_DP = -50
        private const val FINE_SCRUB_RATIO = 3
        private const val STOP_SCRUBBING_TIMEOUT_MS: Long = 1000
        private const val DEFAULT_INCREMENT_COUNT = 20
        private const val SHOWN_SCRUBBER_SCALE = 1.0f
        private const val HIDDEN_SCRUBBER_SCALE = 0.0f
        private const val ACCESSIBILITY_CLASS_NAME = "android.widget.SeekBar"

        private fun dpToPx(density: Float, dps: Int): Int {
            return (dps * density + 0.5f).toInt()
        }

        private fun pxToDp(density: Float, px: Int): Int {
            return (px / density).toInt()
        }
    }
}
