package androidx.media3.common

import android.content.Context
import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.annotation.IntDef
import androidx.media3.common.C.RoleFlags
import androidx.media3.common.C.SelectionFlags
import androidx.media3.common.util.BundleCollectionUtil
import androidx.media3.common.util.Util.intToStringMaxRadix
import androidx.media3.common.util.Util.normalizeLanguageCode
import com.google.common.base.Function
import com.google.common.base.MoreObjects
import com.google.common.primitives.Ints

open class TrackSelectionParameters protected constructor(builder: Builder) {
    val maxVideoWidth: Int
    val maxVideoHeight: Int
    val maxVideoFrameRate: Int
    val maxVideoBitrate: Int
    val minVideoWidth: Int
    val minVideoHeight: Int
    val minVideoFrameRate: Int
    val minVideoBitrate: Int
    val viewportWidth: Int
    val viewportHeight: Int
    val isViewportSizeLimitedByPhysicalDisplaySize: Boolean
    val viewportOrientationMayChange: Boolean
    val preferredVideoMimeTypes: List<String>
    val preferredVideoLabels: List<String>
    val preferredVideoLanguages: List<String>

    @RoleFlags
    val preferredVideoRoleFlags: @RoleFlags Int
    val preferredAudioLanguages: List<String>
    val preferredAudioLabels: List<String>

    @RoleFlags
    val preferredAudioRoleFlags: @RoleFlags Int
    val maxAudioChannelCount: Int
    val maxAudioBitrate: Int
    val preferredAudioMimeTypes: List<String>
    val audioOffloadPreferences: AudioOffloadPreferences
    val selectTextByDefault: Boolean
    val preferredTextLanguages: List<String>
    val preferredTextLabels: List<String>

    @RoleFlags
    val preferredTextRoleFlags: @RoleFlags Int
    val usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager: Boolean

    @SelectionFlags
    val ignoredTextSelectionFlags: @SelectionFlags Int
    val selectUndeterminedTextLanguage: Boolean
    val isPrioritizeImageOverVideoEnabled: Boolean
    val forceLowestBitrate: Boolean
    val forceHighestSupportedBitrate: Boolean
    val overrides: MutableMap<TrackGroup, TrackSelectionOverride>
    val disabledTrackTypes: Set<Int>

    open fun buildUpon(): Builder {
        return Builder(this)
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as TrackSelectionParameters
        // Video
        return maxVideoWidth == other.maxVideoWidth && maxVideoHeight == other.maxVideoHeight && maxVideoFrameRate == other.maxVideoFrameRate && maxVideoBitrate == other.maxVideoBitrate && minVideoWidth == other.minVideoWidth && minVideoHeight == other.minVideoHeight && minVideoFrameRate == other.minVideoFrameRate && minVideoBitrate == other.minVideoBitrate && viewportOrientationMayChange == other.viewportOrientationMayChange && viewportWidth == other.viewportWidth && viewportHeight == other.viewportHeight && (isViewportSizeLimitedByPhysicalDisplaySize
                == other.isViewportSizeLimitedByPhysicalDisplaySize) && preferredVideoMimeTypes == other.preferredVideoMimeTypes
                && preferredVideoLabels == other.preferredVideoLabels
                && preferredVideoLanguages == other.preferredVideoLanguages
                && preferredVideoRoleFlags == other.preferredVideoRoleFlags // Audio
                && preferredAudioLanguages == other.preferredAudioLanguages
                && preferredAudioRoleFlags == other.preferredAudioRoleFlags && maxAudioChannelCount == other.maxAudioChannelCount && preferredAudioLabels == other.preferredAudioLabels
                && maxAudioBitrate == other.maxAudioBitrate && preferredAudioMimeTypes == other.preferredAudioMimeTypes
                && audioOffloadPreferences == other.audioOffloadPreferences // Text
                && selectTextByDefault == other.selectTextByDefault && preferredTextLabels == other.preferredTextLabels
                && preferredTextLanguages == other.preferredTextLanguages
                && preferredTextRoleFlags == other.preferredTextRoleFlags && (usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager
                == other.usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager) && ignoredTextSelectionFlags == other.ignoredTextSelectionFlags && selectUndeterminedTextLanguage == other.selectUndeterminedTextLanguage // Image
                && isPrioritizeImageOverVideoEnabled == other.isPrioritizeImageOverVideoEnabled // General
                && forceLowestBitrate == other.forceLowestBitrate && forceHighestSupportedBitrate == other.forceHighestSupportedBitrate && overrides == other.overrides
                && disabledTrackTypes == other.disabledTrackTypes
    }

    override fun hashCode(): Int {
        var result = 1
        // Video
        result = 31 * result + maxVideoWidth
        result = 31 * result + maxVideoHeight
        result = 31 * result + maxVideoFrameRate
        result = 31 * result + maxVideoBitrate
        result = 31 * result + minVideoWidth
        result = 31 * result + minVideoHeight
        result = 31 * result + minVideoFrameRate
        result = 31 * result + minVideoBitrate
        result = 31 * result + (if (viewportOrientationMayChange) 1 else 0)
        result = 31 * result + viewportWidth
        result = 31 * result + viewportHeight
        result = 31 * result + (if (isViewportSizeLimitedByPhysicalDisplaySize) 1 else 0)
        result = 31 * result + preferredVideoMimeTypes.hashCode()
        result = 31 * result + preferredVideoLabels.hashCode()
        result = 31 * result + preferredVideoLanguages.hashCode()
        result = 31 * result + preferredVideoRoleFlags
        // Audio
        result = 31 * result + preferredAudioLanguages.hashCode()
        result = 31 * result + preferredAudioRoleFlags
        result = 31 * result + maxAudioChannelCount
        result = 31 * result + preferredAudioLabels.hashCode()
        result = 31 * result + maxAudioBitrate
        result = 31 * result + preferredAudioMimeTypes.hashCode()
        result = 31 * result + audioOffloadPreferences.hashCode()
        // Text
        result = 31 * result + (if (selectTextByDefault) 1 else 0)
        result = 31 * result + preferredTextLanguages.hashCode()
        result = 31 * result + preferredTextRoleFlags
        result =
            31 * result + (if (usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager) 1 else 0)
        result = 31 * result + preferredTextLabels.hashCode()
        result = 31 * result + ignoredTextSelectionFlags
        result = 31 * result + (if (selectUndeterminedTextLanguage) 1 else 0)
        // Image
        result = 31 * result + (if (isPrioritizeImageOverVideoEnabled) 1 else 0)
        // General
        result = 31 * result + (if (forceLowestBitrate) 1 else 0)
        result = 31 * result + (if (forceHighestSupportedBitrate) 1 else 0)
        result = 31 * result + overrides.hashCode()
        result = 31 * result + disabledTrackTypes.hashCode()
        return result
    }

