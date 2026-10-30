package androidx.media3.exoplayer.audio

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.audio.AudioManagerCompat.getAudioManager
import androidx.media3.common.util.Util.createHandlerForCurrentOrMainLooper
import androidx.media3.common.util.Util.isTv
import androidx.media3.exoplayer.util.SpatializerWrapper

class AudioCapabilitiesReceiver(
    context: Context,
    private val listener: Listener,
    private var audioAttributes: AudioAttributes,
    private var routedDevice: AudioDeviceInfo?
) {
    interface Listener {
        fun onAudioCapabilitiesChanged(audioCapabilities: AudioCapabilities?)
    }

    private val context: Context = context.applicationContext
    private val handler = createHandlerForCurrentOrMainLooper()
    private val audioDeviceCallback = AudioDeviceCallback()
    private val hdmiAudioPlugBroadcastReceiver = HdmiAudioPlugBroadcastReceiver()
    private val externalSurroundSoundSettingObserver: ExternalSurroundSoundSettingObserver? =
        AudioCapabilities.externalSurroundSoundGlobalSettingUri?.let {
            ExternalSurroundSoundSettingObserver(
                handler, context.contentResolver, it
            )
        }
    private var spatializer: SpatializerWrapper? = null
    private var audioCapabilities: AudioCapabilities? = null
    private var registered = false

    fun overrideCapabilities(audioCapabilities: AudioCapabilities) {
        onNewAudioCapabilities(audioCapabilities)
    }

    fun setAudioAttributes(audioAttributes: AudioAttributes) {
        if (audioAttributes == this.audioAttributes) {
            return
        }
        this.audioAttributes = audioAttributes
        onNewAudioCapabilities(
            AudioCapabilities.getCapabilitiesInternal(
                context, audioAttributes, routedDevice, this.spatializerChannelMasks
            )
        )
    }

    fun setRoutedDevice(routedDevice: AudioDeviceInfo?) {
        if (routedDevice == this.routedDevice) {
            return
        }
        this.routedDevice = routedDevice
        onNewAudioCapabilities(
            AudioCapabilities.getCapabilitiesInternal(
                context, audioAttributes, this.routedDevice, this.spatializerChannelMasks
            )
        )
    }

    fun register(): AudioCapabilities {
        if (registered) {
            return audioCapabilities!!
        }
        registered = true
        externalSurroundSoundSettingObserver?.register()
        getAudioManager(context)
            .registerAudioDeviceCallback(audioDeviceCallback, handler)
        if (Build.VERSION.SDK_INT >= 32 && spatializer == null) {
            val deviceIsTv = isTv(context)
            spatializer =
                SpatializerWrapper(
                    context,
                    { updateCurrentAudioCapabilities() },
                    deviceIsTv
                )
        }
        val stickyIntent =
            context.registerReceiver(
                hdmiAudioPlugBroadcastReceiver,
                IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG),  
                null,
                handler
            )
        val spatializerChannelMasks =
            this.spatializerChannelMasks
        audioCapabilities =
            AudioCapabilities.getCapabilitiesInternal(
                context, stickyIntent, audioAttributes, routedDevice, spatializerChannelMasks
            )
        return audioCapabilities!!
    }

    fun unregister() {
        if (!registered) {
            return
        }
        audioCapabilities = null
        getAudioManager(context).unregisterAudioDeviceCallback(audioDeviceCallback)
        if (Build.VERSION.SDK_INT >= 32 && spatializer != null) {
            spatializer!!.release()
            spatializer = null
        }
        context.unregisterReceiver(hdmiAudioPlugBroadcastReceiver)
        externalSurroundSoundSettingObserver?.unregister()
        registered = false
    }

    private val spatializerChannelMasks: List<Int>
        get() = if (Build.VERSION.SDK_INT >= 32 && spatializer != null)
            spatializer!!.spatializedChannelMasks
        else
            listOf()

    private fun onNewAudioCapabilities(newAudioCapabilities: AudioCapabilities) {
        if (registered && newAudioCapabilities != audioCapabilities) {
            audioCapabilities = newAudioCapabilities
            listener.onAudioCapabilitiesChanged(newAudioCapabilities)
        }
    }

    private fun updateCurrentAudioCapabilities() {
        val spatializerChannelMasks =
            this.spatializerChannelMasks
        onNewAudioCapabilities(
            AudioCapabilities.getCapabilitiesInternal(
                context, audioAttributes, routedDevice, spatializerChannelMasks
            )
        )
    }

    private inner class HdmiAudioPlugBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (!isInitialStickyBroadcast) {
                val spatializerChannelMasks: List<Int> =
                    this@AudioCapabilitiesReceiver.spatializerChannelMasks
                onNewAudioCapabilities(
                    AudioCapabilities.getCapabilitiesInternal(
                        context, intent, audioAttributes, routedDevice, spatializerChannelMasks
                    )
                )
            }
        }
    }

    private inner class ExternalSurroundSoundSettingObserver(
        handler: Handler?,
        private val resolver: ContentResolver,
        private val settingUri: Uri
    ) : ContentObserver(handler) {
        fun register() {
            resolver.registerContentObserver(settingUri, false, this)
        }

        fun unregister() {
            resolver.unregisterContentObserver(this)
        }

        override fun onChange(selfChange: Boolean) {
            updateCurrentAudioCapabilities()
        }
    }

    private inner class AudioDeviceCallback : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo?>?) {
            updateCurrentAudioCapabilities()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo?>) {
            if (removedDevices.contains(routedDevice)) {
                routedDevice = null
            }
            updateCurrentAudioCapabilities()
        }
    }
}
