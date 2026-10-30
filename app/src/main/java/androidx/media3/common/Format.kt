package androidx.media3.common

import android.os.Bundle
import android.text.TextUtils
import androidx.annotation.IntDef
import androidx.media3.common.C.AuxiliaryTrackType
import androidx.media3.common.C.CryptoType
import androidx.media3.common.C.PcmEncoding
import androidx.media3.common.C.RoleFlags
import androidx.media3.common.C.SelectionFlags
import androidx.media3.common.C.StereoMode
import androidx.media3.common.util.BundleCollectionUtil
import androidx.media3.common.util.Util
import com.google.common.base.Function
import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.collect.Lists
import com.google.common.math.DoubleMath
import kotlin.Any
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.plus

class Format private constructor(builder: Builder) {
    class Builder {
        internal var id: String? = null
        internal var label: String? = null
        internal var labels: List<Label>
        internal var language: String? = null

        @SelectionFlags
        internal var selectionFlags: @SelectionFlags Int = 0

        @RoleFlags
        internal var roleFlags: @RoleFlags Int = 0
        internal var selectionPriority: Float

        @AuxiliaryTrackType
        internal var auxiliaryTrackType: @AuxiliaryTrackType Int = 0
        internal var averageBitrate: Int
        internal var peakBitrate: Int
        internal var codecs: String? = null
        internal var metadata: Metadata? = null
        internal var customData: Any? = null
        internal var primaryTrackGroupId: String? = null

        internal var containerMimeType: String? = null

        // Sample specific.
        internal var sampleMimeType: String? = null
        internal var maxInputSize: Int
        internal var maxNumReorderSamples: Int
        internal var initializationData: List<ByteArray>? = null
        internal var drmInitData: DrmInitData? = null
        internal var subsampleOffsetUs: Long
        internal var hasPrerollSamples = false

        // Video specific.
        internal var width: Int
        internal var height: Int
        internal var pixelFormat: Int
        internal var decodedWidth: Int
        internal var decodedHeight: Int
        internal var frameRate: Float
        internal var rotationDegrees = 0
        internal var mirrorHorizontal = false
        internal var pixelWidthHeightRatio: Float
        internal var projectionData: ByteArray? = null
        var stereoMode: @StereoMode Int
        var colorInfo: ColorInfo? = null
        var maxSubLayers: Int

        // Audio specific.
        internal var channelCount: Int
        internal var channelMask: Int
        internal var sampleRate: Int
        internal var pcmEncoding: @PcmEncoding Int
        internal var encoderDelay = 0
        internal var encoderPadding = 0

        // Text specific.
        internal var accessibilityChannel: Int
        internal var cueReplacementBehavior: @CueReplacementBehavior Int

        // Image specific
        internal var tileCountHorizontal: Int
        internal var tileCountVertical: Int

        // Provided by the source.
        internal var cryptoType: @CryptoType Int

        constructor() {
            labels = listOf()
            selectionPriority = NO_VALUE.toFloat()
            averageBitrate = NO_VALUE
            peakBitrate = NO_VALUE
            // Sample specific.
            maxInputSize = NO_VALUE
            maxNumReorderSamples = NO_VALUE
            subsampleOffsetUs = OFFSET_SAMPLE_RELATIVE
            // Video specific.
            width = NO_VALUE
            height = NO_VALUE
            pixelFormat = NO_VALUE
            decodedWidth = NO_VALUE
            decodedHeight = NO_VALUE
            frameRate = NO_VALUE.toFloat()
            pixelWidthHeightRatio = 1.0f
            stereoMode = NO_VALUE
            maxSubLayers = NO_VALUE
            // Audio specific.
            channelCount = NO_VALUE
            channelMask = NO_VALUE
            sampleRate = NO_VALUE
            pcmEncoding = NO_VALUE
            // Text specific.
            accessibilityChannel = NO_VALUE
            cueReplacementBehavior = CUE_REPLACEMENT_BEHAVIOR_MERGE
            // Image specific.
            tileCountHorizontal = NO_VALUE
            tileCountVertical = NO_VALUE
            // Provided by the source.
            cryptoType = C.CRYPTO_TYPE_NONE
            auxiliaryTrackType = C.AUXILIARY_TRACK_TYPE_UNDEFINED
        }

        internal constructor(format: Format) {
            this.id = format.id
            this.label = format.label
            this.labels = format.labels
            this.language = format.language
            this.selectionFlags = format.selectionFlags
            this.roleFlags = format.roleFlags
            this.selectionPriority = format.selectionPriority
            this.averageBitrate = format.averageBitrate
            this.peakBitrate = format.peakBitrate
            this.codecs = format.codecs
            this.metadata = format.metadata
            this.customData = format.customData
            this.primaryTrackGroupId = format.primaryTrackGroupId
            // Container specific.
            this.containerMimeType = format.containerMimeType
            // Sample specific.
            this.sampleMimeType = format.sampleMimeType
            this.maxInputSize = format.maxInputSize
            this.maxNumReorderSamples = format.maxNumReorderSamples
            this.initializationData = format.initializationData
            this.drmInitData = format.drmInitData
            this.subsampleOffsetUs = format.subsampleOffsetUs
            this.hasPrerollSamples = format.hasPrerollSamples
            // Video specific.
            this.width = format.width
            this.height = format.height
            this.pixelFormat = format.pixelFormat
            this.decodedWidth = format.decodedWidth
            this.decodedHeight = format.decodedHeight
            this.frameRate = format.frameRate
            this.rotationDegrees = format.rotationDegrees
            this.mirrorHorizontal = format.mirrorHorizontal
            this.pixelWidthHeightRatio = format.pixelWidthHeightRatio
            this.projectionData = format.projectionData
            this.stereoMode = format.stereoMode
            this.colorInfo = format.colorInfo
            this.maxSubLayers = format.maxSubLayers
            // Audio specific.
            this.channelCount = format.channelCount
            this.channelMask = format.channelMask
            this.sampleRate = format.sampleRate
            this.pcmEncoding = format.pcmEncoding
            this.encoderDelay = format.encoderDelay
            this.encoderPadding = format.encoderPadding
            // Text specific.
            this.accessibilityChannel = format.accessibilityChannel
            this.cueReplacementBehavior = format.cueReplacementBehavior
            // Image specific.
            this.tileCountHorizontal = format.tileCountHorizontal
            this.tileCountVertical = format.tileCountVertical
            // Provided by the source.
            this.cryptoType = format.cryptoType
        }