    init {
        // Video
        this.maxVideoWidth = builder.maxVideoWidth
        this.maxVideoHeight = builder.maxVideoHeight
        this.maxVideoFrameRate = builder.maxVideoFrameRate
        this.maxVideoBitrate = builder.maxVideoBitrate
        this.minVideoWidth = builder.minVideoWidth
        this.minVideoHeight = builder.minVideoHeight
        this.minVideoFrameRate = builder.minVideoFrameRate
        this.minVideoBitrate = builder.minVideoBitrate
        this.viewportWidth = builder.viewportWidth
        this.viewportHeight = builder.viewportHeight
        this.isViewportSizeLimitedByPhysicalDisplaySize =
            builder.isViewportSizeLimitedByPhysicalDisplaySize
        this.viewportOrientationMayChange = builder.viewportOrientationMayChange
        this.preferredVideoMimeTypes = builder.preferredVideoMimeTypes!!
        this.preferredVideoLabels = builder.preferredVideoLabels!!
        this.preferredVideoLanguages = builder.preferredVideoLanguages!!
        this.preferredVideoRoleFlags = builder.preferredVideoRoleFlags
        // Audio
        this.preferredAudioLanguages = builder.preferredAudioLanguages!!
        this.preferredAudioRoleFlags = builder.preferredAudioRoleFlags
        this.maxAudioChannelCount = builder.maxAudioChannelCount
        this.preferredAudioLabels = builder.preferredAudioLabels!!
        this.maxAudioBitrate = builder.maxAudioBitrate
        this.preferredAudioMimeTypes = builder.preferredAudioMimeTypes!!
        this.audioOffloadPreferences = builder.audioOffloadPreferences!!
        // Text
        this.selectTextByDefault = builder.selectTextByDefault
        this.preferredTextLanguages = builder.preferredTextLanguages!!
        this.preferredTextRoleFlags = builder.preferredTextRoleFlags
        this.usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager =
            builder.usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager
        this.preferredTextLabels = builder.preferredTextLabels!!
        this.ignoredTextSelectionFlags = builder.ignoredTextSelectionFlags
        this.selectUndeterminedTextLanguage = builder.selectUndeterminedTextLanguage
        // Image
        this.isPrioritizeImageOverVideoEnabled = builder.isPrioritizeImageOverVideoEnabled
        // General
        this.forceLowestBitrate = builder.forceLowestBitrate
        this.forceHighestSupportedBitrate = builder.forceHighestSupportedBitrate
        this.overrides = builder.overrides?.toMutableMap() ?: mutableMapOf()
        this.disabledTrackTypes = builder.disabledTrackTypes?.toSet() ?: emptySet()
    }

