package androidx.media3.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.C
import androidx.media3.common.MediaLibraryInfo.registerModule
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.ViewProvider
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.RepeatModeUtil
import androidx.media3.common.util.RepeatModeUtil.RepeatToggleModes

import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.createHandlerForCurrentLooper
import androidx.media3.common.util.Util.getDrawable
import androidx.media3.common.util.Util.getStringForTime
import androidx.media3.common.util.Util.handlePauseButtonAction
import androidx.media3.common.util.Util.handlePlayButtonAction
import androidx.media3.common.util.Util.handlePlayPauseButtonAction
import androidx.media3.common.util.Util.msToUs
import androidx.media3.common.util.Util.shouldEnablePlayPauseButton
import androidx.media3.common.util.Util.shouldShowPlayButton
import androidx.media3.common.util.Util.usToMs
import androidx.media3.ui.TimeBar.OnScrubListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.plr.R
import com.google.common.base.Preconditions
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Formatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.min

class PlayerControlView  constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    playbackAttrs: AttributeSet? = attrs
) : FrameLayout(context, attrs, defStyleAttr) {
    @Deprecated(
        """Register a {@link PlayerView.ControllerVisibilityListener} via {@link
   *     PlayerView#setControllerVisibilityListener(PlayerView.ControllerVisibilityListener)}
        instead. Using {@link PlayerControlView} as a standalone class without {@link PlayerView}
        is deprecated."""
    )
    interface VisibilityListener {
        fun onVisibilityChange(visibility: Int)
    }

    interface ProgressUpdateListener {
        fun onProgressUpdate(position: Long, bufferedPosition: Long)
    }

    @Deprecated(
        """Register a {@link PlayerView.FullscreenButtonClickListener} via {@link
   *     PlayerView#setFullscreenButtonClickListener(PlayerView.FullscreenButtonClickListener)}
        instead. Using {@link PlayerControlView} as a standalone class without {@link PlayerView}
        is deprecated."""
    )
    interface OnFullScreenModeChangedListener {
        fun onFullScreenModeChanged(isFullScreen: Boolean)
    }

    private val controlViewLayoutManager: PlayerControlViewLayoutManager
    private val resources: Resources
    private val handler: Handler
    private val componentListener = ComponentListener()
    private val exoplayerClazz: Class<*>?
    private val setScrubbingModeEnabledMethod: Method?
    private val isScrubbingModeEnabledMethod: Method?
    private val compositionPlayerClazz: Class<*>?
    private val compositionPlayerSetScrubbingModeEnabledMethod: Method?
    private val compositionPlayerIsScrubbingModeEnabledMethod: Method?
    private val visibilityListeners = CopyOnWriteArrayList<VisibilityListener>()
    private val settingsView: RecyclerView
    private val settingsAdapter: SettingsAdapter
    private val playbackSpeedAdapter: PlaybackSpeedAdapter
    private val textTrackSelectionAdapter: TextTrackSelectionAdapter
    private val audioTrackSelectionAdapter: AudioTrackSelectionAdapter
    private val trackNameProvider: TrackNameProvider
    private val settingsWindow: PopupWindow
    private val settingsWindowMargin: Int
    private val previousButton: ImageView?
    private val nextButton: ImageView?
    private val playPauseButton: ImageView?
    private val fastForwardButton: View?
    private val rewindButton: View?
    private val fastForwardButtonTextView: TextView?
    private val rewindButtonTextView: TextView?
    private val repeatToggleButton: ImageView?
    private val shuffleButton: ImageView?
    private val vrButton: ImageView?
    private val subtitleButton: ImageView?
    private val fullscreenButton: ImageView?
    private val minimalFullscreenButton: ImageView?
    private val settingsButton: View?
    private val playbackSpeedButton: View?
    private val audioTrackButton: View?
    private val durationView: TextView?
    private val positionView: TextView?
    private val timeBar: TimeBar?
    private val formatBuilder = StringBuilder()
    private val formatter = Formatter(formatBuilder, Locale.getDefault())
    private val period = Timeline.Period()
    private val window = Timeline.Window()
    private val updateProgressAction = Runnable { updateProgress() }
    private val playButtonDrawable: Drawable?
    private val pauseButtonDrawable: Drawable?
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
    private val subtitleOnButtonDrawable: Drawable?
    private val subtitleOffButtonDrawable: Drawable?
    private val subtitleOnContentDescription: String?
    private val subtitleOffContentDescription: String?
    private val fullscreenExitDrawable: Drawable?
    private val fullscreenEnterDrawable: Drawable?
    private val fullscreenExitContentDescription: String?
    private val fullscreenEnterContentDescription: String?
    private var player: Player? = null
    private var progressUpdateListener: ProgressUpdateListener? = null
    private var onFullScreenModeChangedListener: OnFullScreenModeChangedListener? = null
    private var isFullscreen = false
    private var isAttachedToWindow = false
    private var showMultiWindowTimeBar = false
    private var showPlayButtonIfSuppressed = true
    private var multiWindowTimeBar = false
    private var scrubbing = false
    private var showTimeoutMs = DEFAULT_SHOW_TIMEOUT_MS
    private var timeBarScrubbingEnabled = false
    private var timeBarMinUpdateIntervalMs: Int = DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS
    private var repeatToggleModes: @RepeatToggleModes Int = DEFAULT_REPEAT_TOGGLE_MODES
    private var adGroupTimesMs = LongArray(0)
    private var playedAdGroups = BooleanArray(0)
    private var extraAdGroupTimesMs: LongArray? = LongArray(0)
    private var extraPlayedAdGroups: BooleanArray? = BooleanArray(0)
    private var currentWindowOffset: Long = 0
    private var needToHideBars: Boolean

    init {
        var controllerLayoutId: Int = R.layout.exo_player_control_view
        var playDrawableResId: Int = R.drawable.exo_styled_controls_play
        var pauseDrawableResId: Int = R.drawable.exo_styled_controls_pause
        var nextDrawableResId: Int = R.drawable.exo_styled_controls_next
        var fastForwardDrawableResId: Int = R.drawable.exo_styled_controls_simple_fastforward
        var previousDrawableResId: Int = R.drawable.exo_styled_controls_previous
        var rewindDrawableResId: Int = R.drawable.exo_styled_controls_simple_rewind
        var fullscreenExitDrawableResId: Int = R.drawable.exo_styled_controls_fullscreen_exit
        var fullscreenEnterDrawableResId: Int = R.drawable.exo_styled_controls_fullscreen_enter
        var repeatOffDrawableResId: Int = R.drawable.exo_styled_controls_repeat_off
        var repeatOneDrawableResId: Int = R.drawable.exo_styled_controls_repeat_one
        var repeatAllDrawableResId: Int = R.drawable.exo_styled_controls_repeat_all
        var shuffleOnDrawableResId: Int = R.drawable.exo_styled_controls_shuffle_on
        var shuffleOffDrawableResId: Int = R.drawable.exo_styled_controls_shuffle_off
        var subtitleOnDrawableResId: Int = R.drawable.exo_styled_controls_subtitle_on
        var subtitleOffDrawableResId: Int = R.drawable.exo_styled_controls_subtitle_off
        var vrDrawableResId: Int = R.drawable.exo_styled_controls_vr

        var showRewindButton = true
        var showFastForwardButton = true
        var showPreviousButton = true
        var showNextButton = true
        var showShuffleButton = false
        var showSubtitleButton = false
        var animationEnabled = true
        var showVrButton = false

        if (playbackAttrs != null) {
            val a = context.theme
                .obtainStyledAttributes(
                    playbackAttrs,
                    R.styleable.PlayerControlView,
                    defStyleAttr,  
                    0
                )
            try {
                controllerLayoutId = a.getResourceId(
                    R.styleable.PlayerControlView_controller_layout_id,
                    controllerLayoutId
                )
                playDrawableResId =
                    a.getResourceId(R.styleable.PlayerControlView_play_icon, playDrawableResId)
                pauseDrawableResId =
                    a.getResourceId(R.styleable.PlayerControlView_pause_icon, pauseDrawableResId)
                nextDrawableResId =
                    a.getResourceId(R.styleable.PlayerControlView_next_icon, nextDrawableResId)
                fastForwardDrawableResId = a.getResourceId(
                    R.styleable.PlayerControlView_fastforward_icon, fastForwardDrawableResId
                )
                previousDrawableResId =
                    a.getResourceId(
                        R.styleable.PlayerControlView_previous_icon,
                        previousDrawableResId
                    )
                rewindDrawableResId =
                    a.getResourceId(R.styleable.PlayerControlView_rewind_icon, rewindDrawableResId)
                fullscreenExitDrawableResId =
                    a.getResourceId(
                        R.styleable.PlayerControlView_fullscreen_exit_icon,
                        fullscreenExitDrawableResId
                    )
                fullscreenEnterDrawableResId =
                    a.getResourceId(
                        R.styleable.PlayerControlView_fullscreen_enter_icon,
                        fullscreenEnterDrawableResId
                    )
                repeatOffDrawableResId =
                    a.getResourceId(
                        R.styleable.PlayerControlView_repeat_off_icon,
                        repeatOffDrawableResId
                    )
                repeatOneDrawableResId =
                    a.getResourceId(
                        R.styleable.PlayerControlView_repeat_one_icon,
                        repeatOneDrawableResId
                    )
                repeatAllDrawableResId =
                    a.getResourceId(
                        R.styleable.PlayerControlView_repeat_all_icon,
                        repeatAllDrawableResId
                    )
                shuffleOnDrawableResId =
                    a.getResourceId(
                        R.styleable.PlayerControlView_shuffle_on_icon,
                        shuffleOnDrawableResId
                    )
                shuffleOffDrawableResId =
                    a.getResourceId(
                        R.styleable.PlayerControlView_shuffle_off_icon, shuffleOffDrawableResId
                    )
                subtitleOnDrawableResId =
                    a.getResourceId(
                        R.styleable.PlayerControlView_subtitle_on_icon, subtitleOnDrawableResId
                    )
                subtitleOffDrawableResId =
                    a.getResourceId(
                        R.styleable.PlayerControlView_subtitle_off_icon, subtitleOffDrawableResId
                    )
                vrDrawableResId =
                    a.getResourceId(R.styleable.PlayerControlView_vr_icon, vrDrawableResId)
                showTimeoutMs = a.getInt(R.styleable.PlayerControlView_show_timeout, showTimeoutMs)
                repeatToggleModes = getRepeatToggleModes(a, repeatToggleModes)
                showRewindButton =
                    a.getBoolean(R.styleable.PlayerControlView_show_rewind_button, showRewindButton)
                showFastForwardButton =
                    a.getBoolean(
                        R.styleable.PlayerControlView_show_fastforward_button, showFastForwardButton
                    )
                showPreviousButton =
                    a.getBoolean(
                        R.styleable.PlayerControlView_show_previous_button,
                        showPreviousButton
                    )
                showNextButton =
                    a.getBoolean(R.styleable.PlayerControlView_show_next_button, showNextButton)
                showShuffleButton =
                    a.getBoolean(
                        R.styleable.PlayerControlView_show_shuffle_button,
                        showShuffleButton
                    )
                showSubtitleButton =
                    a.getBoolean(
                        R.styleable.PlayerControlView_show_subtitle_button,
                        showSubtitleButton
                    )
                showVrButton =
                    a.getBoolean(R.styleable.PlayerControlView_show_vr_button, showVrButton)
                timeBarScrubbingEnabled =
                    a.getBoolean(R.styleable.PlayerControlView_time_bar_scrubbing_enabled, false)
                setTimeBarMinUpdateInterval(
                    a.getInt(
                        R.styleable.PlayerControlView_time_bar_min_update_interval,
                        timeBarMinUpdateIntervalMs
                    )
                )
                animationEnabled =
                    a.getBoolean(R.styleable.PlayerControlView_animation_enabled, animationEnabled)
            } finally {
                a.recycle()
            }
        }
        LayoutInflater.from(context).inflate(controllerLayoutId,  this)
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS)
        var exoplayerClazz: Class<*>? = null
        var setScrubbingModeEnabledMethod: Method? = null
        var isScrubbingModeEnabledMethod: Method? = null
        try {
            exoplayerClazz = Class.forName("androidx.media3.exoplayer.ExoPlayer")
            setScrubbingModeEnabledMethod =
                exoplayerClazz.getMethod(
                    "setScrubbingModeEnabled",
                    Boolean::class.javaPrimitiveType
                )
            isScrubbingModeEnabledMethod = exoplayerClazz.getMethod("isScrubbingModeEnabled")
        } catch (e: ClassNotFoundException) {
            // Expected if ExoPlayer module not available.
        } catch (e: NoSuchMethodException) {
        }
        this.exoplayerClazz = exoplayerClazz
        this.setScrubbingModeEnabledMethod = setScrubbingModeEnabledMethod
        this.isScrubbingModeEnabledMethod = isScrubbingModeEnabledMethod

        var compositionPlayerClazz: Class<*>? = null
        var compositionPlayerSetScrubbingModeEnabledMethod: Method? = null
        var compositionPlayerIsScrubbingModeEnabledMethod: Method? = null
        try {
            compositionPlayerClazz = Class.forName("androidx.media3.transformer.CompositionPlayer")
            compositionPlayerSetScrubbingModeEnabledMethod =
                compositionPlayerClazz.getMethod(
                    "setScrubbingModeEnabled",
                    Boolean::class.javaPrimitiveType
                )
            compositionPlayerIsScrubbingModeEnabledMethod =
                compositionPlayerClazz.getMethod("isScrubbingModeEnabled")
        } catch (e: ClassNotFoundException) {
            // Expected if transformer module not available.
        } catch (e: NoSuchMethodException) {
        }
        this.compositionPlayerClazz = compositionPlayerClazz
        this.compositionPlayerSetScrubbingModeEnabledMethod =
            compositionPlayerSetScrubbingModeEnabledMethod
        this.compositionPlayerIsScrubbingModeEnabledMethod =
            compositionPlayerIsScrubbingModeEnabledMethod

        durationView = findViewById(R.id.exo_duration)
        positionView = findViewById(R.id.exo_position)

        subtitleButton = findViewById(R.id.exo_subtitle)
        subtitleButton?.setOnClickListener(componentListener)

        fullscreenButton = findViewById(R.id.exo_fullscreen)
        initializeFullscreenButton(fullscreenButton) { onFullscreenButtonClicked(it) }
        minimalFullscreenButton = findViewById(R.id.exo_minimal_fullscreen)
        initializeFullscreenButton(minimalFullscreenButton) { onFullscreenButtonClicked(it) }

        settingsButton = findViewById(R.id.exo_settings)
        settingsButton?.setOnClickListener(componentListener)

        playbackSpeedButton = findViewById(R.id.exo_playback_speed)
        playbackSpeedButton?.setOnClickListener(componentListener)

        audioTrackButton = findViewById(R.id.exo_audio_track)
        audioTrackButton?.setOnClickListener(componentListener)

        val customTimeBar: TimeBar? = findViewById(R.id.exo_progress)
        val timeBarPlaceholder = findViewById<View?>(R.id.exo_progress_placeholder)
        if (customTimeBar != null) {
            timeBar = customTimeBar
        } else if (timeBarPlaceholder != null) {
            // Propagate playbackAttrs as timebarAttrs so that DefaultTimeBar's custom attributes are
            // transferred, but standard attributes (e.g. background) are not.
            val defaultTimeBar =
                DefaultTimeBar(context, null, 0, playbackAttrs, R.style.ExoStyledControls_TimeBar)
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
        timeBar?.addListener(componentListener)

        handler = createHandlerForCurrentLooper()
        resources = context.resources
        playPauseButton = findViewById(R.id.exo_play_pause)
        playPauseButton?.setOnClickListener(componentListener)
        previousButton = findViewById(R.id.exo_prev)
        if (previousButton != null) {
            previousButton.setImageDrawable(getDrawable(context, resources, previousDrawableResId))
            previousButton.setOnClickListener(componentListener)
        }
        nextButton = findViewById(R.id.exo_next)
        if (nextButton != null) {
            nextButton.setImageDrawable(getDrawable(context, resources, nextDrawableResId))
            nextButton.setOnClickListener(componentListener)
        }
        val typeface = ResourcesCompat.getFont(context, R.font.roboto_medium_numbers)
        val rewButton = findViewById<ImageView?>(R.id.exo_rew)
        val rewButtonWithAmount = findViewById<TextView?>(R.id.exo_rew_with_amount)
        if (rewButton != null) {
            // For a simple rewind button without seek-back increment value
            rewButton.setImageDrawable(getDrawable(context, resources, rewindDrawableResId))
            rewindButton = rewButton
            rewindButtonTextView = null
        } else if (rewButtonWithAmount != null) {
            // For a circular rewind button with the amount in the middle
            rewButtonWithAmount.setTypeface(typeface)
            rewindButtonTextView = rewButtonWithAmount
            rewindButton = rewindButtonTextView
        } else {
            rewindButtonTextView = null
            rewindButton = null
        }
        rewindButton?.setOnClickListener(componentListener)
        val ffwdButton = findViewById<ImageView?>(R.id.exo_ffwd)
        val ffwdButtonWithAmount = findViewById<TextView?>(R.id.exo_ffwd_with_amount)
        if (ffwdButton != null) {
            // For a simple fast forward button without seek-forward increment value
            ffwdButton.setImageDrawable(getDrawable(context, resources, fastForwardDrawableResId))
            fastForwardButton = ffwdButton
            fastForwardButtonTextView = null
        } else if (ffwdButtonWithAmount != null) {
            // For a circular fastforward button with the amount in the middle
            ffwdButtonWithAmount.setTypeface(typeface)
            fastForwardButtonTextView = ffwdButtonWithAmount
            fastForwardButton = fastForwardButtonTextView
        } else {
            fastForwardButtonTextView = null
            fastForwardButton = null
        }
        fastForwardButton?.setOnClickListener(componentListener)
        repeatToggleButton = findViewById(R.id.exo_repeat_toggle)
        repeatToggleButton?.setOnClickListener(componentListener)
        shuffleButton = findViewById(R.id.exo_shuffle)
        shuffleButton?.setOnClickListener(componentListener)

        buttonAlphaEnabled =
            resources.getInteger(R.integer.exo_media_button_opacity_percentage_enabled)
                .toFloat() / 100
        buttonAlphaDisabled =
            resources.getInteger(R.integer.exo_media_button_opacity_percentage_disabled)
                .toFloat() / 100

        vrButton = findViewById(R.id.exo_vr)
        if (vrButton != null) {
            vrButton.setImageDrawable(getDrawable(context, resources, vrDrawableResId))
            updateButton( false, vrButton)
        }

        controlViewLayoutManager = PlayerControlViewLayoutManager(this)
        controlViewLayoutManager.isAnimationEnabled = (animationEnabled)

        val settingTexts = arrayOfNulls<String>(2)
        val settingIcons = arrayOfNulls<Drawable>(2)
        settingTexts[SETTINGS_PLAYBACK_SPEED_POSITION] =
            resources.getString(R.string.exo_controls_playback_speed)
        settingIcons[SETTINGS_PLAYBACK_SPEED_POSITION] =
            getDrawable(context, resources, R.drawable.exo_styled_controls_speed)
        settingTexts[SETTINGS_AUDIO_TRACK_SELECTION_POSITION] =
            resources.getString(R.string.exo_track_selection_title_audio)
        settingIcons[SETTINGS_AUDIO_TRACK_SELECTION_POSITION] =
            getDrawable(context, resources, R.drawable.exo_styled_controls_audiotrack)
        settingsAdapter = SettingsAdapter(settingTexts, settingIcons)
        settingsWindowMargin = resources.getDimensionPixelSize(R.dimen.exo_settings_offset)
        settingsView =
            LayoutInflater.from(context)
                .inflate(R.layout.exo_styled_settings_list,  null) as RecyclerView
        settingsView.setAdapter(settingsAdapter)
        settingsView.setLayoutManager(LinearLayoutManager(getContext()))
        settingsWindow =
            PopupWindow(settingsView, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, true)
        settingsWindow.setOnDismissListener(componentListener)
        needToHideBars = true

        trackNameProvider = DefaultTrackNameProvider(getResources())
        subtitleOnButtonDrawable = getDrawable(context, resources, subtitleOnDrawableResId)
        subtitleOffButtonDrawable = getDrawable(context, resources, subtitleOffDrawableResId)
        subtitleOnContentDescription =
            resources.getString(R.string.exo_controls_cc_enabled_description)
        subtitleOffContentDescription =
            resources.getString(R.string.exo_controls_cc_disabled_description)
        textTrackSelectionAdapter = TextTrackSelectionAdapter()
        audioTrackSelectionAdapter = AudioTrackSelectionAdapter()
        playbackSpeedAdapter =
            PlaybackSpeedAdapter(
                resources.getStringArray(R.array.exo_controls_playback_speeds), PLAYBACK_SPEEDS
            )

        playButtonDrawable = getDrawable(context, resources, playDrawableResId)
        pauseButtonDrawable = getDrawable(context, resources, pauseDrawableResId)
        fullscreenExitDrawable = getDrawable(context, resources, fullscreenExitDrawableResId)
        fullscreenEnterDrawable = getDrawable(context, resources, fullscreenEnterDrawableResId)
        repeatOffButtonDrawable = getDrawable(context, resources, repeatOffDrawableResId)
        repeatOneButtonDrawable = getDrawable(context, resources, repeatOneDrawableResId)
        repeatAllButtonDrawable = getDrawable(context, resources, repeatAllDrawableResId)
        shuffleOnButtonDrawable = getDrawable(context, resources, shuffleOnDrawableResId)
        shuffleOffButtonDrawable = getDrawable(context, resources, shuffleOffDrawableResId)
        fullscreenExitContentDescription =
            resources.getString(R.string.exo_controls_fullscreen_exit_description)
        fullscreenEnterContentDescription =
            resources.getString(R.string.exo_controls_fullscreen_enter_description)
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

        // TODO(insun) : Make showing bottomBar configurable. (ex. show_bottom_bar attribute).
        val bottomBar = findViewById<ViewGroup?>(R.id.exo_bottom_bar)
        controlViewLayoutManager.setShowButton(bottomBar, true)
        controlViewLayoutManager.setShowButton(fastForwardButton, showFastForwardButton)
        controlViewLayoutManager.setShowButton(rewindButton, showRewindButton)
        controlViewLayoutManager.setShowButton(previousButton, showPreviousButton)
        controlViewLayoutManager.setShowButton(nextButton, showNextButton)
        controlViewLayoutManager.setShowButton(shuffleButton, showShuffleButton)
        controlViewLayoutManager.setShowButton(subtitleButton, showSubtitleButton)
        controlViewLayoutManager.setShowButton(vrButton, showVrButton)
        controlViewLayoutManager.setShowButton(
            repeatToggleButton, repeatToggleModes != RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE
        )
        addOnLayoutChangeListener { v: View?, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int ->
            this.onLayoutChange(
                v,
                left,
                top,
                right,
                bottom,
                oldLeft,
                oldTop,
                oldRight,
                oldBottom
            )
        }
    }

    override fun hasOverlappingRendering(): Boolean {
        return false
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
        if (this.player != null) {
            this.player!!.removeListener(componentListener)
        }
        this.player = player
        if (player != null) {
            player.addListener(componentListener)
        }
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

    @Deprecated(
        """Register a {@link PlayerView.ControllerVisibilityListener} via {@link
   *     PlayerView#setControllerVisibilityListener(PlayerView.ControllerVisibilityListener)}
        instead. Using {@link PlayerControlView} as a standalone class without {@link PlayerView}
        is deprecated."""
    )
    fun addVisibilityListener(listener: VisibilityListener?) {
        visibilityListeners.add(listener!!)
    }

    @Deprecated(
        """Register a {@link PlayerView.ControllerVisibilityListener} via {@link
   *     PlayerView#setControllerVisibilityListener(PlayerView.ControllerVisibilityListener)}
        instead. Using {@link PlayerControlView} as a standalone class without {@link PlayerView}
        is deprecated."""
    )

    fun removeVisibilityListener(listener: VisibilityListener?) {
        visibilityListeners.remove(listener)
    }

    fun setProgressUpdateListener(listener: ProgressUpdateListener?) {
        this.progressUpdateListener = listener
    }

    fun setShowRewindButton(showRewindButton: Boolean) {
        controlViewLayoutManager.setShowButton(rewindButton, showRewindButton)
        updateNavigation()
    }

    fun setShowFastForwardButton(showFastForwardButton: Boolean) {
        controlViewLayoutManager.setShowButton(fastForwardButton, showFastForwardButton)
        updateNavigation()
    }

    fun setShowPreviousButton(showPreviousButton: Boolean) {
        controlViewLayoutManager.setShowButton(previousButton, showPreviousButton)
        updateNavigation()
    }

    fun setShowNextButton(showNextButton: Boolean) {
        controlViewLayoutManager.setShowButton(nextButton, showNextButton)
        updateNavigation()
    }

    fun getShowTimeoutMs(): Int {
        return showTimeoutMs
    }

    fun setShowTimeoutMs(showTimeoutMs: Int) {
        this.showTimeoutMs = showTimeoutMs
        if (this.isFullyVisible) {
            controlViewLayoutManager.resetHideCallbacks()
        }
    }

    @RepeatToggleModes
    fun getRepeatToggleModes(): @RepeatToggleModes Int {
        return repeatToggleModes
    }

    fun setRepeatToggleModes(@RepeatToggleModes repeatToggleModes: @RepeatToggleModes Int) {
        this.repeatToggleModes = repeatToggleModes
        if (player != null && player!!.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) {
            @Player.RepeatMode val currentMode: @Player.RepeatMode Int = player!!.repeatMode
            if (repeatToggleModes == RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE
                && currentMode != Player.REPEAT_MODE_OFF
            ) {
                player!!.repeatMode = Player.REPEAT_MODE_OFF
            } else if (repeatToggleModes == RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE
                && currentMode == Player.REPEAT_MODE_ALL
            ) {
                player!!.repeatMode = Player.REPEAT_MODE_ONE
            } else if (repeatToggleModes == RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL
                && currentMode == Player.REPEAT_MODE_ONE
            ) {
                player!!.repeatMode = Player.REPEAT_MODE_ALL
            }
        }
        controlViewLayoutManager.setShowButton(
            repeatToggleButton, repeatToggleModes != RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE
        )
        updateRepeatModeButton()
    }

    var showShuffleButton: Boolean
        get() = controlViewLayoutManager.getShowButton(shuffleButton)
        set(showShuffleButton) {
            controlViewLayoutManager.setShowButton(shuffleButton, showShuffleButton)
            updateShuffleButton()
        }

    var showSubtitleButton: Boolean
        get() = controlViewLayoutManager.getShowButton(subtitleButton)
        set(showSubtitleButton) {
            controlViewLayoutManager.setShowButton(subtitleButton, showSubtitleButton)
        }

    fun setMediaRouteButtonViewProvider(mediaRouteButtonViewProvider: ViewProvider?) {
        val mediaRouteButtonPlaceholder =
            findViewById<View>(R.id.exo_media_route_button_placeholder)
        checkNotNull(mediaRouteButtonPlaceholder) { "The media route button placeholder is missing." }
        if (mediaRouteButtonViewProvider == null) {
            mediaRouteButtonPlaceholder.setVisibility(GONE)
            return
        }

        val parent = mediaRouteButtonPlaceholder.getParent() as ViewGroup
        checkNotNull(parent) { "The media route button placeholder has no parent view." }
        Futures.addCallback<View?>(
            mediaRouteButtonViewProvider.getView(parent),
            object : FutureCallback<View> {
                override fun onSuccess(mediaRouteButtonView: View) {
                    val layoutParams = mediaRouteButtonPlaceholder.getLayoutParams()
                    checkNotNull(layoutParams) { "The media route button placeholder missing layout params." }
                    mediaRouteButtonView.setId(R.id.exo_media_route_button_placeholder)
                    mediaRouteButtonView.setLayoutParams(layoutParams)
                    val mediaRouteButtonIndex = parent.indexOfChild(mediaRouteButtonPlaceholder)
                    parent.removeView(mediaRouteButtonPlaceholder)
                    parent.addView(mediaRouteButtonView, mediaRouteButtonIndex)
                    mediaRouteButtonView.setVisibility(VISIBLE)
                    controlViewLayoutManager.setShowButton(mediaRouteButtonView, true)
                }

                override fun onFailure(e: Throwable) {
                    mediaRouteButtonPlaceholder.setVisibility(GONE)
                }
            },
            Executor { r: Runnable? -> handler.post(r!!) })
    }

    var showVrButton: Boolean
        get() = controlViewLayoutManager.getShowButton(vrButton)
        set(showVrButton) {
            controlViewLayoutManager.setShowButton(vrButton, showVrButton)
        }

    fun setVrButtonListener(onClickListener: OnClickListener?) {
        if (vrButton != null) {
            vrButton.setOnClickListener(onClickListener)
            updateButton(onClickListener != null, vrButton)
        }
    }

    var isAnimationEnabled: Boolean
        get() = controlViewLayoutManager.isAnimationEnabled
        set(animationEnabled) {
            controlViewLayoutManager.isAnimationEnabled = (animationEnabled)
        }

    fun setTimeBarScrubbingEnabled(timeBarScrubbingEnabled: Boolean) {
        this.timeBarScrubbingEnabled = timeBarScrubbingEnabled
    }

    fun setTimeBarMinUpdateInterval(minUpdateIntervalMs: Int) {
        // Do not accept values below 16ms (60fps) and larger than the maximum update interval.
        timeBarMinUpdateIntervalMs =
            constrainValue(minUpdateIntervalMs, 16, MAX_UPDATE_INTERVAL_MS)
    }

    @Suppress("deprecation")
    @Deprecated(
        """Register a {@link PlayerView.FullscreenButtonClickListener} via {@link
   *     PlayerView#setFullscreenButtonClickListener(PlayerView.FullscreenButtonClickListener)}
        instead. Using {@link PlayerControlView} as a standalone class without {@link PlayerView}
        is deprecated."""
    )
    fun setOnFullScreenModeChangedListener(
        listener: OnFullScreenModeChangedListener?
    ) {
        onFullScreenModeChangedListener = listener
        updateFullscreenButtonVisibility(fullscreenButton, listener != null)
        updateFullscreenButtonVisibility(minimalFullscreenButton, listener != null)
    }

    fun show() {
        controlViewLayoutManager.show()
    }

    fun hide() {
        controlViewLayoutManager.hide()
    }

    fun hideImmediately() {
        controlViewLayoutManager.hideImmediately()
    }

    val isFullyVisible: Boolean
        get() = controlViewLayoutManager.isFullyVisible

    val isVisible: Boolean
        get() = getVisibility() == VISIBLE

    @Suppress("deprecation")
    fun notifyOnVisibilityChange() {
        for (visibilityListener in visibilityListeners) {
            visibilityListener.onVisibilityChange(getVisibility())
        }
    }

    fun updateAll() {
        updatePlayPauseButton()
        updateNavigation()
        updateRepeatModeButton()
        updateShuffleButton()
        updateTrackLists()
        updatePlaybackSpeedList()
        updateTimeline()
    }

    private fun updatePlayPauseButton() {
        if (!this.isVisible || !isAttachedToWindow) {
            return
        }
        if (playPauseButton != null) {
            val shouldShowPlayButton = shouldShowPlayButton(player, showPlayButtonIfSuppressed)
            val drawable = if (shouldShowPlayButton) playButtonDrawable else pauseButtonDrawable
            @StringRes val stringRes: Int =
                if (shouldShowPlayButton)
                    R.string.exo_controls_play_description
                else
                    R.string.exo_controls_pause_description
            playPauseButton.setImageDrawable(drawable)
            playPauseButton.setContentDescription(resources.getString(stringRes))

            val enablePlayPause = shouldEnablePlayPauseButton(player)
            updateButton(enablePlayPause, playPauseButton)
        }
    }

    private fun updateNavigation() {
        if (!this.isVisible || !isAttachedToWindow) {
            return
        }

        val player = this.player
        var enableSeeking = false
        var enablePrevious = false
        var enableRewind = false
        var enableFastForward = false
        var enableNext = false
        if (player != null) {
            enableSeeking =
                if (showMultiWindowTimeBar && canShowMultiWindowTimeBar(player, window))
                    player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                else
                    player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            enablePrevious = player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)
            enableRewind = player.isCommandAvailable(Player.COMMAND_SEEK_BACK)
            enableFastForward = player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)
            enableNext = player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)
        }

        if (enableRewind) {
            updateRewindButton()
        }
        if (enableFastForward) {
            updateFastForwardButton()
        }

        updateButton(enablePrevious, previousButton)
        updateButton(enableRewind, rewindButton)
        updateButton(enableFastForward, fastForwardButton)
        updateButton(enableNext, nextButton)
        if (timeBar != null) {
            timeBar.setEnabled(enableSeeking)
        }
    }

    private fun updateRewindButton() {
        val rewindMs =
            if (player != null) player!!.seekBackIncrement else C.DEFAULT_SEEK_BACK_INCREMENT_MS
        val rewindSec = (rewindMs / 1000).toInt()
        if (rewindButtonTextView != null) {
            rewindButtonTextView.setText(rewindSec.toString())
        }
        if (rewindButton != null) {
            rewindButton.setContentDescription(
                resources.getQuantityString(
                    R.plurals.exo_controls_rewind_by_amount_description, rewindSec, rewindSec
                )
            )
        }
    }

    private fun updateFastForwardButton() {
        val fastForwardMs =
            if (player != null) player!!.seekForwardIncrement else C.DEFAULT_SEEK_FORWARD_INCREMENT_MS
        val fastForwardSec = (fastForwardMs / 1000).toInt()
        if (fastForwardButtonTextView != null) {
            fastForwardButtonTextView.setText(fastForwardSec.toString())
        }
        if (fastForwardButton != null) {
            fastForwardButton.setContentDescription(
                resources.getQuantityString(
                    R.plurals.exo_controls_fastforward_by_amount_description,
                    fastForwardSec,
                    fastForwardSec
                )
            )
        }
    }

    private fun updateRepeatModeButton() {
        if (!this.isVisible || !isAttachedToWindow || repeatToggleButton == null) {
            return
        }

        if (repeatToggleModes == RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE) {
            updateButton( false, repeatToggleButton)
            return
        }

        val player = this.player
        if (player == null || !player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) {
            updateButton( false, repeatToggleButton)
            repeatToggleButton.setImageDrawable(repeatOffButtonDrawable)
            repeatToggleButton.setContentDescription(repeatOffButtonContentDescription)
            return
        }

        updateButton( true, repeatToggleButton)
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
    }

    private fun updateShuffleButton() {
        if (!this.isVisible || !isAttachedToWindow || shuffleButton == null) {
            return
        }

        val player = this.player
        if (!controlViewLayoutManager.getShowButton(shuffleButton)) {
            updateButton( false, shuffleButton)
        } else if (player == null || !player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)) {
            updateButton( false, shuffleButton)
            shuffleButton.setImageDrawable(shuffleOffButtonDrawable)
            shuffleButton.setContentDescription(shuffleOffContentDescription)
        } else {
            updateButton( true, shuffleButton)
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

    private fun updateTrackLists() {
        initTrackSelectionAdapter()
        updateButton(textTrackSelectionAdapter.getItemCount() > 0, subtitleButton)
        updateSettingsButton()
    }

    private fun initTrackSelectionAdapter() {
        textTrackSelectionAdapter.clear()
        audioTrackSelectionAdapter.clear()
        if (player == null || !player!!.isCommandAvailable(Player.COMMAND_GET_TRACKS) || !player!!.isCommandAvailable(
                Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS
            )
        ) {
            return
        }
        val tracks = player!!.currentTracks
        audioTrackSelectionAdapter.init(
            gatherSupportedTrackInfosOfType(
                tracks!!,
                C.TRACK_TYPE_AUDIO
            )
        )
        if (controlViewLayoutManager.getShowButton(subtitleButton)) {
            textTrackSelectionAdapter.init(
                gatherSupportedTrackInfosOfType(
                    tracks,
                    C.TRACK_TYPE_TEXT
                )
            )
        } else {
            textTrackSelectionAdapter.init(listOf())
        }
    }

    private fun gatherSupportedTrackInfosOfType(
        tracks: Tracks, trackType: @C.TrackType Int
    ): MutableList<TrackInformation> {
        val trackInfos = mutableListOf<TrackInformation>()
        val trackGroups: MutableList<Tracks.Group> = tracks.groups
        for (trackGroupIndex in trackGroups.indices) {
            val trackGroup = trackGroups.get(trackGroupIndex)
            if (trackGroup.type != trackType) {
                continue
            }
            for (trackIndex in 0..<trackGroup.length) {
                if (!trackGroup.isTrackSupported(trackIndex)) {
                    continue
                }
                val trackFormat = trackGroup.getTrackFormat(trackIndex)
                if ((trackFormat.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) {
                    continue
                }
                val trackName = trackNameProvider.getTrackName(trackFormat)
                trackInfos.add(TrackInformation(tracks, trackGroupIndex, trackIndex, trackName))
            }
        }
        return trackInfos
    }

    private fun updateTimeline() {
        val player = this.player
        if (player == null) {
            return
        }
        multiWindowTimeBar = showMultiWindowTimeBar && canShowMultiWindowTimeBar(player, window)
        currentWindowOffset = 0
        var durationUs: Long = 0
        var adGroupCount = 0
        val timeline =
            if (player.isCommandAvailable(Player.COMMAND_GET_TIMELINE))
                player.currentTimeline
            else
                Timeline.EMPTY
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
                                    if (adGroupTimesMs.size == 0) 1 else adGroupTimesMs.size * 2
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
        } else if (player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
            val playerDurationMs = player.contentDuration
            if (playerDurationMs != C.TIME_UNSET) {
                durationUs = msToUs(playerDurationMs)
            }
        }
        val durationMs = usToMs(durationUs)
        if (durationView != null) {
            durationView.setText(getStringForTime(formatBuilder, formatter, durationMs))
        }
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
        if (!this.isVisible || !isAttachedToWindow) {
            return
        }
        val player = this.player
        var position: Long = 0
        var bufferedPosition: Long = 0
        if (player != null && player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
            position = currentWindowOffset + player.contentPosition
            bufferedPosition = currentWindowOffset + player.contentBufferedPosition
        }
        if (positionView != null && !scrubbing) {
            positionView.setText(getStringForTime(formatBuilder, formatter, position))
        }
        if (timeBar != null) {
            timeBar.setPosition(position)
            // Hide the buffering bar in scrubbing mode.
            timeBar.setBufferedPosition(if (isScrubbingModeEnabled(player)) position else bufferedPosition)
        }
        if (progressUpdateListener != null) {
            progressUpdateListener!!.onProgressUpdate(position, bufferedPosition)
        }

        // Cancel any pending updates and schedule a new one if necessary.
        removeCallbacks(updateProgressAction)
        val playbackState = if (player == null) Player.STATE_IDLE else player.playbackState
        if (player != null && player.isPlaying) {
            var mediaTimeDelayMs =
                if (timeBar != null) timeBar.preferredUpdateDelay else MAX_UPDATE_INTERVAL_MS.toLong()

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

    private fun updatePlaybackSpeedList() {
        if (player == null) {
            return
        }
        playbackSpeedAdapter.updateSelectedIndex(player!!.playbackParameters!!.speed)
        settingsAdapter.setSubTextAtPosition(
            SETTINGS_PLAYBACK_SPEED_POSITION,
            playbackSpeedAdapter.selectedText
        )
        updateSettingsButton()
    }

    private fun updateSettingsButton() {
        updateButton(settingsAdapter.hasSettingsToShow(), settingsButton)
    }

    private fun updateSettingsWindowSize() {
        settingsView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)

        val maxWidth = getWidth() - settingsWindowMargin * 2
        val itemWidth = settingsView.getMeasuredWidth()
        val width = min(itemWidth, maxWidth)
        settingsWindow.setWidth(width)

        val maxHeight = getHeight() - settingsWindowMargin * 2
        val totalHeight = settingsView.getMeasuredHeight()
        val height = min(maxHeight, totalHeight)
        settingsWindow.setHeight(height)
    }

    private fun displaySettingsWindow(adapter: RecyclerView.Adapter<*>?, anchorView: View?) {
        settingsView.setAdapter(adapter)

        updateSettingsWindowSize()

        needToHideBars = false
        settingsWindow.dismiss()
        needToHideBars = true

        val xoff = getWidth() - settingsWindow.getWidth() - settingsWindowMargin
        val yoff = -settingsWindow.getHeight() - settingsWindowMargin

        settingsWindow.showAsDropDown(anchorView, xoff, yoff)
    }

    private fun setPlaybackSpeed(speed: Float) {
        if (player == null || !player!!.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            return
        }
        player!!.playbackParameters = player!!.playbackParameters!!.withSpeed(speed)
    }

    fun requestPlayPauseFocus() {
        if (playPauseButton != null) {
            playPauseButton.requestFocus()
        }
    }

    private fun updateButton(enabled: Boolean, view: View?) {
        if (view == null) {
            return
        }
        view.setEnabled(enabled)
        view.setAlpha(if (enabled) buttonAlphaEnabled else buttonAlphaDisabled)
    }

    private fun seekToTimeBarPosition(player: Player, positionMs: Long) {
        var positionMs = positionMs
        if (multiWindowTimeBar) {
            if (player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)
                && player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            ) {
                val timeline = player.currentTimeline
                val windowCount = timeline.windowCount
                var windowIndex = 0
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
                player.seekTo(windowIndex, positionMs)
            }
        } else if (player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            player.seekTo(positionMs)
        }
        updateProgress()
    }

    private fun onFullscreenButtonClicked(v: View?) {
        updateIsFullscreen(!isFullscreen)
    }

    fun updateIsFullscreen(isFullscreen: Boolean) {
        if (this.isFullscreen == isFullscreen) {
            return
        }

        this.isFullscreen = isFullscreen
        updateFullscreenButtonForState(fullscreenButton, isFullscreen)
        updateFullscreenButtonForState(minimalFullscreenButton, isFullscreen)

        if (onFullScreenModeChangedListener != null) {
            onFullScreenModeChangedListener!!.onFullScreenModeChanged(isFullscreen)
        }
    }

    private fun updateFullscreenButtonForState(
        fullscreenButton: ImageView?, isFullscreen: Boolean
    ) {
        if (fullscreenButton == null) {
            return
        }
        if (isFullscreen) {
            fullscreenButton.setImageDrawable(fullscreenExitDrawable)
            fullscreenButton.setContentDescription(fullscreenExitContentDescription)
        } else {
            fullscreenButton.setImageDrawable(fullscreenEnterDrawable)
            fullscreenButton.setContentDescription(fullscreenEnterContentDescription)
        }
    }

    private fun onSettingViewClicked(position: Int) {
        if (position == SETTINGS_PLAYBACK_SPEED_POSITION) {
            displaySettingsWindow(
                playbackSpeedAdapter,
                settingsButton!!
            )
        } else if (position == SETTINGS_AUDIO_TRACK_SELECTION_POSITION) {
            displaySettingsWindow(
                audioTrackSelectionAdapter,
                settingsButton!!
            )
        } else {
            settingsWindow.dismiss()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controlViewLayoutManager.onAttachedToWindow()
        isAttachedToWindow = true
        if (this.isFullyVisible) {
            controlViewLayoutManager.resetHideCallbacks()
        }
        updateAll()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        controlViewLayoutManager.onDetachedFromWindow()
        isAttachedToWindow = false
        removeCallbacks(updateProgressAction)
        controlViewLayoutManager.removeHideCallbacks()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return dispatchMediaKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    fun dispatchMediaKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.getKeyCode()
        val player = this.player
        if (player == null || !isHandledMediaKey(keyCode)) {
            return false
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                if (player.playbackState != Player.STATE_ENDED
                    && player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)
                ) {
                    player.seekForward()
                }
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                && player.isCommandAvailable(Player.COMMAND_SEEK_BACK)
            ) {
                player.seekBack()
            } else if (event.getRepeatCount() == 0) {
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> handlePlayPauseButtonAction(
                        player,
                        showPlayButtonIfSuppressed
                    )

                    KeyEvent.KEYCODE_MEDIA_PLAY -> handlePlayButtonAction(player)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> handlePauseButtonAction(player)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) {
                        player.seekToNext()
                    }

                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) {
                        player.seekToPrevious()
                    }

                    else -> {}
                }
            }
        }
        return true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        controlViewLayoutManager.onLayout(changed, left, top, right, bottom)
    }

    private fun onLayoutChange(
        v: View?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        oldLeft: Int,
        oldTop: Int,
        oldRight: Int,
        oldBottom: Int
    ) {
        val width = right - left
        val height = bottom - top
        val oldWidth = oldRight - oldLeft
        val oldHeight = oldBottom - oldTop

        if ((width != oldWidth || height != oldHeight) && settingsWindow.isShowing()) {
            updateSettingsWindowSize()
            val xOffset = getWidth() - settingsWindow.getWidth() - settingsWindowMargin
            val yOffset = -settingsWindow.getHeight() - settingsWindowMargin
            settingsWindow.update(v, xOffset, yOffset, -1, -1)
        }
    }

    private fun isScrubbingModeEnabled(player: Player?): Boolean {
        try {
            return (isExoPlayer(player)
                    && isScrubbingModeEnabledMethod!!.invoke(player) as Boolean)
                    || (isCompositionPlayer(player)
                    && compositionPlayerIsScrubbingModeEnabledMethod!!.invoke(player) as Boolean)
        } catch (e: IllegalAccessException) {
            throw RuntimeException(e)
        } catch (e: InvocationTargetException) {
            throw RuntimeException(e)
        }
    }

    private fun isExoPlayer(player: Player?): Boolean {
        return player != null && exoplayerClazz != null && exoplayerClazz.isAssignableFrom(player.javaClass)
    }

    private fun isCompositionPlayer(player: Player?): Boolean {
        return player != null && compositionPlayerClazz != null && compositionPlayerClazz.isAssignableFrom(
            player.javaClass
        )
    }

    private inner class ComponentListener

        : Player.Listener, OnScrubListener, OnClickListener, PopupWindow.OnDismissListener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED
                )
            ) {
                updatePlayPauseButton()
            }
            if (events.containsAny(
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED
                )
            ) {
                updateProgress()
            }
            if (events.containsAny(
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED
                )
            ) {
                updateRepeatModeButton()
            }
            if (events.containsAny(
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED
                )
            ) {
                updateShuffleButton()
            }
            if (events.containsAny(
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_SEEK_BACK_INCREMENT_CHANGED,
                    Player.EVENT_SEEK_FORWARD_INCREMENT_CHANGED,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED
                )
            ) {
                updateNavigation()
            }
            if (events.containsAny(
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED
                )
            ) {
                updateTimeline()
            }
            if (events.containsAny(
                    Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED
                )
            ) {
                updatePlaybackSpeedList()
            }
            if (events.containsAny(
                    Player.EVENT_TRACKS_CHANGED,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED
                )
            ) {
                updateTrackLists()
            }
        }

        override fun onScrubStart(timeBar: TimeBar, position: Long) {
            scrubbing = true
            if (positionView != null) {
                positionView.setText(getStringForTime(formatBuilder, formatter, position))
            }
            controlViewLayoutManager.removeHideCallbacks()
            if (player != null && timeBarScrubbingEnabled) {
                if (isExoPlayer(player)) {
                    try {
                        setScrubbingModeEnabledMethod!!.invoke(player, true)
                    } catch (e: IllegalAccessException) {
                        throw RuntimeException(e)
                    } catch (e: InvocationTargetException) {
                        throw RuntimeException(e)
                    }
                } else if (isCompositionPlayer(player)) {
                    try {
                        compositionPlayerSetScrubbingModeEnabledMethod!!.invoke(player, true)
                    } catch (e: IllegalAccessException) {
                        throw RuntimeException(e)
                    } catch (e: InvocationTargetException) {
                        throw RuntimeException(e)
                    }
                } else {
                    w(
                        TAG,
                        ("Time bar scrubbing is enabled, but player is not an ExoPlayer or CompositionPlayer"
                                + " instance, so ignoring (because we can't enable scrubbing mode). player.class="
                                + player!!.javaClass)
                    )
                }
            }
            if (isScrubbingModeEnabled(player)) {
                seekToTimeBarPosition(player!!, position)
            }
        }

        override fun onScrubMove(timeBar: TimeBar, position: Long) {
            if (positionView != null) {
                positionView.setText(getStringForTime(formatBuilder, formatter, position))
            }
            if (isScrubbingModeEnabled(player)) {
                seekToTimeBarPosition(player!!, position)
            }
        }

        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
            scrubbing = false
            if (player != null) {
                if (!canceled) {
                    seekToTimeBarPosition(player!!, position)
                }
                if (isExoPlayer(player)) {
                    try {
                        setScrubbingModeEnabledMethod!!.invoke(player, false)
                    } catch (e: IllegalAccessException) {
                        throw RuntimeException(e)
                    } catch (e: InvocationTargetException) {
                        throw RuntimeException(e)
                    }
                } else if (isCompositionPlayer(player)) {
                    try {
                        compositionPlayerSetScrubbingModeEnabledMethod!!.invoke(player, false)
                    } catch (e: IllegalAccessException) {
                        throw RuntimeException(e)
                    } catch (e: InvocationTargetException) {
                        throw RuntimeException(e)
                    }
                }
            }
            controlViewLayoutManager.resetHideCallbacks()
        }

        override fun onDismiss() {
            if (needToHideBars) {
                controlViewLayoutManager.resetHideCallbacks()
            }
        }

        override fun onClick(view: View?) {
            val player = this@PlayerControlView.player
            if (player == null) {
                return
            }
            controlViewLayoutManager.resetHideCallbacks()
            if (nextButton === view) {
                if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) {
                    player.seekToNext()
                }
            } else if (previousButton === view) {
                if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) {
                    player.seekToPrevious()
                }
            } else if (fastForwardButton === view) {
                if (player.playbackState != Player.STATE_ENDED
                    && player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)
                ) {
                    player.seekForward()
                }
            } else if (rewindButton === view) {
                if (player.isCommandAvailable(Player.COMMAND_SEEK_BACK)) {
                    player.seekBack()
                }
            } else if (playPauseButton === view) {
                handlePlayPauseButtonAction(player, showPlayButtonIfSuppressed)
            } else if (repeatToggleButton === view) {
                if (player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) {
                    player.repeatMode =
                        RepeatModeUtil.getNextRepeatMode(player.repeatMode, repeatToggleModes)
                }
            } else if (shuffleButton === view) {
                if (player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)) {
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                }
            } else if (settingsButton === view) {
                controlViewLayoutManager.removeHideCallbacks()
                displaySettingsWindow(settingsAdapter, settingsButton)
            } else if (playbackSpeedButton === view) {
                controlViewLayoutManager.removeHideCallbacks()
                displaySettingsWindow(playbackSpeedAdapter, playbackSpeedButton)
            } else if (audioTrackButton === view) {
                controlViewLayoutManager.removeHideCallbacks()
                displaySettingsWindow(audioTrackSelectionAdapter, audioTrackButton)
            } else if (subtitleButton === view) {
                controlViewLayoutManager.removeHideCallbacks()
                displaySettingsWindow(textTrackSelectionAdapter, subtitleButton)
            }
        }
    }

    private inner class SettingsAdapter(mainTexts: Array<String?>, iconIds: Array<Drawable?>) :
        RecyclerView.Adapter<SettingViewHolder?>() {
        private val mainTexts: Array<String?>?
        private val subTexts: Array<String?>
        private val iconIds: Array<Drawable?>

        init {
            this.mainTexts = mainTexts
            this.subTexts = arrayOfNulls<String>(mainTexts.size)
            this.iconIds = iconIds
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SettingViewHolder {
            val v: View =
                LayoutInflater.from(getContext())
                    .inflate(
                        R.layout.exo_styled_settings_list_item,
                        parent,  
                        false
                    )
            return SettingViewHolder(v)
        }

        override fun onBindViewHolder(holder: SettingViewHolder, position: Int) {
            if (shouldShowSetting(position)) {
                holder.itemView.setLayoutParams(
                    RecyclerView.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                    )
                )
            } else {
                holder.itemView.setLayoutParams(RecyclerView.LayoutParams(0, 0))
            }

            holder.mainTextView.setText(mainTexts!![position])

            if (subTexts[position] == null) {
                holder.subTextView.setVisibility(GONE)
            } else {
                holder.subTextView.setText(subTexts[position])
            }

            if (iconIds[position] == null) {
                holder.iconView.setVisibility(GONE)
            } else {
                holder.iconView.setImageDrawable(iconIds[position])
            }
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getItemCount(): Int {
            return mainTexts!!.size
        }

        fun setSubTextAtPosition(position: Int, subText: String?) {
            this.subTexts[position] = subText
        }

        fun hasSettingsToShow(): Boolean {
            return shouldShowSetting(SETTINGS_AUDIO_TRACK_SELECTION_POSITION)
                    || shouldShowSetting(SETTINGS_PLAYBACK_SPEED_POSITION)
        }

        fun shouldShowSetting(position: Int): Boolean {
            if (player == null) {
                return false
            }
            when (position) {
                SETTINGS_AUDIO_TRACK_SELECTION_POSITION -> return player!!.isCommandAvailable(Player.COMMAND_GET_TRACKS)
                        && player!!.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)

                SETTINGS_PLAYBACK_SPEED_POSITION -> return player!!.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)
                else -> return true
            }
        }
    }

    private inner class SettingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val mainTextView: TextView
        val subTextView: TextView
        val iconView: ImageView

        init {
            if (Build.VERSION.SDK_INT < 26) {
                // Workaround for https://github.com/google/ExoPlayer/issues/9061.
                itemView.setFocusable(true)
            }
            mainTextView = itemView.findViewById<TextView>(R.id.exo_main_text)
            subTextView = itemView.findViewById<TextView>(R.id.exo_sub_text)
            iconView = itemView.findViewById<ImageView>(R.id.exo_icon)
            itemView.setOnClickListener(OnClickListener { v: View? ->
                onSettingViewClicked(
                    adapterPosition
                )
            })
        }
    }

    private inner class PlaybackSpeedAdapter(
        private val playbackSpeedTexts: Array<String?>,
        private val playbackSpeeds: FloatArray
    ) : RecyclerView.Adapter<SubSettingViewHolder?>() {
        private var selectedIndex = 0

        fun updateSelectedIndex(playbackSpeed: Float) {
            var closestMatchIndex = 0
            var closestMatchDifference = Float.MAX_VALUE
            for (i in playbackSpeeds.indices) {
                val difference = abs(playbackSpeed - playbackSpeeds[i])
                if (difference < closestMatchDifference) {
                    closestMatchIndex = i
                    closestMatchDifference = difference
                }
            }
            selectedIndex = closestMatchIndex
        }

        val selectedText: String?
            get() = playbackSpeedTexts[selectedIndex]

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubSettingViewHolder {
            val v: View =
                LayoutInflater.from(getContext())
                    .inflate(
                        R.layout.exo_styled_sub_settings_list_item,
                        parent,  
                        false
                    )
            return SubSettingViewHolder(v)
        }

        override fun onBindViewHolder(holder: SubSettingViewHolder, position: Int) {
            if (position < playbackSpeedTexts.size) {
                holder.textView.setText(playbackSpeedTexts[position])
            }
            if (position == selectedIndex) {
                holder.itemView.setSelected(true)
                holder.checkView.setVisibility(VISIBLE)
            } else {
                holder.itemView.setSelected(false)
                holder.checkView.setVisibility(INVISIBLE)
            }
            holder.itemView.setOnClickListener(
                OnClickListener { v: View? ->
                    if (position != selectedIndex) {
                        setPlaybackSpeed(playbackSpeeds[position])
                    }
                    settingsWindow.dismiss()
                })
        }

        override fun getItemCount(): Int {
            return playbackSpeedTexts.size
        }
    }

    private class TrackInformation(
        tracks: Tracks,
        trackGroupIndex: Int,
        val trackIndex: Int,
        val trackName: String
    ) {
        val trackGroup: Tracks.Group = tracks.groups[trackGroupIndex]
        val isSelected: Boolean
            get() = trackGroup.isTrackSelected(trackIndex)
    }

    private inner class TextTrackSelectionAdapter : TrackSelectionAdapter() {
        override fun init(trackInformations: List<TrackInformation>) {
            var subtitleIsOn = false
            for (i in trackInformations.indices) {
                if (trackInformations[i].isSelected) {
                    subtitleIsOn = true
                    break
                }
            }
            if (subtitleButton != null) {
                subtitleButton.setImageDrawable(
                    if (subtitleIsOn) subtitleOnButtonDrawable else subtitleOffButtonDrawable
                )
                subtitleButton.setContentDescription(
                    if (subtitleIsOn) subtitleOnContentDescription else subtitleOffContentDescription
                )
            }
            this.tracks = trackInformations
        }

        override fun onBindViewHolderAtZeroPosition(holder: SubSettingViewHolder) {
            // CC options include "None" at the zero position, which disables text rendering except for
            // forced text tracks that can't be disabled (and are also not shown in the selection list).
            holder.textView.setText(R.string.exo_track_selection_none)
            var isTrackSelectionOff = true
            for (i in tracks.indices) {
                if (tracks[i].isSelected) {
                    isTrackSelectionOff = false
                    break
                }
            }
            holder.checkView.visibility = if (isTrackSelectionOff) VISIBLE else INVISIBLE
            holder.itemView.setOnClickListener { v: View ->
                if (player != null
                    && player!!.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
                ) {
                    val trackSelectionParameters =
                        player!!.trackSelectionParameters
                    player!!.trackSelectionParameters = trackSelectionParameters!!
                        .buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())
                        .setPreferredTextLanguage(null)
                        .setPreferredTextRoleFlags(0)
                        .build()
                    settingsWindow.dismiss()
                }
            }
        }

        override fun onBindViewHolder(holder: SubSettingViewHolder, position: Int) {
            super.onBindViewHolder(holder, position)
            if (position > 0) {
                val track = tracks[position - 1]
                holder.checkView.visibility = if (track.isSelected) VISIBLE else INVISIBLE
            }
        }

        override fun onTrackSelection(subtext: String) {
            // No-op
        }
    }

    private inner class AudioTrackSelectionAdapter : TrackSelectionAdapter() {
        override fun onBindViewHolderAtZeroPosition(holder: SubSettingViewHolder) {
            // Audio track selection option includes "Auto" at the top.
            holder.textView.setText(R.string.exo_track_selection_auto)
            // hasSelectionOverride is true means there is an explicit track selection, not "Auto".
            val parameters = player!!.trackSelectionParameters
            val hasSelectionOverride = hasSelectionOverride(parameters!!)
            holder.checkView.visibility = if (hasSelectionOverride) INVISIBLE else VISIBLE
            holder.itemView.setOnClickListener(
                OnClickListener { v: View? ->
                    if (player == null
                        || !player!!.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
                    ) {
                        return@OnClickListener
                    }
                    val trackSelectionParameters =
                        player!!.trackSelectionParameters
                    player!!.trackSelectionParameters = (
                            trackSelectionParameters!!
                                .buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,  false)
                                .build()
                            )
                    settingsAdapter.setSubTextAtPosition(
                        SETTINGS_AUDIO_TRACK_SELECTION_POSITION,
                        resources.getString(R.string.exo_track_selection_auto)
                    )
                    settingsWindow.dismiss()
                })
        }

        fun hasSelectionOverride(trackSelectionParameters: TrackSelectionParameters): Boolean {
            for (i in tracks.indices) {
                val trackGroup = tracks[i].trackGroup.mediaTrackGroup
                if (trackSelectionParameters.overrides.containsKey(trackGroup)) {
                    return true
                }
            }
            return false
        }

        override fun onTrackSelection(subtext: String) {
            settingsAdapter.setSubTextAtPosition(SETTINGS_AUDIO_TRACK_SELECTION_POSITION, subtext)
        }

        override fun init(trackInformations: List<TrackInformation>) {
            this.tracks = trackInformations
            // Update subtext in settings menu with current audio track selection.
            val params = player!!.trackSelectionParameters
            if (trackInformations.isEmpty()) {
                settingsAdapter.setSubTextAtPosition(
                    SETTINGS_AUDIO_TRACK_SELECTION_POSITION,
                    resources.getString(R.string.exo_track_selection_none)
                )
                // TODO(insun) : Make the audio item in main settings (settingsAdapater)
                //  to be non-clickable.
            } else if (!hasSelectionOverride(params!!)) {
                settingsAdapter.setSubTextAtPosition(
                    SETTINGS_AUDIO_TRACK_SELECTION_POSITION,
                    resources.getString(R.string.exo_track_selection_auto)
                )
            } else {
                for (i in trackInformations.indices) {
                    val track = trackInformations[i]
                    if (track.isSelected) {
                        settingsAdapter.setSubTextAtPosition(
                            SETTINGS_AUDIO_TRACK_SELECTION_POSITION, track.trackName
                        )
                        break
                    }
                }
            }
        }
    }

    private abstract inner class TrackSelectionAdapter protected constructor() :
        RecyclerView.Adapter<SubSettingViewHolder>() {
        protected var tracks: List<TrackInformation>

        init {
            this.tracks = ArrayList<TrackInformation>()
        }

        abstract fun init(trackInformations: List<TrackInformation>)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubSettingViewHolder {
            val v: View =
                LayoutInflater.from(getContext())
                    .inflate(
                        R.layout.exo_styled_sub_settings_list_item,
                        parent,  
                        false
                    )
            return SubSettingViewHolder(v)
        }

        protected abstract fun onBindViewHolderAtZeroPosition(holder: SubSettingViewHolder)

        protected abstract fun onTrackSelection(subtext: String)

        override fun onBindViewHolder(holder: SubSettingViewHolder, position: Int) {
            val player = this@PlayerControlView.player
            if (player == null) {
                return
            }
            if (position == 0) {
                onBindViewHolderAtZeroPosition(holder)
            } else {
                val track = tracks.get(position - 1)
                val mediaTrackGroup = track.trackGroup.mediaTrackGroup
                val params = player.trackSelectionParameters
                val explicitlySelected =
                    params!!.overrides.get(mediaTrackGroup) != null && track.isSelected
                holder.textView.setText(track.trackName)
                holder.checkView.setVisibility(if (explicitlySelected) VISIBLE else INVISIBLE)
                holder.itemView.setOnClickListener(
                    OnClickListener { v: View? ->
                        if (!player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) {
                            return@OnClickListener
                        }
                        val trackSelectionParameters =
                            player.trackSelectionParameters
                        player.trackSelectionParameters = trackSelectionParameters!!
                            .buildUpon()
                            .setOverrideForType(
                                TrackSelectionOverride(
                                    mediaTrackGroup, listOf(track.trackIndex)
                                )
                            )
                            .setTrackTypeDisabled(track.trackGroup.type,  false)
                            .build()
                        onTrackSelection(track.trackName)
                        settingsWindow.dismiss()
                    })
            }
        }

        override fun getItemCount(): Int {
            return if (tracks.isEmpty()) 0 else tracks.size + 1
        }

        fun clear() {
            tracks = mutableListOf<TrackInformation>()
        }
    }

    private class SubSettingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView
        val checkView: View

        init {
            if (Build.VERSION.SDK_INT < 26) {
                // Workaround for https://github.com/google/ExoPlayer/issues/9061.
                itemView.setFocusable(true)
            }
            textView = itemView.findViewById<TextView>(R.id.exo_text)
            checkView = itemView.findViewById<View>(R.id.exo_check)
        }
    }

    companion object {
        // TODO: b/422411856 - Add tests for PlayerControlView.
        init {
            registerModule("media3.ui")
        }

        const val DEFAULT_SHOW_TIMEOUT_MS: Int = 5000

        @RepeatToggleModes
        val DEFAULT_REPEAT_TOGGLE_MODES: @RepeatToggleModes Int =
            RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE

        const val DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS: Int = 200

        const val MAX_WINDOWS_FOR_MULTI_WINDOW_TIME_BAR: Int = 100

        private const val TAG = "PlayerControlView"

        private const val MAX_UPDATE_INTERVAL_MS = 1000

        // LINT.IfChange(playback_speeds)
        private val PLAYBACK_SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

        // LINT.ThenChange("../../../../res/values/strings.xml:playback_speeds")
        private const val SETTINGS_PLAYBACK_SPEED_POSITION = 0
        private const val SETTINGS_AUDIO_TRACK_SELECTION_POSITION = 1

        @SuppressLint("InlinedApi")
        private fun isHandledMediaKey(keyCode: Int): Boolean {
            return keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }

        private fun canShowMultiWindowTimeBar(player: Player, window: Timeline.Window): Boolean {
            if (!player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) {
                return false
            }
            val timeline = player.currentTimeline
            val windowCount = timeline.windowCount
            if (windowCount <= 1 || windowCount > MAX_WINDOWS_FOR_MULTI_WINDOW_TIME_BAR) {
                return false
            }
            for (i in 0..<windowCount) {
                if (timeline.getWindow(i, window).durationUs == C.TIME_UNSET) {
                    return false
                }
            }
            return true
        }

        private fun initializeFullscreenButton(
            fullscreenButton: View?,
            listener: OnClickListener?
        ) {
            if (fullscreenButton == null) {
                return
            }
            fullscreenButton.setVisibility(GONE)
            fullscreenButton.setOnClickListener(listener)
        }

        private fun updateFullscreenButtonVisibility(
            fullscreenButton: View?, visible: Boolean
        ) {
            if (fullscreenButton == null) {
                return
            }
            if (visible) {
                fullscreenButton.setVisibility(VISIBLE)
            } else {
                fullscreenButton.setVisibility(GONE)
            }
        }

        @RepeatToggleModes
        private fun getRepeatToggleModes(
            a: TypedArray, @RepeatToggleModes defaultValue: @RepeatToggleModes Int
        ): @RepeatToggleModes Int {
            return a.getInt(R.styleable.PlayerControlView_repeat_toggle_modes, defaultValue)
        }
    }
}