        fun setId(id: String?): Builder {
            this.id = id
            return this
        }

        fun setId(id: Int): Builder {
            this.id = id.toString()
            return this
        }

        fun setLabel(label: String?): Builder {
            this.label = label
            return this
        }

        fun setLabels(labels: List<Label>): Builder {
            this.labels = labels.toList()
            return this
        }

        fun setLanguage(language: String?): Builder {
            this.language = language
            return this
        }

        fun setSelectionFlags(@SelectionFlags selectionFlags: @SelectionFlags Int): Builder {
            this.selectionFlags = selectionFlags
            return this
        }

        fun setRoleFlags(@RoleFlags roleFlags: @RoleFlags Int): Builder {
            this.roleFlags = roleFlags
            return this
        }

        fun setSelectionPriority(selectionPriority: Float): Builder {
            this.selectionPriority = selectionPriority
            return this
        }

        fun setAuxiliaryTrackType(@AuxiliaryTrackType auxiliaryTrackType: @AuxiliaryTrackType Int): Builder {
            this.auxiliaryTrackType = auxiliaryTrackType
            return this
        }

        fun setAverageBitrate(averageBitrate: Int): Builder {
            this.averageBitrate = averageBitrate
            return this
        }

        fun setPeakBitrate(peakBitrate: Int): Builder {
            this.peakBitrate = peakBitrate
            return this
        }

        fun setCodecs(codecs: String?): Builder {
            this.codecs = codecs
            return this
        }

        fun setMetadata(metadata: Metadata?): Builder {
            this.metadata = metadata
            return this
        }

        fun setCustomData(customData: Any?): Builder {
            this.customData = customData
            return this
        }

        fun setPrimaryTrackGroupId(primaryTrackGroupId: String?): Builder {
            this.primaryTrackGroupId = primaryTrackGroupId
            return this
        }

        fun setContainerMimeType(containerMimeType: String?): Builder {
            this.containerMimeType = MimeTypes.normalizeMimeType(containerMimeType)
            return this
        }

        fun setSampleMimeType(sampleMimeType: String?): Builder {
            this.sampleMimeType = MimeTypes.normalizeMimeType(sampleMimeType)
            return this
        }

        fun setMaxInputSize(maxInputSize: Int): Builder {
            this.maxInputSize = maxInputSize
            return this
        }

        fun setMaxNumReorderSamples(maxNumReorderSamples: Int): Builder {
            this.maxNumReorderSamples = maxNumReorderSamples
            return this
        }

        fun setInitializationData(initializationData: List<ByteArray>?): Builder {
            this.initializationData = initializationData
            return this
        }

        fun setDrmInitData(drmInitData: DrmInitData?): Builder {
            this.drmInitData = drmInitData
            return this
        }

        fun setSubsampleOffsetUs(subsampleOffsetUs: Long): Builder {
            this.subsampleOffsetUs = subsampleOffsetUs
            return this
        }

        fun setHasPrerollSamples(hasPrerollSamples: Boolean): Builder {
            this.hasPrerollSamples = hasPrerollSamples
            return this
        }

        fun setWidth(width: Int): Builder {
            this.width = width
            return this
        }

        fun setHeight(height: Int): Builder {
            this.height = height
            return this
        }

        fun setPixelFormat(pixelFormat: Int): Builder {
            this.pixelFormat = pixelFormat
            return this
        }

        fun setDecodedWidth(decodedWidth: Int): Builder {
            this.decodedWidth = decodedWidth
            return this
        }

        fun setDecodedHeight(decodedHeight: Int): Builder {
            this.decodedHeight = decodedHeight
            return this
        }

        fun setFrameRate(frameRate: Float): Builder {
            Preconditions.checkArgument(frameRate == NO_VALUE.toFloat() || frameRate > 0f)
            this.frameRate = frameRate
            return this
        }

        fun setRotationDegrees(rotationDegrees: Int): Builder {
            this.rotationDegrees = rotationDegrees
            return this
        }

        fun setMirrorHorizontal(mirrorHorizontal: Boolean): Builder {
            this.mirrorHorizontal = mirrorHorizontal
            return this
        }

        fun setPixelWidthHeightRatio(pixelWidthHeightRatio: Float): Builder {
            this.pixelWidthHeightRatio = pixelWidthHeightRatio
            return this
        }

        fun setProjectionData(projectionData: ByteArray?): Builder {
            this.projectionData = projectionData
            return this
        }

        fun setStereoMode(stereoMode: @StereoMode Int): Builder {
            this.stereoMode = stereoMode
            return this
        }

        fun setColorInfo(colorInfo: ColorInfo?): Builder {
            this.colorInfo = colorInfo
            return this
        }

        fun setMaxSubLayers(maxSubLayers: Int): Builder {
            this.maxSubLayers = maxSubLayers
            return this
        }

        fun setChannelCount(channelCount: Int): Builder {
            this.channelCount = channelCount
            return this
        }

        fun setChannelMask(channelMask: Int): Builder {
            this.channelMask = channelMask
            return this
        }

        fun setSampleRate(sampleRate: Int): Builder {
            this.sampleRate = sampleRate
            return this
        }

        fun setPcmEncoding(pcmEncoding: @PcmEncoding Int): Builder {
            this.pcmEncoding = pcmEncoding
            return this
        }

        fun setEncoderDelay(encoderDelay: Int): Builder {
            this.encoderDelay = encoderDelay
            return this
        }

        fun setEncoderPadding(encoderPadding: Int): Builder {
            this.encoderPadding = encoderPadding
            return this
        }

        fun setAccessibilityChannel(accessibilityChannel: Int): Builder {
            this.accessibilityChannel = accessibilityChannel
            return this
        }

