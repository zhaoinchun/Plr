package com.example.plr

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("UnsafeOptInUsageError")
class ExoVideoSwitchActivity : AppCompatActivity(), CoroutineScope by MainScope(), Player.Listener {
    private val TAG = "ExoVideoSwitchActivity"
    private var den = 1f
    private val list = mutableListOf<String>().apply {
        repeat(14) {
            add("/data/user/0/com.example.test/app_gallery/a${it + 1}.mp4")
        }
    }
    lateinit var player: ExoPlayer
    lateinit var pv: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        den = resources.displayMetrics.density

        LinearLayout(this).apply {
            setContentView(this)
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }
            addView(TextView(context).apply {
                text = "ggeee"
            })
            orientation = LinearLayout.VERTICAL

            pv = simplePlayerView() {
            }.apply {
                layoutParams = LinearLayout.LayoutParams((200 * den).toInt(), (200 * den).toInt())
            }
            f()
        }
    }

    fun f() {
        MainScope().launch {
            player = withContext(Dispatchers.Default) {
                Log.e(TAG, "f: s")
                ExoPlayer.Builder(this@ExoVideoSwitchActivity)
                    .setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(1_500, 5_000, 300, 800)
                            .setPrioritizeTimeOverSizeThresholds(true)
                            .build()
                    )
                    .build().also {
                        Log.e(TAG, "f: s2")
                    }
            }
            player.apply {
                repeatMode = Player.REPEAT_MODE_OFF
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                addListener(this@ExoVideoSwitchActivity)
            }
            pv.setPlayer(this@ExoVideoSwitchActivity.player)
            val uri = Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .path(R.raw.a6.toString())
                .build()
            val mediaItem = MediaItem.fromUri(uri)
            player.setMediaItem(mediaItem)

            player.prepare()
            player.play()
        }
    }

    private var resume = false

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        Log.e(TAG, "onPlayerErrorxxx: " + error)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        Log.e(TAG, "onPlaybackStateChanged: $playbackState")
    }

    override fun onRenderedFirstFrame() {
        Log.e(TAG, "onRenderedFirstFrame: ")
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        Log.e(TAG, "onMediaItemTransition: $mediaItem")
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        super.onTimelineChanged(timeline, reason)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
    }

    override fun onResume() {
        super.onResume()
        resume = true
        resumePlay()
//        requestAudioFocus().also {
//            Log.e(TAG, "onResume: $it")
//        }
    }

    private fun resumePlay() {
//        if (exoPlayer.mediaMetadata != null) {
//            exoPlayer.play()
//        }
//        mediaPlayer.isPlaying
//        mediaPlayer.prepareAsync()
//        mediaPlayer.start()
    }

    override fun onPause() {
        super.onPause()
        resume = false
        try {
//            player.pause()
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}

inline fun ViewGroup.simplePlayerView(
    @IdRes id: Int = -1,
    init: PlayerView.() -> Unit = {}
): PlayerView {
    // 默认构造有很多用不上的view，去掉不必要的
    val view = LayoutInflater.from(context).inflate(R.layout.layout_player, null) as PlayerView
    view.id = id
    view.setUseController(false)
    view.init()
    addView(view)
    return view
}
