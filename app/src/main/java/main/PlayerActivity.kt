package main

import android.content.ContentResolver
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.AdsConfiguration
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util.checkCleartextTrafficPermitted
import androidx.media3.common.util.Util.maybeRequestReadStoragePermission
import androidx.media3.datasource.DataSchemeDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlayerImpl
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.ima.ImaAdsLoader
import androidx.media3.exoplayer.ima.ImaServerSideAdInsertionMediaSource
import androidx.media3.exoplayer.ima.ImaServerSideAdInsertionMediaSource.AdsLoader.State.Companion.fromBundle
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ads.AdsLoader
import androidx.media3.exoplayer.util.DebugTextViewHelper
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.PlayerView
import androidx.media3.ui.PlayerView.ControllerVisibilityListener
import com.example.plr.R
import main.IntentUtil.createMediaItemsFromIntent
import main.IntentUtil.parseRepeatModeExtra
import org.checkerframework.checker.nullness.qual.MonotonicNonNull
import kotlin.math.max

/** An activity that plays media using [ExoPlayer].  */
class PlayerActivity : AppCompatActivity(), View.OnClickListener, ControllerVisibilityListener {
    protected var playerView: PlayerView? = null
    protected var debugRootView: LinearLayout? = null
    protected var debugTextView: TextView? = null
    protected var player: ExoPlayer? = null

    private var isShowingTrackSelectionDialog = false
    private var selectTracksButton: Button? = null
    private var dataSourceFactory: DataSource.Factory? = null
    private var mediaItems: MutableList<MediaItem>? = null
    private var trackSelectionParameters: TrackSelectionParameters? = null
    private var debugViewHelper: DebugTextViewHelper? = null
    private var lastSeenTracks: Tracks? = null
    private var startAutoPlay = false
    private var startItemIndex = 0
    private var startPosition: Long = 0

    // For ad playback only.
    private var clientSideAdsLoader: AdsLoader? = null

    private var serverSideAdsLoader: ImaServerSideAdInsertionMediaSource.AdsLoader? = null

    private var serverSideAdsLoaderState: @MonotonicNonNull ImaServerSideAdInsertionMediaSource.AdsLoader.State? =
        null

    // Activity lifecycle.
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataSourceFactory = DemoUtil.getDataSourceFactory( /* context= */this)

        setContentView()
        debugRootView = findViewById<LinearLayout>(R.id.controls_root)
        debugTextView = findViewById<TextView>(R.id.debug_text_view)
        selectTracksButton = findViewById<Button>(R.id.select_tracks_button)
        selectTracksButton!!.setOnClickListener(this)

        playerView = findViewById<PlayerView?>(R.id.player_view)
        playerView!!.setControllerVisibilityListener(this)
        playerView!!.setErrorMessageProvider { e ->
            var errorString = getString(R.string.error_generic)
            val cause = e.cause
            if (cause is DecoderInitializationException) {
                // Special case for decoder initialization failures.
                val decoderInitializationException =
                    cause
                if (decoderInitializationException.codecInfo == null) {
                    if (decoderInitializationException.cause is DecoderQueryException) {
                        errorString = getString(R.string.error_querying_decoders)
                    } else if (decoderInitializationException.secureDecoderRequired) {
                        errorString =
                            getString(
                                R.string.error_no_secure_decoder,
                                decoderInitializationException.mimeType
                            )
                    } else {
                        errorString =
                            getString(
                                R.string.error_no_decoder,
                                decoderInitializationException.mimeType
                            )
                    }
                } else {
                    errorString =
                        getString(
                            R.string.error_instantiating_decoder,
                            decoderInitializationException.codecInfo.name
                        )
                }
            }
            return@setErrorMessageProvider Pair(0, errorString)
        }
        playerView!!.requestFocus()