        fun setCueReplacementBehavior(cueReplacementBehavior: @CueReplacementBehavior Int): Builder {
            this.cueReplacementBehavior = cueReplacementBehavior
            return this
        }

        fun setTileCountHorizontal(tileCountHorizontal: Int): Builder {
            this.tileCountHorizontal = tileCountHorizontal
            return this
        }

        fun setTileCountVertical(tileCountVertical: Int): Builder {
            this.tileCountVertical = tileCountVertical
            return this
        }

        fun setCryptoType(cryptoType: @CryptoType Int): Builder {
            this.cryptoType = cryptoType
            return this
        }

        fun build(): Format {
            return Format(this)
        }
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [CUE_REPLACEMENT_BEHAVIOR_MERGE, CUE_REPLACEMENT_BEHAVIOR_REPLACE]
    )
    annotation class CueReplacementBehavior

    val id: String?
    val label: String?
    val labels: List<Label>
    val language: String?

    @SelectionFlags
    val selectionFlags: @SelectionFlags Int

    @RoleFlags
    val roleFlags: @RoleFlags Int
    val selectionPriority: Float

    @AuxiliaryTrackType
    val auxiliaryTrackType: @AuxiliaryTrackType Int
    val averageBitrate: Int
    val peakBitrate: Int
    val bitrate: Int
    val codecs: String?
    val metadata: Metadata?
    val customData: Any?
    val primaryTrackGroupId: String?
    val containerMimeType: String?
    val sampleMimeType: String?
    val maxInputSize: Int
    val maxNumReorderSamples: Int
    val initializationData: List<ByteArray>
    val drmInitData: DrmInitData?
    val subsampleOffsetUs: Long
    val hasPrerollSamples: Boolean
    val width: Int
    val height: Int
    val pixelFormat: Int
    val decodedWidth: Int
    val decodedHeight: Int
    val frameRate: Float
    val rotationDegrees: Int
    val mirrorHorizontal: Boolean
    val pixelWidthHeightRatio: Float
    val projectionData: ByteArray?
    val stereoMode: @StereoMode Int
    val colorInfo: ColorInfo?
    val maxSubLayers: Int
    val channelCount: Int
    val channelMask: Int
    val sampleRate: Int
    val pcmEncoding: @PcmEncoding Int
    val encoderDelay: Int
    val encoderPadding: Int
    val accessibilityChannel: Int
    val cueReplacementBehavior: @CueReplacementBehavior Int
    val tileCountHorizontal: Int
    val tileCountVertical: Int
    val cryptoType: @CryptoType Int
    private var hashCode = 0

    fun buildUpon(): Builder {
        return Format.Builder(this)
    }

    fun withManifestFormatInfo(manifestFormat: Format): Format {
        if (this === manifestFormat) {
            // No need to copy from ourselves.
            return this
        }

        val trackType: @C.TrackType Int = MimeTypes.getTrackType(sampleMimeType)

        // Use manifest value only.
        val id = manifestFormat.id
        val selectionPriority = manifestFormat.selectionPriority
        val tileCountHorizontal = manifestFormat.tileCountHorizontal
        val tileCountVertical = manifestFormat.tileCountVertical

        // Prefer manifest values, but fill in from sample format if missing.
        val label = if (manifestFormat.label != null) manifestFormat.label else this.label
        val labels: List<Label> =
            if (!manifestFormat.labels.isEmpty()) manifestFormat.labels else this.labels
        var language = this.language
        if ((trackType == C.TRACK_TYPE_TEXT || trackType == C.TRACK_TYPE_AUDIO)
            && manifestFormat.language != null
        ) {
            language = manifestFormat.language
        }

        // Prefer sample format values, but fill in from manifest if missing.
        val averageBitrate =
            if (this.averageBitrate == NO_VALUE) manifestFormat.averageBitrate else this.averageBitrate
        val peakBitrate =
            if (this.peakBitrate == NO_VALUE) manifestFormat.peakBitrate else this.peakBitrate
        var codecs = this.codecs
        if (codecs == null) {
            // The manifest format may be muxed, so filter only codecs of this format's type. If we still
            // have more than one codec then we're unable to uniquely identify which codec to fill in.
            val codecsOfType = Util.getCodecsOfType(manifestFormat.codecs, trackType)
            if (Util.splitCodecs(codecsOfType).size == 1) {
                codecs = codecsOfType
            }
        }
        val primaryTrackGroupId =
            if (this.primaryTrackGroupId != null)
                this.primaryTrackGroupId
            else
                manifestFormat.primaryTrackGroupId

        val metadata =
            if (this.metadata == null)
                manifestFormat.metadata
            else
                this.metadata.copyWithAppendedEntriesFrom(manifestFormat.metadata)

        var frameRate = this.frameRate
        if (frameRate == NO_VALUE.toFloat() && trackType == C.TRACK_TYPE_VIDEO) {
            frameRate = manifestFormat.frameRate
        }

        // Merge manifest and sample format values.
        @SelectionFlags val selectionFlags: @SelectionFlags Int =
            this.selectionFlags or manifestFormat.selectionFlags
        @RoleFlags val roleFlags: @RoleFlags Int = this.roleFlags or manifestFormat.roleFlags
        val drmInitData =
            DrmInitData.createSessionCreationData(manifestFormat.drmInitData, this.drmInitData)

        return buildUpon()
            .setId(id)
            .setLabel(label)
            .setLabels(labels)
            .setLanguage(language)
            .setSelectionFlags(selectionFlags)
            .setRoleFlags(roleFlags)
            .setSelectionPriority(selectionPriority)
            .setAverageBitrate(averageBitrate)
            .setPeakBitrate(peakBitrate)
            .setCodecs(codecs)
            .setMetadata(metadata)
            .setPrimaryTrackGroupId(primaryTrackGroupId)
            .setDrmInitData(drmInitData)
            .setFrameRate(frameRate)
            .setTileCountHorizontal(tileCountHorizontal)
            .setTileCountVertical(tileCountVertical)
            .build()
    }

    fun copyWithCryptoType(cryptoType: @CryptoType Int): Format {
        return buildUpon().setCryptoType(cryptoType).build()
    }

