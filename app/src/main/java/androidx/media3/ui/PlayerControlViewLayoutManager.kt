package androidx.media3.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.animation.LinearInterpolator
import com.example.plr.R
import kotlin.math.max
import androidx.core.view.size
import androidx.core.view.isVisible

internal class PlayerControlViewLayoutManager(private val playerControlView: PlayerControlView) {
    private val controlsBackground: View? =
        playerControlView.findViewById(R.id.exo_controls_background)
    private val topControls: ViewGroup? = playerControlView.findViewById(R.id.exo_top_controls)
    private val centerControls: ViewGroup? =
        playerControlView.findViewById(R.id.exo_center_controls)
    private val bottomBar: ViewGroup? = playerControlView.findViewById(R.id.exo_bottom_bar)
    private val minimalControls: ViewGroup? =
        playerControlView.findViewById(R.id.exo_minimal_controls)
    private val basicControls: ViewGroup? = playerControlView.findViewById(R.id.exo_basic_controls)
    private val extraControls: ViewGroup? = playerControlView.findViewById(R.id.exo_extra_controls)
    private val extraControlsScrollView: ViewGroup? =
        playerControlView.findViewById(R.id.exo_extra_controls_scroll_view)
    private val timeView: ViewGroup? = playerControlView.findViewById(R.id.exo_time)
    private val timeBar: View? = playerControlView.findViewById(R.id.exo_progress)
    private val overflowShowButton: View? = playerControlView.findViewById(R.id.exo_overflow_show)

    private val hideMainBarAnimator: AnimatorSet
    private val hideProgressBarAnimator: AnimatorSet
    private val hideAllBarsAnimator: AnimatorSet
    private val showMainBarAnimator: AnimatorSet
    private val showAllBarsAnimator: AnimatorSet
    private val overflowShowAnimator: ValueAnimator
    private val overflowHideAnimator: ValueAnimator

    private val showAllBarsRunnable = Runnable { this.showAllBars() }
    private val hideAllBarsRunnable = Runnable { this.hideAllBars() }
    private val hideProgressBarRunnable = Runnable { this.hideProgressBar() }
    private val hideMainBarRunnable = Runnable { this.hideMainBar() }
    private val hideControllerRunnable = Runnable { this.hideController() }
    private val onLayoutChangeListener =
        OnLayoutChangeListener { v: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int ->
            onLayoutChange(
                v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom
            )
        }
    private val shownButtons = mutableListOf<View>()

    private var uxState = UX_STATE_ALL_VISIBLE
    private var isMinimalMode = false
    private var needToShowBars = false
    var isAnimationEnabled = true

