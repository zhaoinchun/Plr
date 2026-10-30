package androidx.media3.exoplayer.trackselection

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Pair
import android.util.SparseArray
import android.util.SparseBooleanArray
import android.view.accessibility.CaptioningManager
import androidx.annotation.IntDef
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.BundleCollectionUtil
import androidx.media3.common.util.BundleCollectionUtil.fromBundleSparseArray
import androidx.media3.common.util.BundleCollectionUtil.toBundleSparseArray
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.getCurrentDisplayModeSize
import androidx.media3.common.util.Util.getLocaleLanguageTag
import androidx.media3.common.util.Util.intToStringMaxRadix
import androidx.media3.common.util.Util.isTv
import androidx.media3.common.util.Util.splitAtFirst
import androidx.media3.common.util.Util.systemLanguageCodes
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererCapabilities.AdaptiveSupport
import androidx.media3.exoplayer.RendererCapabilities.DecoderSupport
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getAlternativeCodecMimeType
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.util.SpatializerWrapper
import com.google.common.base.Function
import com.google.common.base.Preconditions
import com.google.common.base.Predicate
import com.google.common.collect.ComparisonChain
import com.google.common.collect.Ordering
import com.google.common.primitives.Ints
import com.google.errorprone.annotations.InlineMe

import java.util.Arrays
import java.util.Collections
import kotlin.concurrent.Volatile
import androidx.core.util.size
import androidx.core.util.isEmpty