    val pixelCount: Int
        get() = if (width == NO_VALUE || height == NO_VALUE) NO_VALUE else (width * height)

    override fun toString(): String {
        return ("Format("
                + id
                + ", "
                + label
                + ", "
                + containerMimeType
                + ", "
                + sampleMimeType
                + ", "
                + codecs
                + ", "
                + bitrate
                + ", "
                + language
                + ", ["
                + width
                + ", "
                + height
                + ", "
                + frameRate
                + ", "
                + colorInfo
                + "]"
                + ", ["
                + channelCount
                + ", "
                + channelMask
                + ", "
                + sampleRate
                + "])")
    }

    override fun hashCode(): Int {
        if (hashCode == 0) {
            // Some fields for which hashing is expensive are deliberately omitted.
            var result = 17
            result = 31 * result + (if (id == null) 0 else id.hashCode())
            result = 31 * result + (if (label == null) 0 else label.hashCode())
            result = 31 * result + labels.hashCode()
            result = 31 * result + (if (language == null) 0 else language.hashCode())
            result = 31 * result + selectionFlags
            result = 31 * result + roleFlags
            result = 31 * result + selectionPriority.toBits()
            result = 31 * result + auxiliaryTrackType
            result = 31 * result + averageBitrate
            result = 31 * result + peakBitrate
            result = 31 * result + (if (codecs == null) 0 else codecs.hashCode())
            result = 31 * result + (if (metadata == null) 0 else metadata.hashCode())
            result = 31 * result + (if (customData == null) 0 else customData.hashCode())
            result =
                31 * result + (if (primaryTrackGroupId == null) 0 else primaryTrackGroupId.hashCode())
            // Container specific.
            result =
                31 * result + (if (containerMimeType == null) 0 else containerMimeType.hashCode())
            // Sample specific.
            result = 31 * result + (if (sampleMimeType == null) 0 else sampleMimeType.hashCode())
            result = 31 * result + maxInputSize
            // [Omitted] initializationData.
            // [Omitted] drmInitData.
            result = 31 * result + subsampleOffsetUs.toInt()
            // Video specific.
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + pixelFormat
            result = 31 * result + decodedWidth
            result = 31 * result + decodedHeight
            result = 31 * result + frameRate.toBits()
            result = 31 * result + rotationDegrees
            result = 31 * result + (if (mirrorHorizontal) 1 else 0)
            result = 31 * result + pixelWidthHeightRatio.toBits()
            // [Omitted] projectionData.
            result = 31 * result + stereoMode
            // [Omitted] colorInfo.
            result = 31 * result + maxSubLayers
            // Audio specific.
            result = 31 * result + channelCount
            result = 31 * result + channelMask
            result = 31 * result + sampleRate
            result = 31 * result + pcmEncoding
            result = 31 * result + encoderDelay
            result = 31 * result + encoderPadding
            // Text specific.
            result = 31 * result + accessibilityChannel
            // Image specific.
            result = 31 * result + tileCountHorizontal
            result = 31 * result + tileCountVertical
            // Provided by the source.
            result = 31 * result + cryptoType
            hashCode = result
        }
        return hashCode
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as Format
        if (hashCode != 0 && other.hashCode != 0 && hashCode != other.hashCode) {
            return false
        }
        selectionPriority.toBits()
        // Field equality checks ordered by type, with the cheapest checks first.
        return selectionFlags == other.selectionFlags && roleFlags == other.roleFlags && auxiliaryTrackType == other.auxiliaryTrackType && averageBitrate == other.averageBitrate && peakBitrate == other.peakBitrate && maxInputSize == other.maxInputSize && subsampleOffsetUs == other.subsampleOffsetUs && width == other.width && height == other.height && pixelFormat == other.pixelFormat && decodedWidth == other.decodedWidth && decodedHeight == other.decodedHeight && rotationDegrees == other.rotationDegrees && mirrorHorizontal == other.mirrorHorizontal && stereoMode == other.stereoMode && maxSubLayers == other.maxSubLayers && channelCount == other.channelCount && channelMask == other.channelMask && sampleRate == other.sampleRate && pcmEncoding == other.pcmEncoding && encoderDelay == other.encoderDelay && encoderPadding == other.encoderPadding && accessibilityChannel == other.accessibilityChannel && tileCountHorizontal == other.tileCountHorizontal && tileCountVertical == other.tileCountVertical && cryptoType == other.cryptoType && frameRate.compareTo(
            other.frameRate
        ) == 0 && selectionPriority.compareTo(other.selectionPriority) == 0 && pixelWidthHeightRatio.compareTo(
            other.pixelWidthHeightRatio
        ) == 0 && id == other.id
                && label == other.label
                && labels == other.labels
                && codecs == other.codecs
                && primaryTrackGroupId == other.primaryTrackGroupId
                && containerMimeType == other.containerMimeType
                && sampleMimeType == other.sampleMimeType
                && language == other.language
                && projectionData.contentEquals(other.projectionData) && metadata == other.metadata
                && colorInfo == other.colorInfo
                && drmInitData == other.drmInitData
                && initializationDataEquals(other)
                && customData == other.customData
    }

    fun initializationDataEquals(other: Format): Boolean {
        if (initializationData.size != other.initializationData.size) {
            return false
        }
        for (i in initializationData.indices) {
            if (!initializationData[i].contentEquals(other.initializationData[i])) {
                return false
            }
        }
        return true
    }

