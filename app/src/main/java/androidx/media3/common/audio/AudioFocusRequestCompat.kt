package androidx.media3.common.audio

import android.media.AudioFocusRequest
import android.media.AudioManager.OnAudioFocusChangeListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.Util.createHandler
import androidx.media3.common.util.Util.postOrRun
import java.util.Objects

data class AudioFocusRequestCompat internal constructor(
    val focusGain: @AudioManagerCompat.AudioFocusGain Int,
    var onAudioFocusChangeListener: OnAudioFocusChangeListener,
    val focusChangeHandler: Handler = Handler(Looper.getMainLooper()),
    val audioAttributes: AudioAttributes = AudioAttributes.DEFAULT,
    val pauseOnDuck: Boolean = false,
    val acceptsDelayedFocusGain: Boolean = false,
    private var frameworkAudioFocusRequest: Any? = null
) {

    init {
        if (Build.VERSION.SDK_INT < 26) {
            this.onAudioFocusChangeListener =
                OnAudioFocusChangeListenerHandlerCompat(
                    onAudioFocusChangeListener, focusChangeHandler
                )
        } else {
            this.onAudioFocusChangeListener = onAudioFocusChangeListener
        }

        if (Build.VERSION.SDK_INT >= 26) {
            this.frameworkAudioFocusRequest =
                AudioFocusRequest.Builder(focusGain)
                    .setAudioAttributes(audioAttributes.platformAudioAttributes!!)
                    .setWillPauseWhenDucked(pauseOnDuck)
                    .setOnAudioFocusChangeListener(onAudioFocusChangeListener, focusChangeHandler)
                    .setAcceptsDelayedFocusGain(acceptsDelayedFocusGain)
                    .build()
        } else {
            this.frameworkAudioFocusRequest = null
        }
    }

    fun willPauseWhenDucked(): Boolean {
        return pauseOnDuck
    }

    fun acceptsDelayedFocusGain(): Boolean {
        return acceptsDelayedFocusGain
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is AudioFocusRequestCompat) {
            return false
        }
        val that = o
        return focusGain == that.focusGain && pauseOnDuck == that.pauseOnDuck && onAudioFocusChangeListener == that.onAudioFocusChangeListener
                && focusChangeHandler == that.focusChangeHandler
                && audioAttributes == that.audioAttributes
    }

    override fun hashCode(): Int {
        return Objects.hash(
            focusGain, onAudioFocusChangeListener, focusChangeHandler, audioAttributes, pauseOnDuck
        )
    }

    @get:RequiresApi(26)
    val audioFocusRequest: AudioFocusRequest
        get() = frameworkAudioFocusRequest as AudioFocusRequest

    companion object {
        private fun isValidFocusGain(focusGain: @AudioManagerCompat.AudioFocusGain Int): Boolean {
            when (focusGain) {
                AudioManagerCompat.AUDIOFOCUS_GAIN, AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT, AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> return true
                else -> return false
            }
        }
    }

    private class OnAudioFocusChangeListenerHandlerCompat
        (private val listener: OnAudioFocusChangeListener, handler: Handler) :
        OnAudioFocusChangeListener {
        private val handler: Handler = createHandler(handler.looper, null)

        override fun onAudioFocusChange(focusChange: Int) {
            postOrRun(handler, Runnable { listener.onAudioFocusChange(focusChange) })
        }
    }
}