        if (savedInstanceState != null) {
            trackSelectionParameters =
                TrackSelectionParameters.fromBundle(
                    savedInstanceState.getBundle(KEY_TRACK_SELECTION_PARAMETERS)!!
                )
            startAutoPlay = savedInstanceState.getBoolean(KEY_AUTO_PLAY)
            startItemIndex = savedInstanceState.getInt(KEY_ITEM_INDEX)
            startPosition = savedInstanceState.getLong(KEY_POSITION)
            restoreServerSideAdsLoaderState(savedInstanceState)
        } else {
            trackSelectionParameters = TrackSelectionParameters.Builder().build()
            clearStartPosition()
        }
    }

    public override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        releasePlayer()
        releaseClientSideAdsLoader()
        clearStartPosition()
        setIntent(intent)
    }

    public override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT > 23) {
            initializePlayer()
            if (playerView != null) {
                playerView!!.onResume()
            }
        }
    }

    public override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT == 23 || player == null) {
            initializePlayer()
            if (playerView != null) {
                playerView!!.onResume()
            }
        }
    }

    public override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT == 23) {
            if (playerView != null) {
                playerView!!.onPause()
            }
            releasePlayer()
        }
    }

    public override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT > 23) {
            if (playerView != null) {
                playerView!!.onPause()
            }
            releasePlayer()
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        releaseClientSideAdsLoader()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String?>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.size == 0) {
            // Empty results are triggered if a permission is requested while another request was already
            // pending and can be safely ignored in this case.
            return
        }
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializePlayer()
        } else {
            showToast(R.string.storage_permission_denied)
            finish()
        }
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        updateTrackSelectorParameters()
        updateStartPosition()
        outState.putBundle(KEY_TRACK_SELECTION_PARAMETERS, trackSelectionParameters!!.toBundle())
        outState.putBoolean(KEY_AUTO_PLAY, startAutoPlay)
        outState.putInt(KEY_ITEM_INDEX, startItemIndex)
        outState.putLong(KEY_POSITION, startPosition)
        saveServerSideAdsLoaderState(outState)
    }

    // Activity input
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // See whether the player view wants to handle media or DPAD keys events.
        return playerView!!.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    // OnClickListener methods
    override fun onClick(view: View?) {
        if (view === selectTracksButton && !isShowingTrackSelectionDialog &&
            TrackSelectionDialog.willHaveContent(player!!)
        ) {
            isShowingTrackSelectionDialog = true
            val trackSelectionDialog =
                TrackSelectionDialog.createForPlayer(
                    player!!,  /* onDismissListener= */
                    DialogInterface.OnDismissListener { dismissedDialog: DialogInterface? ->
                        isShowingTrackSelectionDialog = false
                    })
            trackSelectionDialog.show(getSupportFragmentManager(),  /* tag= */null)
        }
    }

    // PlayerView.ControllerVisibilityListener implementation
    override fun onVisibilityChanged(visibility: Int) {
        debugRootView!!.setVisibility(visibility)
    }

    // Internal methods
    protected fun setContentView() {
        setContentView(R.layout.player_activity)
    }

    /**
     * @return Whether initialization was successful.
     */
    protected fun initializePlayer(): Boolean {
        val intent = getIntent()
        if (player == null) {
            mediaItems = createMediaItems(intent)
            if (mediaItems!!.isEmpty()) {
                return false
            }
            lastSeenTracks = Tracks.EMPTY
            val preferExtensionDecoders =
                intent.getBooleanExtra(IntentUtil.PREFER_EXTENSION_DECODERS_EXTRA, false)
            val renderersFactory =
                DemoUtil.buildRenderersFactory(this, preferExtensionDecoders)
            player = ExoPlayerImpl(
                this, mediaSourceFactory = createMediaSourceFactory(),
                renderersFactory = renderersFactory
            ).apply { init() }

            player!!.trackSelectionParameters = trackSelectionParameters
            player!!.addListener(PlayerEventListener())
            player!!.addAnalyticsListener(EventLogger())
            player!!.setAudioAttributes(AudioAttributes.DEFAULT,  /* handleAudioFocus= */true)
            player!!.playWhenReady = startAutoPlay
            playerView!!.setPlayer(player)
            configurePlayerWithServerSideAdsLoader()
            debugViewHelper = DebugTextViewHelper(player!!, debugTextView!!)
            debugViewHelper!!.start()
        }
        val haveStartPosition = startItemIndex != C.INDEX_UNSET
        if (haveStartPosition) {
            player!!.seekTo(startItemIndex, startPosition)
        }
        player!!.setMediaItems(mediaItems!!,  /* resetPosition= */!haveStartPosition)
        player!!.prepare()
        val repeatModeExtra = intent.getStringExtra(IntentUtil.REPEAT_MODE_EXTRA)
        if (repeatModeExtra != null) {
            player!!.repeatMode = parseRepeatModeExtra(repeatModeExtra)
        }
        updateButtonVisibility()
        return true
    }

    private fun createMediaSourceFactory(): MediaSource.Factory {
        val drmSessionManagerProvider =
            DefaultDrmSessionManagerProvider()
        drmSessionManagerProvider.setDrmHttpDataSourceFactory(
            DemoUtil.getHttpDataSourceFactory( /* context= */this)
        )
        val serverSideAdLoaderBuilder =
            ImaServerSideAdInsertionMediaSource.AdsLoader.Builder( /* context= */this, playerView)
        if (serverSideAdsLoaderState != null) {
            serverSideAdLoaderBuilder.setAdsLoaderState(serverSideAdsLoaderState!!)
        }
        serverSideAdsLoader = serverSideAdLoaderBuilder.build()
        val imaServerSideAdInsertionMediaSourceFactory =
            ImaServerSideAdInsertionMediaSource.Factory(
                serverSideAdsLoader!!,
                DefaultMediaSourceFactory( /* context= */this)
                    .setDataSourceFactory(dataSourceFactory!!)
            )
        return DefaultMediaSourceFactory( /* context= */this)
            .setDataSourceFactory(dataSourceFactory!!)
            .setDrmSessionManagerProvider(drmSessionManagerProvider)
            .setLocalAdInsertionComponents(
                { adsConfiguration: AdsConfiguration? ->
                    this.getClientSideAdsLoader(
                        adsConfiguration
                    )
                },  /* adViewProvider= */playerView!!
            )
            .setServerSideAdInsertionMediaSourceFactory(imaServerSideAdInsertionMediaSourceFactory)
    }

    private fun configurePlayerWithServerSideAdsLoader() {
        serverSideAdsLoader!!.player = player
    }

    private fun createMediaItems(intent: Intent): MutableList<MediaItem> {
        val action = intent.getAction()
        val actionIsListView = IntentUtil.ACTION_VIEW_LIST == action
        if (!actionIsListView && IntentUtil.ACTION_VIEW != action) {
            showToast(getString(R.string.unexpected_intent_action, action))
            finish()
            return mutableListOf()
        }

        val mediaItems: MutableList<MediaItem> =
            createMediaItems(intent, DemoUtil.getDownloadTracker(this)!!)
        for (i in mediaItems.indices) {
            val mediaItem = mediaItems.get(i)

            if (!checkCleartextTrafficPermitted(mediaItem)) {
                showToast(R.string.error_cleartext_not_permitted)
                finish()
                return mutableListOf()
            }
            if (maybeRequestReadStoragePermission( /* activity= */this, mediaItem)) {
                // The player will be reinitialized if the permission is granted.
                return mutableListOf()
            }

            val drmConfiguration = mediaItem.localConfiguration!!.drmConfiguration
            if (drmConfiguration != null) {
                if (!FrameworkMediaDrm.isCryptoSchemeSupported(drmConfiguration.scheme)) {
                    showToast(R.string.error_drm_unsupported_scheme)
                    finish()
                    return mutableListOf()
                }
            }
        }
        return mediaItems
    }

    private fun getClientSideAdsLoader(adsConfiguration: AdsConfiguration?): AdsLoader? {
        // The ads loader is reused for multiple playbacks, so that ad playback can resume.
        if (clientSideAdsLoader == null) {
            clientSideAdsLoader = ImaAdsLoader.Builder( /* context= */this).build()
        }
        clientSideAdsLoader!!.setPlayer(player)
        return clientSideAdsLoader
    }

    protected fun releasePlayer() {
        if (player != null) {
            updateTrackSelectorParameters()
            updateStartPosition()
            releaseServerSideAdsLoader()
            debugViewHelper!!.stop()
            debugViewHelper = null
            player!!.release()
            player = null
            playerView!!.setPlayer( /* player= */null)
            mediaItems = mutableListOf()
        }
        if (clientSideAdsLoader != null) {
            clientSideAdsLoader!!.setPlayer(null)
        } else {
            playerView!!.adViewGroup.removeAllViews()
        }
    }

    private fun releaseServerSideAdsLoader() {
        serverSideAdsLoaderState = serverSideAdsLoader!!.release()
        serverSideAdsLoader = null
    }

    private fun releaseClientSideAdsLoader() {
        if (clientSideAdsLoader != null) {
            clientSideAdsLoader!!.release()
            clientSideAdsLoader = null
            playerView!!.adViewGroup.removeAllViews()
        }
    }

    private fun saveServerSideAdsLoaderState(outState: Bundle) {
        if (serverSideAdsLoaderState != null) {
            outState.putBundle(
                KEY_SERVER_SIDE_ADS_LOADER_STATE,
                serverSideAdsLoaderState!!.toBundle()
            )
        }
    }

    private fun restoreServerSideAdsLoaderState(savedInstanceState: Bundle) {
        val adsLoaderStateBundle = savedInstanceState.getBundle(KEY_SERVER_SIDE_ADS_LOADER_STATE)
        if (adsLoaderStateBundle != null) {
            serverSideAdsLoaderState =
                fromBundle(adsLoaderStateBundle)
        }
    }

    private fun updateTrackSelectorParameters() {
        if (player != null) {
            trackSelectionParameters = player!!.trackSelectionParameters
        }
    }

    private fun updateStartPosition() {
        if (player != null) {
            startAutoPlay = player!!.playWhenReady
            startItemIndex = player!!.currentMediaItemIndex
            startPosition = max(0, player!!.contentPosition)
        }
    }

    protected fun clearStartPosition() {
        startAutoPlay = true
        startItemIndex = C.INDEX_UNSET
        startPosition = C.TIME_UNSET
    }

    // User controls
    private fun updateButtonVisibility() {
        selectTracksButton!!.setEnabled(
            player != null && TrackSelectionDialog.willHaveContent(player!!)
        )
    }

    private fun showControls() {
        debugRootView!!.setVisibility(View.VISIBLE)
    }

    private fun showToast(messageId: Int) {
        showToast(getString(messageId))
    }

    private fun showToast(message: String?) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show()
    }

    private inner class PlayerEventListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                showControls()
            }
            updateButtonVisibility()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player!!.seekToDefaultPosition()
                player!!.prepare()
            } else {
                updateButtonVisibility()
                showControls()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateButtonVisibility()
            if (tracks === lastSeenTracks) {
                return
            }
            if (tracks.containsType(C.TRACK_TYPE_VIDEO)
                && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO,  /* allowExceedsCapabilities= */true)
            ) {
                showToast(R.string.error_unsupported_video)
            }
            if (tracks.containsType(C.TRACK_TYPE_AUDIO)
                && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO,  /* allowExceedsCapabilities= */true)
            ) {
                showToast(R.string.error_unsupported_audio)
            }
            lastSeenTracks = tracks
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (playerView == null) {
                return
            }
            if (mediaItem == null) {
                playerView!!.setTimeBarScrubbingEnabled(false)
                return
            }
            val uriScheme = mediaItem.localConfiguration!!.uri.getScheme()
            playerView!!.setTimeBarScrubbingEnabled(
                TextUtils.isEmpty(uriScheme)
                        || uriScheme == ContentResolver.SCHEME_FILE
                        || uriScheme == "asset"
                        || uriScheme == DataSchemeDataSource.SCHEME_DATA
                        || uriScheme == ContentResolver.SCHEME_ANDROID_RESOURCE
            )
        }
    }

    companion object {
        // Saved instance state keys.
        private const val KEY_TRACK_SELECTION_PARAMETERS = "track_selection_parameters"
        private const val KEY_SERVER_SIDE_ADS_LOADER_STATE = "server_side_ads_loader_state"
        private const val KEY_ITEM_INDEX = "item_index"
        private const val KEY_POSITION = "position"
        private const val KEY_AUTO_PLAY = "auto_play"

        private fun createMediaItems(
            intent: Intent,
            downloadTracker: DownloadTracker
        ): MutableList<MediaItem> {
            val mediaItems: MutableList<MediaItem> = ArrayList<MediaItem>()
            for (item in createMediaItemsFromIntent(intent)) {
                mediaItems.add(
                    Companion.maybeSetDownloadProperties(
                        item!!, downloadTracker.getDownloadRequest(item.localConfiguration!!.uri)
                    )!!
                )
            }
            return mediaItems
        }

        private fun maybeSetDownloadProperties(
            item: MediaItem, downloadRequest: DownloadRequest?
        ): MediaItem? {
            if (downloadRequest == null) {
                return item
            }
            val builder = item.buildUpon()
            builder
                .setMediaId(downloadRequest.id)
                .setUri(downloadRequest.uri)
                .setCustomCacheKey(downloadRequest.customCacheKey)
                .setMimeType(downloadRequest.mimeType)
                .setStreamKeys(downloadRequest.streamKeys)
            val drmConfiguration = item.localConfiguration!!.drmConfiguration
            if (drmConfiguration != null) {
                builder.setDrmConfiguration(
                    drmConfiguration.copy(keySetId = downloadRequest.keySetId)
                )
            }
            return builder.build()
        }
    }
}
