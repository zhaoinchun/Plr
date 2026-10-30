package androidx.media3.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageView.ScaleType
import android.widget.TextView
import android.window.SurfaceSyncGroup
import androidx.annotation.ColorInt
import androidx.annotation.IntDef
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.media3.common.AdOverlayInfo
import androidx.media3.common.AdViewProvider
import androidx.media3.common.C
import androidx.media3.common.ErrorMessageProvider
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.ViewProvider
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.RepeatModeUtil.RepeatToggleModes

import androidx.media3.common.util.Util.getDrawable
import androidx.media3.ui.AspectRatioFrameLayout.AspectRatioListener
import androidx.media3.ui.PlayerControlView.OnFullScreenModeChangedListener
import com.example.plr.R
import com.google.common.base.Preconditions
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class PlayerView(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int
) : FrameLayout(context, attrs, defStyleAttr), AdViewProvider {

    interface ControllerVisibilityListener {
        fun onVisibilityChanged(visibility: Int)
    }

    interface FullscreenButtonClickListener {
        fun onFullscreenButtonClick(isFullscreen: Boolean)
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [ARTWORK_DISPLAY_MODE_OFF, ARTWORK_DISPLAY_MODE_FIT, ARTWORK_DISPLAY_MODE_FILL])
    annotation class ArtworkDisplayMode

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [IMAGE_DISPLAY_MODE_FIT, IMAGE_DISPLAY_MODE_FILL])
    annotation class ImageDisplayMode

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [SHOW_BUFFERING_NEVER, SHOW_BUFFERING_WHEN_PLAYING, SHOW_BUFFERING_ALWAYS])
    annotation class ShowBuffering

    // LINT.ThenChange(../../../../res/values/attrs.xml)
    private val componentListener: ComponentListener
    private val contentFrame: AspectRatioFrameLayout?
    private val shutterView: View?

    val videoSurfaceView: View?
    private val surfaceViewIgnoresVideoAspectRatio: Boolean
    private val surfaceSyncGroupV34: SurfaceSyncGroupCompatV34?
    private val imageView: ImageView?
    private val artworkView: ImageView?

    val subtitleView: SubtitleView?
    private val bufferingView: View?
    private val errorMessageView: TextView?
    private val controller: PlayerControlView?
    private val adOverlayFrameLayout: FrameLayout?

    val overlayFrameLayout: FrameLayout?
    private val mainLooperHandler: Handler
    private val exoPlayerClazz: Class<*>?
    private val setImageOutputMethod: Method?
    private val imageOutput: Any?

    private var player: Player? = null
    private var useController: Boolean = false

    // At most one of controllerVisibilityListener and legacyControllerVisibilityListener is non-null.
    private var controllerVisibilityListener: ControllerVisibilityListener? = null

    @Suppress("deprecation")
    private var legacyControllerVisibilityListener: PlayerControlView.VisibilityListener? = null

    private var fullscreenButtonClickListener: FullscreenButtonClickListener? = null

    private var artworkDisplayMode: @ArtworkDisplayMode Int = 0
    private var imageDisplayMode: @ImageDisplayMode Int = 0

    private var defaultArtwork: Drawable? = null
    private var showBuffering: @ShowBuffering Int = 0
    private var keepContentOnPlayerReset = false
    private var errorMessageProvider: ErrorMessageProvider<in PlaybackException>? = null
    private var customErrorMessage: CharSequence? = null
    private var controllerShowTimeoutMs: Int = 0

    var controllerAutoShow: Boolean = false
    private var controllerHideDuringAds: Boolean = false
    private var controllerHideOnTouch: Boolean = false
    private var enableComposeSurfaceSyncWorkaround = false

    constructor(context: Context, attrs: AttributeSet? = null) : this(
        context,
        attrs,  
        0
    )

    // Using deprecated PlayerControlView.VisibilityListener internally
    init {
        componentListener = ComponentListener()
        mainLooperHandler = Handler(Looper.getMainLooper())

        if (isInEditMode()) {
            contentFrame = null
            shutterView = null
            this.videoSurfaceView = null
            surfaceViewIgnoresVideoAspectRatio = false
            surfaceSyncGroupV34 = null
            imageView = null
            artworkView = null
            subtitleView = null
            bufferingView = null
            errorMessageView = null
            controller = null
            adOverlayFrameLayout = null
            overlayFrameLayout = null
            exoPlayerClazz = null
            setImageOutputMethod = null
            imageOutput = null
            val logo = ImageView(context)
            configureEditModeLogo(context, getResources(), logo)
            addView(logo)
        } else {

            var shutterColorSet = false
            var shutterColor = 0
            var playerLayoutId: Int = R.layout.exo_player_view
            var useArtwork = true
            var artworkDisplayMode: Int = ARTWORK_DISPLAY_MODE_FIT
            var imageDisplayMode: Int = IMAGE_DISPLAY_MODE_FIT
            var defaultArtworkId = 0
            var useController = true
            var surfaceType: Int = SURFACE_TYPE_SURFACE_VIEW
            var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            var controllerShowTimeoutMs: Int = PlayerControlView.DEFAULT_SHOW_TIMEOUT_MS
            var controllerHideOnTouch = true
            var controllerAutoShow = true
            var controllerHideDuringAds = true
            var showBuffering: Int = SHOW_BUFFERING_NEVER
            if (attrs != null) {
                val a =
                    context
                        .getTheme()
                        .obtainStyledAttributes(
                            attrs, R.styleable.PlayerView, defStyleAttr,  0
                        )
                try {
                    shutterColorSet = a.hasValue(R.styleable.PlayerView_shutter_background_color)
                    shutterColor =
                        a.getColor(R.styleable.PlayerView_shutter_background_color, shutterColor)
                    playerLayoutId =
                        a.getResourceId(R.styleable.PlayerView_player_layout_id, playerLayoutId)
                    useArtwork = a.getBoolean(R.styleable.PlayerView_use_artwork, useArtwork)
                    artworkDisplayMode =
                        a.getInt(R.styleable.PlayerView_artwork_display_mode, artworkDisplayMode)
                    defaultArtworkId =
                        a.getResourceId(R.styleable.PlayerView_default_artwork, defaultArtworkId)
                    imageDisplayMode =
                        a.getInt(R.styleable.PlayerView_image_display_mode, imageDisplayMode)
                    useController =
                        a.getBoolean(R.styleable.PlayerView_use_controller, useController)
                    surfaceType = a.getInt(R.styleable.PlayerView_surface_type, surfaceType)
                    resizeMode = a.getInt(R.styleable.PlayerView_resize_mode, resizeMode)
                    controllerShowTimeoutMs =
                        a.getInt(R.styleable.PlayerView_show_timeout, controllerShowTimeoutMs)
                    controllerHideOnTouch =
                        a.getBoolean(R.styleable.PlayerView_hide_on_touch, controllerHideOnTouch)
                    controllerAutoShow =
                        a.getBoolean(R.styleable.PlayerView_auto_show, controllerAutoShow)
                    showBuffering =
                        a.getInteger(R.styleable.PlayerView_show_buffering, showBuffering)
                    keepContentOnPlayerReset =
                        a.getBoolean(
                            R.styleable.PlayerView_keep_content_on_player_reset,
                            keepContentOnPlayerReset
                        )
                    controllerHideDuringAds =
                        a.getBoolean(
                            R.styleable.PlayerView_hide_during_ads,
                            controllerHideDuringAds
                        )
                } finally {
                    a.recycle()
                }
            }

            LayoutInflater.from(context).inflate(playerLayoutId, this)
            setDescendantFocusability(FOCUS_AFTER_DESCENDANTS)

            // Content frame.
            contentFrame = findViewById<AspectRatioFrameLayout?>(R.id.exo_content_frame)
            if (contentFrame != null) {
                setResizeModeRaw(contentFrame, resizeMode)
            }

            // Shutter view.
            shutterView = findViewById<View?>(R.id.exo_shutter)
            if (shutterView != null && shutterColorSet) {
                shutterView.setBackgroundColor(shutterColor)
            }

            // Create a surface view and insert it into the content frame, if there is one.
            var surfaceViewIgnoresVideoAspectRatio = false
            if (contentFrame != null && surfaceType != SURFACE_TYPE_NONE) {
                val params =
                    ViewGroup.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
                    )
                when (surfaceType) {
                    SURFACE_TYPE_TEXTURE_VIEW -> this.videoSurfaceView = TextureView(context)
                    SURFACE_TYPE_SPHERICAL_GL_SURFACE_VIEW -> {
                        try {
                            // LINT.IfChange
                            val clazz =
                                Class.forName("androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView")
                            this.videoSurfaceView =
                                clazz.getConstructor(Context::class.java)
                                    .newInstance(context) as View
                            // LINT.ThenChange(../../../../../../proguard-rules.txt)
                        } catch (e: Exception) {
                            throw IllegalStateException(
                                "spherical_gl_surface_view requires an ExoPlayer dependency", e
                            )
                        }
                        surfaceViewIgnoresVideoAspectRatio = true
                    }

                    SURFACE_TYPE_VIDEO_DECODER_GL_SURFACE_VIEW -> try {
                        // LINT.IfChange
                        val clazz =
                            Class.forName("androidx.media3.exoplayer.video.VideoDecoderGLSurfaceView")
                        this.videoSurfaceView =
                            clazz.getConstructor(Context::class.java).newInstance(context) as View
                        // LINT.ThenChange(../../../../../../proguard-rules.txt)
                    } catch (e: Exception) {
                        throw IllegalStateException(
                            "video_decoder_gl_surface_view requires an ExoPlayer dependency", e
                        )
                    }

                    else -> {
                        val view = SurfaceView(context)
                        if (Build.VERSION.SDK_INT >= 34) {
                            Api34.setSurfaceLifecycleToFollowsAttachment(view)
                        }
                        this.videoSurfaceView = view
                    }
                }
                videoSurfaceView.setLayoutParams(params)
                // We don't want surfaceView to be clickable separately to the PlayerView itself, but we
                // do want to register as an OnClickListener so that surfaceView implementations can propagate
                // click events up to the PlayerView by calling their own performClick method.
                videoSurfaceView.setOnClickListener(componentListener)
                videoSurfaceView.setClickable(false)
                contentFrame.addView(this.videoSurfaceView, 0)
            } else {
                this.videoSurfaceView = null
            }
            this.surfaceViewIgnoresVideoAspectRatio = surfaceViewIgnoresVideoAspectRatio
            this.surfaceSyncGroupV34 =
                if (Build.VERSION.SDK_INT == 34) SurfaceSyncGroupCompatV34() else null

            // Ad overlay frame layout.
            adOverlayFrameLayout = findViewById<FrameLayout?>(R.id.exo_ad_overlay)

            // Overlay frame layout.
            overlayFrameLayout = findViewById<FrameLayout?>(R.id.exo_overlay)

            // Image view.
            imageView = findViewById<ImageView?>(R.id.exo_image)
            this.imageDisplayMode = imageDisplayMode

            // ExoPlayer image output classes and methods.
            var exoPlayerClazz: Class<*>?
            var setImageOutputMethod: Method?
            var imageOutput: Any?
            try {
                exoPlayerClazz = Class.forName("androidx.media3.exoplayer.ExoPlayer")
                val imageOutputClazz = Class.forName("androidx.media3.exoplayer.image.ImageOutput")
                setImageOutputMethod = exoPlayerClazz.getMethod("setImageOutput", imageOutputClazz)
                imageOutput =
                    Proxy.newProxyInstance(
                        imageOutputClazz.getClassLoader(),
                        arrayOf<Class<*>>(imageOutputClazz),
                        InvocationHandler { proxy: Any?, method: Method?, args: Array<Any?>? ->
                            if (method!!.getName() == "onImageAvailable") {
                                onImageAvailable(args!![1] as Bitmap?)
                            }
                            null
                        })
            } catch (e: ClassNotFoundException) {
                // Expected if ExoPlayer module not available.
                exoPlayerClazz = null
                setImageOutputMethod = null
                imageOutput = null
            } catch (e: NoSuchMethodException) {
                exoPlayerClazz = null
                setImageOutputMethod = null
                imageOutput = null
            }
            this.exoPlayerClazz = exoPlayerClazz
            this.setImageOutputMethod = setImageOutputMethod
            this.imageOutput = imageOutput

            // Artwork view.
            artworkView = findViewById<ImageView?>(R.id.exo_artwork)
            val isArtworkEnabled =
                useArtwork && artworkDisplayMode != ARTWORK_DISPLAY_MODE_OFF && artworkView != null
            this.artworkDisplayMode =
                if (isArtworkEnabled) artworkDisplayMode else ARTWORK_DISPLAY_MODE_OFF
            if (defaultArtworkId != 0) {
                defaultArtwork = ContextCompat.getDrawable(getContext(), defaultArtworkId)
            }

            // Subtitle view.
            subtitleView = findViewById<SubtitleView?>(R.id.exo_subtitles)
            if (subtitleView != null) {
                subtitleView.setUserDefaultStyle()
                subtitleView.setUserDefaultTextSize()
            }

            // Buffering view.
            bufferingView = findViewById<View?>(R.id.exo_buffering)
            if (bufferingView != null) {
                bufferingView.setVisibility(GONE)
            }
            this.showBuffering = showBuffering

            // Error message view.
            errorMessageView = findViewById<TextView?>(R.id.exo_error_message)
            if (errorMessageView != null) {
                errorMessageView.setVisibility(GONE)
            }

            // Playback control view.
            val customController = findViewById<PlayerControlView?>(R.id.exo_controller)
            val controllerPlaceholder = findViewById<View?>(R.id.exo_controller_placeholder)
            if (customController != null) {
                this.controller = customController
            } else if (controllerPlaceholder != null) {
                // Propagate attrs as playbackAttrs so that PlayerControlView's custom attributes are
                // transferred, but standard attributes (e.g. background) are not.
                this.controller = PlayerControlView(context, null, 0, attrs)
                controller.setId(R.id.exo_controller)
                controller.setLayoutParams(controllerPlaceholder.getLayoutParams())
                val parent = (controllerPlaceholder.getParent() as ViewGroup)
                val controllerIndex = parent.indexOfChild(controllerPlaceholder)
                parent.removeView(controllerPlaceholder)
                parent.addView(controller, controllerIndex)
            } else {
                this.controller = null
            }
            this.controllerShowTimeoutMs = if (controller != null) controllerShowTimeoutMs else 0
            this.controllerHideOnTouch = controllerHideOnTouch
            this.controllerAutoShow = controllerAutoShow
            this.controllerHideDuringAds = controllerHideDuringAds
            this.useController = useController && controller != null
            if (controller != null) {
                controller.hideImmediately()
                controller.addVisibilityListener( componentListener)
            }
            if (useController) {
                setClickable(true)
            }
            updateContentDescription()
        }
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

        val oldPlayer = this.player
        if (oldPlayer != null) {
            oldPlayer.removeListener(componentListener)
            if (oldPlayer.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
                if (this.videoSurfaceView is TextureView) {
                    oldPlayer.clearVideoTextureView(this.videoSurfaceView)
                } else if (this.videoSurfaceView is SurfaceView) {
                    oldPlayer.clearVideoSurfaceView(this.videoSurfaceView)
                }
            }
            clearImageOutput(oldPlayer)
        }
        if (subtitleView != null) {
            subtitleView.setCues(null)
        }
        this.player = player
        if (useController()) {
            controller!!.setPlayer(player)
        }
        updateBuffering()
        updateErrorMessage()
        updateForCurrentTrackSelections( true)
        if (player != null) {
            if (player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
                if (this.videoSurfaceView is TextureView) {
                    player.setVideoTextureView(this.videoSurfaceView)
                } else if (this.videoSurfaceView is SurfaceView) {
                    player.setVideoSurfaceView(this.videoSurfaceView)
                }
                if (!player.isCommandAvailable(Player.COMMAND_GET_TRACKS)
                    || player.currentTracks!!.isTypeSupported(C.TRACK_TYPE_VIDEO)
                ) {
                    // If the player already is or was playing a video, onVideoSizeChanged isn't called.
                    updateAspectRatio()
                }
            }
            if (subtitleView != null && player.isCommandAvailable(Player.COMMAND_GET_TEXT)) {
                subtitleView.setCues(player.currentCues!!.cues)
            }
            player.addListener(componentListener)
            setImageOutput(player)
            maybeShowController(false)
        } else {
            hideController()
        }
    }

    private fun setImageOutput(player: Player) {
        if (exoPlayerClazz != null && exoPlayerClazz.isAssignableFrom(player.javaClass)) {
            try {
                setImageOutputMethod!!.invoke(player, imageOutput!!)
            } catch (e: IllegalAccessException) {
                throw RuntimeException(e)
            } catch (e: InvocationTargetException) {
                throw RuntimeException(e)
            }
        }
    }

    // null allowed as method parameter in invoke
    private fun clearImageOutput(player: Player) {
        if (exoPlayerClazz != null && exoPlayerClazz.isAssignableFrom(player.javaClass)) {
            try {
                setImageOutputMethod!!.invoke(player, *arrayOf<Any?>(null))
            } catch (e: IllegalAccessException) {
                throw RuntimeException(e)
            } catch (e: InvocationTargetException) {
                throw RuntimeException(e)
            }
        }
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (this.videoSurfaceView is SurfaceView) {
            // Work around https://github.com/google/ExoPlayer/issues/3160.
            videoSurfaceView.setVisibility(visibility)
        }
    }

    var resizeMode: @AspectRatioFrameLayout.ResizeMode Int
        get() {
            return contentFrame!!.getResizeMode()
        }
        set(resizeMode) {
            contentFrame!!.setResizeMode(resizeMode)
        }

    @get:Deprecated("Use {@link #getArtworkDisplayMode()} instead.")

    @set:Deprecated("Use {@link #setArtworkDisplayMode(int)} instead.")
    var useArtwork: Boolean
        get() = this.artworkDisplayMode != ARTWORK_DISPLAY_MODE_OFF
        set(useArtwork) {
            setArtworkDisplayMode(if (useArtwork) ARTWORK_DISPLAY_MODE_OFF else ARTWORK_DISPLAY_MODE_FIT)
        }

    fun setArtworkDisplayMode(artworkDisplayMode: @ArtworkDisplayMode Int) {
        Preconditions.checkState(artworkDisplayMode == ARTWORK_DISPLAY_MODE_OFF || artworkView != null)
        if (this.artworkDisplayMode != artworkDisplayMode) {
            this.artworkDisplayMode = artworkDisplayMode
            updateForCurrentTrackSelections( false)
        }
    }

    fun getArtworkDisplayMode(): @ArtworkDisplayMode Int {
        return artworkDisplayMode
    }

    fun getDefaultArtwork(): Drawable? {
        return defaultArtwork
    }

    fun setDefaultArtwork(defaultArtwork: Drawable?) {
        if (this.defaultArtwork !== defaultArtwork) {
            this.defaultArtwork = defaultArtwork
            updateForCurrentTrackSelections( false)
        }
    }

    fun setImageDisplayMode(imageDisplayMode: @ImageDisplayMode Int) {
        Preconditions.checkState(imageView != null)
        if (this.imageDisplayMode != imageDisplayMode) {
            this.imageDisplayMode = imageDisplayMode
            updateImageViewAspectRatio()
        }
    }

    fun getImageDisplayMode(): @ImageDisplayMode Int {
        return imageDisplayMode
    }

    fun getUseController(): Boolean {
        return useController
    }

    fun setUseController(useController: Boolean) {
        Preconditions.checkState(!useController || controller != null)
        setClickable(useController || hasOnClickListeners())
        if (this.useController == useController) {
            return
        }
        this.useController = useController
        if (useController()) {
            controller!!.setPlayer(player)
        } else if (controller != null) {
            controller.hide()
            controller.setPlayer( null)
        }
        updateContentDescription()
    }

    fun setShutterBackgroundColor(@ColorInt color: Int) {
        if (shutterView != null) {
            shutterView.setBackgroundColor(color)
        }
    }

    fun setKeepContentOnPlayerReset(keepContentOnPlayerReset: Boolean) {
        if (this.keepContentOnPlayerReset != keepContentOnPlayerReset) {
            this.keepContentOnPlayerReset = keepContentOnPlayerReset
            updateForCurrentTrackSelections( false)
        }
    }

    fun setShowBuffering(showBuffering: @ShowBuffering Int) {
        if (this.showBuffering != showBuffering) {
            this.showBuffering = showBuffering
            updateBuffering()
        }
    }

    fun setErrorMessageProvider(
        errorMessageProvider: ErrorMessageProvider<in PlaybackException>?
    ) {
        if (this.errorMessageProvider !== errorMessageProvider) {
            this.errorMessageProvider = errorMessageProvider
            updateErrorMessage()
        }
    }

    fun setCustomErrorMessage(message: CharSequence?) {
        Preconditions.checkState(errorMessageView != null)
        customErrorMessage = message
        updateErrorMessage()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (player != null && player!!.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            && player!!.isPlayingAd
        ) {
            return super.dispatchKeyEvent(event)
        }

        val isDpadKey = isDpadKey(event.getKeyCode())
        var handled = false
        if (isDpadKey && useController() && !controller!!.isFullyVisible) {
            // Handle the key event by showing the controller.
            maybeShowController(true)
            handled = true
        } else if (dispatchMediaKeyEvent(event) || super.dispatchKeyEvent(event)) {
            // The key event was handled as a media key or by the super class. We should also show the
            // controller, or extend its show timeout if already visible.
            maybeShowController(true)
            handled = true
        } else if (isDpadKey && useController()) {
            // The key event wasn't handled, but we should extend the controller's show timeout.
            maybeShowController(true)
        }
        return handled
    }

    fun dispatchMediaKeyEvent(event: KeyEvent): Boolean {
        return useController() && controller!!.dispatchMediaKeyEvent(event)
    }

    val isControllerFullyVisible: Boolean
        get() = controller != null && controller.isFullyVisible

    fun showController() {
        showController(shouldShowControllerIndefinitely())
    }

    fun hideController() {
        if (controller != null) {
            controller.hide()
        }
    }

    fun getControllerShowTimeoutMs(): Int {
        return controllerShowTimeoutMs
    }

    fun setControllerShowTimeoutMs(controllerShowTimeoutMs: Int) {
        this.controllerShowTimeoutMs = controllerShowTimeoutMs
        if (controller!!.isFullyVisible) {
            // Update the controller's timeout if necessary.
            showController()
        }
    }

    fun getControllerHideOnTouch(): Boolean {
        return controllerHideOnTouch
    }

    fun setControllerHideOnTouch(controllerHideOnTouch: Boolean) {
        controller!!
        this.controllerHideOnTouch = controllerHideOnTouch
        updateContentDescription()
    }

    fun setControllerHideDuringAds(controllerHideDuringAds: Boolean) {
        this.controllerHideDuringAds = controllerHideDuringAds
    }

    @Suppress("deprecation") // Clearing the legacy listener.
    fun setControllerVisibilityListener(listener: ControllerVisibilityListener?) {
        this.controllerVisibilityListener = listener
        if (listener != null) {
            setControllerVisibilityListener(null as PlayerControlView.VisibilityListener?)
        }
    }

    fun setControllerAnimationEnabled(animationEnabled: Boolean) {
        controller!!.isAnimationEnabled = (animationEnabled)
    }

    @Suppress("deprecation")

    @Deprecated("Use {@link #setControllerVisibilityListener(ControllerVisibilityListener)} instead.")
    fun setControllerVisibilityListener(
        listener: PlayerControlView.VisibilityListener?
    ) {
        if (this.legacyControllerVisibilityListener === listener) {
            return
        }

        if (this.legacyControllerVisibilityListener != null) {
            controller!!.removeVisibilityListener(this.legacyControllerVisibilityListener)
        }
        this.legacyControllerVisibilityListener = listener
        if (listener != null) {
            controller!!.addVisibilityListener(listener)
            setControllerVisibilityListener(null as ControllerVisibilityListener?)
        }
    }

    @Suppress("deprecation") // Calling the deprecated method on PlayerControlView for now.
    fun setFullscreenButtonClickListener(listener: FullscreenButtonClickListener?) {
        this.fullscreenButtonClickListener = listener
        controller!!.setOnFullScreenModeChangedListener(componentListener)
    }

    fun setFullscreenButtonState(isFullscreen: Boolean) {
        controller!!.updateIsFullscreen(isFullscreen)
    }

    @Suppress("deprecation") // Forwarding deprecated call

    @Deprecated(
        """Use {@link #setFullscreenButtonClickListener(FullscreenButtonClickListener)}
        instead."""
    )
    fun setControllerOnFullScreenModeChangedListener(
        listener: OnFullScreenModeChangedListener?
    ) {
        this.fullscreenButtonClickListener = null
        controller!!.setOnFullScreenModeChangedListener(listener)
    }

    fun setShowRewindButton(showRewindButton: Boolean) {
        controller!!.setShowRewindButton(showRewindButton)
    }

    fun setShowFastForwardButton(showFastForwardButton: Boolean) {
        controller!!.setShowFastForwardButton(showFastForwardButton)
    }

    fun setShowPreviousButton(showPreviousButton: Boolean) {
        controller!!.setShowPreviousButton(showPreviousButton)
    }

    fun setShowNextButton(showNextButton: Boolean) {
        controller!!.setShowNextButton(showNextButton)
    }

    fun setRepeatToggleModes(@RepeatToggleModes repeatToggleModes: @RepeatToggleModes Int) {
        controller!!.setRepeatToggleModes(repeatToggleModes)
    }

    fun setShowShuffleButton(showShuffleButton: Boolean) {
        controller!!.showShuffleButton = (showShuffleButton)
    }

    fun setShowSubtitleButton(showSubtitleButton: Boolean) {
        controller!!.showSubtitleButton = (showSubtitleButton)
    }

    fun setShowVrButton(showVrButton: Boolean) {
        controller!!.showVrButton = (showVrButton)
    }

    fun setMediaRouteButtonViewProvider(mediaRouteButtonViewProvider: ViewProvider?) {
        controller!!.setMediaRouteButtonViewProvider(mediaRouteButtonViewProvider)
    }

    @Suppress("deprecation") // Forwarding to deprecated method.

    @Deprecated(
        """Replace multi-window time bar display by merging source windows together instead,
        for example using ExoPlayer's {@code ConcatenatingMediaSource2}."""
    )
    fun setShowMultiWindowTimeBar(showMultiWindowTimeBar: Boolean) {
        controller!!.setShowMultiWindowTimeBar(showMultiWindowTimeBar)
    }

    fun setTimeBarScrubbingEnabled(timeBarScrubbingEnabled: Boolean) {
        controller!!.setTimeBarScrubbingEnabled(timeBarScrubbingEnabled)
    }

    fun setShowPlayButtonIfPlaybackIsSuppressed(showPlayButtonIfSuppressed: Boolean) {
        controller!!.setShowPlayButtonIfPlaybackIsSuppressed(showPlayButtonIfSuppressed)
    }

    fun setExtraAdGroupMarkers(
        extraAdGroupTimesMs: LongArray?, extraPlayedAdGroups: BooleanArray?
    ) {
        controller!!.setExtraAdGroupMarkers(extraAdGroupTimesMs, extraPlayedAdGroups)
    }

    fun setAspectRatioListener(
        listener: AspectRatioListener?
    ) {
        contentFrame!!.setAspectRatioListener(listener)
    }

    fun setEnableComposeSurfaceSyncWorkaround(enableComposeSurfaceSyncWorkaround: Boolean) {
        this.enableComposeSurfaceSyncWorkaround = enableComposeSurfaceSyncWorkaround
    }

    override fun performClick(): Boolean {
        toggleControllerVisibility()
        return super.performClick()
    }

    override fun onTrackballEvent(ev: MotionEvent?): Boolean {
        if (!useController() || player == null) {
            return false
        }
        maybeShowController(true)
        return true
    }

    fun onResume() {
        if (this.videoSurfaceView is GLSurfaceView) {
            this.videoSurfaceView.onResume()
        }
    }

    fun onPause() {
        if (this.videoSurfaceView is GLSurfaceView) {
            this.videoSurfaceView.onPause()
        }
    }

    protected fun onContentAspectRatioChanged(
        contentFrame: AspectRatioFrameLayout?, aspectRatio: Float
    ) {
        if (contentFrame != null) {
            contentFrame.setAspectRatio(aspectRatio)
        }
    }

    override val adViewGroup: ViewGroup
        get() {
            return adOverlayFrameLayout!!
        }

    override fun getAdOverlayInfos(): MutableList<AdOverlayInfo> {
        val overlayViews: MutableList<AdOverlayInfo> = ArrayList<AdOverlayInfo>()
        if (overlayFrameLayout != null) {
            overlayViews.add(
                AdOverlayInfo.Builder(overlayFrameLayout, AdOverlayInfo.PURPOSE_NOT_VISIBLE)
                    .setDetailedReason("Transparent overlay does not impact viewability")
                    .build()
            )
        }
        if (controller != null) {
            overlayViews.add(
                AdOverlayInfo.Builder(controller, AdOverlayInfo.PURPOSE_CONTROLS).build()
            )
        }
        return overlayViews.toMutableList()
    }

    // Internal methods.
    private fun useController(): Boolean {
        if (useController) {
            controller!!
            return true
        }
        return false
    }

    private fun useArtwork(): Boolean {
        if (artworkDisplayMode != ARTWORK_DISPLAY_MODE_OFF) {
            artworkView!!
            return true
        }
        return false
    }

    private fun toggleControllerVisibility() {
        if (!useController() || player == null) {
            return
        }
        if (!controller!!.isFullyVisible) {
            maybeShowController(true)
        } else if (controllerHideOnTouch) {
            controller.hide()
        }
    }

    private fun maybeShowController(isForced: Boolean) {
        if (this.isPlayingAd && controllerHideDuringAds) {
            return
        }
        if (useController()) {
            val wasShowingIndefinitely =
                controller!!.isFullyVisible && controller.getShowTimeoutMs() <= 0
            val shouldShowIndefinitely = shouldShowControllerIndefinitely()
            if (isForced || wasShowingIndefinitely || shouldShowIndefinitely) {
                showController(shouldShowIndefinitely)
            }
        }
    }

    private fun shouldShowControllerIndefinitely(): Boolean {
        if (player == null) {
            return true
        }
        val playbackState = player!!.playbackState
        return controllerAutoShow
                && (!player!!.isCommandAvailable(Player.COMMAND_GET_TIMELINE)
                || !player!!.currentTimeline.isEmpty)
                && (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED || !player!!.playWhenReady)
    }

    private fun showController(showIndefinitely: Boolean) {
        if (!useController()) {
            return
        }
        controller!!.setShowTimeoutMs(if (showIndefinitely) 0 else controllerShowTimeoutMs)
        controller.show()
    }

    private val isPlayingAd: Boolean
        get() = player != null && player!!.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                && player!!.isPlayingAd
                && player!!.playWhenReady

    private fun updateForCurrentTrackSelections(isNewPlayer: Boolean) {
        val player = this.player

        // Unless configured to keep, clear output completely when tracks are empty or for a new player.
        val hasTracks =
            player != null && player.isCommandAvailable(Player.COMMAND_GET_TRACKS)
                    && !player.currentTracks!!.isEmpty
        if (!keepContentOnPlayerReset && (!hasTracks || isNewPlayer)) {
            hideArtwork()
            closeShutter()
            hideAndClearImage()
        }
        if (!hasTracks) {
            // Nothing else to check.
            return
        }

        val hasSelectedVideoTrack = hasSelectedVideoTrack()
        val hasSelectedImageTrack = hasSelectedImageTrack()

        // When video and image are disabled, close shutter and clear image. Do nothing if one or both
        // are enabled to keep the current state (previous frame or video). Once ready, the first frame
        // or image will update the view.
        if (!hasSelectedVideoTrack && !hasSelectedImageTrack) {
            closeShutter()
            hideAndClearImage()
        }
        // Exception: If both were enabled (shutter open and image set) and we switch to one only, we
        // can hide the other output immediately as no further callbacks will arrive.
        val wasVideoAndImageSet =
            shutterView != null && shutterView.getVisibility() == INVISIBLE && this.isImageSet
        if (hasSelectedImageTrack && !hasSelectedVideoTrack && wasVideoAndImageSet) {
            closeShutter()
            showImage()
        } else if (hasSelectedVideoTrack && !hasSelectedImageTrack && wasVideoAndImageSet) {
            hideAndClearImage()
        }

        // Display artwork if enabled and available, else hide it.
        val shouldShowArtwork = !hasSelectedVideoTrack && !hasSelectedImageTrack && useArtwork()
        if (shouldShowArtwork) {
            if (setArtworkFromMediaMetadata(player)) {
                return
            }
            if (setDrawableArtwork(defaultArtwork)) {
                return
            }
        }
        // Artwork disabled or unavailable.
        hideArtwork()
    }

    private fun setArtworkFromMediaMetadata(player: Player?): Boolean {
        if (player == null || !player.isCommandAvailable(Player.COMMAND_GET_METADATA)) {
            return false
        }
        val mediaMetadata = player.mediaMetadata
        if (mediaMetadata!!.artworkData == null) {
            return false
        }
        val bitmap =
            BitmapFactory.decodeByteArray(
                mediaMetadata.artworkData,  0, mediaMetadata.artworkData.size
            )
        return setDrawableArtwork(BitmapDrawable(getResources(), bitmap))
    }

    private fun setDrawableArtwork(drawable: Drawable?): Boolean {
        if (artworkView != null && drawable != null) {
            val drawableWidth = drawable.getIntrinsicWidth()
            val drawableHeight = drawable.getIntrinsicHeight()
            if (drawableWidth > 0 && drawableHeight > 0) {
                var artworkLayoutAspectRatio = drawableWidth.toFloat() / drawableHeight
                var scaleStyle = ScaleType.FIT_XY
                if (artworkDisplayMode == ARTWORK_DISPLAY_MODE_FILL) {
                    artworkLayoutAspectRatio = getWidth().toFloat() / getHeight()
                    scaleStyle = ScaleType.CENTER_CROP
                }
                onContentAspectRatioChanged(contentFrame, artworkLayoutAspectRatio)
                artworkView.setScaleType(scaleStyle)
                artworkView.setImageDrawable(drawable)
                artworkView.setVisibility(VISIBLE)
                return true
            }
        }
        return false
    }

    private fun hideArtwork() {
        if (artworkView != null) {
            artworkView.setImageResource(android.R.color.transparent) // Clears any bitmap reference.
            artworkView.setVisibility(INVISIBLE)
        }
    }

    private fun hasSelectedImageTrack(): Boolean {
        val player = this.player
        return player != null && imageOutput != null && player.isCommandAvailable(Player.COMMAND_GET_TRACKS)
                && player.currentTracks!!.isTypeSelected(C.TRACK_TYPE_IMAGE)
    }

    private fun hasSelectedVideoTrack(): Boolean {
        val player = this.player
        return player != null && player.isCommandAvailable(Player.COMMAND_GET_TRACKS)
                && player.currentTracks!!.isTypeSelected(C.TRACK_TYPE_VIDEO)
    }

    private val isImageSet: Boolean
        get() {
            if (imageView == null) {
                return false
            }
            val drawable = imageView.getDrawable()
            // The transparent placeholder has an alpha value of 0, but BitmapDrawables never have alpha 0.
            return drawable != null && drawable.getAlpha() != 0
        }

    private fun setImage(drawable: Drawable?) {
        if (imageView == null) {
            return
        }
        imageView.setImageDrawable(drawable)
        updateImageViewAspectRatio()
    }

    private fun updateImageViewAspectRatio() {
        if (imageView == null) {
            return
        }
        val drawable = imageView.getDrawable()
        if (drawable == null) {
            return
        }
        val drawableWidth = drawable.getIntrinsicWidth()
        val drawableHeight = drawable.getIntrinsicHeight()
        if (drawableWidth <= 0 || drawableHeight <= 0) {
            return
        }
        var drawableLayoutAspectRatio = drawableWidth.toFloat() / drawableHeight
        var scaleStyle = ScaleType.FIT_XY
        if (imageDisplayMode == IMAGE_DISPLAY_MODE_FILL) {
            drawableLayoutAspectRatio = getWidth().toFloat() / getHeight()
            scaleStyle = ScaleType.CENTER_CROP
        }
        if (imageView.getVisibility() == VISIBLE) {
            onContentAspectRatioChanged(contentFrame, drawableLayoutAspectRatio)
        }
        imageView.setScaleType(scaleStyle)
    }

    private fun hideAndClearImage() {
        hideImage()
        if (imageView != null) {
            imageView.setImageResource(android.R.color.transparent)
        }
    }

    private fun showImage() {
        if (imageView != null) {
            imageView.setVisibility(VISIBLE)
            updateImageViewAspectRatio()
        }
    }

    private fun hideImage() {
        if (imageView != null) {
            imageView.setVisibility(INVISIBLE)
        }
    }

    private fun onImageAvailable(bitmap: Bitmap?) {
        mainLooperHandler.post(
            Runnable {
                setImage(BitmapDrawable(getResources(), bitmap))
                if (!hasSelectedVideoTrack()) {
                    showImage()
                    closeShutter()
                }
            })
    }

    private fun closeShutter() {
        if (shutterView != null) {
            shutterView.setVisibility(VISIBLE)
        }
    }

    private fun updateBuffering() {
        if (bufferingView != null) {
            val showBufferingSpinner =
                player != null && player!!.playbackState == Player.STATE_BUFFERING && (showBuffering == SHOW_BUFFERING_ALWAYS
                        || (showBuffering == SHOW_BUFFERING_WHEN_PLAYING && player!!.playWhenReady))
            bufferingView.setVisibility(if (showBufferingSpinner) VISIBLE else GONE)
        }
    }

    private fun updateErrorMessage() {
        if (errorMessageView != null) {
            if (customErrorMessage != null) {
                errorMessageView.setText(customErrorMessage)
                errorMessageView.setVisibility(VISIBLE)
                return
            }
            val error = if (player != null) player!!.playerError else null
            if (error != null && errorMessageProvider != null) {
                val errorMessage: CharSequence? =
                    errorMessageProvider!!.getErrorMessage(error).second
                errorMessageView.setText(errorMessage)
                errorMessageView.setVisibility(VISIBLE)
            } else {
                errorMessageView.setVisibility(GONE)
            }
        }
    }

    private fun updateContentDescription() {
        if (controller == null || !useController) {
            setContentDescription( null)
        } else if (controller.isFullyVisible) {
            setContentDescription( 
                if (controllerHideOnTouch)
                    getResources().getString(R.string.exo_controls_hide)
                else
                    null
            )
        } else {
            setContentDescription( 
                getResources().getString(R.string.exo_controls_show)
            )
        }
    }

    private fun updateControllerVisibility() {
        if (this.isPlayingAd && controllerHideDuringAds) {
            hideController()
        } else {
            maybeShowController(false)
        }
    }

    private fun updateAspectRatio() {
        val videoSize = if (player != null) player!!.videoSize else VideoSize.UNKNOWN
        val width = videoSize!!.width
        val height = videoSize.height
        val videoAspectRatio =
            if (height == 0 || width == 0) 0f else (width * videoSize.pixelWidthHeightRatio) / height
        onContentAspectRatioChanged(
            contentFrame, if (surfaceViewIgnoresVideoAspectRatio) 0f else videoAspectRatio
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (Build.VERSION.SDK_INT == 34 && surfaceSyncGroupV34 != null && enableComposeSurfaceSyncWorkaround) {
            surfaceSyncGroupV34.maybeMarkSyncReadyAndClear()
        }
    }

    @SuppressLint("InlinedApi")
    private fun isDpadKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_UP_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_DOWN_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
    }

    // Implementing the deprecated PlayerControlView.VisibilityListener and
    // PlayerControlView.OnFullScreenModeChangedListener for now.
    @Suppress("deprecation")
    private inner class ComponentListener

        : Player.Listener, OnClickListener, PlayerControlView.VisibilityListener,
        OnFullScreenModeChangedListener {
        private val period: Timeline.Period
        private var lastPeriodUidWithTracks: Any? = null

        init {
            period = Timeline.Period()
        }

        // Player.Listener implementation
        override fun onCues(cueGroup: CueGroup) {
            subtitleView?.setCues(cueGroup.cues)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize == VideoSize.UNKNOWN
                || player == null || player!!.playbackState == Player.STATE_IDLE
            ) {
                return
            }
            updateAspectRatio()
        }

        override fun onSurfaceSizeChanged(width: Int, height: Int) {
            if (Build.VERSION.SDK_INT == 34 && this@PlayerView.videoSurfaceView is SurfaceView
                && enableComposeSurfaceSyncWorkaround
            ) {
                // Register a SurfaceSyncGroup to work around https://github.com/androidx/media/issues/1237
                // (only present on API 34, fixed on API 35).
                surfaceSyncGroupV34!!.postRegister(
                        mainLooperHandler,
                        this@PlayerView.videoSurfaceView as SurfaceView,
                        Runnable { this@PlayerView.invalidate() })
            }
        }

        override fun onRenderedFirstFrame() {
            if (shutterView != null) {
                shutterView.setVisibility(INVISIBLE)
                if (hasSelectedImageTrack()) {
                    hideImage()
                } else {
                    hideAndClearImage()
                }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            // Suppress the update if transitioning to an unprepared period within the same window. This
            // is necessary to avoid closing the shutter when such a transition occurs. See:
            // https://github.com/google/ExoPlayer/issues/5507.
            val player = this@PlayerView.player!!
            val timeline =
                if (player.isCommandAvailable(Player.COMMAND_GET_TIMELINE))
                    player.currentTimeline
                else
                    Timeline.EMPTY
            if (timeline.isEmpty) {
                lastPeriodUidWithTracks = null
            } else if (player.isCommandAvailable(Player.COMMAND_GET_TRACKS)
                && !player.currentTracks!!.isEmpty
            ) {
                lastPeriodUidWithTracks =
                    timeline.getPeriod(player.currentPeriodIndex, period,  true).uid
            } else if (lastPeriodUidWithTracks != null) {
                val lastPeriodIndexWithTracks = timeline.getIndexOfPeriod(lastPeriodUidWithTracks!!)
                if (lastPeriodIndexWithTracks != C.INDEX_UNSET) {
                    val lastWindowIndexWithTracks =
                        timeline.getPeriod(lastPeriodIndexWithTracks, period).windowIndex
                    if (player.currentMediaItemIndex == lastWindowIndexWithTracks) {
                        // We're in the same media item. Suppress the update.
                        return
                    }
                }
                lastPeriodUidWithTracks = null
            }

            updateForCurrentTrackSelections( false)
        }

        override fun onPlaybackStateChanged(@Player.State playbackState: @Player.State Int) {
            updateBuffering()
            updateErrorMessage()
            updateControllerVisibility()
        }

        override fun onPlayWhenReadyChanged(
            playWhenReady: Boolean,
            @Player.PlayWhenReadyChangeReason reason: @Player.PlayWhenReadyChangeReason Int
        ) {
            updateBuffering()
            updateControllerVisibility()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            @Player.DiscontinuityReason reason: @Player.DiscontinuityReason Int
        ) {
            if (this@PlayerView.isPlayingAd && controllerHideDuringAds) {
                hideController()
            }
        }

        // OnClickListener implementation
        override fun onClick(view: View?) {
            toggleControllerVisibility()
        }

        // PlayerControlView.VisibilityListener implementation
        override fun onVisibilityChange(visibility: Int) {
            updateContentDescription()
            if (controllerVisibilityListener != null) {
                controllerVisibilityListener!!.onVisibilityChanged(visibility)
            }
        }

        // PlayerControlView.OnFullScreenModeChangedListener implementation
        override fun onFullScreenModeChanged(isFullscreen: Boolean) {
            if (fullscreenButtonClickListener != null) {
                fullscreenButtonClickListener!!.onFullscreenButtonClick(isFullscreen)
            }
        }
    }

    @RequiresApi(34)
    private object Api34 {
        fun setSurfaceLifecycleToFollowsAttachment(surfaceView: SurfaceView) {
            surfaceView.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
        }
    }

    @RequiresApi(34)
    private class SurfaceSyncGroupCompatV34 {
        var surfaceSyncGroup: SurfaceSyncGroup? = null

        fun postRegister(
            mainLooperHandler: Handler, surfaceView: SurfaceView, invalidate: Runnable
        ) {
            mainLooperHandler.post(
                Runnable {
                    val rootSurfaceControl = surfaceView.getRootSurfaceControl()
                    if (rootSurfaceControl == null) {
                        // The SurfaceView isn't attached to a window, so don't apply the workaround.
                        return@Runnable
                    }
                    surfaceSyncGroup = SurfaceSyncGroup("exo-sync-b-334901521")
                    Preconditions.checkState(
                        surfaceSyncGroup!!.add(
                            rootSurfaceControl,
                            Runnable {})
                    )
                    invalidate.run()
                    rootSurfaceControl.applyTransactionOnDraw(SurfaceControl.Transaction())
                })
        }

        fun maybeMarkSyncReadyAndClear() {
            if (surfaceSyncGroup != null) {
                surfaceSyncGroup!!.markSyncReady()
                surfaceSyncGroup = null
            }
        }
    }

    companion object {

        const val ARTWORK_DISPLAY_MODE_OFF: Int = 0

        const val ARTWORK_DISPLAY_MODE_FIT: Int = 1

        const val ARTWORK_DISPLAY_MODE_FILL: Int = 2

        const val IMAGE_DISPLAY_MODE_FIT: Int = 0

        const val IMAGE_DISPLAY_MODE_FILL: Int = 1

        const val SHOW_BUFFERING_NEVER: Int = 0

        const val SHOW_BUFFERING_WHEN_PLAYING: Int = 1

        const val SHOW_BUFFERING_ALWAYS: Int = 2

        // LINT.ThenChange(../../../../res/values/attrs.xml)
        // LINT.IfChange
        private const val SURFACE_TYPE_NONE = 0
        private const val SURFACE_TYPE_SURFACE_VIEW = 1
        private const val SURFACE_TYPE_TEXTURE_VIEW = 2
        private const val SURFACE_TYPE_SPHERICAL_GL_SURFACE_VIEW = 3
        private const val SURFACE_TYPE_VIDEO_DECODER_GL_SURFACE_VIEW = 4

        fun switchTargetView(
            player: Player?, oldPlayerView: PlayerView?, newPlayerView: PlayerView?
        ) {
            if (oldPlayerView === newPlayerView) {
                return
            }
            // We attach the new view before detaching the old one because this ordering allows the player
            // to swap directly from one surface to another, without transitioning through a state where no
            // surface is attached. This is significantly more efficient and achieves a more seamless
            // transition when using platform provided video decoders.
            if (newPlayerView != null) {
                newPlayerView.setPlayer(player)
            }
            if (oldPlayerView != null) {
                oldPlayerView.setPlayer(null)
            }
        }

        private fun configureEditModeLogo(
            context: Context,
            resources: Resources,
            logo: ImageView
        ) {
            logo.setImageDrawable(getDrawable(context, resources, R.drawable.exo_edit_mode_logo))
            logo.setBackgroundColor(
                resources.getColor(
                    R.color.exo_edit_mode_background_color,
                    null
                )
            )
        }

        private fun setResizeModeRaw(aspectRatioFrame: AspectRatioFrameLayout, resizeMode: Int) {
            aspectRatioFrame.setResizeMode(resizeMode)
        }
    }
}