    @CallSuper
   open fun toBundle(): Bundle {
        val bundle = Bundle()

        // Video
        bundle.putInt(FIELD_MAX_VIDEO_WIDTH, maxVideoWidth)
        bundle.putInt(FIELD_MAX_VIDEO_HEIGHT, maxVideoHeight)
        bundle.putInt(FIELD_MAX_VIDEO_FRAMERATE, maxVideoFrameRate)
        bundle.putInt(FIELD_MAX_VIDEO_BITRATE, maxVideoBitrate)
        bundle.putInt(FIELD_MIN_VIDEO_WIDTH, minVideoWidth)
        bundle.putInt(FIELD_MIN_VIDEO_HEIGHT, minVideoHeight)
        bundle.putInt(FIELD_MIN_VIDEO_FRAMERATE, minVideoFrameRate)
        bundle.putInt(FIELD_MIN_VIDEO_BITRATE, minVideoBitrate)
        bundle.putInt(FIELD_VIEWPORT_WIDTH, viewportWidth)
        bundle.putInt(FIELD_VIEWPORT_HEIGHT, viewportHeight)
        bundle.putBoolean(
            FIELD_IS_VIEWPORT_SIZE_LIMITED_BY_PHYSICAL_DISPLAY_SIZE,
            isViewportSizeLimitedByPhysicalDisplaySize
        )
        bundle.putBoolean(FIELD_VIEWPORT_ORIENTATION_MAY_CHANGE, viewportOrientationMayChange)
        bundle.putStringArray(
            FIELD_PREFERRED_VIDEO_MIMETYPES, preferredVideoMimeTypes.toTypedArray<String>()
        )
        bundle.putStringArray(
            FIELD_PREFERRED_VIDEO_LANGUAGES, preferredVideoLanguages.toTypedArray<String>()
        )
        bundle.putStringArray(
            FIELD_PREFERRED_VIDEO_LABELS, preferredVideoLabels.toTypedArray<String>()
        )
        bundle.putInt(FIELD_PREFERRED_VIDEO_ROLE_FLAGS, preferredVideoRoleFlags)
        // Audio
        bundle.putStringArray(
            FIELD_PREFERRED_AUDIO_LANGUAGES, preferredAudioLanguages.toTypedArray<String>()
        )
        bundle.putInt(FIELD_PREFERRED_AUDIO_ROLE_FLAGS, preferredAudioRoleFlags)
        bundle.putInt(FIELD_MAX_AUDIO_CHANNEL_COUNT, maxAudioChannelCount)
        bundle.putInt(FIELD_MAX_AUDIO_BITRATE, maxAudioBitrate)
        bundle.putStringArray(
            FIELD_PREFERRED_AUDIO_LABELS, preferredAudioLabels.toTypedArray<String>()
        )
        bundle.putStringArray(
            FIELD_PREFERRED_AUDIO_MIME_TYPES, preferredAudioMimeTypes.toTypedArray<String>()
        )
        // Text
        bundle.putBoolean(FIELD_SELECT_TEXT_BY_DEFAULT, selectTextByDefault)
        bundle.putStringArray(
            FIELD_PREFERRED_TEXT_LANGUAGES, preferredTextLanguages.toTypedArray<String>()
        )
        bundle.putInt(FIELD_PREFERRED_TEXT_ROLE_FLAGS, preferredTextRoleFlags)
        bundle.putBoolean(
            FIELD_USE_PREFERRED_TEXT_LANGUAGES_AND_ROLE_FLAGS_FROM_CAPTIONING_MANAGER,
            usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager
        )
        bundle.putStringArray(
            FIELD_PREFERRED_TEXT_LABELS,
            preferredTextLabels.toTypedArray<String>()
        )
        bundle.putInt(FIELD_IGNORED_TEXT_SELECTION_FLAGS, ignoredTextSelectionFlags)
        bundle.putBoolean(FIELD_SELECT_UNDETERMINED_TEXT_LANGUAGE, selectUndeterminedTextLanguage)
        bundle.putInt(FIELD_AUDIO_OFFLOAD_MODE_PREFERENCE, audioOffloadPreferences.audioOffloadMode)
        bundle.putBoolean(
            FIELD_IS_GAPLESS_SUPPORT_REQUIRED, audioOffloadPreferences.isGaplessSupportRequired
        )
        bundle.putBoolean(
            FIELD_IS_SPEED_CHANGE_SUPPORT_REQUIRED,
            audioOffloadPreferences.isSpeedChangeSupportRequired
        )
        bundle.putBundle(FIELD_AUDIO_OFFLOAD_PREFERENCES, audioOffloadPreferences.toBundle())
        // Image
        bundle.putBoolean(
            FIELD_IS_PREFER_IMAGE_OVER_VIDEO_ENABLED,
            isPrioritizeImageOverVideoEnabled
        )
        // General
        bundle.putBoolean(FIELD_FORCE_LOWEST_BITRATE, forceLowestBitrate)
        bundle.putBoolean(FIELD_FORCE_HIGHEST_SUPPORTED_BITRATE, forceHighestSupportedBitrate)
        bundle.putParcelableArrayList(
            FIELD_SELECTION_OVERRIDES,
            BundleCollectionUtil.toBundleArrayList(
                overrides.values,
                Function { obj: TrackSelectionOverride -> obj.toBundle() })
        )
        bundle.putIntArray(FIELD_DISABLED_TRACK_TYPE, Ints.toArray(disabledTrackTypes))

        return bundle
    }

    class AudioOffloadPreferences private constructor(builder: Builder) {
        @MustBeDocumented
        @Retention(AnnotationRetention.SOURCE)
        @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
        @IntDef(
            value = [AUDIO_OFFLOAD_MODE_REQUIRED, AUDIO_OFFLOAD_MODE_ENABLED, AUDIO_OFFLOAD_MODE_DISABLED]
        )
        annotation class AudioOffloadMode

        class Builder {
            var audioOffloadMode: @AudioOffloadMode Int
            var isGaplessSupportRequired = false
            var isSpeedChangeSupportRequired = false

            init {
                this.audioOffloadMode = AUDIO_OFFLOAD_MODE_DISABLED
            }

            fun setAudioOffloadMode(audioOffloadMode: @AudioOffloadMode Int): Builder {
                this.audioOffloadMode = audioOffloadMode
                return this
            }

            fun setIsGaplessSupportRequired(isGaplessSupportRequired: Boolean): Builder {
                this.isGaplessSupportRequired = isGaplessSupportRequired
                return this
            }

            fun setIsSpeedChangeSupportRequired(isSpeedChangeSupportRequired: Boolean): Builder {
                this.isSpeedChangeSupportRequired = isSpeedChangeSupportRequired
                return this
            }

            fun build(): AudioOffloadPreferences {
                return AudioOffloadPreferences(this)
            }
        }

        val audioOffloadMode: @AudioOffloadMode Int
        val isGaplessSupportRequired: Boolean
        val isSpeedChangeSupportRequired: Boolean

        fun buildUpon(): Builder {
            return Builder()
                .setAudioOffloadMode(audioOffloadMode)
                .setIsGaplessSupportRequired(isGaplessSupportRequired)
                .setIsSpeedChangeSupportRequired(isSpeedChangeSupportRequired)
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj == null || javaClass != obj.javaClass) {
                return false
            }
            val other = obj as AudioOffloadPreferences
            return audioOffloadMode == other.audioOffloadMode && isGaplessSupportRequired == other.isGaplessSupportRequired && isSpeedChangeSupportRequired == other.isSpeedChangeSupportRequired
        }

        override fun hashCode(): Int {
            var result = 1
            result = 31 * result + audioOffloadMode
            result = 31 * result + (if (isGaplessSupportRequired) 1 else 0)
            result = 31 * result + (if (isSpeedChangeSupportRequired) 1 else 0)
            return result
        }

        init {
            this.audioOffloadMode = builder.audioOffloadMode
            this.isGaplessSupportRequired = builder.isGaplessSupportRequired
            this.isSpeedChangeSupportRequired = builder.isSpeedChangeSupportRequired
        }