    init {
        id = builder.id
        language = builder.language?.let { Util.normalizeLanguageCode(it) }
        if (builder.labels.isEmpty() && builder.label != null) {
            labels = listOf(Label(language, builder.label!!))
            label = builder.label
        } else if (!builder.labels.isEmpty() && builder.label == null) {
            labels = builder.labels
            label = getDefaultLabel(builder.labels, language)
        } else {
            Preconditions.checkState(isLabelPartOfLabels(builder))
            labels = builder.labels
            label = builder.label
        }
        selectionFlags = builder.selectionFlags

        Preconditions.checkState(
            builder.auxiliaryTrackType == C.AUXILIARY_TRACK_TYPE_UNDEFINED
                    || (builder.roleFlags and C.ROLE_FLAG_AUXILIARY) != 0,
            "Auxiliary track type must only be set to a value other than AUXILIARY_TRACK_TYPE_UNDEFINED"
                    + " only when ROLE_FLAG_AUXILIARY is set"
        )
        roleFlags = builder.roleFlags
        selectionPriority = builder.selectionPriority
        auxiliaryTrackType = builder.auxiliaryTrackType
        averageBitrate = builder.averageBitrate
        peakBitrate = builder.peakBitrate
        bitrate = if (peakBitrate != NO_VALUE) peakBitrate else averageBitrate
        codecs = builder.codecs
        metadata = builder.metadata
        customData = builder.customData
        primaryTrackGroupId = builder.primaryTrackGroupId
        // Container specific.
        containerMimeType = builder.containerMimeType
        // Sample specific.
        sampleMimeType = builder.sampleMimeType
        maxInputSize = builder.maxInputSize
        maxNumReorderSamples = builder.maxNumReorderSamples
        initializationData =
            (if (builder.initializationData == null) listOf<ByteArray>() else builder.initializationData)!!
        drmInitData = builder.drmInitData
        subsampleOffsetUs = builder.subsampleOffsetUs
        hasPrerollSamples = builder.hasPrerollSamples
        // Video specific.
        width = builder.width
        height = builder.height
        pixelFormat = builder.pixelFormat
        decodedWidth = builder.decodedWidth
        decodedHeight = builder.decodedHeight
        frameRate = builder.frameRate
        rotationDegrees = if (builder.rotationDegrees == NO_VALUE) 0 else builder.rotationDegrees
        mirrorHorizontal = builder.mirrorHorizontal
        pixelWidthHeightRatio =
            if (builder.pixelWidthHeightRatio == NO_VALUE.toFloat()) 1f else builder.pixelWidthHeightRatio
        projectionData = builder.projectionData
        stereoMode = builder.stereoMode
        colorInfo = builder.colorInfo
        maxSubLayers = builder.maxSubLayers
        // Audio specific.
        channelCount = builder.channelCount
        channelMask = builder.channelMask
        if (channelCount != NO_VALUE && channelMask != NO_VALUE) {
            Preconditions.checkState(
                Integer.bitCount(channelMask) == channelCount,
                "channelCount and channelMask are inconsistent. channelCount=%s, channelMask=%s",
                channelCount,
                channelMask
            )
        }
        sampleRate = builder.sampleRate
        pcmEncoding = builder.pcmEncoding
        encoderDelay = if (builder.encoderDelay == NO_VALUE) 0 else builder.encoderDelay
        encoderPadding = if (builder.encoderPadding == NO_VALUE) 0 else builder.encoderPadding
        // Text specific.
        accessibilityChannel = builder.accessibilityChannel
        cueReplacementBehavior = builder.cueReplacementBehavior
        // Image specific.
        tileCountHorizontal = builder.tileCountHorizontal
        tileCountVertical = builder.tileCountVertical
        // Provided by source.
        if (builder.cryptoType == C.CRYPTO_TYPE_NONE && drmInitData != null) {
            // Encrypted content cannot use CRYPTO_TYPE_NONE.
            cryptoType = C.CRYPTO_TYPE_UNSUPPORTED
        } else {
            cryptoType = builder.cryptoType
        }
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putString(FIELD_ID, id)
        bundle.putString(FIELD_LABEL, label)
        bundle.putParcelableArrayList(
            FIELD_LABELS,
            BundleCollectionUtil.toBundleArrayList<Label>(
                labels,
                Function { obj: Label? -> obj!!.toBundle() })
        )
        bundle.putString(FIELD_LANGUAGE, language)
        bundle.putInt(FIELD_SELECTION_FLAGS, selectionFlags)
        bundle.putInt(FIELD_ROLE_FLAGS, roleFlags)
        bundle.putFloat(FIELD_SELECTION_PRIORITY, selectionPriority)
        if (auxiliaryTrackType != DEFAULT.auxiliaryTrackType) {
            bundle.putInt(FIELD_AUXILIARY_TRACK_TYPE, auxiliaryTrackType)
        }
        bundle.putInt(FIELD_AVERAGE_BITRATE, averageBitrate)
        bundle.putInt(FIELD_PEAK_BITRATE, peakBitrate)
        bundle.putString(FIELD_CODECS, codecs)
        if (primaryTrackGroupId != null) {
            bundle.putString(FIELD_PRIMARY_TRACK_GROUP_ID, primaryTrackGroupId)
        }
        // The metadata does not implement toBundle() method, hence can not be added.
        // Container specific.
        bundle.putString(FIELD_CONTAINER_MIME_TYPE, containerMimeType)
        // Sample specific.
        bundle.putString(FIELD_SAMPLE_MIME_TYPE, sampleMimeType)
        bundle.putInt(FIELD_MAX_INPUT_SIZE, maxInputSize)
        for (i in initializationData.indices) {
            bundle.putByteArray(keyForInitializationData(i), initializationData[i])
        }
        // DrmInitData doesn't need to be put into Bundle as it's only used in the playing process to
        // initialize the decoder.
        bundle.putParcelable(FIELD_DRM_INIT_DATA, drmInitData)
        bundle.putLong(FIELD_SUBSAMPLE_OFFSET_US, subsampleOffsetUs)
        // Video specific.
        bundle.putInt(FIELD_WIDTH, width)
        bundle.putInt(FIELD_HEIGHT, height)
        bundle.putInt(FIELD_PIXEL_FORMAT, pixelFormat)
        bundle.putInt(FIELD_DECODED_WIDTH, decodedWidth)
        bundle.putInt(FIELD_DECODED_HEIGHT, decodedHeight)
        bundle.putFloat(FIELD_FRAME_RATE, frameRate)
        bundle.putInt(FIELD_ROTATION_DEGREES, rotationDegrees)
        bundle.putBoolean(FIELD_MIRROR_HORIZONTAL, mirrorHorizontal)
        bundle.putFloat(FIELD_PIXEL_WIDTH_HEIGHT_RATIO, pixelWidthHeightRatio)
        bundle.putByteArray(FIELD_PROJECTION_DATA, projectionData)
        bundle.putInt(FIELD_STEREO_MODE, stereoMode)
        if (colorInfo != null) {
            bundle.putBundle(FIELD_COLOR_INFO, colorInfo.toBundle())
        }
        bundle.putInt(FIELD_MAX_SUB_LAYERS, maxSubLayers)
        // Audio specific.
        bundle.putInt(FIELD_CHANNEL_COUNT, channelCount)
        bundle.putInt(FIELD_CHANNEL_MASK, channelMask)
        bundle.putInt(FIELD_SAMPLE_RATE, sampleRate)
        bundle.putInt(FIELD_PCM_ENCODING, pcmEncoding)
        bundle.putInt(FIELD_ENCODER_DELAY, encoderDelay)
        bundle.putInt(FIELD_ENCODER_PADDING, encoderPadding)
        // Text specific.
        bundle.putInt(FIELD_ACCESSIBILITY_CHANNEL, accessibilityChannel)
        // Image specific.
        bundle.putInt(FIELD_TILE_COUNT_HORIZONTAL, tileCountHorizontal)
        bundle.putInt(FIELD_TILE_COUNT_VERTICAL, tileCountVertical)
        // Source specific.
        bundle.putInt(FIELD_CRYPTO_TYPE, cryptoType)
        return bundle
    }

