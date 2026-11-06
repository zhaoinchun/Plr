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

data class Format(
    val id: String? = null,
    var label: String? = null,
    var labels: List<Label> = listOf(),
    var language: String? = null,
    var selectionFlags: @SelectionFlags Int = 0,
    var roleFlags: @RoleFlags Int = 0,
    var selectionPriority: Float = NO_VALUE.toFloat(),
    var auxiliaryTrackType: @AuxiliaryTrackType Int = C.AUXILIARY_TRACK_TYPE_UNDEFINED,
    val codecs: String? = null,
    val metadata: Metadata? = null,
    var customData: Any? = null,
    var primaryTrackGroupId: String? = null,
    val containerMimeType: String? = null,

    // Sample specific.
    val sampleMimeType: String? = null,
    var maxInputSize: Int = NO_VALUE,
    var maxNumReorderSamples: Int = NO_VALUE,
    val initializationData: List<ByteArray> = listOf(),
    val drmInitData: DrmInitData? = null,
    var subsampleOffsetUs: Long = OFFSET_SAMPLE_RELATIVE,
    var hasPrerollSamples: Boolean = false,

    // Video specific.
    var width: Int = NO_VALUE,
    var height: Int = NO_VALUE,
    var pixelFormat: Int = NO_VALUE,
    var decodedWidth: Int = NO_VALUE,
    var decodedHeight: Int = NO_VALUE,
    var frameRate: Float = NO_VALUE.toFloat(),
    var rotationDegrees: Int = 0,
    var mirrorHorizontal: Boolean = false,
    var pixelWidthHeightRatio: Float = 1.0f,
    var projectionData: ByteArray? = null,
    var stereoMode: @StereoMode Int = NO_VALUE,
    val colorInfo: ColorInfo? = null,
    var maxSubLayers: Int = NO_VALUE,

    // Audio specific.
    var channelCount: Int = NO_VALUE,
    var channelMask: Int = NO_VALUE,
    var sampleRate: Int = NO_VALUE,
    var pcmEncoding: @PcmEncoding Int = NO_VALUE,
    var encoderDelay: Int = 0,
    var encoderPadding: Int = 0,

    // Text specific.
    var accessibilityChannel: Int = NO_VALUE,
    var cueReplacementBehavior: @CueReplacementBehavior Int =
        CUE_REPLACEMENT_BEHAVIOR_MERGE,

    // Image specific
    var tileCountHorizontal: Int = NO_VALUE,
    var tileCountVertical: Int = NO_VALUE,

    // Provided by the source.
    var cryptoType: @CryptoType Int = C.CRYPTO_TYPE_NONE,
    var peakBitrate: Int = NO_VALUE,
    var averageBitrate: Int = NO_VALUE,
) {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [CUE_REPLACEMENT_BEHAVIOR_MERGE, CUE_REPLACEMENT_BEHAVIOR_REPLACE]
    )
    annotation class CueReplacementBehavior

    val bitrate: Int = if (peakBitrate != NO_VALUE) peakBitrate else averageBitrate
    private var hashCode = 0

    init {
        this.language = language?.let { Util.normalizeLanguageCode(it) }
        if (labels.isEmpty() && label != null) {
            this.labels = listOf(Label(this.language, label!!))
            this.label = label
        } else if (!labels.isEmpty() && label == null) {
            this.labels = labels
            this.label = getDefaultLabel(labels, this.language)
        } else {
            Preconditions.checkState(isLabelPartOfLabels(label, labels))
            this.labels = labels
            this.label = label
        }

        // Provided by source.
        if (cryptoType == C.CRYPTO_TYPE_NONE && drmInitData != null) {
            // Encrypted content cannot use CRYPTO_TYPE_NONE.
            this.cryptoType = C.CRYPTO_TYPE_UNSUPPORTED
        } else {
            this.cryptoType = cryptoType
        }
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
            this.metadata?.copyWithAppendedEntriesFrom(manifestFormat.metadata)
                ?: manifestFormat.metadata

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

        return this.copy(
            id = id,
            label = label,
            labels = labels,
            language = language,
            selectionFlags = selectionFlags,
            roleFlags = roleFlags,
            selectionPriority = selectionPriority,
            averageBitrate = averageBitrate,
            peakBitrate = peakBitrate,
            codecs = codecs,
            metadata = metadata,
            primaryTrackGroupId = primaryTrackGroupId,
            drmInitData = drmInitData,
            frameRate = frameRate,
            tileCountHorizontal = tileCountHorizontal,
            tileCountVertical = tileCountVertical
        )
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
        val initializationData = initializationData!!
        if (initializationData.size != other.initializationData!!.size) {
            return false
        }
        for (i in initializationData.indices) {
            if (!initializationData[i].contentEquals(other.initializationData!![i])) {
                return false
            }
        }
        return true
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
        for (i in initializationData!!.indices) {
            bundle.putByteArray(keyForInitializationData(i), initializationData!![i])
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
            bundle.putBundle(FIELD_COLOR_INFO, colorInfo!!.toBundle())
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
        private val DEFAULT = Format()
        private fun isLabelPartOfLabels(
            label: String? = null,
            labels: List<Label> = listOf()
        ): Boolean {
            if (labels.isEmpty() && label == null) {
                return true
            }
            for (i in labels.indices) {
                if (labels[i].value == label) {
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
                for (i in 0..<format.drmInitData!!.schemeDataCount) {
                    val schemeUuid = format.drmInitData!!.get(i)?.uuid
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
            if (format.colorInfo != null && format.colorInfo!!.isValid) {
                builder.append(", color=").append(format.colorInfo!!.toLogString())
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
            BundleCollectionUtil.ensureClassLoader(bundle)
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
            val initializationData: MutableList<ByteArray> = mutableListOf()
            var i = 0
            while (true) {
                val data = bundle.getByteArray(keyForInitializationData(i)) ?: break
                initializationData.add(data)
                i++
            }
            var frameRate = bundle.getFloat(FIELD_FRAME_RATE, DEFAULT.frameRate)
            if (frameRate <= 0f) {
                frameRate = NO_VALUE.toFloat()
            }

            return Format(
                id = bundle.getString(FIELD_ID) ?: DEFAULT.id,
                label = bundle.getString(FIELD_LABEL) ?: DEFAULT.label,
                labels = labels,
                language = defaultIfNull<String?>(
                    bundle.getString(FIELD_LANGUAGE),
                    DEFAULT.language
                ),
                selectionFlags = bundle.getInt(FIELD_SELECTION_FLAGS, DEFAULT.selectionFlags),
                roleFlags = bundle.getInt(FIELD_ROLE_FLAGS, DEFAULT.roleFlags),
                selectionPriority = bundle.getFloat(
                    FIELD_SELECTION_PRIORITY,
                    DEFAULT.selectionPriority
                ),
                auxiliaryTrackType = bundle.getInt(
                    FIELD_AUXILIARY_TRACK_TYPE,
                    DEFAULT.auxiliaryTrackType
                ),
                averageBitrate = bundle.getInt(FIELD_AVERAGE_BITRATE, DEFAULT.averageBitrate),
                peakBitrate = bundle.getInt(FIELD_PEAK_BITRATE, DEFAULT.peakBitrate),
                codecs = bundle.getString(FIELD_CODECS) ?: DEFAULT.codecs,
                primaryTrackGroupId = defaultIfNull<String?>(
                    bundle.getString(FIELD_PRIMARY_TRACK_GROUP_ID), DEFAULT.primaryTrackGroupId
                ),
                containerMimeType = bundle.getString(FIELD_CONTAINER_MIME_TYPE)
                    ?: DEFAULT.containerMimeType,
                sampleMimeType = bundle.getString(FIELD_SAMPLE_MIME_TYPE) ?: DEFAULT.sampleMimeType,
                maxInputSize = bundle.getInt(FIELD_MAX_INPUT_SIZE, DEFAULT.maxInputSize),
                initializationData = initializationData,
                drmInitData = bundle.getParcelable(FIELD_DRM_INIT_DATA),
                subsampleOffsetUs = bundle.getLong(
                    FIELD_SUBSAMPLE_OFFSET_US,
                    DEFAULT.subsampleOffsetUs
                ),
                width = bundle.getInt(FIELD_WIDTH, DEFAULT.width),
                height = bundle.getInt(FIELD_HEIGHT, DEFAULT.height),
                pixelFormat = bundle.getInt(FIELD_PIXEL_FORMAT, DEFAULT.pixelFormat),
                decodedWidth = bundle.getInt(FIELD_DECODED_WIDTH, DEFAULT.decodedWidth),
                decodedHeight = bundle.getInt(FIELD_DECODED_HEIGHT, DEFAULT.decodedHeight),
                frameRate = frameRate,
                rotationDegrees = bundle.getInt(FIELD_ROTATION_DEGREES, DEFAULT.rotationDegrees),
                mirrorHorizontal = bundle.getBoolean(
                    FIELD_MIRROR_HORIZONTAL,
                    DEFAULT.mirrorHorizontal
                ),
                pixelWidthHeightRatio = bundle.getFloat(
                    FIELD_PIXEL_WIDTH_HEIGHT_RATIO,
                    DEFAULT.pixelWidthHeightRatio
                ),
                projectionData = bundle.getByteArray(FIELD_PROJECTION_DATA),
                stereoMode = bundle.getInt(FIELD_STEREO_MODE, DEFAULT.stereoMode),
                maxSubLayers = bundle.getInt(FIELD_MAX_SUB_LAYERS, DEFAULT.maxSubLayers),
                colorInfo = bundle.getBundle(FIELD_COLOR_INFO)?.let { ColorInfo.fromBundle(it) },
                channelCount = bundle.getInt(FIELD_CHANNEL_COUNT, DEFAULT.channelCount),
                channelMask = bundle.getInt(FIELD_CHANNEL_MASK, DEFAULT.channelMask),
                sampleRate = bundle.getInt(FIELD_SAMPLE_RATE, DEFAULT.sampleRate),
                pcmEncoding = bundle.getInt(FIELD_PCM_ENCODING, DEFAULT.pcmEncoding),
                encoderDelay = bundle.getInt(FIELD_ENCODER_DELAY, DEFAULT.encoderDelay),
                encoderPadding = bundle.getInt(FIELD_ENCODER_PADDING, DEFAULT.encoderPadding),
                accessibilityChannel = bundle.getInt(
                    FIELD_ACCESSIBILITY_CHANNEL,
                    DEFAULT.accessibilityChannel
                ),
                tileCountHorizontal = bundle.getInt(
                    FIELD_TILE_COUNT_HORIZONTAL,
                    DEFAULT.tileCountHorizontal
                ),
                tileCountVertical = bundle.getInt(
                    FIELD_TILE_COUNT_VERTICAL,
                    DEFAULT.tileCountVertical
                ),
                cryptoType = bundle.getInt(FIELD_CRYPTO_TYPE, DEFAULT.cryptoType),
            )
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