    init {
        val overflowHideButton = playerControlView.findViewById<View?>(R.id.exo_overflow_hide)
        if (overflowShowButton != null && overflowHideButton != null) {
            overflowShowButton.setOnClickListener {
                onOverflowButtonClick(it)
            }
            overflowHideButton.setOnClickListener {
                onOverflowButtonClick(it)
            }
        }

        val fadeOutAnimator = ValueAnimator.ofFloat(1.0f, 0.0f)
        fadeOutAnimator.interpolator = LinearInterpolator()
        fadeOutAnimator.addUpdateListener { animation: ValueAnimator ->
            val animatedValue = animation.getAnimatedValue() as Float
            controlsBackground?.setAlpha(animatedValue)
            topControls?.setAlpha(animatedValue)
            centerControls?.setAlpha(animatedValue)
            minimalControls?.setAlpha(animatedValue)
        }
        fadeOutAnimator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    if (timeBar is DefaultTimeBar && !isMinimalMode) {
                        timeBar.hideScrubber(DURATION_FOR_HIDING_ANIMATION_MS)
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    controlsBackground?.visibility = View.INVISIBLE
                    topControls?.visibility = View.INVISIBLE
                    centerControls?.visibility = View.INVISIBLE
                    minimalControls?.visibility = View.INVISIBLE
                }
            })

        val fadeInAnimator = ValueAnimator.ofFloat(0.0f, 1.0f)
        fadeInAnimator.interpolator = LinearInterpolator()
        fadeInAnimator.addUpdateListener { animation: ValueAnimator ->
            val animatedValue = animation.getAnimatedValue() as Float
            controlsBackground?.setAlpha(animatedValue)
            topControls?.setAlpha(animatedValue)
            centerControls?.setAlpha(animatedValue)
            minimalControls?.setAlpha(animatedValue)
        }
        fadeInAnimator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    controlsBackground?.visibility = View.VISIBLE
                    topControls?.visibility = View.VISIBLE
                    centerControls?.visibility = View.VISIBLE
                    minimalControls?.visibility =
                        if (isMinimalMode) View.VISIBLE else View.INVISIBLE
                    if (timeBar is DefaultTimeBar && !isMinimalMode) {
                        timeBar.showScrubber(DURATION_FOR_SHOWING_ANIMATION_MS)
                    }
                }
            })

        val resources = playerControlView.resources
        val translationYForProgressBar =
            (resources.getDimension(R.dimen.exo_styled_bottom_bar_height)
                    - resources.getDimension(R.dimen.exo_styled_progress_bar_height))
        val translationYForNoBars = resources.getDimension(R.dimen.exo_styled_bottom_bar_height)

        hideMainBarAnimator = AnimatorSet()
        hideMainBarAnimator.setDuration(DURATION_FOR_HIDING_ANIMATION_MS)
        hideMainBarAnimator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    setUxState(UX_STATE_ANIMATING_HIDE)
                }

                override fun onAnimationEnd(animation: Animator) {
                    setUxState(UX_STATE_ONLY_PROGRESS_VISIBLE)
                    if (needToShowBars) {
                        playerControlView.post(showAllBarsRunnable)
                        needToShowBars = false
                    }
                }
            })
        hideMainBarAnimator
            .play(fadeOutAnimator)
            .with(ofTranslationY(0f, translationYForProgressBar, timeBar))
            .with(ofTranslationY(0f, translationYForProgressBar, bottomBar))

        hideProgressBarAnimator = AnimatorSet()
        hideProgressBarAnimator.setDuration(DURATION_FOR_HIDING_ANIMATION_MS)
        hideProgressBarAnimator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    setUxState(UX_STATE_ANIMATING_HIDE)
                }

                override fun onAnimationEnd(animation: Animator) {
                    setUxState(UX_STATE_NONE_VISIBLE)
                    if (needToShowBars) {
                        playerControlView.post(showAllBarsRunnable)
                        needToShowBars = false
                    }
                }
            })
        hideProgressBarAnimator
            .play(ofTranslationY(translationYForProgressBar, translationYForNoBars, timeBar))
            .with(ofTranslationY(translationYForProgressBar, translationYForNoBars, bottomBar))

        hideAllBarsAnimator = AnimatorSet()
        hideAllBarsAnimator.setDuration(DURATION_FOR_HIDING_ANIMATION_MS)
        hideAllBarsAnimator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    setUxState(UX_STATE_ANIMATING_HIDE)
                }

                override fun onAnimationEnd(animation: Animator) {
                    setUxState(UX_STATE_NONE_VISIBLE)
                    if (needToShowBars) {
                        playerControlView.post(showAllBarsRunnable)
                        needToShowBars = false
                    }
                }
            })
        hideAllBarsAnimator
            .play(fadeOutAnimator)
            .with(ofTranslationY(0f, translationYForNoBars, timeBar))
            .with(ofTranslationY(0f, translationYForNoBars, bottomBar))

        showMainBarAnimator = AnimatorSet()
        showMainBarAnimator.setDuration(DURATION_FOR_SHOWING_ANIMATION_MS)
        showMainBarAnimator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    setUxState(UX_STATE_ANIMATING_SHOW)
                }

                override fun onAnimationEnd(animation: Animator) {
                    setUxState(UX_STATE_ALL_VISIBLE)
                }
            })
        showMainBarAnimator
            .play(fadeInAnimator)
            .with(ofTranslationY(translationYForProgressBar, 0f, timeBar))
            .with(ofTranslationY(translationYForProgressBar, 0f, bottomBar))

        showAllBarsAnimator = AnimatorSet()
        showAllBarsAnimator.setDuration(DURATION_FOR_SHOWING_ANIMATION_MS)
        showAllBarsAnimator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    setUxState(UX_STATE_ANIMATING_SHOW)
                }

                override fun onAnimationEnd(animation: Animator) {
                    setUxState(UX_STATE_ALL_VISIBLE)
                }
            })
        showAllBarsAnimator
            .play(fadeInAnimator)
            .with(ofTranslationY(translationYForNoBars, 0f, timeBar))
            .with(ofTranslationY(translationYForNoBars, 0f, bottomBar))

        overflowShowAnimator = ValueAnimator.ofFloat(0.0f, 1.0f)
        overflowShowAnimator.setDuration(DURATION_FOR_SHOWING_ANIMATION_MS)
        overflowShowAnimator.addUpdateListener { animation: ValueAnimator? ->
            animateOverflow(animation!!.getAnimatedValue() as Float)
        }
        overflowShowAnimator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    if (extraControlsScrollView != null) {
                        extraControlsScrollView.visibility = View.VISIBLE
                        extraControlsScrollView.translationX =
                            extraControlsScrollView.width.toFloat()
                        extraControlsScrollView.scrollTo(extraControlsScrollView.width, 0)
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    basicControls?.visibility = View.INVISIBLE
                }
            })

        overflowHideAnimator = ValueAnimator.ofFloat(1.0f, 0.0f)
        overflowHideAnimator.setDuration(DURATION_FOR_SHOWING_ANIMATION_MS)
        overflowHideAnimator.addUpdateListener { animation: ValueAnimator ->
            animateOverflow(
                animation.getAnimatedValue() as Float
            )
        }
        overflowHideAnimator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    basicControls?.visibility = View.VISIBLE
                }

                override fun onAnimationEnd(animation: Animator) {
                    extraControlsScrollView?.visibility = View.INVISIBLE
                }
            })
    }

    fun show() {
        if (!playerControlView.isVisible) {
            playerControlView.visibility = View.VISIBLE
            playerControlView.updateAll()
            playerControlView.requestPlayPauseFocus()
        }
        showAllBars()
    }

    fun hide() {
        if (uxState == UX_STATE_ANIMATING_HIDE || uxState == UX_STATE_NONE_VISIBLE) {
            return
        }
        removeHideCallbacks()
        if (!this.isAnimationEnabled) {
            hideController()
        } else if (uxState == UX_STATE_ONLY_PROGRESS_VISIBLE) {
            hideProgressBar()
        } else {
            hideAllBars()
        }
    }

    fun hideImmediately() {
        if (uxState == UX_STATE_ANIMATING_HIDE || uxState == UX_STATE_NONE_VISIBLE) {
            return
        }
        removeHideCallbacks()
        hideController()
    }

    fun resetHideCallbacks() {
        if (uxState == UX_STATE_ANIMATING_HIDE) {
            return
        }
        removeHideCallbacks()
        val showTimeoutMs = playerControlView.getShowTimeoutMs()
        if (showTimeoutMs > 0) {
            if (!this.isAnimationEnabled) {
                postDelayedRunnable(hideControllerRunnable, showTimeoutMs.toLong())
            } else if (uxState == UX_STATE_ONLY_PROGRESS_VISIBLE) {
                postDelayedRunnable(hideProgressBarRunnable, ANIMATION_INTERVAL_MS)
            } else {
                postDelayedRunnable(hideMainBarRunnable, showTimeoutMs.toLong())
            }
        }
    }

    fun removeHideCallbacks() {
        playerControlView.removeCallbacks(hideControllerRunnable)
        playerControlView.removeCallbacks(hideAllBarsRunnable)
        playerControlView.removeCallbacks(hideMainBarRunnable)
        playerControlView.removeCallbacks(hideProgressBarRunnable)
    }

    fun onAttachedToWindow() {
        playerControlView.addOnLayoutChangeListener(onLayoutChangeListener)
    }

    fun onDetachedFromWindow() {
        playerControlView.removeOnLayoutChangeListener(onLayoutChangeListener)
    }

    val isFullyVisible: Boolean
        get() = uxState == UX_STATE_ALL_VISIBLE && playerControlView.isVisible

    fun setShowButton(button: View?, showButton: Boolean) {
        if (button == null) {
            return
        }
        if (!showButton) {
            button.visibility = View.GONE
            shownButtons.remove(button)
            return
        }
        if (isMinimalMode && shouldHideInMinimalMode(button)) {
            button.visibility = View.INVISIBLE
        } else {
            button.visibility = View.VISIBLE
        }
        shownButtons.add(button)
    }

    fun getShowButton(button: View?): Boolean {
        return button != null && shownButtons.contains(button)
    }

    private fun setUxState(uxState: Int) {
        val prevUxState = this.uxState
        this.uxState = uxState
        if (uxState == UX_STATE_NONE_VISIBLE) {
            playerControlView.visibility = View.GONE
        } else if (prevUxState == UX_STATE_NONE_VISIBLE) {
            playerControlView.visibility = View.VISIBLE
        }
        if (prevUxState != uxState) {
            playerControlView.notifyOnVisibilityChange()
        }
    }

    fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        controlsBackground?.layout(0, 0, right - left, bottom - top)
    }

    private fun onLayoutChange(
        v: View,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        oldLeft: Int,
        oldTop: Int,
        oldRight: Int,
        oldBottom: Int
    ) {
        val useMinimalMode = useMinimalMode()
        if (isMinimalMode != useMinimalMode) {
            isMinimalMode = useMinimalMode
            v.post { updateLayoutForSizeChange() }
        }
        val widthChanged = (right - left) != (oldRight - oldLeft)
        if (!isMinimalMode && widthChanged) {
            v.post { onLayoutWidthChanged() }
        }
    }

    private fun onOverflowButtonClick(v: View) {
        resetHideCallbacks()
        if (v.id == R.id.exo_overflow_show) {
            overflowShowAnimator.start()
        } else if (v.id == R.id.exo_overflow_hide) {
            overflowHideAnimator.start()
        }
    }

    private fun showAllBars() {
        if (!this.isAnimationEnabled) {
            setUxState(UX_STATE_ALL_VISIBLE)
            resetHideCallbacks()
            return
        }

        when (uxState) {
            UX_STATE_NONE_VISIBLE -> showAllBarsAnimator.start()
            UX_STATE_ONLY_PROGRESS_VISIBLE -> showMainBarAnimator.start()
            UX_STATE_ANIMATING_HIDE -> needToShowBars = true
            UX_STATE_ANIMATING_SHOW -> return
            else -> {}
        }
        resetHideCallbacks()
    }

    private fun hideAllBars() {
        hideAllBarsAnimator.start()
    }

    private fun hideProgressBar() {
        hideProgressBarAnimator.start()
    }

    private fun hideMainBar() {
        hideMainBarAnimator.start()
        postDelayedRunnable(hideProgressBarRunnable, ANIMATION_INTERVAL_MS)
    }

    private fun hideController() {
        setUxState(UX_STATE_NONE_VISIBLE)
    }

    private fun postDelayedRunnable(runnable: Runnable?, interval: Long) {
        if (interval >= 0) {
            playerControlView.postDelayed(runnable, interval)
        }
    }

    private fun animateOverflow(animatedValue: Float) {
        if (extraControlsScrollView != null) {
            val extraControlTranslationX =
                (extraControlsScrollView.width * (1 - animatedValue)).toInt()
            extraControlsScrollView.translationX = extraControlTranslationX.toFloat()
        }

        timeView?.setAlpha(1 - animatedValue)
        basicControls?.setAlpha(1 - animatedValue)
    }

    private fun useMinimalMode(): Boolean {
        val width =
            (playerControlView.width
                    - playerControlView.getPaddingLeft()
                    - playerControlView.getPaddingRight())
        val height =
            playerControlView.height - playerControlView.paddingBottom - playerControlView.paddingTop

        val centerControlWidth: Int =
            (getWidthWithMargins(centerControls)
                    - (centerControls?.let { it.paddingLeft + it.paddingRight } ?: 0))
        val centerControlHeight: Int =
            (getHeightWithMargins(centerControls)
                    - (centerControls?.let { it.paddingTop + it.paddingBottom } ?: 0))

        val defaultModeMinimumWidth = max(
            centerControlWidth,
            getWidthWithMargins(timeView) + getWidthWithMargins(overflowShowButton)
        )
        val defaultModeMinimumHeight: Int =
            centerControlHeight + (2 * getHeightWithMargins(bottomBar))

        return width <= defaultModeMinimumWidth || height <= defaultModeMinimumHeight
    }

    private fun updateLayoutForSizeChange() {
        minimalControls?.visibility = if (isMinimalMode) View.VISIBLE else View.INVISIBLE

        if (timeBar != null) {
            val timeBarMarginBottom =
                playerControlView
                    .resources
                    .getDimensionPixelSize(R.dimen.exo_styled_progress_margin_bottom)
            val timeBarParams = timeBar.layoutParams as MarginLayoutParams?
            if (timeBarParams != null) {
                timeBarParams.bottomMargin = (if (isMinimalMode) 0 else timeBarMarginBottom)
                timeBar.setLayoutParams(timeBarParams)
            }
            if (timeBar is DefaultTimeBar) {
                val defaultTimeBar = timeBar
                if (isMinimalMode) {
                    defaultTimeBar.hideScrubber( true)
                } else if (uxState == UX_STATE_ONLY_PROGRESS_VISIBLE) {
                    defaultTimeBar.hideScrubber( false)
                } else if (uxState != UX_STATE_ANIMATING_HIDE) {
                    defaultTimeBar.showScrubber()
                }
            }
        }

        for (v in shownButtons) {
            v.visibility =
                if (isMinimalMode && shouldHideInMinimalMode(v)) View.INVISIBLE else View.VISIBLE
        }
    }

    private fun shouldHideInMinimalMode(button: View): Boolean {
        val id = button.id
        return (id == R.id.exo_bottom_bar || id == R.id.exo_media_route_button_placeholder
                || id == R.id.exo_prev || id == R.id.exo_next || id == R.id.exo_rew
                || id == R.id.exo_rew_with_amount || id == R.id.exo_ffwd
                || id == R.id.exo_ffwd_with_amount)
    }

    private fun onLayoutWidthChanged() {
        if (basicControls == null || extraControls == null) {
            return
        }
        val width =
            playerControlView.width - playerControlView.paddingLeft - playerControlView.paddingRight

        // Reset back to all controls being basic controls and the overflow not being needed. The last
        // child of extraControls is the overflow hide button, which shouldn't be moved back.
        while (extraControls.size > 1) {
            val controlViewIndex = extraControls.size - 2
            val controlView = extraControls.getChildAt(controlViewIndex)
            extraControls.removeViewAt(controlViewIndex)
            basicControls.addView(controlView,  0)
        }
        overflowShowButton?.visibility = View.GONE

        // Calculate how much of the available width is occupied. The last child of basicControls is the
        // overflow show button, which we're currently assuming will not be visible.
        var occupiedWidth: Int = getWidthWithMargins(timeView)
        val endIndex = basicControls.size - 1
        for (i in 0..<endIndex) {
            val controlView = basicControls.getChildAt(i)
            occupiedWidth += getWidthWithMargins(controlView)
        }

        if (occupiedWidth > width) {
            // We need to move some controls to extraControls.
            if (overflowShowButton != null) {
                overflowShowButton.visibility = View.VISIBLE
                occupiedWidth += getWidthWithMargins(overflowShowButton)
            }
            val controlsToMove = ArrayList<View?>()
            // The last child of basicControls is the overflow show button, which shouldn't be moved.
            for (i in 0..<endIndex) {
                val control = basicControls.getChildAt(i)
                occupiedWidth -= getWidthWithMargins(control)
                controlsToMove.add(control)
                if (occupiedWidth <= width) {
                    break
                }
            }
            if (!controlsToMove.isEmpty()) {
                basicControls.removeViews( 0, controlsToMove.size)
                for (i in controlsToMove.indices) {
                    // The last child of extraControls is the overflow hide button. Add controls before it.
                    val index = extraControls.size - 1
                    extraControls.addView(controlsToMove[i], index)
                }
            }
        } else {
            // If extraControls are visible, hide them since they're now empty.
            if (extraControlsScrollView != null && extraControlsScrollView.isVisible && !overflowHideAnimator.isStarted) {
                overflowShowAnimator.cancel()
                overflowHideAnimator.start()
            }
        }
    }

    companion object {
        private const val ANIMATION_INTERVAL_MS: Long = 2000
        private const val DURATION_FOR_HIDING_ANIMATION_MS: Long = 250
        private const val DURATION_FOR_SHOWING_ANIMATION_MS: Long = 250
        private const val UX_STATE_ALL_VISIBLE = 0
        private const val UX_STATE_ONLY_PROGRESS_VISIBLE = 1
        private const val UX_STATE_NONE_VISIBLE = 2
        private const val UX_STATE_ANIMATING_HIDE = 3
        private const val UX_STATE_ANIMATING_SHOW = 4

        private fun ofTranslationY(
            startValue: Float,
            endValue: Float,
            target: View?
        ): ObjectAnimator? {
            return ObjectAnimator.ofFloat(target, "translationY", startValue, endValue)
        }

        private fun getWidthWithMargins(v: View?): Int {
            if (v == null) {
                return 0
            }
            var width = v.width
            val layoutParams = v.layoutParams
            if (layoutParams is MarginLayoutParams) {
                width += layoutParams.leftMargin + layoutParams.rightMargin
            }
            return width
        }

        private fun getHeightWithMargins(v: View?): Int {
            if (v == null) {
                return 0
            }
            var height = v.height
            val layoutParams = v.layoutParams
            if (layoutParams is MarginLayoutParams) {
                height += layoutParams.topMargin + layoutParams.bottomMargin
            }
            return height
        }
    }
}