    companion object {
        const val CUE_REPLACEMENT_BEHAVIOR_MERGE: Int = 1
        const val CUE_REPLACEMENT_BEHAVIOR_REPLACE: Int = 2
        const val NO_VALUE: Int = -1
        val OFFSET_SAMPLE_RELATIVE: Long = Long.MAX_VALUE
        private val DEFAULT = Builder().build()
        private fun isLabelPartOfLabels(builder: Builder): Boolean {
            if (builder.labels.isEmpty() && builder.label == null) {
                return true
            }
            for (i in builder.labels.indices) {
                if (builder.labels[i].value == builder.label) {
                    return true
                }
            }
            return false
        }

        fun toLogString(format: Format?): String {
            if (format == null) {
                return "null"
            }
            val commaJoiner = Joiner.on(',')
            val builder = StringBuilder()
            builder.append("id=").append(format.id).append(", mimeType=")
                .append(format.sampleMimeType)
            if (format.containerMimeType != null) {
                builder.append(", container=").append(format.containerMimeType)
            }
            if (format.primaryTrackGroupId != null) {
                builder.append(", primaryGroupId=").append(format.primaryTrackGroupId)
            }
            if (format.bitrate != NO_VALUE) {
                builder.append(", bitrate=").append(format.bitrate)
            }
            if (format.selectionPriority != NO_VALUE.toFloat()) {
                builder.append(", selectionPriority=").append(format.selectionPriority)
            }
            if (format.codecs != null) {
                builder.append(", codecs=").append(format.codecs)
            }
            if (format.drmInitData != null) {
                val schemes: MutableSet<String?> = LinkedHashSet<String?>()
                for (i in 0..<format.drmInitData.schemeDataCount) {
                    val schemeUuid = format.drmInitData.get(i)?.uuid
                    if (schemeUuid == C.COMMON_PSSH_UUID) {
                        schemes.add("cenc")
                    } else if (schemeUuid == C.CLEARKEY_UUID) {
                        schemes.add("clearkey")
                    } else if (schemeUuid == C.PLAYREADY_UUID) {
                        schemes.add("playready")
                    } else if (schemeUuid == C.WIDEVINE_UUID) {
                        schemes.add("widevine")
                    } else if (schemeUuid == C.UUID_NIL) {
                        schemes.add("universal")
                    } else {
                        schemes.add("unknown (" + schemeUuid + ")")
                    }
                }
                builder.append(", drm=[")
                commaJoiner.appendTo(builder, schemes)
                builder.append(']')
            }
            if (format.width != NO_VALUE && format.height != NO_VALUE) {
                builder.append(", res=").append(format.width).append("x").append(format.height)
            }
            if (format.decodedWidth != NO_VALUE && format.decodedHeight != NO_VALUE) {
                builder
                    .append(", decRes=")
                    .append(format.decodedWidth)
                    .append("x")
                    .append(format.decodedHeight)
            }
            if (!DoubleMath.fuzzyEquals(format.pixelWidthHeightRatio.toDouble(), 1.0, 0.001)) {
                builder.append(", par=")
                    .append(Util.formatInvariant("%.3f", format.pixelWidthHeightRatio))
            }
            if (format.colorInfo != null && format.colorInfo.isValid) {
                builder.append(", color=").append(format.colorInfo.toLogString())
            }
            if (format.pixelFormat != NO_VALUE) {
                builder.append(", pixelFormat=").append(format.pixelFormat)
            }
            if (format.frameRate != NO_VALUE.toFloat()) {
                builder.append(", fps=").append(format.frameRate)
            }
            if (format.rotationDegrees != 0) {
                builder.append(", rotation=").append(format.rotationDegrees)
            }
            if (format.mirrorHorizontal) {
                builder.append(", mirrorHorizontal")
            }
            if (format.maxSubLayers != NO_VALUE) {
                builder.append(", maxSubLayers=").append(format.maxSubLayers)
            }
            if (format.channelCount != NO_VALUE) {
                builder.append(", channels=").append(format.channelCount)
            }
            if (format.channelMask != NO_VALUE) {
                builder.append(", channel_mask=").append(format.channelMask)
            }
            if (format.sampleRate != NO_VALUE) {
                builder.append(", sample_rate=").append(format.sampleRate)
            }
            if (format.language != null) {
                builder.append(", language=").append(format.language)
            }
            if (!format.labels.isEmpty()) {
                builder.append(", labels=[")
                commaJoiner.appendTo(
                    builder,
                    Lists.transform<Label, String?>(
                        format.labels,
                        Function { l: Label? -> l!!.language + ": " + l.value })
                )
                builder.append("]")
            }
            if (format.selectionFlags != 0) {
                builder.append(", selectionFlags=[")
                commaJoiner.appendTo(builder, Util.getSelectionFlagStrings(format.selectionFlags))
                builder.append("]")
            }
            if (format.roleFlags != 0) {
                builder.append(", roleFlags=[")
                commaJoiner.appendTo(builder, Util.getRoleFlagStrings(format.roleFlags))
                builder.append("]")
            }
            if (format.customData != null) {
                builder.append(", customData=").append(format.customData)
            }
            if ((format.roleFlags and C.ROLE_FLAG_AUXILIARY) != 0) {
                builder
                    .append(", auxiliaryTrackType=")
                    .append(Util.getAuxiliaryTrackTypeString(format.auxiliaryTrackType))
            }
            return builder.toString()
        }

        private val FIELD_ID: String = Util.intToStringMaxRadix(0)
        private val FIELD_LABEL: String = Util.intToStringMaxRadix(1)
        private val FIELD_LANGUAGE: String = Util.intToStringMaxRadix(2)
        private val FIELD_SELECTION_FLAGS: String = Util.intToStringMaxRadix(3)
        private val FIELD_ROLE_FLAGS: String = Util.intToStringMaxRadix(4)
        private val FIELD_AVERAGE_BITRATE: String = Util.intToStringMaxRadix(5)
        private val FIELD_PEAK_BITRATE: String = Util.intToStringMaxRadix(6)
        private val FIELD_CODECS: String = Util.intToStringMaxRadix(7)

        // Do not reuse this key.
        private val UNUSED_FIELD_METADATA: String = Util.intToStringMaxRadix(8)
        private val FIELD_CONTAINER_MIME_TYPE: String = Util.intToStringMaxRadix(9)
        private val FIELD_SAMPLE_MIME_TYPE: String = Util.intToStringMaxRadix(10)
        private val FIELD_MAX_INPUT_SIZE: String = Util.intToStringMaxRadix(11)
        private val FIELD_INITIALIZATION_DATA: String = Util.intToStringMaxRadix(12)
        private val FIELD_DRM_INIT_DATA: String = Util.intToStringMaxRadix(13)
        private val FIELD_SUBSAMPLE_OFFSET_US: String = Util.intToStringMaxRadix(14)
        private val FIELD_WIDTH: String = Util.intToStringMaxRadix(15)
        private val FIELD_HEIGHT: String = Util.intToStringMaxRadix(16)
        private val FIELD_FRAME_RATE: String = Util.intToStringMaxRadix(17)
        private val FIELD_ROTATION_DEGREES: String = Util.intToStringMaxRadix(18)
        private val FIELD_PIXEL_WIDTH_HEIGHT_RATIO: String = Util.intToStringMaxRadix(19)
        private val FIELD_PROJECTION_DATA: String = Util.intToStringMaxRadix(20)
        private val FIELD_STEREO_MODE: String = Util.intToStringMaxRadix(21)
        private val FIELD_COLOR_INFO: String = Util.intToStringMaxRadix(22)
        private val FIELD_CHANNEL_COUNT: String = Util.intToStringMaxRadix(23)
        private val FIELD_SAMPLE_RATE: String = Util.intToStringMaxRadix(24)
        private val FIELD_PCM_ENCODING: String = Util.intToStringMaxRadix(25)
        private val FIELD_ENCODER_DELAY: String = Util.intToStringMaxRadix(26)
        private val FIELD_ENCODER_PADDING: String = Util.intToStringMaxRadix(27)
        private val FIELD_ACCESSIBILITY_CHANNEL: String = Util.intToStringMaxRadix(28)
        private val FIELD_CRYPTO_TYPE: String = Util.intToStringMaxRadix(29)
        private val FIELD_TILE_COUNT_HORIZONTAL: String = Util.intToStringMaxRadix(30)
        private val FIELD_TILE_COUNT_VERTICAL: String = Util.intToStringMaxRadix(31)
        private val FIELD_LABELS: String = Util.intToStringMaxRadix(32)
        private val FIELD_AUXILIARY_TRACK_TYPE: String = Util.intToStringMaxRadix(33)
        private val FIELD_MAX_SUB_LAYERS: String = Util.intToStringMaxRadix(34)
        private val FIELD_DECODED_WIDTH: String = Util.intToStringMaxRadix(35)
        private val FIELD_DECODED_HEIGHT: String = Util.intToStringMaxRadix(36)
        private val FIELD_PRIMARY_TRACK_GROUP_ID: String = Util.intToStringMaxRadix(37)
        private val FIELD_CHANNEL_MASK: String = Util.intToStringMaxRadix(38)
        private val FIELD_MIRROR_HORIZONTAL: String = Util.intToStringMaxRadix(39)
        private val FIELD_PIXEL_FORMAT: String = Util.intToStringMaxRadix(40)
        private val FIELD_SELECTION_PRIORITY: String = Util.intToStringMaxRadix(41)

        fun fromBundle(bundle: Bundle): Format {
            val builder = Builder()
            BundleCollectionUtil.ensureClassLoader(bundle)
            builder
                .setId(defaultIfNull<String?>(bundle.getString(FIELD_ID), DEFAULT.id))
                .setLabel(defaultIfNull<String?>(bundle.getString(FIELD_LABEL), DEFAULT.label))
            val labelsBundles: MutableList<Bundle>? = bundle.getParcelableArrayList<Bundle>(
                FIELD_LABELS
            )
            val labels: List<Label> =
                if (labelsBundles == null)
                    listOf()
                else
                    BundleCollectionUtil.fromBundleList<Label>(Function { bundle: Bundle ->
                        Label.fromBundle(bundle)
                    }, labelsBundles)
            builder
                .setLabels(labels)
                .setLanguage(
                    defaultIfNull<String?>(
                        bundle.getString(FIELD_LANGUAGE),
                        DEFAULT.language
                    )
                )
                .setSelectionFlags(bundle.getInt(FIELD_SELECTION_FLAGS, DEFAULT.selectionFlags))
                .setRoleFlags(bundle.getInt(FIELD_ROLE_FLAGS, DEFAULT.roleFlags))
                .setSelectionPriority(
                    bundle.getFloat(
                        FIELD_SELECTION_PRIORITY,
                        DEFAULT.selectionPriority
                    )
                )
                .setAuxiliaryTrackType(
                    bundle.getInt(FIELD_AUXILIARY_TRACK_TYPE, DEFAULT.auxiliaryTrackType)
                )
                .setAverageBitrate(bundle.getInt(FIELD_AVERAGE_BITRATE, DEFAULT.averageBitrate))
                .setPeakBitrate(bundle.getInt(FIELD_PEAK_BITRATE, DEFAULT.peakBitrate))
                .setCodecs(defaultIfNull<String?>(bundle.getString(FIELD_CODECS), DEFAULT.codecs))
                .setPrimaryTrackGroupId(
                    defaultIfNull<String?>(
                        bundle.getString(FIELD_PRIMARY_TRACK_GROUP_ID), DEFAULT.primaryTrackGroupId
                    )
                ) // Container specific.
                .setContainerMimeType(
                    defaultIfNull<String?>(
                        bundle.getString(FIELD_CONTAINER_MIME_TYPE),
                        DEFAULT.containerMimeType
                    )
                ) // Sample specific.
                .setSampleMimeType(
                    defaultIfNull<String?>(
                        bundle.getString(FIELD_SAMPLE_MIME_TYPE),
                        DEFAULT.sampleMimeType
                    )
                )
                .setMaxInputSize(bundle.getInt(FIELD_MAX_INPUT_SIZE, DEFAULT.maxInputSize))

            val initializationData: MutableList<ByteArray> = mutableListOf()
            var i = 0
            while (true) {
                val data = bundle.getByteArray(keyForInitializationData(i))
                if (data == null) {
                    break
                }
                initializationData.add(data)
                i++
            }
            var frameRate = bundle.getFloat(FIELD_FRAME_RATE, DEFAULT.frameRate)
            if (frameRate <= 0f) {
                frameRate = NO_VALUE.toFloat()
            }
            builder
                .setInitializationData(initializationData)
                .setDrmInitData(bundle.getParcelable(FIELD_DRM_INIT_DATA))
                .setSubsampleOffsetUs(
                    bundle.getLong(
                        FIELD_SUBSAMPLE_OFFSET_US,
                        DEFAULT.subsampleOffsetUs
                    )
                ) // Video specific.
                .setWidth(bundle.getInt(FIELD_WIDTH, DEFAULT.width))
                .setHeight(bundle.getInt(FIELD_HEIGHT, DEFAULT.height))
                .setPixelFormat(bundle.getInt(FIELD_PIXEL_FORMAT, DEFAULT.pixelFormat))
                .setDecodedWidth(bundle.getInt(FIELD_DECODED_WIDTH, DEFAULT.decodedWidth))
                .setDecodedHeight(bundle.getInt(FIELD_DECODED_HEIGHT, DEFAULT.decodedHeight))
                .setFrameRate(frameRate)
                .setRotationDegrees(bundle.getInt(FIELD_ROTATION_DEGREES, DEFAULT.rotationDegrees))
                .setMirrorHorizontal(
                    bundle.getBoolean(
                        FIELD_MIRROR_HORIZONTAL,
                        DEFAULT.mirrorHorizontal
                    )
                )
                .setPixelWidthHeightRatio(
                    bundle.getFloat(FIELD_PIXEL_WIDTH_HEIGHT_RATIO, DEFAULT.pixelWidthHeightRatio)
                )
                .setProjectionData(bundle.getByteArray(FIELD_PROJECTION_DATA))
                .setStereoMode(bundle.getInt(FIELD_STEREO_MODE, DEFAULT.stereoMode))
                .setMaxSubLayers(bundle.getInt(FIELD_MAX_SUB_LAYERS, DEFAULT.maxSubLayers))
            val colorInfoBundle = bundle.getBundle(FIELD_COLOR_INFO)
            if (colorInfoBundle != null) {
                builder.setColorInfo(ColorInfo.fromBundle(colorInfoBundle))
            }
            // Audio specific.
            builder
                .setChannelCount(bundle.getInt(FIELD_CHANNEL_COUNT, DEFAULT.channelCount))
                .setChannelMask(bundle.getInt(FIELD_CHANNEL_MASK, DEFAULT.channelMask))
                .setSampleRate(bundle.getInt(FIELD_SAMPLE_RATE, DEFAULT.sampleRate))
                .setPcmEncoding(bundle.getInt(FIELD_PCM_ENCODING, DEFAULT.pcmEncoding))
                .setEncoderDelay(bundle.getInt(FIELD_ENCODER_DELAY, DEFAULT.encoderDelay))
                .setEncoderPadding(
                    bundle.getInt(
                        FIELD_ENCODER_PADDING,
                        DEFAULT.encoderPadding
                    )
                ) // Text specific.
                .setAccessibilityChannel(
                    bundle.getInt(FIELD_ACCESSIBILITY_CHANNEL, DEFAULT.accessibilityChannel)
                ) // Image specific.
                .setTileCountHorizontal(
                    bundle.getInt(FIELD_TILE_COUNT_HORIZONTAL, DEFAULT.tileCountHorizontal)
                )
                .setTileCountVertical(
                    bundle.getInt(
                        FIELD_TILE_COUNT_VERTICAL,
                        DEFAULT.tileCountVertical
                    )
                ) // Source specific.
                .setCryptoType(bundle.getInt(FIELD_CRYPTO_TYPE, DEFAULT.cryptoType))

            return builder.build()
        }

        private fun keyForInitializationData(initialisationDataIndex: Int): String {
            return (FIELD_INITIALIZATION_DATA
                    + "_"
                    + initialisationDataIndex.toString(Character.MAX_RADIX.coerceIn(2, 36)))
        }

        private fun <T> defaultIfNull(value: T?, defaultValue: T?): T? {
            return value ?: defaultValue
        }

        private fun getDefaultLabel(labels: List<Label>, language: String?): String? {
            for (l in labels) {
                if (TextUtils.equals(l.language, language)) {
                    return l.value
                }
            }
            return labels[0].value
        }
    }
}