        fun toBundle(): Bundle {
            val bundle = Bundle()
            bundle.putInt(FIELD_AUDIO_OFFLOAD_MODE_PREFERENCE, audioOffloadMode)
            bundle.putBoolean(FIELD_IS_GAPLESS_SUPPORT_REQUIRED, isGaplessSupportRequired)
            bundle.putBoolean(FIELD_IS_SPEED_CHANGE_SUPPORT_REQUIRED, isSpeedChangeSupportRequired)
            return bundle
        }

        companion object {
            const val AUDIO_OFFLOAD_MODE_REQUIRED: Int = 2
            const val AUDIO_OFFLOAD_MODE_ENABLED: Int = 1
            const val AUDIO_OFFLOAD_MODE_DISABLED: Int = 0
            val DEFAULT: AudioOffloadPreferences = Builder().build()

            private val FIELD_AUDIO_OFFLOAD_MODE_PREFERENCE = intToStringMaxRadix(1)
            private val FIELD_IS_GAPLESS_SUPPORT_REQUIRED = intToStringMaxRadix(2)
            private val FIELD_IS_SPEED_CHANGE_SUPPORT_REQUIRED = intToStringMaxRadix(3)

            fun fromBundle(bundle: Bundle): AudioOffloadPreferences {
                return Builder()
                    .setAudioOffloadMode(
                        bundle.getInt(FIELD_AUDIO_OFFLOAD_MODE_PREFERENCE, DEFAULT.audioOffloadMode)
                    )
                    .setIsGaplessSupportRequired(
                        bundle.getBoolean(
                            FIELD_IS_GAPLESS_SUPPORT_REQUIRED, DEFAULT.isGaplessSupportRequired
                        )
                    )
                    .setIsSpeedChangeSupportRequired(
                        bundle.getBoolean(
                            FIELD_IS_SPEED_CHANGE_SUPPORT_REQUIRED,
                            DEFAULT.isSpeedChangeSupportRequired
                        )
                    )
                    .build()
            }
        }
    }

    open class Builder {
        // Video
        var maxVideoWidth = 0
        var maxVideoHeight = 0
        var maxVideoFrameRate = 0
        var maxVideoBitrate = 0
        var minVideoWidth = 0
        var minVideoHeight = 0
        var minVideoFrameRate = 0
        var minVideoBitrate = 0
        var viewportWidth = 0
        var viewportHeight = 0
        var isViewportSizeLimitedByPhysicalDisplaySize = false
        var viewportOrientationMayChange = false
        var preferredVideoMimeTypes: List<String> = listOf()
        var preferredVideoLabels: List<String> = listOf()
        var preferredVideoLanguages: List<String> = listOf()

        @RoleFlags
        var preferredVideoRoleFlags: @RoleFlags Int = 0

        // Audio
        var preferredAudioLanguages: List<String> = listOf()
        var preferredAudioLabels: List<String> = listOf()

        @RoleFlags
        var preferredAudioRoleFlags: @RoleFlags Int = 0
        var maxAudioChannelCount = 0
        var maxAudioBitrate = 0
        var preferredAudioMimeTypes: List<String> = listOf()
        var audioOffloadPreferences: AudioOffloadPreferences? = null

        // Text
        var selectTextByDefault = false
        var preferredTextLanguages: List<String> = listOf()

        @RoleFlags
        var preferredTextRoleFlags: @RoleFlags Int = 0
        var usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager = false
        var preferredTextLabels: List<String> = listOf()

        @SelectionFlags
        var ignoredTextSelectionFlags: @SelectionFlags Int = 0
        var selectUndeterminedTextLanguage = false

        // Image
        var isPrioritizeImageOverVideoEnabled = false

        // General
        var forceLowestBitrate = false
        var forceHighestSupportedBitrate = false
        lateinit var overrides: MutableMap<TrackGroup, TrackSelectionOverride>
        var disabledTrackTypes: MutableSet<Int> = mutableSetOf()

        constructor() {
            // Video
            maxVideoWidth = Int.MAX_VALUE
            maxVideoHeight = Int.MAX_VALUE
            maxVideoFrameRate = Int.MAX_VALUE
            maxVideoBitrate = Int.MAX_VALUE
            viewportWidth = Int.MAX_VALUE
            viewportHeight = Int.MAX_VALUE
            isViewportSizeLimitedByPhysicalDisplaySize = true
            viewportOrientationMayChange = true
            preferredVideoMimeTypes = listOf()
            preferredVideoLabels = listOf()
            preferredVideoLanguages = listOf()
            preferredVideoRoleFlags = 0
            // Audio
            preferredAudioLanguages = listOf()
            preferredAudioLabels = listOf()
            preferredAudioRoleFlags = 0
            maxAudioChannelCount = Int.MAX_VALUE
            maxAudioBitrate = Int.MAX_VALUE
            preferredAudioMimeTypes = listOf()
            audioOffloadPreferences = AudioOffloadPreferences.DEFAULT
            // Text
            selectTextByDefault = false
            preferredTextLanguages = listOf()
            preferredTextRoleFlags = 0
            usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager = true
            preferredTextLabels = listOf()
            ignoredTextSelectionFlags = 0
            selectUndeterminedTextLanguage = false
            // Image
            isPrioritizeImageOverVideoEnabled = false
            // General
            forceLowestBitrate = false
            forceHighestSupportedBitrate = false
            overrides = mutableMapOf()
            disabledTrackTypes = mutableSetOf()
        }

        constructor(initialValues: TrackSelectionParameters) {
            init(initialValues)
        }

        constructor(bundle: Bundle) {
            // Video
            maxVideoWidth = bundle.getInt(FIELD_MAX_VIDEO_WIDTH, DEFAULT.maxVideoWidth)
            maxVideoHeight = bundle.getInt(FIELD_MAX_VIDEO_HEIGHT, DEFAULT.maxVideoHeight)
            maxVideoFrameRate = bundle.getInt(FIELD_MAX_VIDEO_FRAMERATE, DEFAULT.maxVideoFrameRate)
            maxVideoBitrate = bundle.getInt(FIELD_MAX_VIDEO_BITRATE, DEFAULT.maxVideoBitrate)
            minVideoWidth = bundle.getInt(FIELD_MIN_VIDEO_WIDTH, DEFAULT.minVideoWidth)
            minVideoHeight = bundle.getInt(FIELD_MIN_VIDEO_HEIGHT, DEFAULT.minVideoHeight)
            minVideoFrameRate = bundle.getInt(FIELD_MIN_VIDEO_FRAMERATE, DEFAULT.minVideoFrameRate)
            minVideoBitrate = bundle.getInt(FIELD_MIN_VIDEO_BITRATE, DEFAULT.minVideoBitrate)
            viewportWidth = bundle.getInt(FIELD_VIEWPORT_WIDTH, DEFAULT.viewportWidth)
            viewportHeight = bundle.getInt(FIELD_VIEWPORT_HEIGHT, DEFAULT.viewportHeight)
            isViewportSizeLimitedByPhysicalDisplaySize =
                viewportWidth == Int.MAX_VALUE && viewportHeight == Int.MAX_VALUE && bundle.getBoolean(
                    FIELD_IS_VIEWPORT_SIZE_LIMITED_BY_PHYSICAL_DISPLAY_SIZE,
                    DEFAULT.isViewportSizeLimitedByPhysicalDisplaySize
                )
            viewportOrientationMayChange =
                bundle.getBoolean(
                    FIELD_VIEWPORT_ORIENTATION_MAY_CHANGE, DEFAULT.viewportOrientationMayChange
                )
            preferredVideoMimeTypes =
                bundle.getStringArray(FIELD_PREFERRED_VIDEO_MIMETYPES)?.toList() ?: listOf()
            preferredVideoLabels =
                bundle.getStringArray(FIELD_PREFERRED_VIDEO_LABELS)?.toList() ?: listOf()
            preferredVideoLanguages =
                bundle.getStringArray(FIELD_PREFERRED_VIDEO_LANGUAGES)?.toList() ?: listOf()
            preferredVideoRoleFlags =
                bundle.getInt(FIELD_PREFERRED_VIDEO_ROLE_FLAGS, DEFAULT.preferredVideoRoleFlags)
            // Audio
            val preferredAudioLanguages1 = bundle.getStringArray(FIELD_PREFERRED_AUDIO_LANGUAGES)
            preferredAudioLanguages = preferredAudioLanguages1?.map {
                normalizeLanguageCode(it)
            }?:listOf()
            preferredAudioLabels =
                bundle.getStringArray(FIELD_PREFERRED_AUDIO_LABELS)?.toList() ?: listOf()
            preferredAudioRoleFlags =
                bundle.getInt(FIELD_PREFERRED_AUDIO_ROLE_FLAGS, DEFAULT.preferredAudioRoleFlags)
            maxAudioChannelCount =
                bundle.getInt(FIELD_MAX_AUDIO_CHANNEL_COUNT, DEFAULT.maxAudioChannelCount)
            maxAudioBitrate = bundle.getInt(FIELD_MAX_AUDIO_BITRATE, DEFAULT.maxAudioBitrate)
            preferredAudioMimeTypes =
                bundle.getStringArray(FIELD_PREFERRED_AUDIO_MIME_TYPES)?.toList() ?: listOf()
            audioOffloadPreferences = getAudioOffloadPreferencesFromBundle(bundle)
            // Text
            selectTextByDefault =
                bundle.getBoolean(FIELD_SELECT_TEXT_BY_DEFAULT, DEFAULT.selectTextByDefault)
            preferredTextLanguages = bundle.getStringArray(FIELD_PREFERRED_TEXT_LANGUAGES)
                ?.map { normalizeLanguageCode(it) }?:listOf()
            preferredTextRoleFlags =
                bundle.getInt(FIELD_PREFERRED_TEXT_ROLE_FLAGS, DEFAULT.preferredTextRoleFlags)
            usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager =
                preferredTextLanguages!!.isEmpty()
                        && preferredTextRoleFlags == 0 && bundle.getBoolean(
                    FIELD_USE_PREFERRED_TEXT_LANGUAGES_AND_ROLE_FLAGS_FROM_CAPTIONING_MANAGER,
                    DEFAULT.usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager
                )
            ignoredTextSelectionFlags =
                bundle.getInt(FIELD_IGNORED_TEXT_SELECTION_FLAGS, DEFAULT.ignoredTextSelectionFlags)
            preferredTextLabels =
                bundle.getStringArray(FIELD_PREFERRED_TEXT_LABELS)?.toList() ?: listOf()
            selectUndeterminedTextLanguage =
                bundle.getBoolean(
                    FIELD_SELECT_UNDETERMINED_TEXT_LANGUAGE, DEFAULT.selectUndeterminedTextLanguage
                )
            // Image
            isPrioritizeImageOverVideoEnabled =
                bundle.getBoolean(
                    FIELD_IS_PREFER_IMAGE_OVER_VIDEO_ENABLED,
                    DEFAULT.isPrioritizeImageOverVideoEnabled
                )

            // General
            forceLowestBitrate =
                bundle.getBoolean(FIELD_FORCE_LOWEST_BITRATE, DEFAULT.forceLowestBitrate)
            forceHighestSupportedBitrate =
                bundle.getBoolean(
                    FIELD_FORCE_HIGHEST_SUPPORTED_BITRATE, DEFAULT.forceHighestSupportedBitrate
                )
            val overrideBundleList: MutableList<Bundle>? = bundle.getParcelableArrayList(
                FIELD_SELECTION_OVERRIDES
            )
            val overrideList: List<TrackSelectionOverride> =
                if (overrideBundleList == null)
                    listOf()
                else
                    BundleCollectionUtil.fromBundleList<TrackSelectionOverride>(
                        Function { obj: Bundle -> TrackSelectionOverride.fromBundle(obj) },
                        overrideBundleList
                    )
            overrides = mutableMapOf()
            for (i in overrideList.indices) {
                val override = overrideList[i]
                overrides!![override.mediaTrackGroup] = override
            }
            val disabledTrackTypeArray =
                MoreObjects.firstNonNull<IntArray>(
                    bundle.getIntArray(FIELD_DISABLED_TRACK_TYPE),
                    IntArray(0)
                )
            disabledTrackTypes = HashSet()
            for (disabledTrackType: @C.TrackType Int in disabledTrackTypeArray) {
                disabledTrackTypes!!.add(disabledTrackType)
            }
        }

        private fun init(parameters: TrackSelectionParameters) {
            // Video
            maxVideoWidth = parameters.maxVideoWidth
            maxVideoHeight = parameters.maxVideoHeight
            maxVideoFrameRate = parameters.maxVideoFrameRate
            maxVideoBitrate = parameters.maxVideoBitrate
            minVideoWidth = parameters.minVideoWidth
            minVideoHeight = parameters.minVideoHeight
            minVideoFrameRate = parameters.minVideoFrameRate
            minVideoBitrate = parameters.minVideoBitrate
            viewportWidth = parameters.viewportWidth
            viewportHeight = parameters.viewportHeight
            isViewportSizeLimitedByPhysicalDisplaySize =
                parameters.isViewportSizeLimitedByPhysicalDisplaySize
            viewportOrientationMayChange = parameters.viewportOrientationMayChange
            preferredVideoLabels = parameters.preferredVideoLabels
            preferredVideoMimeTypes = parameters.preferredVideoMimeTypes
            preferredVideoLanguages = parameters.preferredVideoLanguages
            preferredVideoRoleFlags = parameters.preferredVideoRoleFlags
            // Audio
            preferredAudioLanguages = parameters.preferredAudioLanguages
            preferredAudioRoleFlags = parameters.preferredAudioRoleFlags
            preferredAudioLabels = parameters.preferredAudioLabels
            maxAudioChannelCount = parameters.maxAudioChannelCount
            maxAudioBitrate = parameters.maxAudioBitrate
            preferredAudioMimeTypes = parameters.preferredAudioMimeTypes
            audioOffloadPreferences = parameters.audioOffloadPreferences
            // Text
            selectTextByDefault = parameters.selectTextByDefault
            preferredTextLanguages = parameters.preferredTextLanguages
            preferredTextRoleFlags = parameters.preferredTextRoleFlags
            usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager =
                parameters.usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager
            preferredTextLabels = parameters.preferredTextLabels
            ignoredTextSelectionFlags = parameters.ignoredTextSelectionFlags
            selectUndeterminedTextLanguage = parameters.selectUndeterminedTextLanguage
            // Image
            isPrioritizeImageOverVideoEnabled = parameters.isPrioritizeImageOverVideoEnabled
            // General
            forceLowestBitrate = parameters.forceLowestBitrate
            forceHighestSupportedBitrate = parameters.forceHighestSupportedBitrate
            disabledTrackTypes = parameters.disabledTrackTypes.toMutableSet()
            overrides = parameters.overrides.toMutableMap()
        }

        open fun set(parameters: TrackSelectionParameters): Builder {
            init(parameters)
            return this
        }

        open  fun setMaxVideoSizeSd(): Builder {
            return setMaxVideoSize(1279, 719)
        }

        open   fun clearVideoSizeConstraints(): Builder {
            return setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        open   fun setMaxVideoSize(maxVideoWidth: Int, maxVideoHeight: Int): Builder {
            this.maxVideoWidth = maxVideoWidth
            this.maxVideoHeight = maxVideoHeight
            return this
        }

        open   fun setMaxVideoFrameRate(maxVideoFrameRate: Int): Builder {
            this.maxVideoFrameRate = maxVideoFrameRate
            return this
        }

        open   fun setMaxVideoBitrate(maxVideoBitrate: Int): Builder {
            this.maxVideoBitrate = maxVideoBitrate
            return this
        }

        open   fun setMinVideoSize(minVideoWidth: Int, minVideoHeight: Int): Builder {
            this.minVideoWidth = minVideoWidth
            this.minVideoHeight = minVideoHeight
            return this
        }

        open  fun setMinVideoFrameRate(minVideoFrameRate: Int): Builder {
            this.minVideoFrameRate = minVideoFrameRate
            return this
        }

        open   fun setMinVideoBitrate(minVideoBitrate: Int): Builder {
            this.minVideoBitrate = minVideoBitrate
            return this
        }

        open   fun setViewportSizeToPhysicalDisplaySize(viewportOrientationMayChange: Boolean): Builder {
            this.isViewportSizeLimitedByPhysicalDisplaySize = true
            this.viewportOrientationMayChange = viewportOrientationMayChange
            this.viewportHeight = Int.MAX_VALUE
            this.viewportWidth = Int.MAX_VALUE
            return this
        }

        open  fun setViewportSizeToPhysicalDisplaySize(
            context: Context?, viewportOrientationMayChange: Boolean
        ): Builder {
            return setViewportSizeToPhysicalDisplaySize(viewportOrientationMayChange)
        }

        open   fun clearViewportSizeConstraints(): Builder {
            return setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, true)
        }

        open   fun setViewportSize(
            viewportWidth: Int, viewportHeight: Int, viewportOrientationMayChange: Boolean
        ): Builder {
            this.viewportWidth = viewportWidth
            this.viewportHeight = viewportHeight
            this.viewportOrientationMayChange = viewportOrientationMayChange
            this.isViewportSizeLimitedByPhysicalDisplaySize = false
            return this
        }

        open  fun setPreferredVideoMimeType(mimeType: String?): Builder {
            return setPreferredVideoMimeTypes(mimeType?.let { listOf(it) }?:listOf())
        }

        open  fun setPreferredVideoMimeTypes(mimeTypes: List<String>): Builder {
            preferredVideoMimeTypes = mimeTypes.toList()
            return this
        }

        open fun setPreferredVideoLanguage(preferredVideoLanguage: String?): Builder {
            return setPreferredVideoLanguages(preferredVideoLanguage?.let { listOf(it) }?:listOf())
        }

        open fun setPreferredVideoLanguages(preferredVideoLanguages: List<String>): Builder {
            this.preferredVideoLanguages = preferredVideoLanguages.map {
                normalizeLanguageCode(it)
            }
            return this
        }

        open fun setPreferredVideoLabels(preferredVideoLabels: List<String>): Builder {
            this.preferredVideoLabels = preferredVideoLabels.toList()
            return this
        }

        open fun setPreferredVideoRoleFlags(@RoleFlags preferredVideoRoleFlags: @RoleFlags Int): Builder {
            this.preferredVideoRoleFlags = preferredVideoRoleFlags
            return this
        }

        open fun setPreferredAudioLanguage(preferredAudioLanguage: String?): Builder {
            return setPreferredAudioLanguages(preferredAudioLanguage?.let { listOf(it) }?:listOf())
        }

        open  fun setPreferredAudioLanguages(preferredAudioLanguages: List<String>): Builder {
            this.preferredAudioLanguages =
                preferredAudioLanguages.map { normalizeLanguageCode(it) }
            return this
        }

        open  fun setPreferredAudioRoleFlags(@RoleFlags preferredAudioRoleFlags: @RoleFlags Int): Builder {
            this.preferredAudioRoleFlags = preferredAudioRoleFlags
            return this
        }

        open   fun setPreferredAudioLabels(preferredAudioLabels: List<String>): Builder {
            this.preferredAudioLabels = preferredAudioLabels.toList()
            return this
        }

        open   fun setMaxAudioChannelCount(maxAudioChannelCount: Int): Builder {
            this.maxAudioChannelCount = maxAudioChannelCount
            return this
        }

        open    fun setMaxAudioBitrate(maxAudioBitrate: Int): Builder {
            this.maxAudioBitrate = maxAudioBitrate
            return this
        }

        open   fun setPreferredAudioMimeType(mimeType: String?): Builder {
            return setPreferredAudioMimeTypes(mimeType?.let { listOf(it) }?:listOf())
        }

        open   fun setPreferredAudioMimeTypes(mimeTypes: List<String>): Builder {
            preferredAudioMimeTypes = mimeTypes.toList()
            return this
        }

        open   fun setAudioOffloadPreferences(audioOffloadPreferences: AudioOffloadPreferences?): Builder {
            this.audioOffloadPreferences = audioOffloadPreferences
            return this
        }

        open   fun setSelectTextByDefault(selectTextByDefault: Boolean): Builder {
            this.selectTextByDefault = selectTextByDefault
            return this
        }

        open    fun setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings(): Builder {
            usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager = true
            preferredTextLanguages = listOf()
            preferredTextRoleFlags = 0
            return this
        }

        open  fun setPreferredTextLanguage(preferredTextLanguage: String?): Builder {
            return setPreferredTextLanguages(preferredTextLanguage?.let { listOf(it) }?:listOf())
        }

        open   fun setPreferredTextLanguages(preferredTextLanguages: List<String>): Builder {
            this.preferredTextLanguages = preferredTextLanguages.map {
                normalizeLanguageCode(it)
            }
            this.usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager = false
            return this
        }

        open  fun setPreferredTextRoleFlags(@RoleFlags preferredTextRoleFlags: @RoleFlags Int): Builder {
            this.preferredTextRoleFlags = preferredTextRoleFlags
            this.usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager = false
            return this
        }

        open fun setPreferredTextLabels(preferredTextLabels: List<String>): Builder {
            this.preferredTextLabels = preferredTextLabels.toList()
            return this
        }

        open  fun setIgnoredTextSelectionFlags(@SelectionFlags ignoredTextSelectionFlags: @SelectionFlags Int): Builder {
            this.ignoredTextSelectionFlags = ignoredTextSelectionFlags
            return this
        }

        open   fun setSelectUndeterminedTextLanguage(selectUndeterminedTextLanguage: Boolean): Builder {
            this.selectUndeterminedTextLanguage = selectUndeterminedTextLanguage
            return this
        }

        open fun setPrioritizeImageOverVideoEnabled(isPrioritizeImageOverVideoEnabled: Boolean): Builder {
            this.isPrioritizeImageOverVideoEnabled = isPrioritizeImageOverVideoEnabled
            return this
        }

        open  fun setForceLowestBitrate(forceLowestBitrate: Boolean): Builder {
            this.forceLowestBitrate = forceLowestBitrate
            return this
        }

        open   fun setForceHighestSupportedBitrate(forceHighestSupportedBitrate: Boolean): Builder {
            this.forceHighestSupportedBitrate = forceHighestSupportedBitrate
            return this
        }

        open  fun addOverride(override: TrackSelectionOverride): Builder {
            overrides!![override.mediaTrackGroup] = override
            return this
        }

        open  fun setOverrideForType(override: TrackSelectionOverride): Builder {
            clearOverridesOfType(override.type)
            overrides!![override.mediaTrackGroup] = override
            return this
        }

        open   fun clearOverride(mediaTrackGroup: TrackGroup?): Builder {
            overrides!!.remove(mediaTrackGroup)
            return this
        }

        open    fun clearOverridesOfType(trackType: @C.TrackType Int): Builder {
            val it = overrides!!.values.iterator()
            while (it.hasNext()) {
                val override = it.next()
                if (override?.type == trackType) {
                    it.remove()
                }
            }
            return this
        }

        open    fun clearOverrides(): Builder {
            overrides!!.clear()
            return this
        }

        open   fun setDisabledTrackTypes(disabledTrackTypes: MutableSet<Int>): Builder {
            this.disabledTrackTypes!!.clear()
            this.disabledTrackTypes!!.addAll(disabledTrackTypes)
            return this
        }

        open  fun setTrackTypeDisabled(trackType: @C.TrackType Int, disabled: Boolean): Builder {
            if (disabled) {
                disabledTrackTypes!!.add(trackType)
            } else {
                disabledTrackTypes!!.remove(trackType)
            }
            return this
        }

       open fun build(): TrackSelectionParameters {
            return TrackSelectionParameters(this)
        }

        companion object {
            private fun getAudioOffloadPreferencesFromBundle(bundle: Bundle): AudioOffloadPreferences {
                val audioOffloadPreferencesBundle =
                    bundle.getBundle(FIELD_AUDIO_OFFLOAD_PREFERENCES)
                return if (audioOffloadPreferencesBundle != null)
                    AudioOffloadPreferences.fromBundle(audioOffloadPreferencesBundle)
                else
                    AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(
                            bundle.getInt(
                                FIELD_AUDIO_OFFLOAD_MODE_PREFERENCE,
                                AudioOffloadPreferences.DEFAULT.audioOffloadMode
                            )
                        )
                        .setIsGaplessSupportRequired(
                            bundle.getBoolean(
                                FIELD_IS_GAPLESS_SUPPORT_REQUIRED,
                                AudioOffloadPreferences.DEFAULT.isGaplessSupportRequired
                            )
                        )
                        .setIsSpeedChangeSupportRequired(
                            bundle.getBoolean(
                                FIELD_IS_SPEED_CHANGE_SUPPORT_REQUIRED,
                                AudioOffloadPreferences.DEFAULT.isSpeedChangeSupportRequired
                            )
                        )
                        .build()
            }
        }
    }

    companion object {
        val DEFAULT: TrackSelectionParameters = Builder().build()

        private val FIELD_PREFERRED_AUDIO_LANGUAGES = intToStringMaxRadix(1)
        private val FIELD_PREFERRED_AUDIO_ROLE_FLAGS = intToStringMaxRadix(2)
        private val FIELD_PREFERRED_TEXT_LANGUAGES = intToStringMaxRadix(3)
        private val FIELD_PREFERRED_TEXT_ROLE_FLAGS = intToStringMaxRadix(4)
        private val FIELD_SELECT_UNDETERMINED_TEXT_LANGUAGE = intToStringMaxRadix(5)
        private val FIELD_MAX_VIDEO_WIDTH = intToStringMaxRadix(6)
        private val FIELD_MAX_VIDEO_HEIGHT = intToStringMaxRadix(7)
        private val FIELD_MAX_VIDEO_FRAMERATE = intToStringMaxRadix(8)
        private val FIELD_MAX_VIDEO_BITRATE = intToStringMaxRadix(9)
        private val FIELD_MIN_VIDEO_WIDTH = intToStringMaxRadix(10)
        private val FIELD_MIN_VIDEO_HEIGHT = intToStringMaxRadix(11)
        private val FIELD_MIN_VIDEO_FRAMERATE = intToStringMaxRadix(12)
        private val FIELD_MIN_VIDEO_BITRATE = intToStringMaxRadix(13)
        private val FIELD_VIEWPORT_WIDTH = intToStringMaxRadix(14)
        private val FIELD_VIEWPORT_HEIGHT = intToStringMaxRadix(15)
        private val FIELD_VIEWPORT_ORIENTATION_MAY_CHANGE = intToStringMaxRadix(16)
        private val FIELD_PREFERRED_VIDEO_MIMETYPES = intToStringMaxRadix(17)
        private val FIELD_MAX_AUDIO_CHANNEL_COUNT = intToStringMaxRadix(18)
        private val FIELD_MAX_AUDIO_BITRATE = intToStringMaxRadix(19)
        private val FIELD_PREFERRED_AUDIO_MIME_TYPES = intToStringMaxRadix(20)
        private val FIELD_FORCE_LOWEST_BITRATE = intToStringMaxRadix(21)
        private val FIELD_FORCE_HIGHEST_SUPPORTED_BITRATE = intToStringMaxRadix(22)
        private val FIELD_SELECTION_OVERRIDES = intToStringMaxRadix(23)
        private val FIELD_DISABLED_TRACK_TYPE = intToStringMaxRadix(24)
        private val FIELD_PREFERRED_VIDEO_ROLE_FLAGS = intToStringMaxRadix(25)
        private val FIELD_IGNORED_TEXT_SELECTION_FLAGS = intToStringMaxRadix(26)
        private val FIELD_AUDIO_OFFLOAD_MODE_PREFERENCE = intToStringMaxRadix(27)
        private val FIELD_IS_GAPLESS_SUPPORT_REQUIRED = intToStringMaxRadix(28)
        private val FIELD_IS_SPEED_CHANGE_SUPPORT_REQUIRED = intToStringMaxRadix(29)
        private val FIELD_AUDIO_OFFLOAD_PREFERENCES = intToStringMaxRadix(30)
        private val FIELD_IS_PREFER_IMAGE_OVER_VIDEO_ENABLED = intToStringMaxRadix(31)
        private val FIELD_PREFERRED_VIDEO_LANGUAGES = intToStringMaxRadix(32)
        private val FIELD_IS_VIEWPORT_SIZE_LIMITED_BY_PHYSICAL_DISPLAY_SIZE =
            intToStringMaxRadix(33)
        private val FIELD_USE_PREFERRED_TEXT_LANGUAGES_AND_ROLE_FLAGS_FROM_CAPTIONING_MANAGER =
            intToStringMaxRadix(34)
        private val FIELD_SELECT_TEXT_BY_DEFAULT = intToStringMaxRadix(35)
        private val FIELD_PREFERRED_VIDEO_LABELS = intToStringMaxRadix(36)
        private val FIELD_PREFERRED_AUDIO_LABELS = intToStringMaxRadix(37)
        private val FIELD_PREFERRED_TEXT_LABELS = intToStringMaxRadix(38)
        protected const val FIELD_CUSTOM_ID_BASE: Int = 1000

        fun fromBundle(bundle: Bundle): TrackSelectionParameters {
            return Builder(bundle).build()
        }
    }
}
