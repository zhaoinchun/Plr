package androidx.media3.common

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.media3.common.util.Util.intToStringMaxRadix

data class AudioAttributes(
    var contentType: Int = C.AUDIO_CONTENT_TYPE_UNKNOWN,
    var flags: Int = 0,
    var usage: Int = C.USAGE_MEDIA,
    var allowedCapturePolicy: Int = C.ALLOW_CAPTURE_BY_ALL,
    var spatializationBehavior: Int = C.SPATIALIZATION_BEHAVIOR_AUTO,
    var isContentSpatialized: Boolean = false,
    var hapticChannelsMuted: Boolean = true,
) {
    var platformAudioAttributes: android.media.AudioAttributes? = null
        private set
        get() {
            if (field == null) {
                val builder = android.media.AudioAttributes.Builder()
                    .setContentType(contentType)
                    .setFlags(flags)
                    .setUsage(usage)
                if (Build.VERSION.SDK_INT >= 29) {
                    Api29.setAllowedCapturePolicy(
                        builder,
                        allowedCapturePolicy
                    )
                    Api29.setHapticChannelsMuted(
                        builder,
                        hapticChannelsMuted
                    )
                }
                if (Build.VERSION.SDK_INT >= 32) {
                    Api32.setSpatializationBehavior(builder, spatializationBehavior)
                    Api32.setIsContentSpatialized(builder, isContentSpatialized)
                }
                field = builder.build()
            }
            return field
        }

    val volumeControlStream: Int
        get() = this.streamTypeInternal

    fun toBundle(): Bundle {
        val bundle = Bundle()
        if (contentType != C.AUDIO_CONTENT_TYPE_UNKNOWN) {
            bundle.putInt(FIELD_CONTENT_TYPE, contentType)
        }
        if (flags != 0) {
            bundle.putInt(FIELD_FLAGS, flags)
        }
        if (usage != C.USAGE_MEDIA) {
            bundle.putInt(FIELD_USAGE, usage)
        }
        if (allowedCapturePolicy != C.ALLOW_CAPTURE_BY_ALL) {
            bundle.putInt(FIELD_ALLOWED_CAPTURE_POLICY, allowedCapturePolicy)
        }
        if (spatializationBehavior != C.SPATIALIZATION_BEHAVIOR_AUTO) {
            bundle.putInt(FIELD_SPATIALIZATION_BEHAVIOR, spatializationBehavior)
        }
        if (isContentSpatialized) {
            bundle.putBoolean(FIELD_IS_CONTENT_SPATIALIZED, isContentSpatialized)
        }
        if (!hapticChannelsMuted) {
            bundle.putBoolean(FIELD_HAPTIC_CHANNELS_MUTED, hapticChannelsMuted)
        }
        return bundle
    }


    private val streamTypeInternal: Int
        get() {
            if (Build.VERSION.SDK_INT >= 26) {
                val platformStreamType: Int
                try {
                    platformStreamType = this.platformAudioAttributes!!.volumeControlStream
                } catch (_: RuntimeException) {
                    // The platform method may throw if it doesn't recognize the configured values.
                    return C.STREAM_TYPE_MUSIC
                }
                return if (platformStreamType == AudioManager.USE_DEFAULT_STREAM_TYPE)
                    C.STREAM_TYPE_MUSIC
                else
                    platformStreamType
            }
            // Flags to stream type mapping
            if ((flags and C.FLAG_AUDIBILITY_ENFORCED) == C.FLAG_AUDIBILITY_ENFORCED) {
                return C.STREAM_TYPE_SYSTEM
            }
            // Usage to stream type mapping
            return when (usage) {
                C.USAGE_ASSISTANCE_SONIFICATION -> C.STREAM_TYPE_SYSTEM
                C.USAGE_VOICE_COMMUNICATION -> C.STREAM_TYPE_VOICE_CALL
                C.USAGE_VOICE_COMMUNICATION_SIGNALLING -> C.STREAM_TYPE_DTMF
                C.USAGE_ALARM -> C.STREAM_TYPE_ALARM
                C.USAGE_NOTIFICATION_RINGTONE -> C.STREAM_TYPE_RING
                C.USAGE_NOTIFICATION, C.USAGE_NOTIFICATION_COMMUNICATION_REQUEST, C.USAGE_NOTIFICATION_COMMUNICATION_INSTANT, C.USAGE_NOTIFICATION_COMMUNICATION_DELAYED, C.USAGE_NOTIFICATION_EVENT -> C.STREAM_TYPE_NOTIFICATION
                C.USAGE_ASSISTANCE_ACCESSIBILITY -> C.STREAM_TYPE_ACCESSIBILITY
                C.USAGE_MEDIA, C.USAGE_GAME, C.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, C.USAGE_ASSISTANT, C.USAGE_UNKNOWN -> C.STREAM_TYPE_MUSIC
                else -> C.STREAM_TYPE_MUSIC
            }
        }

    @RequiresApi(29)
    private object Api29 {
        @SuppressLint("WrongConstant") // Setting C.AudioAllowedCapturePolicy to platform API.
        fun setAllowedCapturePolicy(
            builder: android.media.AudioAttributes.Builder, allowedCapturePolicy: Int
        ) {
            builder.setAllowedCapturePolicy(allowedCapturePolicy)
        }

        fun setHapticChannelsMuted(
            builder: android.media.AudioAttributes.Builder, hapticChannelsMuted: Boolean
        ) {
            builder.setHapticChannelsMuted(hapticChannelsMuted)
        }
    }

    @RequiresApi(32)
    private object Api32 {
        fun setSpatializationBehavior(
            builder: android.media.AudioAttributes.Builder, spatializationBehavior: Int
        ) {
            builder.setSpatializationBehavior(spatializationBehavior)
        }

        fun setIsContentSpatialized(
            builder: android.media.AudioAttributes.Builder, isContentSpatialized: Boolean
        ) {
            builder.setIsContentSpatialized(isContentSpatialized)
        }
    }

    companion object {
        val DEFAULT: AudioAttributes = AudioAttributes()

        fun fromPlatformAudioAttributes(audioAttributes: AudioAttributes): AudioAttributes {
            val builder = AudioAttributes(
                contentType = audioAttributes.contentType,
                flags = audioAttributes.flags,
                usage = audioAttributes.usage
            )
            if (Build.VERSION.SDK_INT >= 29) {
                builder.allowedCapturePolicy = audioAttributes.allowedCapturePolicy
                builder.hapticChannelsMuted = audioAttributes.hapticChannelsMuted
            }
            if (Build.VERSION.SDK_INT >= 32) {
                builder.spatializationBehavior = audioAttributes.spatializationBehavior
                builder.isContentSpatialized = audioAttributes.isContentSpatialized
            }
            return builder
        }

        private val FIELD_CONTENT_TYPE = intToStringMaxRadix(0)
        private val FIELD_FLAGS = intToStringMaxRadix(1)
        private val FIELD_USAGE = intToStringMaxRadix(2)
        private val FIELD_ALLOWED_CAPTURE_POLICY = intToStringMaxRadix(3)
        private val FIELD_SPATIALIZATION_BEHAVIOR = intToStringMaxRadix(4)
        private val FIELD_IS_CONTENT_SPATIALIZED = intToStringMaxRadix(5)
        private val FIELD_HAPTIC_CHANNELS_MUTED = intToStringMaxRadix(6)

        fun fromBundle(bundle: Bundle): AudioAttributes {
            val builder = AudioAttributes()
            if (bundle.containsKey(FIELD_CONTENT_TYPE)) {
                builder.contentType = bundle.getInt(FIELD_CONTENT_TYPE)
            }
            if (bundle.containsKey(FIELD_FLAGS)) {
                builder.flags = bundle.getInt(FIELD_FLAGS)
            }
            if (bundle.containsKey(FIELD_USAGE)) {
                builder.usage = bundle.getInt(FIELD_USAGE)
            }
            if (bundle.containsKey(FIELD_ALLOWED_CAPTURE_POLICY)) {
                builder.allowedCapturePolicy = bundle.getInt(FIELD_ALLOWED_CAPTURE_POLICY)
            }
            if (bundle.containsKey(FIELD_SPATIALIZATION_BEHAVIOR)) {
                builder.spatializationBehavior = bundle.getInt(FIELD_SPATIALIZATION_BEHAVIOR)
            }
            if (bundle.containsKey(FIELD_IS_CONTENT_SPATIALIZED)) {
                builder.isContentSpatialized = bundle.getBoolean(FIELD_IS_CONTENT_SPATIALIZED)
            }
            if (bundle.containsKey(FIELD_HAPTIC_CHANNELS_MUTED)) {
                builder.hapticChannelsMuted = bundle.getBoolean(FIELD_HAPTIC_CHANNELS_MUTED)
            }
            return builder
        }
    }
}
