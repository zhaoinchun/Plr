package androidx.media3.exoplayer.util

import android.content.Context
import android.media.AudioFormat
import android.media.Spatializer
import android.media.Spatializer.OnSpatializerStateChangedListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioManagerCompat.getAudioManager

import androidx.media3.common.util.Util.getAudioTrackChannelConfig
import java.util.concurrent.Executor

@RequiresApi(32)
class SpatializerWrapper(
    context: Context?,
    spatializerChangedCallback: Runnable?,
    deviceIsTv: Boolean?
) {
    private val spatializer: Spatializer?
    val isSpatializationSupported: Boolean
    private val handler: Handler?
    private val listener: OnSpatializerStateChangedListener?

    init {
        val audioManager =
            if (context == null) null else getAudioManager(context)
        if (audioManager == null || (deviceIsTv != null && deviceIsTv)) {
            spatializer = null
            this.isSpatializationSupported = false
            handler = null
            listener = null
        }else {
            this.spatializer = audioManager.spatializer
            this.isSpatializationSupported =
                spatializer.getImmersiveAudioLevel() != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
            if (spatializerChangedCallback == null) {
                handler = null
                listener = null
            } else {
                this.handler = Handler(Looper.myLooper()!!)
                this.listener =
                    object : OnSpatializerStateChangedListener {
                        override fun onSpatializerEnabledChanged(
                            spatializer: Spatializer,
                            enabled: Boolean
                        ) {
                            spatializerChangedCallback.run()
                        }

                        override fun onSpatializerAvailableChanged(
                            spatializer: Spatializer,
                            available: Boolean
                        ) {
                            spatializerChangedCallback.run()
                        }
                    }
                spatializer.addOnSpatializerStateChangedListener(Executor { r: Runnable? ->
                    handler.post(
                        r!!
                    )
                }, listener)
            }
        }
    }

    val isSupportedAvailableAndEnabled: Boolean
        get() = spatializer != null && this.isSpatializationSupported && this.isAvailable && this.isEnabled

    val isAvailable: Boolean
        get() = spatializer != null && spatializer.isAvailable

    val isEnabled: Boolean
        get() = spatializer != null && spatializer.isEnabled

    fun canBeSpatialized(audioAttributes: AudioAttributes, format: Format): Boolean {
        if (!this.isSupportedAvailableAndEnabled) {
            return false
        }
        val linearChannelCount: Int
        if (format.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC) {
            // For E-AC3 JOC, the format is object based. When the channel count is 16, this maps to 12
            // linear channels and the rest are used for objects. See
            // https://github.com/google/ExoPlayer/pull/10322#discussion_r895265881
            linearChannelCount = if (format.channelCount == 16) 12 else format.channelCount
        } else if (format.sampleMimeType == MimeTypes.AUDIO_IAMF) {
            // IAMF with no channel count specified, assume 5.1 channels. This depends on
            // IamfDecoder.SPATIALIZED_OUTPUT_LAYOUT being set to AudioFormat.CHANNEL_OUT_5POINT1. Any
            // changes to that constant will require updates to this logic.
            linearChannelCount =
                if (format.channelCount == Format.NO_VALUE) 6 else format.channelCount
        } else if (format.sampleMimeType == MimeTypes.AUDIO_AC4) {
            // For AC-4 level 3 or level 4, the format may be object based. When the channel count is
            // 18 (level 3 17.1 OBI) or 21 (level 4 20.1 OBI), it is mapped to 24 linear channels (some
            // channels are used for metadata transfer).
            linearChannelCount =
                if (format.channelCount == 18 || format.channelCount == 21) 24 else format.channelCount
        } else {
            linearChannelCount = format.channelCount
        }

        val channelConfig =
            if (format.channelMask != Format.NO_VALUE && format.channelCount == linearChannelCount)
                format.channelMask
            else
                getAudioTrackChannelConfig(linearChannelCount)
        if (channelConfig == AudioFormat.CHANNEL_INVALID) {
            return false
        }
        val builder =
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelConfig)
        if (format.sampleRate != Format.NO_VALUE) {
            builder.setSampleRate(format.sampleRate)
        }
        return spatializer!!.canBeSpatialized(audioAttributes.platformAudioAttributes!!, builder.build())
    }

    val spatializedChannelMasks: List<Int>
        get() {
            if (!this.isSupportedAvailableAndEnabled) {
                return listOf()
            }
            if (Build.VERSION.SDK_INT >= 36) {
                return spatializer!! .spatializedChannelMasks
            }
            return listOf(AudioFormat.CHANNEL_OUT_5POINT1)
        }

    fun release() {
        if (spatializer == null || listener == null || handler == null) {
            return
        }
        spatializer.removeOnSpatializerStateChangedListener(listener)
        handler.removeCallbacksAndMessages( null)
    }
}
