
package androidx.media3.exoplayer

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.common.util.BackgroundThreadStateHandler
import androidx.media3.common.util.BackgroundThreadStateHandler.StateChangeListener
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Log.w
import com.google.common.base.Preconditions

internal class StreamVolumeManager(
    context: Context,
    private val listener: Listener,
    @C.StreamType streamType: @C.StreamType Int,
    audioManagerLooper: Looper,
    listenerLooper: Looper,
    clock: Clock
) {
    interface Listener {
        fun onStreamTypeChanged(@C.StreamType streamType: @C.StreamType Int)
        fun onStreamVolumeChanged(streamVolume: Int, streamMuted: Boolean)
    }

    private val applicationContext: Context
    private val stateHandler: BackgroundThreadStateHandler<StreamVolumeState>

    private var audioManager:AudioManager? = null
    private var receiver: VolumeChangeReceiver? = null
    private var volumeBeforeMute = 0

    init {
        this.applicationContext = context.getApplicationContext()
        val initialState =
            StreamVolumeState(
                streamType,  
                0,  
                false,  
                0,  
                0
            )
        stateHandler =
            BackgroundThreadStateHandler<StreamVolumeState>(
                initialState,
                audioManagerLooper,
                listenerLooper,
                clock,
                StateChangeListener { oldState: StreamVolumeState?, newState: StreamVolumeState? ->
                    this.onStreamVolumeStateChanged(
                        oldState!!,
                        newState!!
                    )
                })
        stateHandler.runInBackground(
            Runnable {
                audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val receiver = VolumeChangeReceiver()
                val filter: IntentFilter = IntentFilter(VOLUME_CHANGED_ACTION)
                try {
                    applicationContext.registerReceiver(receiver, filter)
                    this.receiver = receiver
                } catch (e: RuntimeException) {
                    w(TAG, "Error registering stream volume receiver", e)
                }
                stateHandler.setStateInBackground(generateState(streamType))
            })
    }

    fun setStreamType(@C.StreamType streamType: @C.StreamType Int) {
        stateHandler.updateStateAsync( 
             { state: StreamVolumeState ->
                StreamVolumeState(
                    streamType, state.volume, state.muted, state.minVolume, state.maxVolume
                )
            },  
             { state: StreamVolumeState ->
                if (state.streamType == streamType) state else generateState(
                    streamType
                )
            })
    }

    val minVolume: Int
        get() = stateHandler.get()!!.minVolume

    val maxVolume: Int
        get() = stateHandler.get()!!.maxVolume

    val volume: Int
        get() = stateHandler.get()!!.volume

    val isMuted: Boolean
        get() = stateHandler.get()!!.muted

    @SuppressLint("WrongConstant") // Setting C.VolumeFlags as audio system volume flags.
    fun setVolume(volume: Int, flags: @C.VolumeFlags Int) {
        stateHandler.updateStateAsync( 
             { state: StreamVolumeState ->
                StreamVolumeState(
                    state.streamType,
                    if (volume >= state.minVolume && volume <= state.maxVolume) volume else state.volume,  
                    volume == 0,
                    state.minVolume,
                    state.maxVolume
                )
            },  
             { state: StreamVolumeState ->
                if (volume == state.volume || volume < state.minVolume || volume > state.maxVolume) {
                    return@updateStateAsync state
                }
                 audioManager!!.setStreamVolume(state.streamType, volume, flags)
                generateState(state.streamType)
            })
    }

    @SuppressLint("WrongConstant") // Setting C.VolumeFlags as audio system volume flags.
    fun increaseVolume(flags: @C.VolumeFlags Int) {
        stateHandler.updateStateAsync( 
             { state: StreamVolumeState ->
                StreamVolumeState(
                    state!!.streamType,
                    if (state.volume < state.maxVolume) state.volume + 1 else state.maxVolume,  
                    false,
                    state.minVolume,
                    state.maxVolume
                )
            },  
             { state: StreamVolumeState? ->
                if (state!!.volume >= state.maxVolume) {
                    return@updateStateAsync  state
                }
                 audioManager!!.adjustStreamVolume(state.streamType, AudioManager.ADJUST_RAISE, flags)
                generateState(state.streamType)
            })
    }

    @SuppressLint("WrongConstant") // Setting C.VolumeFlags as audio system volume flags.
    fun decreaseVolume(flags: @C.VolumeFlags Int) {
        stateHandler.updateStateAsync( 
             { state: StreamVolumeState? ->
                StreamVolumeState(
                    state!!.streamType,
                    if (state.volume > state.minVolume) state.volume - 1 else state.minVolume,  
                    state.volume <= 1,
                    state.minVolume,
                    state.maxVolume
                )
            },  
             { state: StreamVolumeState? ->
                if (state!!.volume <= state.minVolume) {
                    return@updateStateAsync state
                }
                 audioManager!! .adjustStreamVolume(state.streamType, AudioManager.ADJUST_LOWER, flags)
                generateState(state.streamType)
            })
    }

    @SuppressLint("WrongConstant") // Setting C.VolumeFlags as audio system volume flags.
    fun setMuted(muted: Boolean, flags: @C.VolumeFlags Int) {
        stateHandler.updateStateAsync( 
             { state: StreamVolumeState? ->
                StreamVolumeState(
                    state!!.streamType,
                    if (state.muted == muted) state.volume else (if (muted) 0 else volumeBeforeMute),
                    muted,
                    state.minVolume,
                    state.maxVolume
                )
            },  
             { state: StreamVolumeState? ->
                if (state!!.muted == muted) {
                    return@updateStateAsync state
                }
                audioManager!!.adjustStreamVolume(
                    state.streamType,
                    if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    flags
                )
                generateState(state.streamType)
            })
    }

    fun release() {
        stateHandler.updateStateAsync( 
             { state: StreamVolumeState -> state },  
             { state: StreamVolumeState ->
                if (receiver != null) {
                    try {
                        applicationContext.unregisterReceiver(receiver)
                    } catch (e: RuntimeException) {
                        w(TAG, "Error unregistering stream volume receiver", e)
                    }
                    receiver = null
                }
                return@updateStateAsync state
            })
    }

    private fun onStreamVolumeStateChanged(
        oldState: StreamVolumeState,
        newState: StreamVolumeState
    ) {
        if (!oldState.muted && newState.muted) {
            volumeBeforeMute = oldState.volume
        }
        if (oldState.volume != newState.volume || oldState.muted != newState.muted) {
            listener.onStreamVolumeChanged(newState.volume, newState.muted)
        }
        if (oldState.streamType != newState.streamType || oldState.minVolume != newState.minVolume || oldState.maxVolume != newState.maxVolume) {
            listener.onStreamTypeChanged(newState.streamType)
        }
    }

    private fun generateState(@C.StreamType streamType: @C.StreamType Int): StreamVolumeState {
        val audioManager  = audioManager!!
        val volume = AudioManagerCompat.getStreamVolume(audioManager!!, streamType)
        val muted = AudioManagerCompat.isStreamMute(audioManager!!, streamType)
        val minVolume = AudioManagerCompat.getStreamMinVolume(audioManager!!, streamType)
        val maxVolume = AudioManagerCompat.getStreamMaxVolume(audioManager!!, streamType)
        return StreamVolumeState(streamType, volume, muted, minVolume, maxVolume)
    }

    private class StreamVolumeState(
        @field:C.StreamType @param:C.StreamType val streamType: @C.StreamType Int,
        val volume: Int,
        val muted: Boolean,
        val minVolume: Int,
        val maxVolume: Int
    )

    private inner class VolumeChangeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // BroadcastReceivers are called on the main thread.
            stateHandler.runInBackground(
                Runnable {
                    if (receiver == null) {
                        // Stale event. StreamVolumeManager is already released.
                        return@Runnable
                    }
                    val streamType = stateHandler.get()!!.streamType
                    stateHandler.setStateInBackground(generateState(streamType))
                })
        }
    }

    companion object {
        private const val TAG = "StreamVolumeManager"

        // TODO(b/151280453): Replace the hidden intent action with an official one.
        // Copied from AudioManager#VOLUME_CHANGED_ACTION
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}