class DefaultTrackSelector private constructor(
    parameters: TrackSelectionParameters,
    private val trackSelectionFactory: ExoTrackSelection.Factory,
    context: Context?
) : MappingTrackSelector(), RendererCapabilities.Listener {

    class Parameters private constructor(builder: Builder) : TrackSelectionParameters(builder) {
        class Builder : TrackSelectionParameters.Builder {
            // Video
            var exceedVideoConstraintsIfNecessary = false
            var allowVideoMixedMimeTypeAdaptiveness = false
            var allowVideoNonSeamlessAdaptiveness = false
            var allowVideoMixedDecoderSupportAdaptiveness = false

            // Audio
            var exceedAudioConstraintsIfNecessary = false
            var allowAudioMixedMimeTypeAdaptiveness = false
            var allowAudioMixedSampleRateAdaptiveness = false
            var allowAudioMixedChannelCountAdaptiveness = false
            var allowAudioMixedDecoderSupportAdaptiveness = false
            var allowAudioNonSeamlessAdaptiveness = false
            var constrainAudioChannelCountToDeviceCapabilities = false

            // General
            var exceedRendererCapabilitiesIfNecessary = false
            var tunnelingEnabled = false
            var allowMultipleAdaptiveSelections = false
            var allowInvalidateSelectionsOnRendererCapabilitiesChange = false

            // Overrides
            val selectionOverrides: SparseArray<MutableMap<TrackGroupArray, SelectionOverride?>>

            val rendererDisabledFlags: SparseBooleanArray

            constructor() : super() {
                selectionOverrides =
                    SparseArray<MutableMap<TrackGroupArray, SelectionOverride?>>()
                rendererDisabledFlags = SparseBooleanArray()
                init()
            }

            @InlineMe(replacement = "this()")
            @Deprecated("Use {@link #Builder()} instead.")
            constructor(context: Context?) : this()

            constructor(initialValues: Parameters) : super(initialValues) {
                // Video
                exceedVideoConstraintsIfNecessary = initialValues.exceedVideoConstraintsIfNecessary
                allowVideoMixedMimeTypeAdaptiveness =
                    initialValues.allowVideoMixedMimeTypeAdaptiveness
                allowVideoNonSeamlessAdaptiveness = initialValues.allowVideoNonSeamlessAdaptiveness
                allowVideoMixedDecoderSupportAdaptiveness =
                    initialValues.allowVideoMixedDecoderSupportAdaptiveness
                // Audio
                exceedAudioConstraintsIfNecessary = initialValues.exceedAudioConstraintsIfNecessary
                allowAudioMixedMimeTypeAdaptiveness =
                    initialValues.allowAudioMixedMimeTypeAdaptiveness
                allowAudioMixedSampleRateAdaptiveness =
                    initialValues.allowAudioMixedSampleRateAdaptiveness
                allowAudioMixedChannelCountAdaptiveness =
                    initialValues.allowAudioMixedChannelCountAdaptiveness
                allowAudioMixedDecoderSupportAdaptiveness =
                    initialValues.allowAudioMixedDecoderSupportAdaptiveness
                allowAudioNonSeamlessAdaptiveness = initialValues.allowAudioNonSeamlessAdaptiveness
                constrainAudioChannelCountToDeviceCapabilities =
                    initialValues.constrainAudioChannelCountToDeviceCapabilities
                // General
                exceedRendererCapabilitiesIfNecessary =
                    initialValues.exceedRendererCapabilitiesIfNecessary
                tunnelingEnabled = initialValues.tunnelingEnabled
                allowMultipleAdaptiveSelections = initialValues.allowMultipleAdaptiveSelections
                allowInvalidateSelectionsOnRendererCapabilitiesChange =
                    initialValues.allowInvalidateSelectionsOnRendererCapabilitiesChange
                // Overrides
                selectionOverrides = cloneSelectionOverrides(initialValues.selectionOverrides)
                rendererDisabledFlags = initialValues.rendererDisabledFlags.clone()
            }

            // Only setter are invoked.
            constructor(bundle: Bundle) : super(bundle) {
                init()
                val defaultValue: Parameters = DEFAULT
                // Video
                setExceedVideoConstraintsIfNecessary(
                    bundle.getBoolean(
                        FIELD_EXCEED_VIDEO_CONSTRAINTS_IF_NECESSARY,
                        defaultValue.exceedVideoConstraintsIfNecessary
                    )
                )
                setAllowVideoMixedMimeTypeAdaptiveness(
                    bundle.getBoolean(
                        FIELD_ALLOW_VIDEO_MIXED_MIME_TYPE_ADAPTIVENESS,
                        defaultValue.allowVideoMixedMimeTypeAdaptiveness
                    )
                )
                setAllowVideoNonSeamlessAdaptiveness(
                    bundle.getBoolean(
                        FIELD_ALLOW_VIDEO_NON_SEAMLESS_ADAPTIVENESS,
                        defaultValue.allowVideoNonSeamlessAdaptiveness
                    )
                )
                setAllowVideoMixedDecoderSupportAdaptiveness(
                    bundle.getBoolean(
                        FIELD_ALLOW_VIDEO_MIXED_DECODER_SUPPORT_ADAPTIVENESS,
                        defaultValue.allowVideoMixedDecoderSupportAdaptiveness
                    )
                )
                // Audio
                setExceedAudioConstraintsIfNecessary(
                    bundle.getBoolean(
                        FIELD_EXCEED_AUDIO_CONSTRAINTS_IF_NECESSARY,
                        defaultValue.exceedAudioConstraintsIfNecessary
                    )
                )
                setAllowAudioMixedMimeTypeAdaptiveness(
                    bundle.getBoolean(
                        FIELD_ALLOW_AUDIO_MIXED_MIME_TYPE_ADAPTIVENESS,
                        defaultValue.allowAudioMixedMimeTypeAdaptiveness
                    )
                )
                setAllowAudioMixedSampleRateAdaptiveness(
                    bundle.getBoolean(
                        FIELD_ALLOW_AUDIO_MIXED_SAMPLE_RATE_ADAPTIVENESS,
                        defaultValue.allowAudioMixedSampleRateAdaptiveness
                    )
                )
                setAllowAudioMixedChannelCountAdaptiveness(
                    bundle.getBoolean(
                        FIELD_ALLOW_AUDIO_MIXED_CHANNEL_COUNT_ADAPTIVENESS,
                        defaultValue.allowAudioMixedChannelCountAdaptiveness
                    )
                )
                setAllowAudioMixedDecoderSupportAdaptiveness(
                    bundle.getBoolean(
                        FIELD_ALLOW_AUDIO_MIXED_DECODER_SUPPORT_ADAPTIVENESS,
                        defaultValue.allowAudioMixedDecoderSupportAdaptiveness
                    )
                )
                setAllowAudioNonSeamlessAdaptiveness(
                    bundle.getBoolean(
                        FIELD_ALLOW_AUDIO_NON_SEAMLESS_ADAPTIVENESS,
                        defaultValue.allowAudioNonSeamlessAdaptiveness
                    )
                )
                setConstrainAudioChannelCountToDeviceCapabilities(
                    bundle.getBoolean(
                        FIELD_CONSTRAIN_AUDIO_CHANNEL_COUNT_TO_DEVICE_CAPABILITIES,
                        defaultValue.constrainAudioChannelCountToDeviceCapabilities
                    )
                )
                // General
                setExceedRendererCapabilitiesIfNecessary(
                    bundle.getBoolean(
                        FIELD_EXCEED_RENDERER_CAPABILITIES_IF_NECESSARY,
                        defaultValue.exceedRendererCapabilitiesIfNecessary
                    )
                )
                setTunnelingEnabled(
                    bundle.getBoolean(FIELD_TUNNELING_ENABLED, defaultValue.tunnelingEnabled)
                )
                setAllowMultipleAdaptiveSelections(
                    bundle.getBoolean(
                        FIELD_ALLOW_MULTIPLE_ADAPTIVE_SELECTIONS,
                        defaultValue.allowMultipleAdaptiveSelections
                    )
                )
                setAllowInvalidateSelectionsOnRendererCapabilitiesChange(
                    bundle.getBoolean(
                        FIELD_ALLOW_INVALIDATE_SELECTIONS_ON_RENDERER_CAPABILITIES_CHANGE,
                        defaultValue.allowInvalidateSelectionsOnRendererCapabilitiesChange
                    )
                )
                // Overrides
                selectionOverrides = SparseArray()
                setSelectionOverridesFromBundle(bundle)
                rendererDisabledFlags =
                    makeSparseBooleanArrayFromTrueKeys(
                        bundle.getIntArray(FIELD_RENDERER_DISABLED_INDICES)
                    )
            }

            override fun set(parameters: TrackSelectionParameters): Builder {
                super.set(parameters)
                return this
            }

            override fun setMaxVideoSizeSd(): Builder {
                super.setMaxVideoSizeSd()
                return this
            }

            override fun clearVideoSizeConstraints(): Builder {
                super.clearVideoSizeConstraints()
                return this
            }

            override fun setMaxVideoSize(maxVideoWidth: Int, maxVideoHeight: Int): Builder {
                super.setMaxVideoSize(maxVideoWidth, maxVideoHeight)
                return this
            }

            override fun setMaxVideoFrameRate(maxVideoFrameRate: Int): Builder {
                super.setMaxVideoFrameRate(maxVideoFrameRate)
                return this
            }

            override fun setMaxVideoBitrate(maxVideoBitrate: Int): Builder {
                super.setMaxVideoBitrate(maxVideoBitrate)
                return this
            }

            override fun setMinVideoSize(minVideoWidth: Int, minVideoHeight: Int): Builder {
                super.setMinVideoSize(minVideoWidth, minVideoHeight)
                return this
            }

            override fun setMinVideoFrameRate(minVideoFrameRate: Int): Builder {
                super.setMinVideoFrameRate(minVideoFrameRate)
                return this
            }

            override fun setMinVideoBitrate(minVideoBitrate: Int): Builder {
                super.setMinVideoBitrate(minVideoBitrate)
                return this
            }

            fun setExceedVideoConstraintsIfNecessary(
                exceedVideoConstraintsIfNecessary: Boolean
            ): Builder {
                this.exceedVideoConstraintsIfNecessary = exceedVideoConstraintsIfNecessary
                return this
            }

            fun setAllowVideoMixedMimeTypeAdaptiveness(
                allowVideoMixedMimeTypeAdaptiveness: Boolean
            ): Builder {
                this.allowVideoMixedMimeTypeAdaptiveness = allowVideoMixedMimeTypeAdaptiveness
                return this
            }

            fun setAllowVideoNonSeamlessAdaptiveness(
                allowVideoNonSeamlessAdaptiveness: Boolean
            ): Builder {
                this.allowVideoNonSeamlessAdaptiveness = allowVideoNonSeamlessAdaptiveness
                return this
            }

            fun setAllowVideoMixedDecoderSupportAdaptiveness(
                allowVideoMixedDecoderSupportAdaptiveness: Boolean
            ): Builder {
                this.allowVideoMixedDecoderSupportAdaptiveness =
                    allowVideoMixedDecoderSupportAdaptiveness
                return this
            }

            override fun setViewportSizeToPhysicalDisplaySize(viewportOrientationMayChange: Boolean): Builder {
                super.setViewportSizeToPhysicalDisplaySize(viewportOrientationMayChange)
                return this
            }

            @Deprecated("")
            override fun setViewportSizeToPhysicalDisplaySize(
                context: Context?, viewportOrientationMayChange: Boolean
            ): Builder {
                super.setViewportSizeToPhysicalDisplaySize(context, viewportOrientationMayChange)
                return this
            }

            override fun clearViewportSizeConstraints(): Builder {
                super.clearViewportSizeConstraints()
                return this
            }

            override fun setViewportSize(
                viewportWidth: Int, viewportHeight: Int, viewportOrientationMayChange: Boolean
            ): Builder {
                super.setViewportSize(viewportWidth, viewportHeight, viewportOrientationMayChange)
                return this
            }

            override fun setPreferredVideoMimeType(mimeType: String?): Builder {
                super.setPreferredVideoMimeType(mimeType)
                return this
            }

            override fun setPreferredVideoMimeTypes(mimeTypes: List<String>): Builder {
                super.setPreferredVideoMimeTypes(mimeTypes)
                return this
            }

            override fun setPreferredVideoLanguage(preferredVideoLanguage: String?): Builder {
                super.setPreferredVideoLanguage(preferredVideoLanguage)
                return this
            }

            override fun setPreferredVideoLanguages(preferredVideoLanguages: List<String>): Builder {
                super.setPreferredVideoLanguages(preferredVideoLanguages)
                return this
            }

            override fun setPreferredVideoLabels(preferredVideoLabels: List<String>): Builder {
                super.setPreferredVideoLabels(preferredVideoLabels)
                return this
            }

            override fun setPreferredVideoRoleFlags(@C.RoleFlags preferredVideoRoleFlags: @C.RoleFlags Int): Builder {
                super.setPreferredVideoRoleFlags(preferredVideoRoleFlags)
                return this
            }

            override fun setPreferredAudioLanguage(preferredAudioLanguage: String?): Builder {
                super.setPreferredAudioLanguage(preferredAudioLanguage)
                return this
            }

            override fun setPreferredAudioLanguages(preferredAudioLanguages: List<String>): Builder {
                super.setPreferredAudioLanguages(preferredAudioLanguages)
                return this
            }

            override fun setPreferredAudioRoleFlags(@C.RoleFlags preferredAudioRoleFlags: @C.RoleFlags Int): Builder {
                super.setPreferredAudioRoleFlags(preferredAudioRoleFlags)
                return this
            }

            override fun setPreferredAudioLabels(preferredAudioLabels: List<String>): Builder {
                super.setPreferredAudioLabels(preferredAudioLabels)
                return this
            }

            override fun setMaxAudioChannelCount(maxAudioChannelCount: Int): Builder {
                super.setMaxAudioChannelCount(maxAudioChannelCount)
                return this
            }

            override fun setMaxAudioBitrate(maxAudioBitrate: Int): Builder {
                super.setMaxAudioBitrate(maxAudioBitrate)
                return this
            }

            fun setExceedAudioConstraintsIfNecessary(
                exceedAudioConstraintsIfNecessary: Boolean
            ): Builder {
                this.exceedAudioConstraintsIfNecessary = exceedAudioConstraintsIfNecessary
                return this
            }

            fun setAllowAudioMixedMimeTypeAdaptiveness(
                allowAudioMixedMimeTypeAdaptiveness: Boolean
            ): Builder {
                this.allowAudioMixedMimeTypeAdaptiveness = allowAudioMixedMimeTypeAdaptiveness
                return this
            }

            fun setAllowAudioMixedSampleRateAdaptiveness(
                allowAudioMixedSampleRateAdaptiveness: Boolean
            ): Builder {
                this.allowAudioMixedSampleRateAdaptiveness = allowAudioMixedSampleRateAdaptiveness
                return this
            }

            fun setAllowAudioMixedChannelCountAdaptiveness(
                allowAudioMixedChannelCountAdaptiveness: Boolean
            ): Builder {
                this.allowAudioMixedChannelCountAdaptiveness =
                    allowAudioMixedChannelCountAdaptiveness
                return this
            }

            fun setAllowAudioMixedDecoderSupportAdaptiveness(
                allowAudioMixedDecoderSupportAdaptiveness: Boolean
            ): Builder {
                this.allowAudioMixedDecoderSupportAdaptiveness =
                    allowAudioMixedDecoderSupportAdaptiveness
                return this
            }

            override fun setPreferredAudioMimeType(mimeType: String?): Builder {
                super.setPreferredAudioMimeType(mimeType)
                return this
            }

            override fun setPreferredAudioMimeTypes(mimeTypes: List<String>): Builder {
                super.setPreferredAudioMimeTypes(mimeTypes)
                return this
            }

            override fun setAudioOffloadPreferences(audioOffloadPreferences: AudioOffloadPreferences?): Builder {
                super.setAudioOffloadPreferences(audioOffloadPreferences!!)
                return this
            }

            fun setAllowAudioNonSeamlessAdaptiveness(
                allowAudioNonSeamlessAdaptiveness: Boolean
            ): Builder {
                this.allowAudioNonSeamlessAdaptiveness = allowAudioNonSeamlessAdaptiveness
                return this
            }

            fun setConstrainAudioChannelCountToDeviceCapabilities(enabled: Boolean): Builder {
                constrainAudioChannelCountToDeviceCapabilities = enabled
                return this
            }

            override fun setSelectTextByDefault(selectTextByDefault: Boolean): Builder {
                super.setSelectTextByDefault(selectTextByDefault)
                return this
            }

            override fun setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings(): Builder {
                super.setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings()
                return this
            }

            override fun setPreferredTextLanguage(preferredTextLanguage: String?): Builder {
                super.setPreferredTextLanguage(preferredTextLanguage)
                return this
            }

            override fun setPreferredTextLanguages(preferredTextLanguages: List<String>): Builder {
                super.setPreferredTextLanguages(preferredTextLanguages)
                return this
            }

            override fun setPreferredTextRoleFlags(@C.RoleFlags preferredTextRoleFlags: @C.RoleFlags Int): Builder {
                super.setPreferredTextRoleFlags(preferredTextRoleFlags)
                return this
            }

            override fun setIgnoredTextSelectionFlags(@C.SelectionFlags ignoredTextSelectionFlags: @C.SelectionFlags Int): Builder {
                super.setIgnoredTextSelectionFlags(ignoredTextSelectionFlags)
                return this
            }

            override fun setPreferredTextLabels(preferredTextLabels: List<String>): Builder {
                super.setPreferredTextLabels(preferredTextLabels)
                return this
            }

            override fun setSelectUndeterminedTextLanguage(selectUndeterminedTextLanguage: Boolean): Builder {
                super.setSelectUndeterminedTextLanguage(selectUndeterminedTextLanguage)
                return this
            }

            @Deprecated("Use {@link #setIgnoredTextSelectionFlags}.")
            open fun setDisabledTextTrackSelectionFlags(
                @C.SelectionFlags disabledTextTrackSelectionFlags: @C.SelectionFlags Int
            ): Builder {
                disabledTextTrackSelectionFlags.also { ignoredTextSelectionFlags = it }
                return this
            }

            override fun setPrioritizeImageOverVideoEnabled(isPrioritizeImageOverVideoEnabled: Boolean): Builder {
                super.setPrioritizeImageOverVideoEnabled(isPrioritizeImageOverVideoEnabled)
                return this
            }

            override fun setForceLowestBitrate(forceLowestBitrate: Boolean): Builder {
                super.setForceLowestBitrate(forceLowestBitrate)
                return this
            }

            override fun setForceHighestSupportedBitrate(forceHighestSupportedBitrate: Boolean): Builder {
                super.setForceHighestSupportedBitrate(forceHighestSupportedBitrate)
                return this
            }

            fun setAllowInvalidateSelectionsOnRendererCapabilitiesChange(
                allowInvalidateSelectionsOnRendererCapabilitiesChange: Boolean
            ): Builder {
                this.allowInvalidateSelectionsOnRendererCapabilitiesChange =
                    allowInvalidateSelectionsOnRendererCapabilitiesChange
                return this
            }

            override fun addOverride(override: TrackSelectionOverride): Builder {
                super.addOverride(override)
                return this
            }

            override fun clearOverride(trackGroup: TrackGroup?): Builder {
                super.clearOverride(trackGroup)
                return this
            }

            override fun setOverrideForType(override: TrackSelectionOverride): Builder {
                super.setOverrideForType(override)
                return this
            }

            override fun clearOverridesOfType(trackType: @C.TrackType Int): Builder {
                super.clearOverridesOfType(trackType)
                return this
            }

            override fun clearOverrides(): Builder {
                super.clearOverrides()
                return this
            }

            override fun setDisabledTrackTypes(disabledTrackTypes: MutableSet<Int>): Builder {
                super.setDisabledTrackTypes(disabledTrackTypes)
                return this
            }

            override fun setTrackTypeDisabled(
                trackType: @C.TrackType Int,
                disabled: Boolean
            ): Builder {
                super.setTrackTypeDisabled(trackType, disabled)
                return this
            }

            fun setExceedRendererCapabilitiesIfNecessary(
                exceedRendererCapabilitiesIfNecessary: Boolean
            ): Builder {
                this.exceedRendererCapabilitiesIfNecessary = exceedRendererCapabilitiesIfNecessary
                return this
            }

            fun setTunnelingEnabled(tunnelingEnabled: Boolean): Builder {
                this.tunnelingEnabled = tunnelingEnabled
                return this
            }

            fun setAllowMultipleAdaptiveSelections(allowMultipleAdaptiveSelections: Boolean): Builder {
                this.allowMultipleAdaptiveSelections = allowMultipleAdaptiveSelections
                return this
            }

            fun setRendererDisabled(rendererIndex: Int, disabled: Boolean): Builder {
                if (rendererDisabledFlags.get(rendererIndex) == disabled) {
                    // The disabled flag is unchanged.
                    return this
                }
                // Only true values are placed in the array to make it easier to check for equality.
                if (disabled) {
                    rendererDisabledFlags.put(rendererIndex, true)
                } else {
                    rendererDisabledFlags.delete(rendererIndex)
                }
                return this
            }

            @Deprecated(
                """Use {@link
       *     TrackSelectionParameters.Builder#addOverride(TrackSelectionOverride)}."""
            )
            fun setSelectionOverride(
                rendererIndex: Int, groups: TrackGroupArray, override: SelectionOverride?
            ): Builder {
                var overrides = selectionOverrides.get(rendererIndex)
                if (overrides == null) {
                    overrides = HashMap()
                    selectionOverrides.put(rendererIndex, overrides)
                }
                if (override == null) {
                    overrides.remove(groups)
                    return this
                }
                if (overrides.containsKey(groups) && overrides[groups] == override) {
                    // The override is unchanged.
                    return this
                }
                overrides[groups] = override
                return this
            }

            @Deprecated("Use {@link TrackSelectionParameters.Builder#clearOverride(TrackGroup)}.")
            fun clearSelectionOverride(rendererIndex: Int, groups: TrackGroupArray?): Builder {
                val overrides = selectionOverrides.get(rendererIndex)
                if (overrides == null || !overrides.containsKey(groups)) {
                    // Nothing to clear.
                    return this
                }
                overrides.remove(groups)
                if (overrides.isEmpty()) {
                    selectionOverrides.remove(rendererIndex)
                }
                return this
            }

            @Deprecated("Use {@link TrackSelectionParameters.Builder#clearOverridesOfType(int)}.")
            fun clearSelectionOverrides(rendererIndex: Int): Builder {
                val overrides = selectionOverrides.get(rendererIndex)
                if (overrides == null || overrides.isEmpty()) {
                    // Nothing to clear.
                    return this
                }
                selectionOverrides.remove(rendererIndex)
                return this
            }

            @Deprecated("Use {@link TrackSelectionParameters.Builder#clearOverrides()}.")
            fun clearSelectionOverrides(): Builder {
                if (selectionOverrides.isEmpty()) {
                    // Nothing to clear.
                    return this
                }
                selectionOverrides.clear()
                return this
            }

            override fun build(): Parameters {
                return Parameters(this)
            }

            private fun init() {
                // Video
                exceedVideoConstraintsIfNecessary = true
                allowVideoMixedMimeTypeAdaptiveness = false
                allowVideoNonSeamlessAdaptiveness = true
                allowVideoMixedDecoderSupportAdaptiveness = false
                // Audio
                exceedAudioConstraintsIfNecessary = true
                allowAudioMixedMimeTypeAdaptiveness = false
                allowAudioMixedSampleRateAdaptiveness = false
                allowAudioMixedChannelCountAdaptiveness = false
                allowAudioMixedDecoderSupportAdaptiveness = false
                allowAudioNonSeamlessAdaptiveness = true
                constrainAudioChannelCountToDeviceCapabilities = true
                // General
                exceedRendererCapabilitiesIfNecessary = true
                tunnelingEnabled = false
                allowMultipleAdaptiveSelections = true
                allowInvalidateSelectionsOnRendererCapabilitiesChange = false
            }

            @Suppress("deprecation") // Filling deprecated field from Bundle
            private fun setSelectionOverridesFromBundle(bundle: Bundle) {
                val rendererIndices =
                    bundle.getIntArray(FIELD_SELECTION_OVERRIDES_RENDERER_INDICES)
                val trackGroupArrayBundles =
                    bundle.getParcelableArrayList<Bundle?>(
                        FIELD_SELECTION_OVERRIDES_TRACK_GROUP_ARRAYS
                    )
                val trackGroupArrays: List<TrackGroupArray> =
                    if (trackGroupArrayBundles == null)
                        listOf()
                    else
                        BundleCollectionUtil.fromBundleList<TrackGroupArray>(
                            TrackGroupArray::fromBundle, trackGroupArrayBundles
                        )
                val selectionOverrideBundles =
                    bundle.getSparseParcelableArray<Bundle?>(FIELD_SELECTION_OVERRIDES)
                val selectionOverrides =
                    if (selectionOverrideBundles == null)
                        SparseArray<SelectionOverride?>()
                    else
                        fromBundleSparseArray<SelectionOverride>(
                            Function { bundle: Bundle? ->
                                SelectionOverride.fromBundle(
                                    bundle!!
                                )
                            }, selectionOverrideBundles
                        )

                if (rendererIndices == null || rendererIndices.size != trackGroupArrays.size) {
                    return  // Incorrect format, ignore all overrides.
                }
                for (i in rendererIndices.indices) {
                    val rendererIndex = rendererIndices[i]
                    val groups = trackGroupArrays[i]
                    val selectionOverride = selectionOverrides[i]
                    setSelectionOverride(rendererIndex, groups, selectionOverride)
                }
            }

            private fun makeSparseBooleanArrayFromTrueKeys(trueKeys: IntArray?): SparseBooleanArray {
                if (trueKeys == null) {
                    return SparseBooleanArray()
                }
                val sparseBooleanArray = SparseBooleanArray(trueKeys.size)
                for (trueKey in trueKeys) {
                    sparseBooleanArray.append(trueKey, true)
                }
                return sparseBooleanArray
            }

            companion object {
                private fun cloneSelectionOverrides(
                    selectionOverrides: SparseArray<MutableMap<TrackGroupArray, SelectionOverride?>>
                ): SparseArray<MutableMap<TrackGroupArray, SelectionOverride?>> {
                    val clone = SparseArray<MutableMap<TrackGroupArray, SelectionOverride?>>()
                    for (i in 0..<selectionOverrides.size) {
                        clone.put(
                            selectionOverrides.keyAt(i),
                            HashMap(selectionOverrides.valueAt(i))
                        )
                    }
                    return clone
                }
            }
        }

        val exceedVideoConstraintsIfNecessary: Boolean
        val allowVideoMixedMimeTypeAdaptiveness: Boolean
        val allowVideoNonSeamlessAdaptiveness: Boolean
        val allowVideoMixedDecoderSupportAdaptiveness: Boolean
        val exceedAudioConstraintsIfNecessary: Boolean
        val allowAudioMixedMimeTypeAdaptiveness: Boolean
        val allowAudioMixedSampleRateAdaptiveness: Boolean
        val allowAudioMixedChannelCountAdaptiveness: Boolean
        val allowAudioMixedDecoderSupportAdaptiveness: Boolean
        val allowAudioNonSeamlessAdaptiveness: Boolean
        val constrainAudioChannelCountToDeviceCapabilities: Boolean
        val exceedRendererCapabilitiesIfNecessary: Boolean
        val tunnelingEnabled: Boolean
        val allowMultipleAdaptiveSelections: Boolean
        val allowInvalidateSelectionsOnRendererCapabilitiesChange: Boolean
        private val selectionOverrides: SparseArray<MutableMap<TrackGroupArray, SelectionOverride?>>
        private val rendererDisabledFlags: SparseBooleanArray

        fun getRendererDisabled(rendererIndex: Int): Boolean {
            return rendererDisabledFlags.get(rendererIndex)
        }

        @Deprecated(
            """Only works to retrieve the overrides set with the deprecated {@link
     *     Builder#setSelectionOverride(int, TrackGroupArray, SelectionOverride)}. Use {@link
     *     TrackSelectionParameters#overrides} instead."""
        )
        fun hasSelectionOverride(rendererIndex: Int, groups: TrackGroupArray): Boolean {
            val overrides = selectionOverrides.get(rendererIndex)
            return overrides != null && overrides.containsKey(groups)
        }

        @Deprecated(
            """Only works to retrieve the overrides set with the deprecated {@link
     *     Builder#setSelectionOverride(int, TrackGroupArray, SelectionOverride)}. Use {@link
     *     TrackSelectionParameters#overrides} instead."""
        )
        fun getSelectionOverride(rendererIndex: Int, groups: TrackGroupArray): SelectionOverride? {
            val overrides = selectionOverrides.get(rendererIndex)
            return if (overrides != null) overrides.get(groups) else null
        }

        override fun buildUpon(): Builder {
            return Parameters.Builder(this)
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj == null || javaClass != obj.javaClass) {
                return false
            }
            val other = obj as Parameters
            return super.equals(other) // Video
                    && exceedVideoConstraintsIfNecessary == other.exceedVideoConstraintsIfNecessary && allowVideoMixedMimeTypeAdaptiveness == other.allowVideoMixedMimeTypeAdaptiveness && allowVideoNonSeamlessAdaptiveness == other.allowVideoNonSeamlessAdaptiveness && (allowVideoMixedDecoderSupportAdaptiveness
                    == other.allowVideoMixedDecoderSupportAdaptiveness) // Audio
                    && exceedAudioConstraintsIfNecessary == other.exceedAudioConstraintsIfNecessary && allowAudioMixedMimeTypeAdaptiveness == other.allowAudioMixedMimeTypeAdaptiveness && allowAudioMixedSampleRateAdaptiveness == other.allowAudioMixedSampleRateAdaptiveness && (allowAudioMixedChannelCountAdaptiveness
                    == other.allowAudioMixedChannelCountAdaptiveness) && (allowAudioMixedDecoderSupportAdaptiveness
                    == other.allowAudioMixedDecoderSupportAdaptiveness) && allowAudioNonSeamlessAdaptiveness == other.allowAudioNonSeamlessAdaptiveness && (constrainAudioChannelCountToDeviceCapabilities
                    == other.constrainAudioChannelCountToDeviceCapabilities) // General
                    && exceedRendererCapabilitiesIfNecessary == other.exceedRendererCapabilitiesIfNecessary && tunnelingEnabled == other.tunnelingEnabled && allowMultipleAdaptiveSelections == other.allowMultipleAdaptiveSelections && (allowInvalidateSelectionsOnRendererCapabilitiesChange
                    == other.allowInvalidateSelectionsOnRendererCapabilitiesChange) // Overrides
                    && areRendererDisabledFlagsEqual(
                rendererDisabledFlags,
                other.rendererDisabledFlags
            )
                    && areSelectionOverridesEqual(selectionOverrides, other.selectionOverrides)
        }

        override fun hashCode(): Int {
            var result = 1
            result = 31 * result + super.hashCode()
            // Video
            result = 31 * result + (if (exceedVideoConstraintsIfNecessary) 1 else 0)
            result = 31 * result + (if (allowVideoMixedMimeTypeAdaptiveness) 1 else 0)
            result = 31 * result + (if (allowVideoNonSeamlessAdaptiveness) 1 else 0)
            result = 31 * result + (if (allowVideoMixedDecoderSupportAdaptiveness) 1 else 0)
            // Audio
            result = 31 * result + (if (exceedAudioConstraintsIfNecessary) 1 else 0)
            result = 31 * result + (if (allowAudioMixedMimeTypeAdaptiveness) 1 else 0)
            result = 31 * result + (if (allowAudioMixedSampleRateAdaptiveness) 1 else 0)
            result = 31 * result + (if (allowAudioMixedChannelCountAdaptiveness) 1 else 0)
            result = 31 * result + (if (allowAudioMixedDecoderSupportAdaptiveness) 1 else 0)
            result = 31 * result + (if (allowAudioNonSeamlessAdaptiveness) 1 else 0)
            result = 31 * result + (if (constrainAudioChannelCountToDeviceCapabilities) 1 else 0)
            // General
            result = 31 * result + (if (exceedRendererCapabilitiesIfNecessary) 1 else 0)
            result = 31 * result + (if (tunnelingEnabled) 1 else 0)
            result = 31 * result + (if (allowMultipleAdaptiveSelections) 1 else 0)
            result =
                31 * result + (if (allowInvalidateSelectionsOnRendererCapabilitiesChange) 1 else 0)
            // Overrides (omitted from hashCode).
            return result
        }

        init {
            // Video
            exceedVideoConstraintsIfNecessary = builder.exceedVideoConstraintsIfNecessary
            allowVideoMixedMimeTypeAdaptiveness = builder.allowVideoMixedMimeTypeAdaptiveness
            allowVideoNonSeamlessAdaptiveness = builder.allowVideoNonSeamlessAdaptiveness
            allowVideoMixedDecoderSupportAdaptiveness =
                builder.allowVideoMixedDecoderSupportAdaptiveness
            // Audio
            exceedAudioConstraintsIfNecessary = builder.exceedAudioConstraintsIfNecessary
            allowAudioMixedMimeTypeAdaptiveness = builder.allowAudioMixedMimeTypeAdaptiveness
            allowAudioMixedSampleRateAdaptiveness = builder.allowAudioMixedSampleRateAdaptiveness
            allowAudioMixedChannelCountAdaptiveness =
                builder.allowAudioMixedChannelCountAdaptiveness
            allowAudioMixedDecoderSupportAdaptiveness =
                builder.allowAudioMixedDecoderSupportAdaptiveness
            allowAudioNonSeamlessAdaptiveness = builder.allowAudioNonSeamlessAdaptiveness
            constrainAudioChannelCountToDeviceCapabilities =
                builder.constrainAudioChannelCountToDeviceCapabilities
            // General
            exceedRendererCapabilitiesIfNecessary = builder.exceedRendererCapabilitiesIfNecessary
            tunnelingEnabled = builder.tunnelingEnabled
            allowMultipleAdaptiveSelections = builder.allowMultipleAdaptiveSelections
            allowInvalidateSelectionsOnRendererCapabilitiesChange =
                builder.allowInvalidateSelectionsOnRendererCapabilitiesChange
            // Overrides
            selectionOverrides = builder.selectionOverrides
            rendererDisabledFlags = builder.rendererDisabledFlags
        }

        override fun toBundle(): Bundle {
            val bundle = super.toBundle()

            // Video
            bundle.putBoolean(
                FIELD_EXCEED_VIDEO_CONSTRAINTS_IF_NECESSARY, exceedVideoConstraintsIfNecessary
            )
            bundle.putBoolean(
                FIELD_ALLOW_VIDEO_MIXED_MIME_TYPE_ADAPTIVENESS, allowVideoMixedMimeTypeAdaptiveness
            )
            bundle.putBoolean(
                FIELD_ALLOW_VIDEO_NON_SEAMLESS_ADAPTIVENESS, allowVideoNonSeamlessAdaptiveness
            )
            bundle.putBoolean(
                FIELD_ALLOW_VIDEO_MIXED_DECODER_SUPPORT_ADAPTIVENESS,
                allowVideoMixedDecoderSupportAdaptiveness
            )
            // Audio
            bundle.putBoolean(
                FIELD_EXCEED_AUDIO_CONSTRAINTS_IF_NECESSARY, exceedAudioConstraintsIfNecessary
            )
            bundle.putBoolean(
                FIELD_ALLOW_AUDIO_MIXED_MIME_TYPE_ADAPTIVENESS, allowAudioMixedMimeTypeAdaptiveness
            )
            bundle.putBoolean(
                FIELD_ALLOW_AUDIO_MIXED_SAMPLE_RATE_ADAPTIVENESS,
                allowAudioMixedSampleRateAdaptiveness
            )
            bundle.putBoolean(
                FIELD_ALLOW_AUDIO_MIXED_CHANNEL_COUNT_ADAPTIVENESS,
                allowAudioMixedChannelCountAdaptiveness
            )
            bundle.putBoolean(
                FIELD_ALLOW_AUDIO_MIXED_DECODER_SUPPORT_ADAPTIVENESS,
                allowAudioMixedDecoderSupportAdaptiveness
            )
            bundle.putBoolean(
                FIELD_ALLOW_AUDIO_NON_SEAMLESS_ADAPTIVENESS, allowAudioNonSeamlessAdaptiveness
            )
            bundle.putBoolean(
                FIELD_CONSTRAIN_AUDIO_CHANNEL_COUNT_TO_DEVICE_CAPABILITIES,
                constrainAudioChannelCountToDeviceCapabilities
            )
            // General
            bundle.putBoolean(
                FIELD_EXCEED_RENDERER_CAPABILITIES_IF_NECESSARY,
                exceedRendererCapabilitiesIfNecessary
            )
            bundle.putBoolean(FIELD_TUNNELING_ENABLED, tunnelingEnabled)
            bundle.putBoolean(
                FIELD_ALLOW_MULTIPLE_ADAPTIVE_SELECTIONS,
                allowMultipleAdaptiveSelections
            )
            bundle.putBoolean(
                FIELD_ALLOW_INVALIDATE_SELECTIONS_ON_RENDERER_CAPABILITIES_CHANGE,
                allowInvalidateSelectionsOnRendererCapabilitiesChange
            )

            putSelectionOverridesToBundle(bundle, selectionOverrides)
            // Only true values are put into rendererDisabledFlags.
            bundle.putIntArray(
                FIELD_RENDERER_DISABLED_INDICES,
                getKeysFromSparseBooleanArray(rendererDisabledFlags)
            )

            return bundle
        }

        companion object {
            val DEFAULT: Parameters = Builder().build()

            @Deprecated("Use {@link #DEFAULT} instead.")
            val DEFAULT_WITHOUT_CONTEXT: Parameters = DEFAULT

            @Deprecated("Use {@link #DEFAULT} instead.")
            fun getDefaults(context: Context?): Parameters {
                return DEFAULT
            }

            private val FIELD_EXCEED_VIDEO_CONSTRAINTS_IF_NECESSARY = intToStringMaxRadix(
                FIELD_CUSTOM_ID_BASE
            )
            private val FIELD_ALLOW_VIDEO_MIXED_MIME_TYPE_ADAPTIVENESS = intToStringMaxRadix(
                FIELD_CUSTOM_ID_BASE + 1
            )
            private val FIELD_ALLOW_VIDEO_NON_SEAMLESS_ADAPTIVENESS = intToStringMaxRadix(
                FIELD_CUSTOM_ID_BASE + 2
            )
            private val FIELD_EXCEED_AUDIO_CONSTRAINTS_IF_NECESSARY = intToStringMaxRadix(
                FIELD_CUSTOM_ID_BASE + 3
            )
            private val FIELD_ALLOW_AUDIO_MIXED_MIME_TYPE_ADAPTIVENESS = intToStringMaxRadix(
                FIELD_CUSTOM_ID_BASE + 4
            )
            private val FIELD_ALLOW_AUDIO_MIXED_SAMPLE_RATE_ADAPTIVENESS = intToStringMaxRadix(
                FIELD_CUSTOM_ID_BASE + 5
            )
            private val FIELD_ALLOW_AUDIO_MIXED_CHANNEL_COUNT_ADAPTIVENESS = intToStringMaxRadix(
                FIELD_CUSTOM_ID_BASE + 6
            )
            private val FIELD_EXCEED_RENDERER_CAPABILITIES_IF_NECESSARY = intToStringMaxRadix(
                FIELD_CUSTOM_ID_BASE + 7
            )
            private val FIELD_TUNNELING_ENABLED = intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 8)
            private val FIELD_ALLOW_MULTIPLE_ADAPTIVE_SELECTIONS = intToStringMaxRadix(
                FIELD_CUSTOM_ID_BASE + 9
            )
            private val FIELD_SELECTION_OVERRIDES_RENDERER_INDICES = intToStringMaxRadix(
                FIELD_CUSTOM_ID_BASE + 10
            )
            private val FIELD_SELECTION_OVERRIDES_TRACK_GROUP_ARRAYS = intToStringMaxRadix(
                FIELD_CUSTOM_ID_BASE + 11
            )
            private val FIELD_SELECTION_OVERRIDES = intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 12)
            private val FIELD_RENDERER_DISABLED_INDICES =
                intToStringMaxRadix(FIELD_CUSTOM_ID_BASE + 13)
            private val FIELD_ALLOW_VIDEO_MIXED_DECODER_SUPPORT_ADAPTIVENESS = intToStringMaxRadix(
                FIELD_CUSTOM_ID_BASE + 14
            )
            private val FIELD_ALLOW_AUDIO_MIXED_DECODER_SUPPORT_ADAPTIVENESS = intToStringMaxRadix(
                FIELD_CUSTOM_ID_BASE + 15
            )
            private val FIELD_CONSTRAIN_AUDIO_CHANNEL_COUNT_TO_DEVICE_CAPABILITIES =
                intToStringMaxRadix(
                    FIELD_CUSTOM_ID_BASE + 16
                )
            private val FIELD_ALLOW_INVALIDATE_SELECTIONS_ON_RENDERER_CAPABILITIES_CHANGE =
                intToStringMaxRadix(
                    FIELD_CUSTOM_ID_BASE + 17
                )
            private val FIELD_ALLOW_AUDIO_NON_SEAMLESS_ADAPTIVENESS = intToStringMaxRadix(
                FIELD_CUSTOM_ID_BASE + 18
            )

            fun fromBundle(bundle: Bundle): Parameters {
                return Parameters.Builder(bundle).build()
            }

            private fun putSelectionOverridesToBundle(
                bundle: Bundle,
                selectionOverrides: SparseArray<MutableMap<TrackGroupArray, SelectionOverride?>>
            ) {
                val rendererIndices = ArrayList<Int>()
                val trackGroupArrays = ArrayList<TrackGroupArray>()
                val selections = SparseArray<SelectionOverride>()

                for (i in 0..<selectionOverrides.size) {
                    val rendererIndex = selectionOverrides.keyAt(i)
                    for (override in selectionOverrides.valueAt(i)!!.entries) {
                        val selection = override.value
                        if (selection != null) {
                            selections.put(trackGroupArrays.size, selection)
                        }
                        trackGroupArrays.add(override.key)
                        rendererIndices.add(rendererIndex)
                    }
                    bundle.putIntArray(
                        FIELD_SELECTION_OVERRIDES_RENDERER_INDICES, Ints.toArray(rendererIndices)
                    )
                    bundle.putParcelableArrayList(
                        FIELD_SELECTION_OVERRIDES_TRACK_GROUP_ARRAYS,
                        BundleCollectionUtil.toBundleArrayList(
                            trackGroupArrays
                        ) { obj: TrackGroupArray -> obj.toBundle() }
                    )
                    bundle.putSparseParcelableArray(
                        FIELD_SELECTION_OVERRIDES,
                        toBundleSparseArray(
                            selections
                        ) { obj: SelectionOverride -> obj.toBundle() }
                    )
                }
            }

            private fun getKeysFromSparseBooleanArray(sparseBooleanArray: SparseBooleanArray): IntArray {
                val keys = IntArray(sparseBooleanArray.size)
                for (i in 0..<sparseBooleanArray.size) {
                    keys[i] = sparseBooleanArray.keyAt(i)
                }
                return keys
            }

            private fun areRendererDisabledFlagsEqual(
                first: SparseBooleanArray, second: SparseBooleanArray
            ): Boolean {
                val firstSize = first.size
                if (second.size != firstSize) {
                    return false
                }
                // Only true values are put into rendererDisabledFlags, so we don't need to compare values.
                for (indexInFirst in 0..<firstSize) {
                    if (second.indexOfKey(first.keyAt(indexInFirst)) < 0) {
                        return false
                    }
                }
                return true
            }

            private fun areSelectionOverridesEqual(
                first: SparseArray<MutableMap<TrackGroupArray, SelectionOverride?>>,
                second: SparseArray<MutableMap<TrackGroupArray, SelectionOverride?>>
            ): Boolean {
                val firstSize = first.size
                if (second.size != firstSize) {
                    return false
                }
                for (indexInFirst in 0..<firstSize) {
                    val indexInSecond = second.indexOfKey(first.keyAt(indexInFirst))
                    if (indexInSecond < 0
                        || !areSelectionOverridesEqual(
                            first.valueAt(indexInFirst)!!, second.valueAt(indexInSecond)!!
                        )
                    ) {
                        return false
                    }
                }
                return true
            }

            private fun areSelectionOverridesEqual(
                first: MutableMap<TrackGroupArray, SelectionOverride?>,
                second: MutableMap<TrackGroupArray, SelectionOverride?>
            ): Boolean {
                val firstSize = first.size
                if (second.size != firstSize) {
                    return false
                }
                for (firstEntry in first.entries) {
                    val key = firstEntry.key
                    if (!second.containsKey(key) || firstEntry.value != second.get(key)) {
                        return false
                    }
                }
                return true
            }
        }
    }

    class SelectionOverride(
        val groupIndex: Int,
        tracks: IntArray,
        val type: @TrackSelection.Type Int
    ) {
        val tracks: IntArray = tracks.copyOf(tracks.size)
        val length: Int = tracks.size

        constructor(groupIndex: Int, vararg tracks: Int) : this(
            groupIndex,
            tracks,
            TrackSelection.TYPE_UNSET
        )

        fun containsTrack(track: Int): Boolean {
            for (overrideTrack in tracks) {
                if (overrideTrack == track) {
                    return true
                }
            }
            return false
        }

        override fun hashCode(): Int {
            val hash = 31 * groupIndex + tracks.contentHashCode()
            return 31 * hash + type
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other == null || javaClass != other.javaClass) {
                return false
            }
            other as SelectionOverride
            return groupIndex == other.groupIndex && tracks.contentEquals(other.tracks) && type == other.type
        }

        init {
            Arrays.sort(this.tracks)
        }

        fun toBundle(): Bundle {
            val bundle = Bundle()
            bundle.putInt(FIELD_GROUP_INDEX, groupIndex)
            bundle.putIntArray(FIELD_TRACKS, tracks)
            bundle.putInt(FIELD_TRACK_TYPE, type)
            return bundle
        }

        companion object {
            private val FIELD_GROUP_INDEX = intToStringMaxRadix(0)
            private val FIELD_TRACKS = intToStringMaxRadix(1)
            private val FIELD_TRACK_TYPE = intToStringMaxRadix(2)

            fun fromBundle(bundle: Bundle): SelectionOverride {
                val groupIndex = bundle.getInt(FIELD_GROUP_INDEX, -1)
                val tracks = bundle.getIntArray(FIELD_TRACKS)
                val trackType = bundle.getInt(FIELD_TRACK_TYPE, -1)
                Preconditions.checkArgument(groupIndex >= 0 && trackType >= 0)
                return SelectionOverride(groupIndex, tracks!!, trackType)
            }
        }
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [SELECTION_ELIGIBILITY_NO, SELECTION_ELIGIBILITY_FIXED, SELECTION_ELIGIBILITY_ADAPTIVE])
    protected annotation class SelectionEligibility

    val context: Context?

    private var applicationParameters: Parameters
    private var playerParameters: Parameters

    @Volatile
    private var playbackThread: Thread? = null

    private var spatializer: SpatializerWrapper? = null
    private var audioAttributes: AudioAttributes
    private var deviceIsTV: Boolean? = null

    constructor(
        context: Context?,
        trackSelectionFactory: ExoTrackSelection.Factory = AdaptiveTrackSelection.Factory()
    ) : this(context, Parameters.DEFAULT, trackSelectionFactory)

    // TODO: When this constructor is deleted, this.context can be made non-null and all
    //       null-conditional can be removed (including the warning logging when it's null).
    @Deprecated(
        """Use {@link #DefaultTrackSelector(Context, TrackSelectionParameters,
   *     ExoTrackSelection.Factory)}"""
    )
    constructor(
        parameters: TrackSelectionParameters,
        trackSelectionFactory: ExoTrackSelection.Factory
    ) : this(parameters, trackSelectionFactory, null)

    constructor(
        context: Context?,
        parameters: TrackSelectionParameters,
        trackSelectionFactory: ExoTrackSelection.Factory = AdaptiveTrackSelection.Factory()
    ) : this(parameters, trackSelectionFactory, context)

    init {
        this.context = context?.applicationContext
        val initialParameters: Parameters
        if (parameters is Parameters) {
            initialParameters = parameters
        } else {
            initialParameters = Parameters.DEFAULT.buildUpon().set(parameters).build()
        }
        this.applicationParameters = initialParameters
        this.playerParameters = initialParameters
        this.audioAttributes = AudioAttributes.DEFAULT
        if (initialParameters.constrainAudioChannelCountToDeviceCapabilities && context == null) {
            w(TAG, AUDIO_CHANNEL_COUNT_CONSTRAINTS_WARN_MESSAGE)
        }
    }

    override fun init(listener: InvalidationListener?, bandwidthMeter: BandwidthMeter) {
        super.init(listener, bandwidthMeter)
        this.playerParameters = parameters as Parameters
    }

    override fun onParametersActivated(parameters: TrackSelectionParameters?) {
        if (parameters == null) {
            return
        }
        if (parameters is Parameters) {
            this.playerParameters = parameters
        } else {
            this.playerParameters =
                Parameters.Builder(this.playerParameters).set(parameters).build()
        }
    }

    override fun release() {
        if (playbackThread != null) {
            Preconditions.checkState(
                Thread.currentThread() == playbackThread,
                "DefaultTrackSelector is accessed on the wrong thread."
            )
        }
        if (Build.VERSION.SDK_INT >= 32 && spatializer != null) {
            spatializer!!.release()
            spatializer = null
        }
        super.release()
    }

    override var parameters: TrackSelectionParameters?
        get() = applicationParameters
        set(value) {
            if (value is Parameters) {
                setParametersInternal(value)
            }
            // Only add the fields of `TrackSelectionParameters` to `parameters`.
            val mergedParameters = Parameters.Builder(applicationParameters).set(value!!).build()
            setParametersInternal(mergedParameters)
        }

    override val isSetParametersSupported: Boolean = true

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        if (this.audioAttributes == audioAttributes) {
            return
        }
        this.audioAttributes = audioAttributes
        maybeInvalidateForAudioChannelCountConstraints()
    }

    fun setParameters(parametersBuilder: Parameters.Builder) {
        setParametersInternal(parametersBuilder.build())
    }

    fun buildUponParameters(): Parameters.Builder {
        return applicationParameters.buildUpon()
    }

    private fun setParametersInternal(parameters: Parameters) {
        val parametersChanged = this.applicationParameters != parameters
        this.applicationParameters = parameters

        if (parametersChanged) {
            if (parameters.constrainAudioChannelCountToDeviceCapabilities && context == null) {
                w(TAG, AUDIO_CHANNEL_COUNT_CONSTRAINTS_WARN_MESSAGE)
            }
            invalidate(parameters)
        }
    }

    override val rendererCapabilitiesListener: RendererCapabilities.Listener?
        get() = this

    // RendererCapabilities.Listener implementation.
    override fun onRendererCapabilitiesChanged(renderer: Renderer) {
        maybeInvalidateForRendererCapabilitiesChange(renderer)
    }

    @Throws(ExoPlaybackException::class)
    override fun selectTracks(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupport: IntArray,
        mediaPeriodId: MediaPeriodId,
        timeline: Timeline
    ): Pair<Array<RendererConfiguration?>, Array<ExoTrackSelection?>> {
        playbackThread = Thread.currentThread()
        if (deviceIsTV == null && context != null) {
            deviceIsTV = isTv(context)
        }
        if (playerParameters.constrainAudioChannelCountToDeviceCapabilities
            && Build.VERSION.SDK_INT >= 32 && spatializer == null
        ) {
            spatializer =
                SpatializerWrapper(
                    context,
                    { this.maybeInvalidateForAudioChannelCountConstraints() },
                    deviceIsTV
                )
        }

        val rendererCount = mappedTrackInfo.rendererCount
        val definitions = arrayOfNulls<ExoTrackSelection.Definition>(rendererCount)

        // Apply overrides once so that selectAllTracks is aware of the overridden tracks.
        applyTrackSelectionOverrides(mappedTrackInfo, playerParameters, definitions)
        applyLegacyRendererOverrides(mappedTrackInfo, playerParameters, definitions)
        applyRendererDisableOverrides(mappedTrackInfo, playerParameters, definitions)

        // Select remaining tracks based on parameters.
        selectAllTracks(
            definitions,
            mappedTrackInfo,
            rendererFormatSupports,
            rendererMixedMimeTypeAdaptationSupport,
            playerParameters
        )

        // Re-apply overrides to enforce them in case selectAllTracks changed them or added additional
        // tracks that shouldn't be enabled according to the overrides.
        applyTrackSelectionOverrides(mappedTrackInfo, playerParameters, definitions)
        applyLegacyRendererOverrides(mappedTrackInfo, playerParameters, definitions)
        applyRendererDisableOverrides(mappedTrackInfo, playerParameters, definitions)

        val rendererTrackSelections =
            trackSelectionFactory.createTrackSelections(
                definitions, getBandwidthMeter(), mediaPeriodId, timeline
            )

        // Initialize the renderer configurations to the default configuration for all renderers with
        // selections, and null otherwise.
        val rendererConfigurations = arrayOfNulls<RendererConfiguration>(rendererCount)
        for (i in 0..<rendererCount) {
            val rendererType: @C.TrackType Int = mappedTrackInfo.getRendererType(i)
            val forceRendererDisabled =
                playerParameters.getRendererDisabled(i)
                        || playerParameters.disabledTrackTypes.contains(rendererType)
            val rendererEnabled =
                !forceRendererDisabled
                        && (mappedTrackInfo.getRendererType(i) == C.TRACK_TYPE_NONE
                        || rendererTrackSelections[i] != null)
            rendererConfigurations[i] = if (rendererEnabled) RendererConfiguration.DEFAULT else null
        }

        // Configure audio and video renderers to use tunneling if appropriate.
        if (playerParameters.tunnelingEnabled) {
            maybeConfigureRenderersForTunneling(
                mappedTrackInfo,
                rendererFormatSupports,
                rendererConfigurations,
                rendererTrackSelections
            )
        }

        // Configure audio renderer to use offload if appropriate.
        if (playerParameters.audioOffloadPreferences.audioOffloadMode != TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED) {
            maybeConfigureRendererForOffload(
                playerParameters,
                mappedTrackInfo,
                rendererFormatSupports,
                rendererConfigurations,
                rendererTrackSelections
            )
        }

        return Pair.create<Array<RendererConfiguration?>, Array<ExoTrackSelection?>>(
            rendererConfigurations,
            rendererTrackSelections
        )
    }

    @Throws(ExoPlaybackException::class)
    protected fun selectAllTracks(
        definitions: Array<ExoTrackSelection.Definition?>,
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupports: IntArray,
        params: Parameters
    ) {
        val rendererCount = mappedTrackInfo.rendererCount

        var selectedAudio: Pair<ExoTrackSelection.Definition, Int>? =
            findDefinitionForType(definitions, C.TRACK_TYPE_AUDIO)
        if (selectedAudio == null) {
            selectedAudio =
                selectAudioTrack(
                    mappedTrackInfo,
                    rendererFormatSupports,
                    rendererMixedMimeTypeAdaptationSupports,
                    params
                )
            if (selectedAudio != null) {
                definitions[selectedAudio.second!!] = selectedAudio.first
            }
        }
        val selectedAudioLanguage =
            if (selectedAudio == null) null
            else
                selectedAudio.first!!.group.getFormat(selectedAudio.first!!.tracks[0]).language

        var selectedVideo: Pair<ExoTrackSelection.Definition, Int>? =
            findDefinitionForType(definitions, C.TRACK_TYPE_VIDEO)
        var selectedImage: Pair<ExoTrackSelection.Definition, Int>? =
            findDefinitionForType(definitions, C.TRACK_TYPE_IMAGE)
        if (selectedVideo == null && selectedImage == null) {
            selectedVideo =
                selectVideoTrack(
                    mappedTrackInfo,
                    rendererFormatSupports,
                    rendererMixedMimeTypeAdaptationSupports,
                    params,
                    selectedAudioLanguage
                )
            selectedImage =
                if (params.isPrioritizeImageOverVideoEnabled || selectedVideo == null)
                    selectImageTrack(mappedTrackInfo, rendererFormatSupports, params)
                else
                    null
            if (selectedImage != null) {
                definitions[selectedImage.second!!] = selectedImage.first
            } else if (selectedVideo != null) {
                definitions[selectedVideo.second!!] = selectedVideo.first
            }
        }

        var selectedText: Pair<ExoTrackSelection.Definition, Int>? =
            findDefinitionForType(definitions, C.TRACK_TYPE_TEXT)
        if (selectedText == null) {
            selectedText =
                selectTextTrack(
                    mappedTrackInfo,
                    rendererFormatSupports,
                    params,
                    selectedAudioLanguage
                )
            if (selectedText != null) {
                definitions[selectedText.second!!] = selectedText.first
            }
        }

        selectMetadataTracks(definitions, mappedTrackInfo, rendererFormatSupports, params)

        for (i in 0..<rendererCount) {
            val trackType = mappedTrackInfo.getRendererType(i)
            if (trackType != C.TRACK_TYPE_VIDEO && trackType != C.TRACK_TYPE_AUDIO && trackType != C.TRACK_TYPE_TEXT && trackType != C.TRACK_TYPE_IMAGE && trackType != C.TRACK_TYPE_METADATA && definitions[i] == null) {
                definitions[i] =
                    selectOtherTrack(
                        trackType,
                        mappedTrackInfo.getTrackGroups(i),
                        rendererFormatSupports[i],
                        params
                    )
            }
        }
    }

    @Throws(ExoPlaybackException::class)
    protected fun selectVideoTrack(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        mixedMimeTypeSupports: IntArray,
        params: Parameters,
        selectedAudioLanguage: String?
    ): Pair<ExoTrackSelection.Definition, Int>? {
        if (params.audioOffloadPreferences.audioOffloadMode == TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_REQUIRED) {
            return null
        }
        val viewportSizeFromDisplay =
            if (params.isViewportSizeLimitedByPhysicalDisplaySize && context != null)
                getCurrentDisplayModeSize(context)
            else
                null
        return selectTracksForType(
            C.TRACK_TYPE_VIDEO,
            mappedTrackInfo,
            rendererFormatSupports,
            { rendererIndex: Int, group: TrackGroup?, support: IntArray? ->
                VideoTrackInfo.createForTrackGroup(
                    rendererIndex,
                    group!!,
                    params,
                    support!!,
                    selectedAudioLanguage,
                    mixedMimeTypeSupports[rendererIndex],
                    viewportSizeFromDisplay
                )
            },
            { infos1: List<VideoTrackInfo>, infos2: List<VideoTrackInfo> ->
                VideoTrackInfo.compareSelections(infos1, infos2)
            })
    }

    @Throws(ExoPlaybackException::class)
    protected fun selectAudioTrack(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupports: IntArray,
        params: Parameters
    ): Pair<ExoTrackSelection.Definition, Int>? {
        var hasVideoRendererWithMappedTracks = false
        for (i in 0..<mappedTrackInfo.rendererCount) {
            if (C.TRACK_TYPE_VIDEO == mappedTrackInfo.getRendererType(i)
                && mappedTrackInfo.getTrackGroups(i).length > 0
            ) {
                hasVideoRendererWithMappedTracks = true
                break
            }
        }
        val hasVideoRendererWithMappedTracksFinal = hasVideoRendererWithMappedTracks
        return selectTracksForType(
            C.TRACK_TYPE_AUDIO,
            mappedTrackInfo,
            rendererFormatSupports,
            { rendererIndex: Int, group: TrackGroup?, support: IntArray? ->
                AudioTrackInfo.createForTrackGroup(
                    rendererIndex,
                    group!!,
                    params,
                    support!!,
                    hasVideoRendererWithMappedTracksFinal,
                    { format: Format? ->
                        isAudioFormatWithinAudioChannelCountConstraints(
                            format!!,
                            params
                        )
                    },
                    rendererMixedMimeTypeAdaptationSupports[rendererIndex]
                )
            },
            { infos1: List<AudioTrackInfo>, infos2: List<AudioTrackInfo> ->
                AudioTrackInfo.compareSelections(infos1, infos2)
            })
    }

    private fun isAudioFormatWithinAudioChannelCountConstraints(
        format: Format, parameters: Parameters
    ): Boolean {
        return !parameters.constrainAudioChannelCountToDeviceCapabilities || (deviceIsTV != null && deviceIsTV == true)
                || (format.channelCount == Format.NO_VALUE || format.channelCount <= 2)
                || (isDolbyAudio(format)
                && (Build.VERSION.SDK_INT < 32 || spatializer == null || !spatializer!!.isSpatializationSupported))
                || (Build.VERSION.SDK_INT >= 32 && spatializer != null && spatializer!!.isSpatializationSupported
                && spatializer!!.isAvailable
                && spatializer!!.isEnabled
                && spatializer!!.canBeSpatialized(audioAttributes, format))
    }

    @Throws(ExoPlaybackException::class)
    protected fun selectTextTrack(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        params: Parameters,
        selectedAudioLanguage: String?
    ): Pair<ExoTrackSelection.Definition, Int>? {
        if (params.audioOffloadPreferences.audioOffloadMode == TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_REQUIRED) {
            return null
        }
        val preferredCaptioningLanguage: String? =
            if (params.usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager)
                getPreferredLanguageFromCaptioningManager(context)
            else
                null
        return selectTracksForType(
            C.TRACK_TYPE_TEXT,
            mappedTrackInfo,
            rendererFormatSupports,
            { rendererIndex: Int, group: TrackGroup?, support: IntArray? ->
                TextTrackInfo.createForTrackGroup(
                    rendererIndex,
                    group!!,
                    params,
                    support!!,
                    selectedAudioLanguage,
                    preferredCaptioningLanguage
                )
            },
            { infos1: List<TextTrackInfo>, infos2: List<TextTrackInfo> ->
                TextTrackInfo.compareSelections(infos1, infos2)
            })
    }

    @Throws(ExoPlaybackException::class)
    protected fun selectImageTrack(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        params: Parameters
    ): Pair<ExoTrackSelection.Definition, Int>? {
        if (params.audioOffloadPreferences.audioOffloadMode == TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_REQUIRED) {
            return null
        }
        return selectTracksForType(
            C.TRACK_TYPE_IMAGE,
            mappedTrackInfo,
            rendererFormatSupports,
            { rendererIndex: Int, group: TrackGroup?, support: IntArray? ->
                ImageTrackInfo.createForTrackGroup(
                    rendererIndex,
                    group!!,
                    params,
                    support!!
                )
            },
            { infos1: List<ImageTrackInfo>, infos2: List<ImageTrackInfo> ->
                ImageTrackInfo.compareSelections(infos1, infos2)
            })
    }

    @Throws(ExoPlaybackException::class)
    protected fun selectMetadataTracks(
        definitions: Array<ExoTrackSelection.Definition?>,
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        params: Parameters
    ) {
        if (params.audioOffloadPreferences.audioOffloadMode == TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_REQUIRED) {
            return
        }
        val primaryTrackGroupIds: Set<String?> =
            getSelectedPrimaryTrackGroupIds(definitions, params)
        // Merge all metadata tracks together to support multiple metadata renderers.
        val metadataGroupsList = ArrayList<TrackGroup>()
        val metadataGroupSupportList = ArrayList<IntArray>()
        for (i in 0..<mappedTrackInfo.rendererCount) {
            val trackType = mappedTrackInfo.getRendererType(i)
            if (trackType != C.TRACK_TYPE_METADATA) {
                continue
            }
            val metadataTracksForRenderer = mappedTrackInfo.getTrackGroups(i)
            for (j in 0..<metadataTracksForRenderer.length) {
                val trackGroup = metadataTracksForRenderer.get(j)
                metadataGroupsList.add(trackGroup)
                val groupSupport = rendererFormatSupports[i][j].clone()
                for (k in groupSupport.indices) {
                    val format = trackGroup.getFormat(k)
                    if (format.primaryTrackGroupId != null
                        && !primaryTrackGroupIds.contains(format.primaryTrackGroupId)
                    ) {
                        // Mark track as unsupported if it's muxed and the matching primary group is not active.
                        groupSupport[k] = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
                    }
                }
                metadataGroupSupportList.add(groupSupport)
            }
        }
        val metadataGroupsArray = metadataGroupsList.toTypedArray()
        val metadataGroups = TrackGroupArray(metadataGroupsArray)
        val metadataGroupSupport: Array<IntArray> = metadataGroupSupportList.toTypedArray()
        // Find the best metadata track per renderer, remove support for the selected track from the
        // list and continue until all metadata renderers have a track or no new track is selected.
        for (i in 0..<mappedTrackInfo.rendererCount) {
            val trackType = mappedTrackInfo.getRendererType(i)
            if (trackType != C.TRACK_TYPE_METADATA) {
                continue
            }
            definitions[i] =
                selectOtherTrack(
                    C.TRACK_TYPE_METADATA,
                    metadataGroups,
                    metadataGroupSupport,
                    params
                )
            if (definitions[i] != null) {
                val groupIndex = metadataGroups.indexOf(definitions[i]!!.group)
                Arrays.fill(
                    metadataGroupSupport[groupIndex],
                    RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
                )
            } else {
                break
            }
        }
    }

    @Throws(ExoPlaybackException::class)
    protected fun selectOtherTrack(
        trackType: Int, groups: TrackGroupArray, formatSupport: Array<IntArray>, params: Parameters
    ): ExoTrackSelection.Definition? {
        if (params.audioOffloadPreferences.audioOffloadMode == TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_REQUIRED) {
            return null
        }
        var selectedGroup: TrackGroup? = null
        var selectedTrackIndex = 0
        var selectedTrackScore: OtherTrackScore? = null
        for (groupIndex in 0..<groups.length) {
            val trackGroup = groups.get(groupIndex)
            val trackFormatSupport = formatSupport[groupIndex]
            for (trackIndex in 0..<trackGroup!!.length) {
                if (RendererCapabilities.isFormatSupported(
                        trackFormatSupport[trackIndex], params.exceedRendererCapabilitiesIfNecessary
                    )
                ) {
                    val format = trackGroup.getFormat(trackIndex)
                    val trackScore = OtherTrackScore(format, trackFormatSupport[trackIndex])
                    if (selectedTrackScore == null || trackScore.compareTo(selectedTrackScore) > 0) {
                        selectedGroup = trackGroup
                        selectedTrackIndex = trackIndex
                        selectedTrackScore = trackScore
                    }
                }
            }
        }
        return if (selectedGroup == null)
            null
        else
            ExoTrackSelection.Definition(selectedGroup, intArrayOf(selectedTrackIndex))
    }

    private fun <T : TrackInfo<T>> selectTracksForType(
        trackType: @C.TrackType Int,
        mappedTrackInfo: MappedTrackInfo,
        formatSupport: Array<Array<IntArray>>,
        trackInfoFactory: TrackInfo.Factory<T>,
        selectionComparator: Comparator<List<T>>
    ): Pair<ExoTrackSelection.Definition, Int>? {
        val possibleSelections = ArrayList<List<T>?>()
        val rendererCount = mappedTrackInfo.rendererCount
        for (rendererIndex in 0..<rendererCount) {
            if (trackType == mappedTrackInfo.getRendererType(rendererIndex)) {
                val groups = mappedTrackInfo.getTrackGroups(rendererIndex)
                for (groupIndex in 0..<groups.length) {
                    val trackGroup = groups.get(groupIndex)
                    val groupSupport = formatSupport[rendererIndex][groupIndex]
                    val trackInfos =
                        trackInfoFactory.create(rendererIndex, trackGroup, groupSupport)
                    val usedTrackInSelection = BooleanArray(trackGroup.length)
                    for (trackIndex in 0..<trackGroup.length) {
                        val trackInfo = trackInfos.get(trackIndex)
                        val eligibility: @SelectionEligibility Int =
                            trackInfo.selectionEligibility
                        if (usedTrackInSelection[trackIndex] || eligibility == SELECTION_ELIGIBILITY_NO) {
                            continue
                        }
                        val selection: MutableList<T>
                        if (eligibility == SELECTION_ELIGIBILITY_FIXED) {
                            selection = mutableListOf<T>(trackInfo)
                        } else {
                            selection = ArrayList()
                            selection.add(trackInfo)
                            for (i in trackIndex + 1..<trackGroup.length) {
                                val otherTrackInfo = trackInfos[i]
                                if (otherTrackInfo.selectionEligibility == SELECTION_ELIGIBILITY_ADAPTIVE) {
                                    if (trackInfo.isCompatibleForAdaptationWith(otherTrackInfo)) {
                                        selection.add(otherTrackInfo)
                                        usedTrackInSelection[i] = true
                                    }
                                }
                            }
                        }
                        possibleSelections.add(selection)
                    }
                }
            }
        }
        if (possibleSelections.isEmpty()) {
            return null
        }
        val bestSelection =
            Collections.max<List<T>>(possibleSelections, selectionComparator)
        val trackIndices = IntArray(bestSelection.size)
        for (i in bestSelection.indices) {
            trackIndices[i] = bestSelection[i].trackIndex
        }
        val firstTrackInfo = bestSelection[0]
        return Pair.create<ExoTrackSelection.Definition, Int>(
            ExoTrackSelection.Definition(firstTrackInfo.trackGroup, trackIndices),
            firstTrackInfo.rendererIndex
        )
    }

    private fun maybeInvalidateForAudioChannelCountConstraints() {
        val shouldInvalidate =
            playerParameters.constrainAudioChannelCountToDeviceCapabilities
                    && Build.VERSION.SDK_INT >= 32 && spatializer != null && spatializer!!.isSpatializationSupported
        if (shouldInvalidate) {
            invalidate(null)
        }
    }

    private fun maybeInvalidateForRendererCapabilitiesChange(renderer: Renderer) {
        if (playerParameters.allowInvalidateSelectionsOnRendererCapabilitiesChange) {
            invalidateForRendererCapabilitiesChange(renderer)
        }
    }

    private abstract class TrackInfo<T : TrackInfo<T>>(
        val rendererIndex: Int, val trackGroup: TrackGroup, val trackIndex: Int
    ) {
        fun interface Factory<T : TrackInfo<T>> {
            fun create(
                rendererIndex: Int,
                trackGroup: TrackGroup?,
                formatSupports: IntArray?
            ): List<T>
        }

        val format: Format = trackGroup.getFormat(trackIndex)

        abstract val selectionEligibility: @SelectionEligibility Int
        abstract fun isCompatibleForAdaptationWith(otherTrack: T): Boolean
    }

    private class VideoTrackInfo(
        rendererIndex: Int,
        trackGroup: TrackGroup,
        trackIndex: Int,
        private val parameters: Parameters,
        formatSupport: @RendererCapabilities.Capabilities Int,
        selectedAudioLanguage: String?,
        mixedMimeTypeAdaptationSupport: @AdaptiveSupport Int,
        isSuitableForViewport: Boolean
    ) : TrackInfo<VideoTrackInfo>(rendererIndex, trackGroup, trackIndex) {
        private val isWithinMaxConstraints: Boolean
        private val isWithinMinConstraints: Boolean
        private val isWithinRendererCapabilities: Boolean
        private val hasReasonableFrameRate: Boolean
        private val bitrate: Int
        private val pixelCount: Int
        private val preferredMimeTypeMatchIndex: Int
        private val preferredLanguageIndex: Int
        private val preferredLanguageScore: Int
        private val preferredRoleFlagsScore: Int
        private val preferredLabelMatchIndex: Int
        private val hasMainOrNoRoleFlag: Boolean
        private val selectedAudioLanguageScore: Int
        private val allowMixedMimeTypes: Boolean
        override val selectionEligibility: @SelectionEligibility Int
        private val usesPrimaryOrFallbackDecoder: Boolean
        private val usesPrimaryDecoder: Boolean
        private val usesHardwareAcceleration: Boolean
        private val codecPreferenceScore: Int
        private val isHdr: Boolean
        private val resolvedMimeType: String?

        init {
            @SuppressLint("WrongConstant") val requiredAdaptiveSupport =
                if (parameters.allowVideoNonSeamlessAdaptiveness)
                    (RendererCapabilities.ADAPTIVE_NOT_SEAMLESS or RendererCapabilities.ADAPTIVE_SEAMLESS)
                else
                    RendererCapabilities.ADAPTIVE_SEAMLESS
            allowMixedMimeTypes =
                parameters.allowVideoMixedMimeTypeAdaptiveness
                        && (mixedMimeTypeAdaptationSupport and requiredAdaptiveSupport) != 0
            isWithinMaxConstraints =
                isSuitableForViewport
                        && (format.width == Format.NO_VALUE || format.width <= parameters.maxVideoWidth)
                        && (format.height == Format.NO_VALUE || format.height <= parameters.maxVideoHeight)
                        && (format.frameRate == Format.NO_VALUE.toFloat()
                        || format.frameRate <= parameters.maxVideoFrameRate)
                        && (format.bitrate == Format.NO_VALUE
                        || format.bitrate <= parameters.maxVideoBitrate)
            isWithinMinConstraints =
                isSuitableForViewport
                        && (format.width == Format.NO_VALUE || format.width >= parameters.minVideoWidth)
                        && (format.height == Format.NO_VALUE || format.height >= parameters.minVideoHeight)
                        && (format.frameRate == Format.NO_VALUE.toFloat()
                        || format.frameRate >= parameters.minVideoFrameRate)
                        && (format.bitrate == Format.NO_VALUE
                        || format.bitrate >= parameters.minVideoBitrate)
            isWithinRendererCapabilities =
                RendererCapabilities.isFormatSupported(formatSupport, false)
            hasReasonableFrameRate =
                format.frameRate != Format.NO_VALUE.toFloat() && format.frameRate >= MIN_REASONABLE_FRAME_RATE
            bitrate = format.bitrate
            pixelCount = format.pixelCount
            var bestLanguageIndex = Int.MAX_VALUE
            var bestLanguageScore = 0
            for (i in parameters.preferredVideoLanguages.indices) {
                val score: Int =
                    getFormatLanguageScore(
                        format,
                        parameters.preferredVideoLanguages[i],
                        false
                    )
                if (score > 0) {
                    bestLanguageIndex = i
                    bestLanguageScore = score
                    break
                }
            }
            preferredLanguageIndex = bestLanguageIndex
            preferredLanguageScore = bestLanguageScore
            preferredRoleFlagsScore =
                getRoleFlagMatchScore(format.roleFlags, parameters.preferredVideoRoleFlags)
            hasMainOrNoRoleFlag =
                format.roleFlags == 0 || (format.roleFlags and C.ROLE_FLAG_MAIN) != 0
            val selectedAudioLanguageUndetermined =
                normalizeUndeterminedLanguageToNull(selectedAudioLanguage) == null
            selectedAudioLanguageScore =
                getFormatLanguageScore(
                    format,
                    selectedAudioLanguage,
                    selectedAudioLanguageUndetermined
                )

            var resolvedMimeType = format.sampleMimeType
            val decoderSupport: @DecoderSupport Int =
                RendererCapabilities.getDecoderSupport(formatSupport)
            if (decoderSupport == RendererCapabilities.DECODER_SUPPORT_FALLBACK_MIMETYPE) {
                val fallbackMimeType = getAlternativeCodecMimeType(format)
                if (fallbackMimeType != null) {
                    resolvedMimeType = fallbackMimeType
                }
            }

            var bestMimeTypeMatchIndex = Int.MAX_VALUE
            for (i in parameters.preferredVideoMimeTypes.indices) {
                if (resolvedMimeType != null && resolvedMimeType == parameters.preferredVideoMimeTypes[i]) {
                    bestMimeTypeMatchIndex = i
                    break
                }
            }
            preferredMimeTypeMatchIndex = bestMimeTypeMatchIndex
            preferredLabelMatchIndex = getBestLabelMatchIndex(
                format, parameters.preferredVideoLabels
            )
            usesPrimaryOrFallbackDecoder =
                decoderSupport == RendererCapabilities.DECODER_SUPPORT_PRIMARY
                        || decoderSupport == RendererCapabilities.DECODER_SUPPORT_FALLBACK_MIMETYPE
            usesPrimaryDecoder = decoderSupport == RendererCapabilities.DECODER_SUPPORT_PRIMARY
            usesHardwareAcceleration =
                (RendererCapabilities.getHardwareAccelerationSupport(formatSupport)
                        == RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED)
            this.resolvedMimeType = resolvedMimeType
            codecPreferenceScore = getVideoCodecPreferenceScore(resolvedMimeType)
            isHdr = usesPrimaryDecoder && ColorInfo.isTransferHdr(format.colorInfo)
            selectionEligibility =
                evaluateSelectionEligibility(formatSupport, requiredAdaptiveSupport)
        }

        override fun isCompatibleForAdaptationWith(otherTrack: VideoTrackInfo): Boolean {
            return (allowMixedMimeTypes
                    || this.resolvedMimeType == otherTrack.resolvedMimeType)
                    && (parameters.allowVideoMixedDecoderSupportAdaptiveness
                    || (this.usesPrimaryOrFallbackDecoder == otherTrack.usesPrimaryOrFallbackDecoder
                    && this.usesHardwareAcceleration == otherTrack.usesHardwareAcceleration))
        }

        fun evaluateSelectionEligibility(
            rendererSupport: @RendererCapabilities.Capabilities Int,
            requiredAdaptiveSupport: @AdaptiveSupport Int
        ): @SelectionEligibility Int {
            if ((format.roleFlags and C.ROLE_FLAG_TRICK_PLAY) != 0) {
                // Ignore trick-play tracks for now.
                return SELECTION_ELIGIBILITY_NO
            }
            if (!RendererCapabilities.isFormatSupported(
                    rendererSupport,
                    parameters.exceedRendererCapabilitiesIfNecessary
                )
            ) {
                return SELECTION_ELIGIBILITY_NO
            }
            if (!isWithinMaxConstraints && !parameters.exceedVideoConstraintsIfNecessary) {
                return SELECTION_ELIGIBILITY_NO
            }
            return if (RendererCapabilities.isFormatSupported(rendererSupport, false)
                && isWithinMinConstraints
                && isWithinMaxConstraints
                && format.bitrate != Format.NO_VALUE && !parameters.forceHighestSupportedBitrate && !parameters.forceLowestBitrate && (rendererSupport and requiredAdaptiveSupport) != 0
            )
                SELECTION_ELIGIBILITY_ADAPTIVE
            else
                SELECTION_ELIGIBILITY_FIXED
        }

        companion object {
            private const val MIN_REASONABLE_FRAME_RATE = 10f

            fun createForTrackGroup(
                rendererIndex: Int,
                trackGroup: TrackGroup,
                params: Parameters,
                formatSupport: IntArray,
                selectedAudioLanguage: String?,
                mixedMimeTypeAdaptationSupport: @AdaptiveSupport Int,
                viewportSizeFromDisplay: Point?
            ): List<VideoTrackInfo> {
                val viewportWidth = viewportSizeFromDisplay?.x ?: params.viewportWidth
                val viewportHeight = viewportSizeFromDisplay?.y ?: params.viewportHeight
                val maxPixelsToRetainForViewport: Int =
                    getMaxVideoPixelsToRetainForViewport(
                        trackGroup,
                        viewportWidth,
                        viewportHeight,
                        params.viewportOrientationMayChange
                    )
                val listBuilder = mutableListOf<VideoTrackInfo>()
                for (i in 0..<trackGroup.length) {
                    val pixelCount = trackGroup.getFormat(i).pixelCount
                    val isSuitableForViewport =
                        maxPixelsToRetainForViewport == Int.MAX_VALUE
                                || (pixelCount != Format.NO_VALUE && pixelCount <= maxPixelsToRetainForViewport)
                    listBuilder.add(
                        VideoTrackInfo(
                            rendererIndex,
                            trackGroup,
                            i,
                            params,
                            formatSupport[i],
                            selectedAudioLanguage,
                            mixedMimeTypeAdaptationSupport,
                            isSuitableForViewport
                        )
                    )
                }
                return listBuilder.toList()
            }

            private fun compareNonQualityPreferences(
                info1: VideoTrackInfo,
                info2: VideoTrackInfo
            ): Int {
                val chain =
                    ComparisonChain.start()
                        .compareFalseFirst(
                            info1.isWithinRendererCapabilities, info2.isWithinRendererCapabilities
                        ) // 1. Compare match with specific content preferences set by the parameters.
                        .compare<Int>(
                            info1.preferredLanguageIndex,
                            info2.preferredLanguageIndex,
                            Ordering.natural<Comparable<*>?>().reverse()
                        )
                        .compare(info1.preferredLanguageScore, info2.preferredLanguageScore)
                        .compare(info1.preferredRoleFlagsScore, info2.preferredRoleFlagsScore)
                        .compare<Int>(
                            info1.preferredLabelMatchIndex,
                            info2.preferredLabelMatchIndex,
                            Ordering.natural<Comparable<*>?>().reverse<Int>()
                        ) // 2. Compare match with implicit content preferences set by the media.
                        .compareFalseFirst(info1.hasMainOrNoRoleFlag, info2.hasMainOrNoRoleFlag)
                        .compare(
                            info1.selectedAudioLanguageScore,
                            info2.selectedAudioLanguageScore
                        ) // 3. Compare match with 'reasonable' frame rate threshold.
                        .compareFalseFirst(
                            info1.hasReasonableFrameRate,
                            info2.hasReasonableFrameRate
                        ) // 4. Compare match with technical preferences set by the parameters.
                        .compareFalseFirst(
                            info1.isWithinMaxConstraints,
                            info2.isWithinMaxConstraints
                        )
                        .compareFalseFirst(
                            info1.isWithinMinConstraints,
                            info2.isWithinMinConstraints
                        )
                        .compare<Int>(
                            info1.preferredMimeTypeMatchIndex,
                            info2.preferredMimeTypeMatchIndex,
                            Ordering.natural<Comparable<*>?>().reverse()
                        ) // 5. Compare match with renderer capability preferences.
                        .compareFalseFirst(
                            info1.usesPrimaryOrFallbackDecoder, info2.usesPrimaryOrFallbackDecoder
                        )
                        .compareFalseFirst(
                            info1.usesHardwareAcceleration,
                            info2.usesHardwareAcceleration
                        )
                return chain.result()
            }

            private fun compareQualityPreferences(
                info1: VideoTrackInfo,
                info2: VideoTrackInfo
            ): Int {
                // The preferred ordering by video quality depends on the constraints:
                // - Not within renderer capabilities: Prefer lower quality because it's more likely to play.
                // - Within min and max constraints: Prefer higher quality.
                // - Within max constraints only: Prefer higher quality because it gets us closest to
                //   satisfying the violated min constraints.
                // - Within min constraints only: Prefer lower quality because it gets us closest to
                //   satisfying the violated max constraints.
                // - Outside min and max constraints: Arbitrarily prefer lower quality.
                val qualityOrdering: Ordering<Int> =
                    if (info1.isWithinMaxConstraints && info1.isWithinRendererCapabilities)
                        FORMAT_VALUE_ORDERING
                    else
                        FORMAT_VALUE_ORDERING.reverse()
                var comparisonChain = ComparisonChain.start()
                if (info1.parameters.forceLowestBitrate) {
                    comparisonChain =
                        comparisonChain.compare<Int>(
                            info1.bitrate,
                            info2.bitrate,
                            FORMAT_VALUE_ORDERING.reverse()
                        )
                }
                var chain =
                    comparisonChain
                        .compareFalseFirst(info1.isHdr, info2.isHdr)
                        .compare<Int>(info1.pixelCount, info2.pixelCount, qualityOrdering)

                if (info1.usesPrimaryOrFallbackDecoder && info1.usesHardwareAcceleration) {
                    chain = chain.compare(info1.codecPreferenceScore, info2.codecPreferenceScore)
                }

                return chain
                    .compareFalseFirst(info1.usesPrimaryDecoder, info2.usesPrimaryDecoder)
                    .compare<Int>(info1.bitrate, info2.bitrate, qualityOrdering)
                    .result()
            }

            fun compareSelections(
                infos1: List<VideoTrackInfo>,
                infos2: List<VideoTrackInfo>
            ): Int {
                return ComparisonChain.start() // Compare non-quality preferences of the best individual track with each other.
                    .compare<VideoTrackInfo?>(
                        Collections.max(
                            infos1,
                            Comparator { info1: VideoTrackInfo?, info2: VideoTrackInfo? ->
                                compareNonQualityPreferences(info1!!, info2!!)
                            }),
                        Collections.max(
                            infos2,
                            Comparator { info1: VideoTrackInfo?, info2: VideoTrackInfo? ->
                                compareNonQualityPreferences(info1!!, info2!!)
                            })
                    ) { info1: VideoTrackInfo?, info2: VideoTrackInfo? ->
                        compareNonQualityPreferences(info1!!, info2!!)
                    } // Prefer selections with more formats (all non-quality preferences being equal).
                    .compare(
                        infos1.size,
                        infos2.size
                    ) // Prefer selections with the best individual track quality.
                    .compare<VideoTrackInfo?>(
                        Collections.max<VideoTrackInfo?>(
                            infos1,
                            Comparator { info1: VideoTrackInfo?, info2: VideoTrackInfo? ->
                                compareQualityPreferences(info1!!, info2!!)
                            }),
                        Collections.max(
                            infos2,
                            Comparator { info1: VideoTrackInfo?, info2: VideoTrackInfo? ->
                                compareQualityPreferences(info1!!, info2!!)
                            })
                    ) { info1: VideoTrackInfo?, info2: VideoTrackInfo? ->
                        compareQualityPreferences(info1!!, info2!!)
                    }
                    .result()
            }
        }
    }

    private class AudioTrackInfo(
        rendererIndex: Int,
        trackGroup: TrackGroup,
        trackIndex: Int,
        private val parameters: Parameters,
        formatSupport: @RendererCapabilities.Capabilities Int,
        hasMappedVideoTracks: Boolean,
        withinAudioChannelCountConstraints: Predicate<Format?>,
        mixedMimeTypeAdaptationSupport: @AdaptiveSupport Int
    ) : TrackInfo<AudioTrackInfo>(rendererIndex, trackGroup, trackIndex),
        Comparable<AudioTrackInfo> {
        override val selectionEligibility: @SelectionEligibility Int
        private val isWithinConstraints: Boolean
        private val language: String?
        private val isWithinRendererCapabilities: Boolean
        private val preferredLanguageScore: Int
        private val preferredLanguageIndex: Int
        private val preferredRoleFlagsScore: Int
        private val preferredLabelMatchIndex: Int
        private val allowMixedMimeTypes: Boolean
        private val hasMainOrNoRoleFlag: Boolean
        private val localeLanguageMatchIndex: Int
        private val localeLanguageScore: Int
        private val isDefaultSelectionFlag: Boolean
        private val channelCount: Int
        private val sampleRate: Int
        private val bitrate: Int
        private val preferredMimeTypeMatchIndex: Int
        private val usesPrimaryDecoder: Boolean
        private val usesHardwareAcceleration: Boolean
        private val isObjectBasedAudio: Boolean

        init {
            @SuppressLint("WrongConstant") val requiredAdaptiveSupport =
                if (parameters.allowAudioNonSeamlessAdaptiveness)
                    (RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
                            or RendererCapabilities.ADAPTIVE_SEAMLESS)
                else
                    RendererCapabilities.ADAPTIVE_SEAMLESS
            allowMixedMimeTypes =
                parameters.allowAudioMixedMimeTypeAdaptiveness
                        && (mixedMimeTypeAdaptationSupport and requiredAdaptiveSupport) != 0
            this.language = normalizeUndeterminedLanguageToNull(format.language)
            isWithinRendererCapabilities =
                RendererCapabilities.isFormatSupported(
                    formatSupport,
                    false
                )
            var bestLanguageScore = 0
            var bestLanguageIndex = Int.MAX_VALUE
            for (i in parameters.preferredAudioLanguages.indices) {
                val score: Int =
                    getFormatLanguageScore(
                        format,
                        parameters.preferredAudioLanguages[i],
                        false
                    )
                if (score > 0) {
                    bestLanguageIndex = i
                    bestLanguageScore = score
                    break
                }
            }
            preferredLanguageIndex = bestLanguageIndex
            preferredLanguageScore = bestLanguageScore
            preferredRoleFlagsScore =
                getRoleFlagMatchScore(format.roleFlags, parameters.preferredAudioRoleFlags)
            preferredLabelMatchIndex = getBestLabelMatchIndex(
                format, parameters.preferredAudioLabels
            )
            hasMainOrNoRoleFlag =
                format.roleFlags == 0 || (format.roleFlags and C.ROLE_FLAG_MAIN) != 0
            isDefaultSelectionFlag = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
            isObjectBasedAudio = isObjectBasedAudio(format)
            channelCount = format.channelCount
            sampleRate = format.sampleRate
            bitrate = format.bitrate
            isWithinConstraints =
                (format.bitrate == Format.NO_VALUE || format.bitrate <= parameters.maxAudioBitrate)
                        && (format.channelCount == Format.NO_VALUE
                        || format.channelCount <= parameters.maxAudioChannelCount)
                        && withinAudioChannelCountConstraints.apply(format)
            val localeLanguages: Array<String> = systemLanguageCodes
            var bestLocaleMatchIndex = Int.MAX_VALUE
            var bestLocaleMatchScore = 0
            for (i in localeLanguages.indices) {
                val score: Int =
                    getFormatLanguageScore(
                        format, localeLanguages[i], false
                    )
                if (score > 0) {
                    bestLocaleMatchIndex = i
                    bestLocaleMatchScore = score
                    break
                }
            }
            localeLanguageMatchIndex = bestLocaleMatchIndex
            localeLanguageScore = bestLocaleMatchScore
            var bestMimeTypeMatchIndex = Int.MAX_VALUE
            for (i in parameters.preferredAudioMimeTypes.indices) {
                if (format.sampleMimeType != null
                    && format.sampleMimeType == parameters.preferredAudioMimeTypes[i]
                ) {
                    bestMimeTypeMatchIndex = i
                    break
                }
            }
            preferredMimeTypeMatchIndex = bestMimeTypeMatchIndex
            usesPrimaryDecoder =
                (RendererCapabilities.getDecoderSupport(formatSupport)
                        == RendererCapabilities.DECODER_SUPPORT_PRIMARY)
            usesHardwareAcceleration =
                (RendererCapabilities.getHardwareAccelerationSupport(formatSupport)
                        == RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED)
            selectionEligibility =
                evaluateSelectionEligibility(
                    formatSupport, hasMappedVideoTracks, requiredAdaptiveSupport
                )
        }

        override fun isCompatibleForAdaptationWith(otherTrack: AudioTrackInfo): Boolean {
            return (parameters.allowAudioMixedChannelCountAdaptiveness
                    || (format.channelCount != Format.NO_VALUE
                    && format.channelCount == otherTrack.format.channelCount))
                    && (allowMixedMimeTypes
                    || (format.sampleMimeType != null
                    && TextUtils.equals(format.sampleMimeType, otherTrack.format.sampleMimeType)))
                    && (parameters.allowAudioMixedSampleRateAdaptiveness
                    || (format.sampleRate != Format.NO_VALUE
                    && format.sampleRate == otherTrack.format.sampleRate))
                    && (parameters.allowAudioMixedDecoderSupportAdaptiveness
                    || (this.usesPrimaryDecoder == otherTrack.usesPrimaryDecoder
                    && this.usesHardwareAcceleration == otherTrack.usesHardwareAcceleration))
        }

        override fun compareTo(other: AudioTrackInfo): Int {
            // If the formats are within constraints and renderer capabilities then prefer higher values
            // of channel count, sample rate and bit rate in that order. Otherwise, prefer lower values.
            val qualityOrdering: Ordering<Int> =
                if (isWithinConstraints && isWithinRendererCapabilities)
                    FORMAT_VALUE_ORDERING
                else
                    FORMAT_VALUE_ORDERING.reverse()
            var comparisonChain =
                ComparisonChain.start()
                    .compareFalseFirst(
                        this.isWithinRendererCapabilities, other.isWithinRendererCapabilities
                    ) // 1. Compare match with specific content preferences set by the parameters.
                    .compare<Int>(
                        this.preferredLanguageIndex,
                        other.preferredLanguageIndex,
                        Ordering.natural<Comparable<*>?>().reverse()
                    )
                    .compare(this.preferredLanguageScore, other.preferredLanguageScore)
                    .compare(this.preferredRoleFlagsScore, other.preferredRoleFlagsScore)
                    .compare<Int>(
                        this.preferredLabelMatchIndex,
                        other.preferredLabelMatchIndex,
                        Ordering.natural<Comparable<*>?>().reverse()
                    ) // 2. Compare match with implicit content preferences set by the media or the system.
                    .compareFalseFirst(this.isDefaultSelectionFlag, other.isDefaultSelectionFlag)
                    .compareFalseFirst(this.hasMainOrNoRoleFlag, other.hasMainOrNoRoleFlag)
                    .compare<Int>(
                        this.localeLanguageMatchIndex,
                        other.localeLanguageMatchIndex,
                        Ordering.natural<Comparable<*>?>().reverse()
                    )
                    .compare(
                        this.localeLanguageScore,
                        other.localeLanguageScore
                    ) // 3. Compare match with technical preferences set by the parameters.
                    .compareFalseFirst(this.isWithinConstraints, other.isWithinConstraints)
                    .compare<Int>(
                        this.preferredMimeTypeMatchIndex,
                        other.preferredMimeTypeMatchIndex,
                        Ordering.natural<Comparable<*>?>().reverse()
                    )
            if (parameters.forceLowestBitrate) {
                comparisonChain =
                    comparisonChain.compare<Int>(
                        this.bitrate,
                        other.bitrate,
                        FORMAT_VALUE_ORDERING.reverse()
                    )
            }
            // 4. Compare match with renderer capability preferences.
            comparisonChain =
                comparisonChain
                    .compareFalseFirst(this.usesPrimaryDecoder, other.usesPrimaryDecoder)
                    .compareFalseFirst(
                        this.usesHardwareAcceleration,
                        other.usesHardwareAcceleration
                    ) // 5. Compare technical quality.
                    .compareFalseFirst(this.isObjectBasedAudio, other.isObjectBasedAudio)
                    .compare<Int>(this.channelCount, other.channelCount, qualityOrdering)
                    .compare<Int>(this.sampleRate, other.sampleRate, qualityOrdering)
            if (this.language == other.language) {
                // Only compare bit rates of tracks with matching language information.
                comparisonChain =
                    comparisonChain.compare<Int>(this.bitrate, other.bitrate, qualityOrdering)
            }
            return comparisonChain.result()
        }

        fun evaluateSelectionEligibility(
            rendererSupport: @RendererCapabilities.Capabilities Int,
            hasMappedVideoTracks: Boolean,
            requiredAdaptiveSupport: @AdaptiveSupport Int
        ): @SelectionEligibility Int {
            if (!RendererCapabilities.isFormatSupported(
                    rendererSupport,
                    parameters.exceedRendererCapabilitiesIfNecessary
                )
            ) {
                return SELECTION_ELIGIBILITY_NO
            }
            if (!isWithinConstraints && !parameters.exceedAudioConstraintsIfNecessary) {
                return SELECTION_ELIGIBILITY_NO
            }
            if (parameters.audioOffloadPreferences.audioOffloadMode == TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_REQUIRED
                && !rendererSupportsOffload(parameters, rendererSupport, format)
            ) {
                return SELECTION_ELIGIBILITY_NO
            }
            return if (RendererCapabilities.isFormatSupported(
                    rendererSupport, false
                )
                && isWithinConstraints
                && format.bitrate != Format.NO_VALUE && !parameters.forceHighestSupportedBitrate && !parameters.forceLowestBitrate && (parameters.allowMultipleAdaptiveSelections || !hasMappedVideoTracks)
                && parameters.audioOffloadPreferences.audioOffloadMode != TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_REQUIRED && (rendererSupport and requiredAdaptiveSupport) != 0
            )
                SELECTION_ELIGIBILITY_ADAPTIVE
            else
                SELECTION_ELIGIBILITY_FIXED
        }

        companion object {
            fun createForTrackGroup(
                rendererIndex: Int,
                trackGroup: TrackGroup,
                params: Parameters,
                formatSupport: IntArray,
                hasMappedVideoTracks: Boolean,
                withinAudioChannelCountConstraints: Predicate<Format?>,
                mixedMimeTypeAdaptationSupport: @AdaptiveSupport Int
            ): List<AudioTrackInfo> {
                val listBuilder = mutableListOf<AudioTrackInfo>()
                for (i in 0..<trackGroup.length) {
                    listBuilder.add(
                        AudioTrackInfo(
                            rendererIndex,
                            trackGroup,
                            i,
                            params,
                            formatSupport[i],
                            hasMappedVideoTracks,
                            withinAudioChannelCountConstraints,
                            mixedMimeTypeAdaptationSupport
                        )
                    )
                }
                return listBuilder
            }

            fun compareSelections(
                infos1: List<AudioTrackInfo>,
                infos2: List<AudioTrackInfo>
            ): Int {
                // Compare best tracks of each selection with each other.
                return Collections.max(infos1).compareTo(Collections.max(infos2))
            }
        }
    }

    private class TextTrackInfo(
        rendererIndex: Int,
        trackGroup: TrackGroup,
        trackIndex: Int,
        parameters: Parameters,
        trackFormatSupport: @RendererCapabilities.Capabilities Int,
        selectedAudioLanguage: String?,
        preferredCaptioningLanguage: String?
    ) : TrackInfo<TextTrackInfo>(rendererIndex, trackGroup, trackIndex),
        Comparable<TextTrackInfo> {
        override val selectionEligibility: @SelectionEligibility Int
        private val isWithinRendererCapabilities: Boolean = RendererCapabilities.isFormatSupported(
            trackFormatSupport, false
        )
        private val isDefault: Boolean
        private val isForced: Boolean
        private val preferredLanguageIndex: Int
        private val preferredLanguageScore: Int
        private val preferredRoleFlagsScore: Int
        private val preferredLabelMatchIndex: Int
        private val selectedAudioLanguageScore: Int
        private val hasCaptionRoleFlags: Boolean

        init {
            val maskedSelectionFlags =
                format.selectionFlags and parameters.ignoredTextSelectionFlags.inv()
            isDefault = (maskedSelectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
            isForced = (maskedSelectionFlags and C.SELECTION_FLAG_FORCED) != 0
            var bestLanguageIndex = Int.MAX_VALUE
            var bestLanguageScore = 0
            // Compare against empty (unset) language if no preference is given to allow the selection of
            // a text track with undetermined language.
            val preferredLanguages: List<String> =
                if (preferredCaptioningLanguage != null)
                    listOf(preferredCaptioningLanguage)
                else
                    if (parameters.preferredTextLanguages.isEmpty())
                        listOf("")
                    else
                        parameters.preferredTextLanguages
            for (i in preferredLanguages.indices) {
                val score: Int =
                    getFormatLanguageScore(
                        format, preferredLanguages[i], parameters.selectUndeterminedTextLanguage
                    )
                if (score > 0) {
                    bestLanguageIndex = i
                    bestLanguageScore = score
                    break
                }
            }
            preferredLanguageIndex = bestLanguageIndex
            preferredLanguageScore = bestLanguageScore
            val preferredRoleFlags =
                if (preferredCaptioningLanguage != null)
                    C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND
                else
                    parameters.preferredTextRoleFlags
            preferredRoleFlagsScore = getRoleFlagMatchScore(format.roleFlags, preferredRoleFlags)
            hasCaptionRoleFlags =
                (format.roleFlags and (C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)) != 0
            preferredLabelMatchIndex = DefaultTrackSelector.getBestLabelMatchIndex(
                format,
                parameters.preferredTextLabels
            )
            val selectedAudioLanguageUndetermined =
                normalizeUndeterminedLanguageToNull(selectedAudioLanguage) == null
            selectedAudioLanguageScore =
                getFormatLanguageScore(
                    format,
                    selectedAudioLanguage,
                    selectedAudioLanguageUndetermined
                )
            val isWithinConstraints =
                preferredLanguageScore > 0 || (parameters.preferredTextLanguages.isEmpty() && preferredRoleFlagsScore > 0)
                        || (parameters.preferredTextLanguages.isEmpty()
                        && preferredLabelMatchIndex != Int.MAX_VALUE)
                        || isDefault
                        || (isForced && selectedAudioLanguageScore > 0)
                        || parameters.selectTextByDefault
            selectionEligibility =
                if (RendererCapabilities.isFormatSupported(
                        trackFormatSupport,
                        parameters.exceedRendererCapabilitiesIfNecessary
                    )
                    && isWithinConstraints
                )
                    SELECTION_ELIGIBILITY_FIXED
                else
                    SELECTION_ELIGIBILITY_NO
        }

        override fun isCompatibleForAdaptationWith(otherTrack: TextTrackInfo): Boolean {
            return false
        }

        override fun compareTo(other: TextTrackInfo): Int {
            var chain =
                ComparisonChain.start()
                    .compareFalseFirst(
                        this.isWithinRendererCapabilities, other.isWithinRendererCapabilities
                    ) // 1. Compare match with specific content preferences set by the parameters.
                    .compare<Int>(
                        this.preferredLanguageIndex,
                        other.preferredLanguageIndex,
                        Ordering.natural<Comparable<*>?>().reverse<Int>()
                    )
                    .compare(this.preferredLanguageScore, other.preferredLanguageScore)
                    .compare(this.preferredRoleFlagsScore, other.preferredRoleFlagsScore)
                    .compare<Int>(
                        this.preferredLabelMatchIndex,
                        other.preferredLabelMatchIndex,
                        Ordering.natural<Comparable<*>?>().reverse<Int>()
                    ) // 2. Compare match with implicit content preferences set by the media.
                    .compareFalseFirst(this.isDefault, other.isDefault)
                    .compare<Boolean?>(
                        this.isForced,
                        other.isForced,  // Prefer non-forced to forced if a preferred text language has been matched.
                        // Where both are provided the non-forced track will usually contain the forced
                        // subtitles as a subset. Otherwise, prefer a forced track.
                        if (preferredLanguageScore == 0) Ordering.natural<Boolean?>() else Ordering.natural<Comparable<*>?>()
                            .reverse<Boolean?>()
                    )
                    .compare(this.selectedAudioLanguageScore, other.selectedAudioLanguageScore)
            if (preferredRoleFlagsScore == 0) {
                chain = chain.compareTrueFirst(this.hasCaptionRoleFlags, other.hasCaptionRoleFlags)
            }
            return chain.result()
        }

        companion object {
            fun createForTrackGroup(
                rendererIndex: Int,
                trackGroup: TrackGroup,
                params: Parameters,
                formatSupport: IntArray,
                selectedAudioLanguage: String?,
                preferredCaptioningLanguage: String?
            ): List<TextTrackInfo> {
                val listBuilder = mutableListOf<TextTrackInfo>()
                for (i in 0..<trackGroup.length) {
                    listBuilder.add(
                        TextTrackInfo(
                            rendererIndex,
                            trackGroup,
                            i,
                            params,
                            formatSupport[i],
                            selectedAudioLanguage,
                            preferredCaptioningLanguage
                        )
                    )
                }
                return listBuilder
            }

            fun compareSelections(
                infos1: List<TextTrackInfo>,
                infos2: List<TextTrackInfo>
            ): Int {
                return infos1[0].compareTo(infos2[0])
            }
        }
    }

    private class ImageTrackInfo(
        rendererIndex: Int,
        trackGroup: TrackGroup,
        trackIndex: Int,
        parameters: Parameters,
        trackFormatSupport: @RendererCapabilities.Capabilities Int
    ) : TrackInfo<ImageTrackInfo>(rendererIndex, trackGroup, trackIndex),
        Comparable<ImageTrackInfo> {
        override val selectionEligibility: @SelectionEligibility Int =
            if (RendererCapabilities.isFormatSupported(
                    trackFormatSupport,
                    parameters.exceedRendererCapabilitiesIfNecessary
                )
            )
                SELECTION_ELIGIBILITY_FIXED
            else
                SELECTION_ELIGIBILITY_NO
        private val pixelCount: Int = format.pixelCount

        override fun isCompatibleForAdaptationWith(otherTrack: ImageTrackInfo): Boolean {
            return false
        }

        override fun compareTo(other: ImageTrackInfo): Int {
            return pixelCount.compareTo(other.pixelCount)
        }

        companion object {
            fun createForTrackGroup(
                rendererIndex: Int,
                trackGroup: TrackGroup,
                params: Parameters,
                formatSupport: IntArray
            ): List<ImageTrackInfo> {
                return List(trackGroup.length) { i ->
                    ImageTrackInfo(
                        rendererIndex, trackGroup, i, params, formatSupport[i]
                    )
                }
            }

            fun compareSelections(
                infos1: List<ImageTrackInfo>,
                infos2: List<ImageTrackInfo>
            ): Int {
                return infos1[0].compareTo(infos2[0])
            }
        }
    }

    private class OtherTrackScore(
        format: Format,
        trackFormatSupport: @RendererCapabilities.Capabilities Int
    ) : Comparable<OtherTrackScore> {
        private val isDefault: Boolean = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
        private val isWithinRendererCapabilities: Boolean = RendererCapabilities.isFormatSupported(
            trackFormatSupport, false
        )

        override fun compareTo(other: OtherTrackScore): Int {
            return ComparisonChain.start()
                .compareFalseFirst(
                    this.isWithinRendererCapabilities,
                    other.isWithinRendererCapabilities
                )
                .compareFalseFirst(this.isDefault, other.isDefault)
                .result()
        }
    }

    companion object {
        private const val TAG = "DefaultTrackSelector"
        private val AUDIO_CHANNEL_COUNT_CONSTRAINTS_WARN_MESSAGE =
            ("Audio channel count constraints cannot be applied without reference to Context. Build the"
                    + " track selector instance with one of the non-deprecated constructors that take a"
                    + " Context argument.")

        protected const val SELECTION_ELIGIBILITY_NO: Int = 0
        protected const val SELECTION_ELIGIBILITY_FIXED: Int = 1
        protected const val SELECTION_ELIGIBILITY_ADAPTIVE: Int = 2
        private const val FRACTION_TO_CONSIDER_FULLSCREEN = 0.98f

        private val FORMAT_VALUE_ORDERING: Ordering<Int> = Ordering.from<Int>(
            Comparator { first: Int?, second: Int? ->
                if (first == Format.NO_VALUE)
                    (if (second == Format.NO_VALUE) 0 else -1)
                else
                    (if (second == Format.NO_VALUE) 1 else (first!! - second!!))
            })

        private fun getSelectedPrimaryTrackGroupIds(
            definitions: Array<ExoTrackSelection.Definition?>, parameters: Parameters
        ): Set<String?> {
            val primaryTrackGroupIds = mutableSetOf<String?>()
            for (i in definitions.indices) {
                val definition: ExoTrackSelection.Definition? = definitions[i]
                if (definition != null && !parameters.getRendererDisabled(i) && !parameters.disabledTrackTypes.contains(
                        definition.group.type
                    )
                ) {
                    primaryTrackGroupIds.add(definition.group.id)
                    for (j in definition.tracks.indices) {
                        val format = definition.group.getFormat(definition.tracks[j])
                        if (format.primaryTrackGroupId != null) {
                            primaryTrackGroupIds.add(format.primaryTrackGroupId)
                        }
                    }
                }
            }
            return primaryTrackGroupIds
        }

        // Utility methods.
        private fun applyTrackSelectionOverrides(
            mappedTrackInfo: MappedTrackInfo,
            params: TrackSelectionParameters,
            outDefinitions: Array<ExoTrackSelection.Definition?>
        ) {
            val rendererCount = mappedTrackInfo.rendererCount

            // Determine overrides to apply.
            val overridesByType = HashMap<Int, TrackSelectionOverride>()
            for (rendererIndex in 0..<rendererCount) {
                collectTrackSelectionOverrides(
                    mappedTrackInfo.getTrackGroups(rendererIndex), params, overridesByType
                )
            }
            collectTrackSelectionOverrides(
                mappedTrackInfo.unmappedTrackGroups, params, overridesByType
            )

            // Apply the overrides.
            for (rendererIndex in 0..<rendererCount) {
                val trackType: @C.TrackType Int = mappedTrackInfo.getRendererType(rendererIndex)
                val overrideForType = overridesByType[trackType] ?: continue

                // If the override is non-empty and applies to this renderer, then apply it. Else we don't
                // want the renderer to be enabled at all, so clear any existing selection.
                val selection: ExoTrackSelection.Definition?
                if (!overrideForType.trackIndices.isEmpty()
                    && (mappedTrackInfo.getTrackGroups(rendererIndex)
                        .indexOf(overrideForType.mediaTrackGroup) != -1)
                ) {
                    selection =
                        ExoTrackSelection.Definition(
                            overrideForType.mediaTrackGroup,
                            overrideForType.trackIndices.toIntArray()
                        )
                } else {
                    selection = null
                }
                outDefinitions[rendererIndex] = selection
            }
        }

        private fun collectTrackSelectionOverrides(
            trackGroups: TrackGroupArray,
            params: TrackSelectionParameters,
            overridesByType: MutableMap<Int, TrackSelectionOverride>
        ) {
            for (trackGroupIndex in 0..<trackGroups.length) {
                val trackGroup = trackGroups.get(trackGroupIndex)
                val override = params.overrides[trackGroup] ?: continue

                val existingOverride = overridesByType[override.type]
                // Only replace an existing override if it's empty and the one being considered is not.
                if (existingOverride == null
                    || (existingOverride.trackIndices.isEmpty() && !override.trackIndices.isEmpty())
                ) {
                    overridesByType[override.type] = override
                }
            }
        }

        @Suppress("deprecation") // Calling legacy hasSelectionOverride and getSelectionOverride
        private fun applyLegacyRendererOverrides(
            mappedTrackInfo: MappedTrackInfo,
            params: Parameters,
            outDefinitions: Array<ExoTrackSelection.Definition?>
        ) {
            val rendererCount = mappedTrackInfo.rendererCount
            for (rendererIndex in 0..<rendererCount) {
                val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                if (!params.hasSelectionOverride(rendererIndex, trackGroups)) {
                    continue
                }
                val override = params.getSelectionOverride(rendererIndex, trackGroups)
                val selection: ExoTrackSelection.Definition?
                if (override != null && override.tracks.isNotEmpty()) {
                    selection =
                        ExoTrackSelection.Definition(
                            trackGroups.get(override.groupIndex), override.tracks, override.type
                        )
                } else {
                    selection = null
                }
                outDefinitions[rendererIndex] = selection
            }
        }

        private fun applyRendererDisableOverrides(
            mappedTrackInfo: MappedTrackInfo,
            parameters: Parameters,
            definitions: Array<ExoTrackSelection.Definition?>
        ) {
            for (i in 0..<mappedTrackInfo.rendererCount) {
                val rendererType: @C.TrackType Int = mappedTrackInfo.getRendererType(i)
                if (parameters.getRendererDisabled(i)
                    || parameters.disabledTrackTypes.contains(rendererType)
                ) {
                    definitions[i] = null
                }
            }
        }

        private fun maybeConfigureRenderersForTunneling(
            mappedTrackInfo: MappedTrackInfo,
            rendererFormatSupports: Array<Array<IntArray>>,
            rendererConfigurations: Array<RendererConfiguration?>,
            trackSelections: Array<ExoTrackSelection?>
        ) {
            // Check whether we can enable tunneling. To enable tunneling we require exactly one audio and
            // one video renderer to support tunneling and have a selection.
            var tunnelingAudioRendererIndex = -1
            var tunnelingVideoRendererIndex = -1
            var enableTunneling = true
            for (i in 0..<mappedTrackInfo.rendererCount) {
                val rendererType = mappedTrackInfo.getRendererType(i)
                val trackSelection = trackSelections[i]
                if ((rendererType == C.TRACK_TYPE_AUDIO || rendererType == C.TRACK_TYPE_VIDEO)
                    && trackSelection != null
                ) {
                    if (Companion.rendererSupportsTunneling(
                            rendererFormatSupports[i],
                            mappedTrackInfo.getTrackGroups(i),
                            trackSelection
                        )
                    ) {
                        if (rendererType == C.TRACK_TYPE_AUDIO) {
                            if (tunnelingAudioRendererIndex != -1) {
                                enableTunneling = false
                                break
                            } else {
                                tunnelingAudioRendererIndex = i
                            }
                        } else {
                            if (tunnelingVideoRendererIndex != -1) {
                                enableTunneling = false
                                break
                            } else {
                                tunnelingVideoRendererIndex = i
                            }
                        }
                    }
                }
            }
            enableTunneling =
                enableTunneling and (tunnelingAudioRendererIndex != -1 && tunnelingVideoRendererIndex != -1)
            if (enableTunneling) {
                val tunnelingRendererConfiguration =
                    RendererConfiguration(AudioSink.OFFLOAD_MODE_DISABLED, true)
                rendererConfigurations[tunnelingAudioRendererIndex] = tunnelingRendererConfiguration
                rendererConfigurations[tunnelingVideoRendererIndex] = tunnelingRendererConfiguration
            }
        }

        private fun rendererSupportsTunneling(
            formatSupport: Array<IntArray>,
            trackGroups: TrackGroupArray,
            selection: ExoTrackSelection?
        ): Boolean {
            if (selection == null) {
                return false
            }
            val trackGroupIndex = trackGroups.indexOf(selection.trackGroup)
            for (i in 0..<selection.length()) {
                val trackFormatSupport: @RendererCapabilities.Capabilities Int =
                    formatSupport[trackGroupIndex][selection.getIndexInTrackGroup(i)]
                if (RendererCapabilities.getTunnelingSupport(trackFormatSupport)
                    != RendererCapabilities.TUNNELING_SUPPORTED
                ) {
                    return false
                }
            }
            return true
        }

        private fun maybeConfigureRendererForOffload(
            parameters: Parameters,
            mappedTrackInfo: MappedTrackInfo,
            rendererFormatSupports: Array<Array<IntArray>>,
            rendererConfigurations: Array<RendererConfiguration?>,
            trackSelections: Array<ExoTrackSelection?>
        ) {
            // Check whether we can enable offload. To enable offload we require exactly one audio track
            // and a renderer with support matching the requirements set by
            // setAudioOffloadPreference. There also cannot be other non-audio renderers with their own
            // selected tracks unless the selection parameters say audio-only is not required.
            var audioRendererIndex = C.INDEX_UNSET
            var audioRenderersSupportingOffload = 0
            var hasNonAudioRendererWithSelectedTracks = false
            for (i in 0..<mappedTrackInfo.rendererCount) {
                val rendererType = mappedTrackInfo.getRendererType(i)
                val trackSelection = trackSelections[i]
                if (rendererType != C.TRACK_TYPE_AUDIO && trackSelection != null) {
                    hasNonAudioRendererWithSelectedTracks = true
                    break
                }
                if (rendererType == C.TRACK_TYPE_AUDIO && trackSelection != null && trackSelection.length() == 1) {
                    val trackGroupIndex =
                        mappedTrackInfo.getTrackGroups(i).indexOf(trackSelection.trackGroup)
                    val trackFormatSupport: @RendererCapabilities.Capabilities Int =
                        rendererFormatSupports[i][trackGroupIndex][trackSelection.getIndexInTrackGroup(
                            0
                        )]
                    if (rendererSupportsOffload(
                            parameters, trackFormatSupport, trackSelection.selectedFormat
                        )
                    ) {
                        audioRendererIndex = i
                        audioRenderersSupportingOffload++
                    }
                }
            }
            if (!hasNonAudioRendererWithSelectedTracks && audioRenderersSupportingOffload == 1) {
                val offloadRendererConfiguration =
                    RendererConfiguration(
                        if (parameters.audioOffloadPreferences.isGaplessSupportRequired)
                            AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED
                        else
                            AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED,
                        rendererConfigurations[audioRendererIndex] != null
                                && rendererConfigurations[audioRendererIndex]!!.tunneling
                    )
                rendererConfigurations[audioRendererIndex] = offloadRendererConfiguration
            }
        }

        private fun rendererSupportsOffload(
            parameters: Parameters,
            formatSupport: @RendererCapabilities.Capabilities Int,
            format: Format
        ): Boolean {
            if (RendererCapabilities.getAudioOffloadSupport(formatSupport) == RendererCapabilities.AUDIO_OFFLOAD_NOT_SUPPORTED) {
                return false
            }
            if (parameters.audioOffloadPreferences.isSpeedChangeSupportRequired
                && ((RendererCapabilities.getAudioOffloadSupport(formatSupport)
                        and RendererCapabilities.AUDIO_OFFLOAD_SPEED_CHANGE_SUPPORTED) == 0)
            ) {
                return false
            }
            // TODO(b/235883373): Add check for OPUS where gapless info is in initialization data
            if (parameters.audioOffloadPreferences.isGaplessSupportRequired) {
                val isGapless = format.encoderDelay != 0 || format.encoderPadding != 0
                val isGaplessSupported =
                    ((RendererCapabilities.getAudioOffloadSupport(formatSupport)
                            and RendererCapabilities.AUDIO_OFFLOAD_GAPLESS_SUPPORTED) != 0)
                return !isGapless || isGaplessSupported
            }
            return true
        }

        @Deprecated("Use {@link RendererCapabilities#isFormatSupported} instead.")
        protected fun isSupported(
            formatSupport: @RendererCapabilities.Capabilities Int, allowExceedsCapabilities: Boolean
        ): Boolean {
            return RendererCapabilities.isFormatSupported(formatSupport, allowExceedsCapabilities)
        }

        protected fun normalizeUndeterminedLanguageToNull(language: String?): String? {
            return if (TextUtils.isEmpty(language) ||
                TextUtils.equals(language, C.LANGUAGE_UNDETERMINED)
            )
                null
            else
                language
        }

        protected fun getFormatLanguageScore(
            format: Format, language: String?, allowUndeterminedFormatLanguage: Boolean
        ): Int {
            var language = language
            if (!TextUtils.isEmpty(language) && language == format.language) {
                // Full literal match of non-empty languages, including matches of an explicit "und" query.
                return 4
            }
            language = normalizeUndeterminedLanguageToNull(language)
            val formatLanguage: String? = normalizeUndeterminedLanguageToNull(format.language)
            if (formatLanguage == null || language == null) {
                // At least one of the languages is undetermined.
                return if (allowUndeterminedFormatLanguage && formatLanguage == null) 1 else 0
            }
            if (formatLanguage.startsWith(language) || language.startsWith(formatLanguage)) {
                // Partial match where one language is a subset of the other (e.g. "zh-hans" and "zh-hans-hk")
                return 3
            }
            val formatMainLanguage = splitAtFirst(formatLanguage, "-")[0]
            val queryMainLanguage = splitAtFirst(language, "-")[0]
            if (formatMainLanguage == queryMainLanguage) {
                // Partial match where only the main language tag is the same (e.g. "fr-fr" and "fr-ca")
                return 2
            }
            return 0
        }

        private fun getMaxVideoPixelsToRetainForViewport(
            group: TrackGroup,
            viewportWidth: Int,
            viewportHeight: Int,
            orientationMayChange: Boolean
        ): Int {
            if (viewportWidth == Int.MAX_VALUE || viewportHeight == Int.MAX_VALUE) {
                return Int.MAX_VALUE
            }
            var maxVideoPixelsToRetain = Int.MAX_VALUE
            for (i in 0..<group.length) {
                val format = group.getFormat(i)
                // Keep track of the number of pixels of the selected format whose resolution is the
                // smallest to exceed the maximum size at which it can be displayed within the viewport.
                if (format.width > 0 && format.height > 0) {
                    val maxVideoSizeInViewport =
                        TrackSelectionUtil.getMaxVideoSizeInViewport(
                            orientationMayChange,
                            viewportWidth,
                            viewportHeight,
                            format.width,
                            format.height
                        )
                    val videoPixels = format.width * format.height
                    if (format.width >= (maxVideoSizeInViewport.x * FRACTION_TO_CONSIDER_FULLSCREEN).toInt() && format.height >= (maxVideoSizeInViewport.y * FRACTION_TO_CONSIDER_FULLSCREEN).toInt() && videoPixels < maxVideoPixelsToRetain) {
                        maxVideoPixelsToRetain = videoPixels
                    }
                }
            }
            return maxVideoPixelsToRetain
        }

        private fun getRoleFlagMatchScore(trackRoleFlags: Int, preferredRoleFlags: Int): Int {
            if (trackRoleFlags != 0 && trackRoleFlags == preferredRoleFlags) {
                // Prefer perfect match over partial matches.
                return Int.MAX_VALUE
            }
            return Integer.bitCount(trackRoleFlags and preferredRoleFlags)
        }

        private fun getBestLabelMatchIndex(
            format: Format,
            preferredLabels: List<String>
        ): Int {
            for (i in preferredLabels.indices) {
                for (j in format.labels.indices) {
                    if (format.labels.get(j).value == preferredLabels[i]) {
                        return i
                    }
                }
            }
            return Int.MAX_VALUE
        }

        private fun getVideoCodecPreferenceScore(mimeType: String?): Int {
            if (mimeType == null) {
                return 0
            }
            return when (mimeType) {
                MimeTypes.VIDEO_DOLBY_VISION -> 5
                MimeTypes.VIDEO_AV1 -> 4
                MimeTypes.VIDEO_H265 -> 3
                MimeTypes.VIDEO_VP9 -> 2
                MimeTypes.VIDEO_H264 -> 1
                else -> 0
            }
        }

        private fun isObjectBasedAudio(format: Format): Boolean {
            if (format.sampleMimeType == null) {
                return false
            }
            return when (format.sampleMimeType) {
                MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_AC4, MimeTypes.AUDIO_IAMF -> true
                else -> false
            }
        }

        private fun isDolbyAudio(format: Format): Boolean {
            if (format.sampleMimeType == null) {
                return false
            }
            return when (format.sampleMimeType) {
                MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_AC4 -> true
                else -> false
            }
        }

        private fun getPreferredLanguageFromCaptioningManager(context: Context?): String? {
            if (context == null) {
                return null
            }
            val captioningManager =
                context.getSystemService(Context.CAPTIONING_SERVICE) as CaptioningManager?
            if (captioningManager == null || !captioningManager.isEnabled) {
                return null
            }
            val preferredLocale = captioningManager.locale ?: return null
            return getLocaleLanguageTag(preferredLocale)
        }

        private fun findDefinitionForType(
            definitions: Array<ExoTrackSelection.Definition?>, trackType: @C.TrackType Int
        ): Pair<ExoTrackSelection.Definition, Int>? {
            for (i in definitions.indices) {
                if (definitions[i] != null && definitions[i]!!.group.type == trackType) {
                    return Pair.create<ExoTrackSelection.Definition, Int>(definitions[i], i)
                }
            }
            return null
        }
    }
}
