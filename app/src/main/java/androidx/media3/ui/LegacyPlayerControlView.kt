package androidx.media3.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.TypedArray
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.MediaLibraryInfo.registerModule
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.RepeatModeUtil
import androidx.media3.common.util.RepeatModeUtil.RepeatToggleModes

import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.getDrawable
import androidx.media3.common.util.Util.getStringForTime
import androidx.media3.common.util.Util.handlePauseButtonAction
import androidx.media3.common.util.Util.handlePlayButtonAction
import androidx.media3.common.util.Util.handlePlayPauseButtonAction
import androidx.media3.common.util.Util.shouldShowPlayButton
import androidx.media3.common.util.Util.usToMs
import androidx.media3.ui.TimeBar.OnScrubListener
import com.example.plr.R
import com.google.common.base.Preconditions
import java.util.Formatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

class LegacyPlayerControlView  constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    playbackAttrs: AttributeSet? = attrs
) : FrameLayout(context, attrs, defStyleAttr) {
    interface VisibilityListener {
        fun onVisibilityChange(visibility: Int)
    }

    interface ProgressUpdateListener {
        fun onProgressUpdate(position: Long, bufferedPosition: Long)
    }

    private val componentListener = ComponentListener()
    private val visibilityListeners = CopyOnWriteArrayList<VisibilityListener>()
    private val previousButton: View?
    private val nextButton: View?
    private val playButton: View?
    private val pauseButton: View?
    private val fastForwardButton: View?
    private val rewindButton: View?
    private val repeatToggleButton: ImageView?
    private val shuffleButton: ImageView?
    private val vrButton: View?
    private val durationView: TextView?
    private val positionView: TextView?
    private val timeBar: TimeBar?
    private val formatBuilder = StringBuilder()
    private val formatter = Formatter(formatBuilder, Locale.getDefault())
    private val period = Timeline.Period()
    private val window = Timeline.Window()
    private val updateProgressAction = Runnable { updateProgress() }
    private val hideAction = Runnable { hide() }

    private val repeatOffButtonDrawable: Drawable?
    private val repeatOneButtonDrawable: Drawable?
    private val repeatAllButtonDrawable: Drawable?
    private val repeatOffButtonContentDescription: String?
    private val repeatOneButtonContentDescription: String?
    private val repeatAllButtonContentDescription: String?
    private val shuffleOnButtonDrawable: Drawable?
    private val shuffleOffButtonDrawable: Drawable?
    private val buttonAlphaEnabled: Float
    private val buttonAlphaDisabled: Float
    private val shuffleOnContentDescription: String?
    private val shuffleOffContentDescription: String?

    private var player: Player? = null
    private var progressUpdateListener: ProgressUpdateListener? = null

    private var isAttachedToWindow = false
    private var showMultiWindowTimeBar = false
    private var showPlayButtonIfSuppressed = true
    private var multiWindowTimeBar = false
    private var scrubbing = false
    private var showTimeoutMs = DEFAULT_SHOW_TIMEOUT_MS
    private var timeBarMinUpdateIntervalMs = DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS

    @RepeatToggleModes
    private var repeatToggleModes: @RepeatToggleModes Int = DEFAULT_REPEAT_TOGGLE_MODES
    private var showRewindButton = true
    private var showFastForwardButton = true
    private var showPreviousButton = true
    private var showNextButton = true
    private var showShuffleButton = false
    private var hideAtMs = C.TIME_UNSET
    private var adGroupTimesMs = LongArray(0)
    private var playedAdGroups = BooleanArray(0)
    private var extraAdGroupTimesMs: LongArray? = LongArray(0)
    private var extraPlayedAdGroups: BooleanArray? = BooleanArray(0)
    private var currentWindowOffset: Long = 0
    private var currentPosition = C.TIME_UNSET
    private var currentBufferedPosition = C.TIME_UNSET

    var showVrButton: Boolean
        get() = vrButton != null && vrButton.isVisible
        set(showVrButton) {
            vrButton?.isVisible = showVrButton
        }

    init {
        var controllerLayoutId: Int = R.layout.exo_legacy_player_control_view
        if (playbackAttrs != null) {
            val a = context.theme
                    .obtainStyledAttributes(
                        playbackAttrs,
                        R.styleable.LegacyPlayerControlView,
                        defStyleAttr,  
                        0
                    )
            try {
                showTimeoutMs =
                    a.getInt(R.styleable.LegacyPlayerControlView_show_timeout, showTimeoutMs)
                controllerLayoutId =
                    a.getResourceId(
                        R.styleable.LegacyPlayerControlView_controller_layout_id, controllerLayoutId
                    )
                repeatToggleModes = getRepeatToggleModes(a, repeatToggleModes)
                showRewindButton =
                    a.getBoolean(
                        R.styleable.LegacyPlayerControlView_show_rewind_button,
                        showRewindButton
                    )
                showFastForwardButton =
                    a.getBoolean(
                        R.styleable.LegacyPlayerControlView_show_fastforward_button,
                        showFastForwardButton
                    )
                showPreviousButton =
                    a.getBoolean(
                        R.styleable.LegacyPlayerControlView_show_previous_button, showPreviousButton
                    )
                showNextButton =
                    a.getBoolean(
                        R.styleable.LegacyPlayerControlView_show_next_button,
                        showNextButton
                    )
                showShuffleButton =
                    a.getBoolean(
                        R.styleable.LegacyPlayerControlView_show_shuffle_button, showShuffleButton
                    )
                setTimeBarMinUpdateInterval(
                    a.getInt(
                        R.styleable.LegacyPlayerControlView_time_bar_min_update_interval,
                        timeBarMinUpdateIntervalMs
                    )
                )
            } finally {
                a.recycle()
            }
        }
        LayoutInflater.from(context).inflate(controllerLayoutId,  this)
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS)

        val customTimeBar: TimeBar? = findViewById(R.id.exo_progress)
        val timeBarPlaceholder = findViewById<View?>(R.id.exo_progress_placeholder)
        if (customTimeBar != null) {
            timeBar = customTimeBar
        } else if (timeBarPlaceholder != null) {
            // Propagate playbackAttrs as timebarAttrs so that DefaultTimeBar's custom attributes are
            // transferred, but standard attributes (e.g. background) are not.
            val defaultTimeBar = DefaultTimeBar(context, null, 0, playbackAttrs)
            defaultTimeBar.setId(R.id.exo_progress)
            defaultTimeBar.setLayoutParams(timeBarPlaceholder.getLayoutParams())
            val parent = (timeBarPlaceholder.getParent() as ViewGroup)
            val timeBarIndex = parent.indexOfChild(timeBarPlaceholder)
            parent.removeView(timeBarPlaceholder)
            parent.addView(defaultTimeBar, timeBarIndex)
            timeBar = defaultTimeBar
        } else {
            timeBar = null
        }
        durationView = findViewById(R.id.exo_duration)
        positionView = findViewById(R.id.exo_position)
        playButton = findViewById(R.id.exo_play)
        pauseButton = findViewById(R.id.exo_pause)
        previousButton = findViewById(R.id.exo_prev)
        nextButton = findViewById(R.id.exo_next)
        rewindButton = findViewById(R.id.exo_rew)
        fastForwardButton = findViewById(R.id.exo_ffwd)
        repeatToggleButton = findViewById(R.id.exo_repeat_toggle)
        shuffleButton = findViewById(R.id.exo_shuffle)

        timeBar?.addListener(componentListener)
        playButton?.setOnClickListener(componentListener)
        pauseButton?.setOnClickListener(componentListener)
        previousButton?.setOnClickListener(componentListener)
        nextButton?.setOnClickListener(componentListener)
        rewindButton?.setOnClickListener(componentListener)
        fastForwardButton?.setOnClickListener(componentListener)
        repeatToggleButton?.setOnClickListener(componentListener)
        shuffleButton?.setOnClickListener(componentListener)
        vrButton = findViewById(R.id.exo_vr)
        this.showVrButton = false
        updateButton(false, false, vrButton)

        val resources = context.resources
        buttonAlphaEnabled =
            resources.getInteger(R.integer.exo_media_button_opacity_percentage_enabled)
                .toFloat() / 100
        buttonAlphaDisabled =
            resources.getInteger(R.integer.exo_media_button_opacity_percentage_disabled)
                .toFloat() / 100

        repeatOffButtonDrawable =
            getDrawable(context, resources, R.drawable.exo_legacy_controls_repeat_off)
        repeatOneButtonDrawable =
            getDrawable(context, resources, R.drawable.exo_legacy_controls_repeat_one)
        repeatAllButtonDrawable =
            getDrawable(context, resources, R.drawable.exo_legacy_controls_repeat_all)
        shuffleOnButtonDrawable =
            getDrawable(context, resources, R.drawable.exo_legacy_controls_shuffle_on)
        shuffleOffButtonDrawable =
            getDrawable(context, resources, R.drawable.exo_legacy_controls_shuffle_off)
        repeatOffButtonContentDescription =
            resources.getString(R.string.exo_controls_repeat_off_description)
        repeatOneButtonContentDescription =
            resources.getString(R.string.exo_controls_repeat_one_description)
        repeatAllButtonContentDescription =
            resources.getString(R.string.exo_controls_repeat_all_description)
        shuffleOnContentDescription =
            resources.getString(R.string.exo_controls_shuffle_on_description)
        shuffleOffContentDescription =
            resources.getString(R.string.exo_controls_shuffle_off_description)
    }

    fun getPlayer(): Player? {
        return player
    }

    fun setPlayer(player: Player?) {
        Preconditions.checkState(Looper.myLooper() == Looper.getMainLooper())
        Preconditions.checkArgument(player == null || player.applicationLooper == Looper.getMainLooper())
        if (this.player === player) {
            return
        }
        this.player?.removeListener(componentListener)
        this.player = player
        player?.addListener(componentListener)
        updateAll()
    }

    @Deprecated(
        """Replace multi-window time bar display by merging source windows together instead,
        for example using ExoPlayer's {@code ConcatenatingMediaSource2}."""
    )
    fun setShowMultiWindowTimeBar(showMultiWindowTimeBar: Boolean) {
        this.showMultiWindowTimeBar = showMultiWindowTimeBar
        updateTimeline()
    }

    fun setShowPlayButtonIfPlaybackIsSuppressed(showPlayButtonIfSuppressed: Boolean) {
        this.showPlayButtonIfSuppressed = showPlayButtonIfSuppressed
        updatePlayPauseButton()
    }

    fun setExtraAdGroupMarkers(
        extraAdGroupTimesMs: LongArray?, extraPlayedAdGroups: BooleanArray?
    ) {
        if (extraAdGroupTimesMs == null) {
            this.extraAdGroupTimesMs = LongArray(0)
            this.extraPlayedAdGroups = BooleanArray(0)
        } else {
            Preconditions.checkArgument(extraAdGroupTimesMs.size == extraPlayedAdGroups!!.size)
            this.extraAdGroupTimesMs = extraAdGroupTimesMs
            this.extraPlayedAdGroups = extraPlayedAdGroups
        }
        updateTimeline()
    }

    fun addVisibilityListener(listener: VisibilityListener?) {
        visibilityListeners.add(listener!!)
    }

    fun removeVisibilityListener(listener: VisibilityListener?) {
        visibilityListeners.remove(listener)
    }

    fun setProgressUpdateListener(listener: ProgressUpdateListener?) {
        this.progressUpdateListener = listener
    }

    fun setShowRewindButton(showRewindButton: Boolean) {
        this.showRewindButton = showRewindButton
        updateNavigation()
    }

    fun setShowFastForwardButton(showFastForwardButton: Boolean) {
        this.showFastForwardButton = showFastForwardButton
        updateNavigation()
    }

    fun setShowPreviousButton(showPreviousButton: Boolean) {
        this.showPreviousButton = showPreviousButton
        updateNavigation()
    }

    fun setShowNextButton(showNextButton: Boolean) {
        this.showNextButton = showNextButton
        updateNavigation()
    }

    fun getShowTimeoutMs(): Int {
        return showTimeoutMs
    }

    fun setShowTimeoutMs(showTimeoutMs: Int) {
        this.showTimeoutMs = showTimeoutMs
        if (this.isVisible) {
            hideAfterTimeout()
        }
    }

    @RepeatToggleModes
    fun getRepeatToggleModes(): @RepeatToggleModes Int {
        return repeatToggleModes
    }

    fun setRepeatToggleModes(@RepeatToggleModes repeatToggleModes: @RepeatToggleModes Int) {
        this.repeatToggleModes = repeatToggleModes
        if (player != null) {
            @Player.RepeatMode val currentMode: @Player.RepeatMode Int = player!!.repeatMode
            when (repeatToggleModes) {
                RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE
                    if currentMode != Player.REPEAT_MODE_OFF
                    -> {
                    player!!.repeatMode = Player.REPEAT_MODE_OFF
                }

                RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE
                    if currentMode == Player.REPEAT_MODE_ALL
                    -> {
                    player!!.repeatMode = Player.REPEAT_MODE_ONE
                }

                RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL
                    if currentMode == Player.REPEAT_MODE_ONE
                    -> {
                    player!!.repeatMode = Player.REPEAT_MODE_ALL
                }
            }
        }
        updateRepeatModeButton()
    }

    fun getShowShuffleButton(): Boolean {
        return showShuffleButton
    }

    fun setShowShuffleButton(showShuffleButton: Boolean) {
        this.showShuffleButton = showShuffleButton
        updateShuffleButton()
    }

    fun setVrButtonListener(onClickListener: OnClickListener?) {
        if (vrButton != null) {
            vrButton.setOnClickListener(onClickListener)
            updateButton(this.showVrButton, onClickListener != null, vrButton)
        }
    }

    fun setTimeBarMinUpdateInterval(minUpdateIntervalMs: Int) {
        // Do not accept values below 16ms (60fps) and larger than the maximum update interval.
        timeBarMinUpdateIntervalMs =
            constrainValue(minUpdateIntervalMs, 16, MAX_UPDATE_INTERVAL_MS)
    }

    fun show() {
        if (!isVisible) {
            isVisible = true
            for (visibilityListener in visibilityListeners) {
                visibilityListener.onVisibilityChange(visibility)
            }
            updateAll()
            requestPlayPauseFocus()
            requestPlayPauseAccessibilityFocus()
        }
        // Call hideAfterTimeout even if already visible to reset the timeout.
        hideAfterTimeout()
    }

    fun hide() {
        if (isVisible) {
            isVisible = false
            for (visibilityListener in visibilityListeners) {
                visibilityListener.onVisibilityChange(visibility)
            }
            removeCallbacks(updateProgressAction)
            removeCallbacks(hideAction)
            hideAtMs = C.TIME_UNSET
        }
    }

    private fun hideAfterTimeout() {
        removeCallbacks(hideAction)
        if (showTimeoutMs > 0) {
            hideAtMs = SystemClock.uptimeMillis() + showTimeoutMs
            if (isAttachedToWindow) {
                postDelayed(hideAction, showTimeoutMs.toLong())
            }
        } else {
            hideAtMs = C.TIME_UNSET
        }
    }

    private fun updateAll() {
        updatePlayPauseButton()
        updateNavigation()
        updateRepeatModeButton()
        updateShuffleButton()
        updateTimeline()
    }

    private fun updatePlayPauseButton() {
        if (!isVisible || !isAttachedToWindow) {
            return
        }
        var requestPlayPauseFocus = false
        var requestPlayPauseAccessibilityFocus = false
        val shouldShowPlayButton = shouldShowPlayButton(player, showPlayButtonIfSuppressed)
        if (playButton != null) {
            requestPlayPauseFocus = !shouldShowPlayButton && playButton.isFocused
            requestPlayPauseAccessibilityFocus =
                (!shouldShowPlayButton && playButton.isAccessibilityFocused)
            playButton.isVisible = shouldShowPlayButton
        }
        if (pauseButton != null) {
            requestPlayPauseFocus =
                requestPlayPauseFocus or (shouldShowPlayButton && pauseButton.isFocused)
            requestPlayPauseAccessibilityFocus = requestPlayPauseAccessibilityFocus or (
                    shouldShowPlayButton && pauseButton.isAccessibilityFocused)
            pauseButton.isVisible = shouldShowPlayButton
        }
        if (requestPlayPauseFocus) {
            requestPlayPauseFocus()
        }
        if (requestPlayPauseAccessibilityFocus) {
            requestPlayPauseAccessibilityFocus()
        }
    }

    private fun updateNavigation() {
        if (!isVisible || !isAttachedToWindow) {
            return
        }
        val player = player
        var enableSeeking = false
        var enablePrevious = false
        var enableRewind = false
        var enableFastForward = false
        var enableNext = false
        if (player != null) {
            enableSeeking = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            enablePrevious = player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)
            enableRewind = player.isCommandAvailable(Player.COMMAND_SEEK_BACK)
            enableFastForward = player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)
            enableNext = player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)
        }

        updateButton(showPreviousButton, enablePrevious, previousButton)
        updateButton(showRewindButton, enableRewind, rewindButton)
        updateButton(showFastForwardButton, enableFastForward, fastForwardButton)
        updateButton(showNextButton, enableNext, nextButton)
        timeBar?.setEnabled(enableSeeking)
    }

    private fun updateRepeatModeButton() {
        if (!this.isVisible || !isAttachedToWindow || repeatToggleButton == null) {
            return
        }
        if (repeatToggleModes == RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE) {
            updateButton(false, false, repeatToggleButton)
            return
        }
        val player = player
        if (player == null) {
            updateButton(true, false, repeatToggleButton)
            repeatToggleButton.setImageDrawable(repeatOffButtonDrawable)
            repeatToggleButton.setContentDescription(repeatOffButtonContentDescription)
            return
        }

        updateButton(true, true, repeatToggleButton)
        when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> {
                repeatToggleButton.setImageDrawable(repeatOffButtonDrawable)
                repeatToggleButton.setContentDescription(repeatOffButtonContentDescription)
            }

            Player.REPEAT_MODE_ONE -> {
                repeatToggleButton.setImageDrawable(repeatOneButtonDrawable)
                repeatToggleButton.setContentDescription(repeatOneButtonContentDescription)
            }

            Player.REPEAT_MODE_ALL -> {
                repeatToggleButton.setImageDrawable(repeatAllButtonDrawable)
                repeatToggleButton.setContentDescription(repeatAllButtonContentDescription)
            }

            else -> {}
        }
        repeatToggleButton.setVisibility(VISIBLE)
    }

    private fun updateShuffleButton() {
        if (!this.isVisible || !isAttachedToWindow || shuffleButton == null) {
            return
        }
        val player = player
        if (!showShuffleButton) {
            updateButton(false, false, shuffleButton)
        } else if (player == null) {
            updateButton(true, false, shuffleButton)
            shuffleButton.setImageDrawable(shuffleOffButtonDrawable)
            shuffleButton.setContentDescription(shuffleOffContentDescription)
        } else {
            updateButton(true, true, shuffleButton)
            shuffleButton.setImageDrawable(
                if (player.shuffleModeEnabled) shuffleOnButtonDrawable else shuffleOffButtonDrawable
            )
            shuffleButton.setContentDescription(
                if (player.shuffleModeEnabled)
                    shuffleOnContentDescription
                else
                    shuffleOffContentDescription
            )
        }
    }

    private fun updateTimeline() {
        val player = player ?: return
        multiWindowTimeBar =
            showMultiWindowTimeBar && canShowMultiWindowTimeBar(player.currentTimeline, window)
        currentWindowOffset = 0
        var durationUs: Long = 0
        var adGroupCount = 0
        val timeline = player.currentTimeline
        if (!timeline.isEmpty) {
            val currentWindowIndex = player.currentMediaItemIndex
            val firstWindowIndex = if (multiWindowTimeBar) 0 else currentWindowIndex
            val lastWindowIndex =
                if (multiWindowTimeBar) timeline.windowCount - 1 else currentWindowIndex
            for (i in firstWindowIndex..lastWindowIndex) {
                if (i == currentWindowIndex) {
                    currentWindowOffset = usToMs(durationUs)
                }
                timeline.getWindow(i, window)
                if (window.durationUs == C.TIME_UNSET) {
                    Preconditions.checkState(!multiWindowTimeBar)
                    break
                }
                for (j in window.firstPeriodIndex..window.lastPeriodIndex) {
                    timeline.getPeriod(j, period)
                    val removedGroups = period.removedAdGroupCount
                    val totalGroups = period.adGroupCount
                    for (adGroupIndex in removedGroups..<totalGroups) {
                        var adGroupTimeInPeriodUs = period.getAdGroupTimeUs(adGroupIndex)
                        if (adGroupTimeInPeriodUs == C.TIME_END_OF_SOURCE) {
                            if (period.durationUs == C.TIME_UNSET) {
                                // Don't show ad markers for postrolls in periods with unknown duration.
                                continue
                            }
                            adGroupTimeInPeriodUs = period.durationUs
                        }
                        val adGroupTimeInWindowUs =
                            adGroupTimeInPeriodUs + period.positionInWindowUs
                        if (adGroupTimeInWindowUs >= 0) {
                            if (adGroupCount == adGroupTimesMs.size) {
                                val newLength =
                                    if (adGroupTimesMs.isEmpty()) 1 else adGroupTimesMs.size * 2
                                adGroupTimesMs = adGroupTimesMs.copyOf(newLength)
                                playedAdGroups = playedAdGroups.copyOf(newLength)
                            }
                            adGroupTimesMs[adGroupCount] =
                                usToMs(durationUs + adGroupTimeInWindowUs)
                            playedAdGroups[adGroupCount] = period.hasPlayedAdGroup(adGroupIndex)
                            adGroupCount++
                        }
                    }
                }
                durationUs += window.durationUs
            }
        }
        val durationMs = usToMs(durationUs)
        durationView?.text = getStringForTime(formatBuilder, formatter, durationMs)
        if (timeBar != null) {
            timeBar.setDuration(durationMs)
            val extraAdGroupCount = extraAdGroupTimesMs!!.size
            val totalAdGroupCount = adGroupCount + extraAdGroupCount
            if (totalAdGroupCount > adGroupTimesMs.size) {
                adGroupTimesMs = adGroupTimesMs.copyOf(totalAdGroupCount)
                playedAdGroups = playedAdGroups.copyOf(totalAdGroupCount)
            }
            System.arraycopy(
                extraAdGroupTimesMs,
                0,
                adGroupTimesMs,
                adGroupCount,
                extraAdGroupCount
            )
            System.arraycopy(
                extraPlayedAdGroups,
                0,
                playedAdGroups,
                adGroupCount,
                extraAdGroupCount
            )
            timeBar.setAdGroupTimesMs(adGroupTimesMs, playedAdGroups, totalAdGroupCount)
        }
        updateProgress()
    }

    private fun updateProgress() {
        if (!isVisible || !isAttachedToWindow) {
            return
        }
        val player = player
        var position: Long = 0
        var bufferedPosition: Long = 0
        if (player != null) {
            position = currentWindowOffset + player.contentPosition
            bufferedPosition = currentWindowOffset + player.contentBufferedPosition
        }
        val positionChanged = position != currentPosition
        val bufferedPositionChanged = bufferedPosition != currentBufferedPosition
        currentPosition = position
        currentBufferedPosition = bufferedPosition

        // Only update the TextView if the position has changed, else TalkBack will repeatedly read the
        // same position to the user.
        if (positionView != null && !scrubbing && positionChanged) {
            positionView.text = getStringForTime(formatBuilder, formatter, position)
        }
        if (timeBar != null) {
            timeBar.setPosition(position)
            timeBar.setBufferedPosition(bufferedPosition)
        }
        if (progressUpdateListener != null && (positionChanged || bufferedPositionChanged)) {
            progressUpdateListener!!.onProgressUpdate(position, bufferedPosition)
        }

        // Cancel any pending updates and schedule a new one if necessary.
        removeCallbacks(updateProgressAction)
        val playbackState = player?.playbackState ?: Player.STATE_IDLE
        if (player != null && player.isPlaying) {
            var mediaTimeDelayMs =
                timeBar?.preferredUpdateDelay ?: MAX_UPDATE_INTERVAL_MS.toLong()

            // Limit delay to the start of the next full second to ensure position display is smooth.
            val mediaTimeUntilNextFullSecondMs = 1000 - position % 1000
            mediaTimeDelayMs = min(mediaTimeDelayMs, mediaTimeUntilNextFullSecondMs)

            // Calculate the delay until the next update in real time, taking playback speed into account.
            val playbackSpeed = player.playbackParameters!!.speed
            var delayMs =
                if (playbackSpeed > 0) (mediaTimeDelayMs / playbackSpeed).toLong() else MAX_UPDATE_INTERVAL_MS.toLong()

            // Constrain the delay to avoid too frequent / infrequent updates.
            delayMs = constrainValue(
                delayMs,
                timeBarMinUpdateIntervalMs.toLong(),
                MAX_UPDATE_INTERVAL_MS.toLong()
            )
            postDelayed(updateProgressAction, delayMs)
        } else if (playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE) {
            postDelayed(updateProgressAction, MAX_UPDATE_INTERVAL_MS.toLong())
        }
    }

    private fun requestPlayPauseFocus() {
        val shouldShowPlayButton = shouldShowPlayButton(player, showPlayButtonIfSuppressed)
        if (shouldShowPlayButton && playButton != null) {
            playButton.requestFocus()
        } else if (!shouldShowPlayButton && pauseButton != null) {
            pauseButton.requestFocus()
        }
    }

    private fun requestPlayPauseAccessibilityFocus() {
        val shouldShowPlayButton = shouldShowPlayButton(player, showPlayButtonIfSuppressed)
        if (shouldShowPlayButton && playButton != null) {
            playButton.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        } else if (!shouldShowPlayButton && pauseButton != null) {
            pauseButton.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
    }

    private fun updateButton(visible: Boolean, enabled: Boolean, view: View?) {
        if (view == null) {
            return
        }
        view.setEnabled(enabled)
        view.setAlpha(if (enabled) buttonAlphaEnabled else buttonAlphaDisabled)
        view.isVisible = visible
    }

    private fun seekToTimeBarPosition(player: Player, positionMs: Long) {
        var positionMs = positionMs
        var windowIndex: Int
        val timeline = player.currentTimeline
        if (multiWindowTimeBar && !timeline.isEmpty) {
            val windowCount = timeline.windowCount
            windowIndex = 0
            while (true) {
                val windowDurationMs = timeline.getWindow(windowIndex, window).durationMs
                if (positionMs < windowDurationMs) {
                    break
                } else if (windowIndex == windowCount - 1) {
                    // Seeking past the end of the last window should seek to the end of the timeline.
                    positionMs = windowDurationMs
                    break
                }
                positionMs -= windowDurationMs
                windowIndex++
            }
        } else {
            windowIndex = player.currentMediaItemIndex
        }
        seekTo(player, windowIndex, positionMs)
        updateProgress()
    }

    private fun seekTo(player: Player, windowIndex: Int, positionMs: Long) {
        player.seekTo(windowIndex, positionMs)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttachedToWindow = true
        if (hideAtMs != C.TIME_UNSET) {
            val delayMs = hideAtMs - SystemClock.uptimeMillis()
            if (delayMs <= 0) {
                hide()
            } else {
                postDelayed(hideAction, delayMs)
            }
        } else if (this.isVisible) {
            hideAfterTimeout()
        }
        updateAll()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isAttachedToWindow = false
        removeCallbacks(updateProgressAction)
        removeCallbacks(hideAction)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            removeCallbacks(hideAction)
        } else if (ev.getAction() == MotionEvent.ACTION_UP) {
            hideAfterTimeout()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return dispatchMediaKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    fun dispatchMediaKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val player = this.player
        if (player == null || !isHandledMediaKey(keyCode)) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                if (player.playbackState != Player.STATE_ENDED) {
                    player.seekForward()
                }
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                player.seekBack()
            } else if (event.repeatCount == 0) {
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> handlePlayPauseButtonAction(
                        player,
                        showPlayButtonIfSuppressed
                    )

                    KeyEvent.KEYCODE_MEDIA_PLAY -> handlePlayButtonAction(player)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> handlePauseButtonAction(player)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> player.seekToNext()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> player.seekToPrevious()
                    else -> {}
                }
            }
        }
        return true
    }

    private inner class ComponentListener
        : Player.Listener, OnScrubListener, OnClickListener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED
                )
            ) {
                updatePlayPauseButton()
            }
            if (events.containsAny(
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED
                )
            ) {
                updateProgress()
            }
            if (events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) {
                updateRepeatModeButton()
            }
            if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                updateShuffleButton()
            }
            if (events.containsAny(
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED
                )
            ) {
                updateNavigation()
            }
            if (events.containsAny(
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_TIMELINE_CHANGED
                )
            ) {
                updateTimeline()
            }
        }

        override fun onScrubStart(timeBar: TimeBar, position: Long) {
            scrubbing = true
            positionView?.text = getStringForTime(formatBuilder, formatter, position)
        }

        override fun onScrubMove(timeBar: TimeBar, position: Long) {
            positionView?.text = getStringForTime(formatBuilder, formatter, position)
        }

        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
            scrubbing = false
            if (!canceled && player != null) {
                seekToTimeBarPosition(player!!, position)
            }
        }

        override fun onClick(view: View?) {
            val player = this@LegacyPlayerControlView.player ?: return
            if (nextButton === view) {
                player.seekToNext()
            } else if (previousButton === view) {
                player.seekToPrevious()
            } else if (fastForwardButton === view) {
                if (player.playbackState != Player.STATE_ENDED) {
                    player.seekForward()
                }
            } else if (rewindButton === view) {
                player.seekBack()
            } else if (playButton === view) {
                handlePlayButtonAction(player)
            } else if (pauseButton === view) {
                handlePauseButtonAction(player)
            } else if (repeatToggleButton === view) {
                player.repeatMode =
                    RepeatModeUtil.getNextRepeatMode(player.repeatMode, repeatToggleModes)
            } else if (shuffleButton === view) {
                player.shuffleModeEnabled = !player.shuffleModeEnabled
            }
        }
    }

    companion object {
        init {
            registerModule("media3.ui")
        }

        const val DEFAULT_SHOW_TIMEOUT_MS: Int = 5000

        @RepeatToggleModes
        val DEFAULT_REPEAT_TOGGLE_MODES: @RepeatToggleModes Int =
            RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE

        const val DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS: Int = 200
        const val MAX_WINDOWS_FOR_MULTI_WINDOW_TIME_BAR: Int = 100
        private const val MAX_UPDATE_INTERVAL_MS = 1000

        @SuppressLint("InlinedApi")
        private fun isHandledMediaKey(keyCode: Int): Boolean {
            return keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }

        private fun canShowMultiWindowTimeBar(
            timeline: Timeline,
            window: Timeline.Window
        ): Boolean {
            if (timeline.windowCount > MAX_WINDOWS_FOR_MULTI_WINDOW_TIME_BAR) {
                return false
            }
            val windowCount = timeline.windowCount
            for (i in 0..<windowCount) {
                if (timeline.getWindow(i, window).durationUs == C.TIME_UNSET) {
                    return false
                }
            }
            return true
        }

        @RepeatToggleModes
        private fun getRepeatToggleModes(
            a: TypedArray, @RepeatToggleModes defaultValue: @RepeatToggleModes Int
        ): @RepeatToggleModes Int {
            return a.getInt(R.styleable.LegacyPlayerControlView_repeat_toggle_modes, defaultValue)
        }
    }
}
