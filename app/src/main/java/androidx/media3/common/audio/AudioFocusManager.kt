package androidx.media3.common.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.os.Handler
import android.os.Looper
import androidx.annotation.IntDef
import androidx.annotation.VisibleForTesting
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioManagerCompat.AUDIOFOCUS_GAIN
import androidx.media3.common.audio.AudioManagerCompat.AUDIOFOCUS_NONE
import androidx.media3.common.audio.AudioManagerCompat.AudioFocusGain
import androidx.media3.common.audio.AudioManagerCompat.getAudioManager
import androidx.media3.common.util.Log.w
import com.google.common.base.Preconditions
import com.google.common.base.Supplier
import com.google.common.base.Suppliers

class AudioFocusManager(
    context: Context,
    eventLooper: Looper,
    private var playerControl: PlayerControl?
) {
    interface PlayerControl {
        fun setVolumeMultiplier(volumeMultiplier: Float)
        fun executePlayerCommand(playerCommand: @PlayerCommand Int)
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [PLAYER_COMMAND_DO_NOT_PLAY, PLAYER_COMMAND_WAIT_FOR_CALLBACK, PLAYER_COMMAND_PLAY_WHEN_READY]
    )
    annotation class PlayerCommand

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [AUDIO_FOCUS_STATE_NOT_REQUESTED, AUDIO_FOCUS_STATE_NO_FOCUS, AUDIO_FOCUS_STATE_HAVE_FOCUS, AUDIO_FOCUS_STATE_LOSS_TRANSIENT, AUDIO_FOCUS_STATE_LOSS_TRANSIENT_DUCK]
    )
    private annotation class AudioFocusState

    private val audioManager =
        Suppliers.memoize<AudioManager> { getAudioManager(context) }
    private val eventHandler: Handler = Handler(eventLooper)
    private var audioAttributes: AudioAttributes? = null

    private var audioFocusState: @AudioFocusState Int = AUDIO_FOCUS_STATE_NOT_REQUESTED
    private var focusGainToRequest: @AudioFocusGain Int = 0
    var volumeMultiplier: Float = VOLUME_MULTIPLIER_DEFAULT
        private set
    private var audioFocusRequest:AudioFocusRequestCompat? = null
    private var rebuildAudioFocusRequest = false

    fun setAudioAttributes(audioAttributes: AudioAttributes?) {
        if (this.audioAttributes != audioAttributes) {
            this.audioAttributes = audioAttributes
            focusGainToRequest = convertAudioAttributesToFocusGain(audioAttributes)
            Preconditions.checkArgument(
                focusGainToRequest == AUDIOFOCUS_GAIN || focusGainToRequest == AUDIOFOCUS_NONE,
                "Automatic handling of audio focus is only available for USAGE_MEDIA and USAGE_GAME."
            )
        }
    }

    fun updateAudioFocus(
        playWhenReady: Boolean, @Player.State playbackState: @Player.State Int
    ): @PlayerCommand Int {
        if (!shouldHandleAudioFocus(playbackState)) {
            abandonAudioFocusIfHeld()
            setAudioFocusState(AUDIO_FOCUS_STATE_NOT_REQUESTED)
            return PLAYER_COMMAND_PLAY_WHEN_READY
        }
        if (playWhenReady) {
            return requestAudioFocus()
        }
        return when (audioFocusState) {
            AUDIO_FOCUS_STATE_NO_FOCUS -> PLAYER_COMMAND_DO_NOT_PLAY
            AUDIO_FOCUS_STATE_LOSS_TRANSIENT -> PLAYER_COMMAND_WAIT_FOR_CALLBACK
            else -> PLAYER_COMMAND_PLAY_WHEN_READY
        }
    }

    fun release() {
        playerControl = null
        abandonAudioFocusIfHeld()
        setAudioFocusState(AUDIO_FOCUS_STATE_NOT_REQUESTED)
    }

    @get:VisibleForTesting
    val focusListener: OnAudioFocusChangeListener
        // Internal methods.
        get() = OnAudioFocusChangeListener { focusChange: Int ->
            handlePlatformAudioFocusChange(focusChange)
        }

    private fun shouldHandleAudioFocus(@Player.State playbackState: @Player.State Int): Boolean {
        return playbackState != Player.STATE_IDLE && focusGainToRequest == AUDIOFOCUS_GAIN
    }

    private fun requestAudioFocus(): @PlayerCommand Int {
        if (audioFocusState == AUDIO_FOCUS_STATE_HAVE_FOCUS) {
            return PLAYER_COMMAND_PLAY_WHEN_READY
        }
        val requestResult = requestAudioFocusInternal()
        if (requestResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            || requestResult == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        ) {
            setAudioFocusState(AUDIO_FOCUS_STATE_HAVE_FOCUS)
            return PLAYER_COMMAND_PLAY_WHEN_READY
        } else {
            setAudioFocusState(AUDIO_FOCUS_STATE_NO_FOCUS)
            return PLAYER_COMMAND_DO_NOT_PLAY
        }
    }

    private fun abandonAudioFocusIfHeld() {
        if (audioFocusState == AUDIO_FOCUS_STATE_NO_FOCUS
            || audioFocusState == AUDIO_FOCUS_STATE_NOT_REQUESTED
        ) {
            return
        }
        if (audioFocusRequest != null) {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager.get(), audioFocusRequest!!)
        }
    }

    private fun requestAudioFocusInternal(): Int {
        if (audioFocusRequest == null || rebuildAudioFocusRequest) {
            val builder = audioFocusRequest?.buildUpon()
                ?: AudioFocusRequestCompat.Builder(focusGainToRequest)

            val willPauseWhenDucked = willPauseWhenDucked()
            this.audioFocusRequest =
                builder
                    .setAudioAttributes(audioAttributes!!)
                    .setWillPauseWhenDucked(willPauseWhenDucked)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(OnAudioFocusChangeListener { focusChange: Int ->
                        this.handlePlatformAudioFocusChange(
                            focusChange
                        )
                    }, eventHandler)
                    .build()

            rebuildAudioFocusRequest = false
        }
        return AudioManagerCompat.requestAudioFocus(audioManager.get(), audioFocusRequest!!)
    }

    private fun willPauseWhenDucked(): Boolean {
        return audioAttributes != null && audioAttributes!!.contentType == C.AUDIO_CONTENT_TYPE_SPEECH
    }

    private fun setAudioFocusState(audioFocusState: @AudioFocusState Int) {
        if (this.audioFocusState == audioFocusState) {
            return
        }
        this.audioFocusState = audioFocusState

        val volumeMultiplier: Float =
            if (audioFocusState == AUDIO_FOCUS_STATE_LOSS_TRANSIENT_DUCK)
                VOLUME_MULTIPLIER_DUCK
            else
                VOLUME_MULTIPLIER_DEFAULT
        if (this.volumeMultiplier == volumeMultiplier) {
            return
        }
        this.volumeMultiplier = volumeMultiplier
        playerControl?.setVolumeMultiplier(volumeMultiplier)
    }

    private fun handlePlatformAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                setAudioFocusState(AUDIO_FOCUS_STATE_HAVE_FOCUS)
                executePlayerCommand(PLAYER_COMMAND_PLAY_WHEN_READY)
                return
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                executePlayerCommand(PLAYER_COMMAND_DO_NOT_PLAY)
                abandonAudioFocusIfHeld()
                setAudioFocusState(AUDIO_FOCUS_STATE_NO_FOCUS)
                return
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || willPauseWhenDucked()) {
                    executePlayerCommand(PLAYER_COMMAND_WAIT_FOR_CALLBACK)
                    setAudioFocusState(AUDIO_FOCUS_STATE_LOSS_TRANSIENT)
                } else {
                    setAudioFocusState(AUDIO_FOCUS_STATE_LOSS_TRANSIENT_DUCK)
                }
                return
            }

            else -> w(TAG, "Unknown focus change type: " + focusChange)
        }
    }

    private fun executePlayerCommand(playerCommand: @PlayerCommand Int) {
        playerControl?.executePlayerCommand(playerCommand)
    }

    companion object {
        private const val TAG = "AudioFocusManager"
        const val PLAYER_COMMAND_DO_NOT_PLAY: Int = -1
        const val PLAYER_COMMAND_WAIT_FOR_CALLBACK: Int = 0
        const val PLAYER_COMMAND_PLAY_WHEN_READY: Int = 1
        private const val AUDIO_FOCUS_STATE_NOT_REQUESTED = 0
        private const val AUDIO_FOCUS_STATE_NO_FOCUS = 1
        private const val AUDIO_FOCUS_STATE_HAVE_FOCUS = 2
        private const val AUDIO_FOCUS_STATE_LOSS_TRANSIENT = 3
        private const val AUDIO_FOCUS_STATE_LOSS_TRANSIENT_DUCK = 4
        private const val VOLUME_MULTIPLIER_DUCK = 0.2f
        private const val VOLUME_MULTIPLIER_DEFAULT = 1.0f

        private fun convertAudioAttributesToFocusGain(
            audioAttributes: AudioAttributes?
        ): @AudioFocusGain Int {
            if (audioAttributes == null) {
                // Don't handle audio focus. It may be either video only contents or developers
                // want to have more finer grained control. (e.g. adding audio focus listener)
                return AUDIOFOCUS_NONE
            }

            when (audioAttributes.usage) {
                C.USAGE_VOICE_COMMUNICATION_SIGNALLING -> return AUDIOFOCUS_NONE
                C.USAGE_GAME, C.USAGE_MEDIA -> return AUDIOFOCUS_GAIN
                C.USAGE_UNKNOWN -> {
                    w(
                        TAG,
                        "Specify a proper usage in the audio attributes for audio focus"
                                + " handling. Using AUDIOFOCUS_GAIN by default."
                    )
                    return AUDIOFOCUS_GAIN
                }

                C.USAGE_ALARM, C.USAGE_VOICE_COMMUNICATION -> return AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT
                C.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, C.USAGE_ASSISTANCE_SONIFICATION, C.USAGE_NOTIFICATION, C.USAGE_NOTIFICATION_COMMUNICATION_DELAYED, C.USAGE_NOTIFICATION_COMMUNICATION_INSTANT, C.USAGE_NOTIFICATION_COMMUNICATION_REQUEST, C.USAGE_NOTIFICATION_EVENT, C.USAGE_NOTIFICATION_RINGTONE -> return AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                C.USAGE_ASSISTANT -> return AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                C.USAGE_ASSISTANCE_ACCESSIBILITY -> {
                    if (audioAttributes.contentType == C.AUDIO_CONTENT_TYPE_SPEECH) {
                        // Voice shouldn't be interrupted by other playback.
                        return AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT
                    }
                    return AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                }

                else -> {
                    w(TAG, "Unidentified audio usage: " + audioAttributes.usage)
                    return AUDIOFOCUS_NONE
                }
            }
        }
    }
}
