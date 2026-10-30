package androidx.media3.common.audio

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes

import androidx.media3.common.util.Util.createHandler
import androidx.media3.common.util.Util.postOrRun
import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.Objects

class AudioFocusRequestCompat internal constructor(
    
    val focusGain: @AudioManagerCompat.AudioFocusGain Int,
    onAudioFocusChangeListener: OnAudioFocusChangeListener,
    focusChangeHandler: Handler,
    audioFocusRequestCompat: AudioAttributes,
    pauseOnDuck: Boolean,
    acceptsDelayedFocusGain: Boolean
) {
    
    val onAudioFocusChangeListener: OnAudioFocusChangeListener?

    val focusChangeHandler: Handler?

    val audioAttributes: AudioAttributes
    private val pauseOnDuck: Boolean
    private val acceptsDelayedFocusGain: Boolean

    private val frameworkAudioFocusRequest: Any?

    init {
        this.focusChangeHandler = focusChangeHandler
        this.audioAttributes = audioFocusRequestCompat
        this.pauseOnDuck = pauseOnDuck
        this.acceptsDelayedFocusGain = acceptsDelayedFocusGain

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

    fun buildUpon(): Builder {
        return AudioFocusRequestCompat.Builder(this)
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

    class Builder {
        private var focusGain: @AudioManagerCompat.AudioFocusGain Int
        private var onAudioFocusChangeListener: OnAudioFocusChangeListener? = null
        private var focusChangeHandler: Handler? = null
        private var audioAttributes: AudioAttributes
        private var pauseOnDuck = false
        private var acceptsDelayedFocusGain = false

        constructor(focusGain: @AudioManagerCompat.AudioFocusGain Int) {
            this.audioAttributes = AudioAttributes.DEFAULT
            this.focusGain = focusGain
        }

         constructor(other: AudioFocusRequestCompat) {
            focusGain = other.focusGain
            onAudioFocusChangeListener = other.onAudioFocusChangeListener
            focusChangeHandler = other.focusChangeHandler
            audioAttributes = other.audioAttributes
            pauseOnDuck = other.willPauseWhenDucked()
        }

        fun setFocusGain(focusGain: @AudioManagerCompat.AudioFocusGain Int): Builder {
            Preconditions.checkArgument(isValidFocusGain(focusGain))
            this.focusGain = focusGain
            return this
        }

        fun setOnAudioFocusChangeListener(listener: OnAudioFocusChangeListener?): Builder {
            return setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
        }

        fun setOnAudioFocusChangeListener(
            listener: OnAudioFocusChangeListener?, handler: Handler?
        ): Builder {
            onAudioFocusChangeListener = listener!!
            focusChangeHandler = handler!!
            return this
        }

        fun setAudioAttributes(attributes: AudioAttributes): Builder {
            audioAttributes = attributes
            return this
        }

        fun setWillPauseWhenDucked(pauseOnDuck: Boolean): Builder {
            this.pauseOnDuck = pauseOnDuck
            return this
        }

        fun setAcceptsDelayedFocusGain(acceptsDelayedFocusGain: Boolean): Builder {
            this.acceptsDelayedFocusGain = acceptsDelayedFocusGain
            return this
        }

        fun build(): AudioFocusRequestCompat {
            checkNotNull(onAudioFocusChangeListener) { "Can't build an AudioFocusRequestCompat instance without a listener" }
            return AudioFocusRequestCompat(
                focusGain,
                onAudioFocusChangeListener!!,
                focusChangeHandler!!,
                audioAttributes,
                pauseOnDuck,
                acceptsDelayedFocusGain
            )
        }

        companion object {
            
            private fun isValidFocusGain(focusGain: @AudioManagerCompat.AudioFocusGain Int): Boolean {
                when (focusGain) {
                    AudioManagerCompat.AUDIOFOCUS_GAIN, AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT, AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> return true
                    else -> return false
                }
            }
        }
    }

    private class OnAudioFocusChangeListenerHandlerCompat
        (private val listener: OnAudioFocusChangeListener, handler: Handler) :
        OnAudioFocusChangeListener {
        private val handler: Handler

        init {
            this.handler = createHandler(handler.getLooper(),  null)
        }

        override fun onAudioFocusChange(focusChange: Int) {
            postOrRun(handler, Runnable { listener.onAudioFocusChange(focusChange) })
        }
    }
}
