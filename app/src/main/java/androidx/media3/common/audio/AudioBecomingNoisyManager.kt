
package androidx.media3.common.audio

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.util.Clock
import androidx.media3.common.util.HandlerWrapper

class AudioBecomingNoisyManager(
    context: Context,
    backgroundLooper: Looper,
    eventLooper: Looper,
    listener: Listener,
    clock: Clock
) {
    private val context: Context = context.applicationContext
    private val receiver = AudioBecomingNoisyReceiver(
            clock.createHandler(eventLooper,  null), listener
        )
    private val backgroundHandler: HandlerWrapper =
        clock.createHandler(backgroundLooper,  null)
    private var isEnabled = false

    interface Listener {
        fun onAudioBecomingNoisy()
    }

    @SuppressLint("UnprotectedReceiver") // Protected system broadcasts must not specify protection.
    fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled) {
            return
        }
        if (enabled) {
            backgroundHandler.post {
                context.registerReceiver(
                    receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                )
            }
            isEnabled = true
        } else {
            backgroundHandler.post { context.unregisterReceiver(receiver) }
            isEnabled = false
        }
    }

    private inner class AudioBecomingNoisyReceiver(
        private val eventHandler: HandlerWrapper,
        private val listener: Listener
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                eventHandler.post { this.callListenerIfEnabled() }
            }
        }

        fun callListenerIfEnabled() {
            if (isEnabled) {
                listener.onAudioBecomingNoisy()
            }
        }
    }
}
