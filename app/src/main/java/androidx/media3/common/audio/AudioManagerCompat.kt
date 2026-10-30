package androidx.media3.common.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import androidx.annotation.IntDef
import androidx.annotation.IntRange
import androidx.media3.common.C
import androidx.media3.common.util.BackgroundExecutor.get
import androidx.media3.common.util.ConditionVariable
import androidx.media3.common.util.Log.w

object AudioManagerCompat {
    private const val TAG = "AudioManagerCompat"

    const val AUDIOFOCUS_NONE: Int = AudioManager.AUDIOFOCUS_NONE
    const val AUDIOFOCUS_GAIN: Int = AudioManager.AUDIOFOCUS_GAIN
    const val AUDIOFOCUS_GAIN_TRANSIENT: Int = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
    const val AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK: Int =
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    const val AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE: Int =
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE

    private var audioManager: AudioManager? = null

    // Lazily initialized under class lock
    private val applicationContext: Context? = null

    @Synchronized
    fun getAudioManager(context: Context): AudioManager {
        val applicationContext = context.applicationContext
        if (AudioManagerCompat.applicationContext !== applicationContext) {
            // Reset cached instance if the application context changed. This should only happen in tests.
            audioManager = null
        }
        audioManager?.also { return it }

        val myLooper = Looper.myLooper()
        if (myLooper == null || myLooper == Looper.getMainLooper()) {
            // The AudioManager will assume the main looper as default callback anyway, so create the
            // instance here without using BackgroundExecutor.
            audioManager =
                applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return audioManager!!
        }
        // Create the audio manager on the BackgroundExecutor to avoid running the potentially blocking
        // command on the main thread but still use a thread that is guaranteed to exist for the
        // lifetime of the app.
        val audioManagerSetCondition = ConditionVariable()
        get().execute {
            audioManager =
                applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManagerSetCondition.open()
        }
        audioManagerSetCondition.blockUninterruptible()
        return audioManager!!
    }

    fun abandonAudioFocusRequest(
        audioManager: AudioManager, focusRequest: AudioFocusRequestCompat
    ): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            return audioManager.abandonAudioFocusRequest(focusRequest.audioFocusRequest)
        } else {
            return audioManager.abandonAudioFocus(focusRequest.onAudioFocusChangeListener)
        }
    }

    fun requestAudioFocus(
        audioManager: AudioManager, focusRequest: AudioFocusRequestCompat
    ): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            return audioManager.requestAudioFocus(focusRequest.audioFocusRequest)
        } else {
            return audioManager.requestAudioFocus(
                focusRequest.onAudioFocusChangeListener,
                focusRequest.audioAttributes.volumeControlStream,
                focusRequest.focusGain
            )
        }
    }

    @IntRange(from = 0)
    fun getStreamMaxVolume(audioManager: AudioManager, streamType: @C.StreamType Int): Int {
        return audioManager.getStreamMaxVolume(streamType)
    }

    @IntRange(from = 0)
    fun getStreamMinVolume(audioManager: AudioManager, streamType: @C.StreamType Int): Int {
        return if (Build.VERSION.SDK_INT >= 28) audioManager.getStreamMinVolume(streamType) else 0
    }

    fun getStreamVolume(audioManager: AudioManager, streamType: @C.StreamType Int): Int {
        // AudioManager#getStreamVolume(int) throws an exception on some devices. See
        // https://github.com/google/ExoPlayer/issues/8191.
        try {
            return audioManager.getStreamVolume(streamType)
        } catch (e: RuntimeException) {
            w(TAG, "Could not retrieve stream volume for stream type " + streamType, e)
            return audioManager.getStreamMaxVolume(streamType)
        }
    }

    fun isStreamMute(audioManager: AudioManager, streamType: @C.StreamType Int): Boolean {
        return audioManager.isStreamMute(streamType)
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [AUDIOFOCUS_NONE, AUDIOFOCUS_GAIN, AUDIOFOCUS_GAIN_TRANSIENT, AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE]
    )
    annotation class AudioFocusGain
}
