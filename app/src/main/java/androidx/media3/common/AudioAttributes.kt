package androidx.media3.common

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.media3.common.C.AudioAllowedCapturePolicy
import androidx.media3.common.C.AudioContentType
import androidx.media3.common.C.AudioFlags
import androidx.media3.common.C.AudioUsage
import androidx.media3.common.C.SpatializationBehavior
import androidx.media3.common.util.Util.intToStringMaxRadix

class AudioAttributes private constructor(
    @field:AudioContentType @param:AudioContentType val contentType: @AudioContentType Int,
    @field:AudioFlags @param:AudioFlags val flags: @AudioFlags Int,
    @field:AudioUsage @param:AudioUsage val usage: @AudioUsage Int,
    @field:AudioAllowedCapturePolicy @param:AudioAllowedCapturePolicy val allowedCapturePolicy: @AudioAllowedCapturePolicy Int,
    val spatializationBehavior: @SpatializationBehavior Int,
    val isContentSpatialized: Boolean,
    val hapticChannelsMuted: Boolean
) {
    class Builder {
        @AudioContentType
        private var contentType: @AudioContentType Int

        @AudioFlags
        private var flags: @AudioFlags Int

        @AudioUsage
        private var usage: @AudioUsage Int

        @AudioAllowedCapturePolicy
        private var allowedCapturePolicy: @AudioAllowedCapturePolicy Int
        private var spatializationBehavior: @SpatializationBehavior Int
        private var isContentSpatialized: Boolean
        private var hapticChannelsMuted: Boolean

        constructor() {
            contentType = C.AUDIO_CONTENT_TYPE_UNKNOWN
            flags = 0
            usage = C.USAGE_MEDIA
            allowedCapturePolicy = C.ALLOW_CAPTURE_BY_ALL
            spatializationBehavior = C.SPATIALIZATION_BEHAVIOR_AUTO
            isContentSpatialized = false
            hapticChannelsMuted = true
        }

        constructor(other: AudioAttributes) {
            contentType = other.contentType
            flags = other.flags
            usage = other.usage
            allowedCapturePolicy = other.allowedCapturePolicy
            spatializationBehavior = other.spatializationBehavior
            isContentSpatialized = other.isContentSpatialized
            hapticChannelsMuted = other.hapticChannelsMuted
        }

        fun setContentType(@AudioContentType contentType: @AudioContentType Int): Builder {
            this.contentType = contentType
            return this
        }

        fun setFlags(@AudioFlags flags: @AudioFlags Int): Builder {
            this.flags = flags
            return this
        }

        fun setUsage(@AudioUsage usage: @AudioUsage Int): Builder {
            this.usage = usage
            return this
        }

        fun setAllowedCapturePolicy(@AudioAllowedCapturePolicy allowedCapturePolicy: @AudioAllowedCapturePolicy Int): Builder {
            this.allowedCapturePolicy = allowedCapturePolicy
            return this
        }

        fun setSpatializationBehavior(spatializationBehavior: @SpatializationBehavior Int): Builder {
            this.spatializationBehavior = spatializationBehavior
            return this
        }

        fun setIsContentSpatialized(isContentSpatialized: Boolean): Builder {
            this.isContentSpatialized = isContentSpatialized
            return this
        }

        fun setHapticChannelsMuted(hapticChannelsMuted: Boolean): Builder {
            this.hapticChannelsMuted = hapticChannelsMuted
            return this
        }

        fun build(): AudioAttributes {
            return AudioAttributes(
                contentType,
                flags,
                usage,
                allowedCapturePolicy,
                spatializationBehavior,
                isContentSpatialized,
                hapticChannelsMuted
            )
        }
    }

    fun buildUpon(): Builder {
        return Builder(this)
    }

    var platformAudioAttributes: android.media.AudioAttributes? = null
        get() {
            if (field == null) {
                @SuppressLint("WrongConstant") val builder// Setting C.AudioContentType and C.AudioUsage to platform API.
                        =
                    android.media.AudioAttributes.Builder()
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
        private set

    @get:C.StreamType
    val volumeControlStream: @C.StreamType Int
        get() = this.streamTypeInternal

    @get:Deprecated("Use {@link #getVolumeControlStream()} instead.")
    @get:C.StreamType
    val streamType: @C.StreamType Int
        get() = this.streamTypeInternal

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as AudioAttributes
        return this.contentType == other.contentType && this.flags == other.flags && this.usage == other.usage && this.allowedCapturePolicy == other.allowedCapturePolicy && this.spatializationBehavior == other.spatializationBehavior && this.isContentSpatialized == other.isContentSpatialized && this.hapticChannelsMuted == other.hapticChannelsMuted
    }

    override fun hashCode(): Int {
        var result = 17
        result = 31 * result + contentType
        result = 31 * result + flags
        result = 31 * result + usage
        result = 31 * result + allowedCapturePolicy
        result = 31 * result + spatializationBehavior
        result = 31 * result + (if (isContentSpatialized) 1 else 0)
        result = 31 * result + (if (hapticChannelsMuted) 1 else 0)
        return result
    }

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

    @get:C.StreamType
    private val streamTypeInternal: @C.StreamType Int
        get() {
            if (Build.VERSION.SDK_INT >= 26) {
                val platformStreamType: Int
                try {
                    platformStreamType = this.platformAudioAttributes!!.volumeControlStream
                } catch (e: RuntimeException) {
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
            when (usage) {
                C.USAGE_ASSISTANCE_SONIFICATION -> return C.STREAM_TYPE_SYSTEM
                C.USAGE_VOICE_COMMUNICATION -> return C.STREAM_TYPE_VOICE_CALL
                C.USAGE_VOICE_COMMUNICATION_SIGNALLING -> return C.STREAM_TYPE_DTMF
                C.USAGE_ALARM -> return C.STREAM_TYPE_ALARM
                C.USAGE_NOTIFICATION_RINGTONE -> return C.STREAM_TYPE_RING
                C.USAGE_NOTIFICATION, C.USAGE_NOTIFICATION_COMMUNICATION_REQUEST, C.USAGE_NOTIFICATION_COMMUNICATION_INSTANT, C.USAGE_NOTIFICATION_COMMUNICATION_DELAYED, C.USAGE_NOTIFICATION_EVENT -> return C.STREAM_TYPE_NOTIFICATION
                C.USAGE_ASSISTANCE_ACCESSIBILITY -> return C.STREAM_TYPE_ACCESSIBILITY
                C.USAGE_MEDIA, C.USAGE_GAME, C.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, C.USAGE_ASSISTANT, C.USAGE_UNKNOWN -> return C.STREAM_TYPE_MUSIC
                else -> return C.STREAM_TYPE_MUSIC
            }
        }

    @RequiresApi(29)
    private object Api29 {
        @SuppressLint("WrongConstant") // Setting C.AudioAllowedCapturePolicy to platform API.
        fun setAllowedCapturePolicy(
            builder: android.media.AudioAttributes.Builder,
            @AudioAllowedCapturePolicy allowedCapturePolicy: @AudioAllowedCapturePolicy Int
        ) {
            builder.setAllowedCapturePolicy(allowedCapturePolicy)
        }

        fun setHapticChannelsMuted(builder: android.media.AudioAttributes.Builder, hapticChannelsMuted: Boolean) {
            builder.setHapticChannelsMuted(hapticChannelsMuted)
        }
    }

    @RequiresApi(32)
    private object Api32 {
        @SuppressLint("WrongConstant") // Setting C.SpatializationBehavior to platform API.
        fun setSpatializationBehavior(
            builder:android.media.AudioAttributes. Builder,
            spatializationBehavior: @SpatializationBehavior Int
        ) {
            builder.setSpatializationBehavior(spatializationBehavior)
        }

        fun setIsContentSpatialized(builder:android.media.AudioAttributes.Builder, isContentSpatialized: Boolean) {
            builder.setIsContentSpatialized(isContentSpatialized)
        }
    }

    companion object {
        val DEFAULT: AudioAttributes = Builder().build()

        @SuppressLint("WrongConstant") // Assigning platform constants as C.AudioAllowedCapturePolicy
        fun fromPlatformAudioAttributes(audioAttributes: AudioAttributes): AudioAttributes {
            val builder =
                Builder()
                    .setContentType(audioAttributes.contentType)
                    .setFlags(audioAttributes.flags)
                    .setUsage(audioAttributes.usage)
            if (Build.VERSION.SDK_INT >= 29) {
                builder.setAllowedCapturePolicy(audioAttributes.allowedCapturePolicy)
                builder.setHapticChannelsMuted(audioAttributes.hapticChannelsMuted)
            }
            if (Build.VERSION.SDK_INT >= 32) {
                builder.setSpatializationBehavior(audioAttributes.spatializationBehavior)
                builder.setIsContentSpatialized(audioAttributes.isContentSpatialized)
            }
            return builder.build()
        }

        private val FIELD_CONTENT_TYPE = intToStringMaxRadix(0)
        private val FIELD_FLAGS = intToStringMaxRadix(1)
        private val FIELD_USAGE = intToStringMaxRadix(2)
        private val FIELD_ALLOWED_CAPTURE_POLICY = intToStringMaxRadix(3)
        private val FIELD_SPATIALIZATION_BEHAVIOR = intToStringMaxRadix(4)
        private val FIELD_IS_CONTENT_SPATIALIZED = intToStringMaxRadix(5)
        private val FIELD_HAPTIC_CHANNELS_MUTED = intToStringMaxRadix(6)

        fun fromBundle(bundle: Bundle): AudioAttributes {
            val builder = Builder()
            if (bundle.containsKey(FIELD_CONTENT_TYPE)) {
                builder.setContentType(bundle.getInt(FIELD_CONTENT_TYPE))
            }
            if (bundle.containsKey(FIELD_FLAGS)) {
                builder.setFlags(bundle.getInt(FIELD_FLAGS))
            }
            if (bundle.containsKey(FIELD_USAGE)) {
                builder.setUsage(bundle.getInt(FIELD_USAGE))
            }
            if (bundle.containsKey(FIELD_ALLOWED_CAPTURE_POLICY)) {
                builder.setAllowedCapturePolicy(bundle.getInt(FIELD_ALLOWED_CAPTURE_POLICY))
            }
            if (bundle.containsKey(FIELD_SPATIALIZATION_BEHAVIOR)) {
                builder.setSpatializationBehavior(bundle.getInt(FIELD_SPATIALIZATION_BEHAVIOR))
            }
            if (bundle.containsKey(FIELD_IS_CONTENT_SPATIALIZED)) {
                builder.setIsContentSpatialized(bundle.getBoolean(FIELD_IS_CONTENT_SPATIALIZED))
            }
            if (bundle.containsKey(FIELD_HAPTIC_CHANNELS_MUTED)) {
                builder.setHapticChannelsMuted(bundle.getBoolean(FIELD_HAPTIC_CHANNELS_MUTED))
            }
            return builder.build()
        }
    }
}
