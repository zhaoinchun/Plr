package androidx.media3.common

import android.os.Bundle
import androidx.media3.common.C.ColorRange
import androidx.media3.common.C.ColorTransfer
import androidx.media3.common.util.Util
import org.checkerframework.dataflow.qual.Pure

class ColorInfo private constructor(
    val colorSpace: @C.ColorSpace Int,
    val colorRange: @ColorRange Int,
    val colorTransfer: @ColorTransfer Int,
    val hdrStaticInfo: ByteArray?,
    val lumaBitdepth: Int,
    val chromaBitdepth: Int
) {
    // Lazily initialized hashcode.
    private var hashCode = 0

    fun buildUpon(): Builder {
        return ColorInfo.Builder(this)
    }

    val isValid: Boolean
        get() = this.isBitdepthValid || this.isDataSpaceValid

    val isBitdepthValid: Boolean
        get() = lumaBitdepth != Format.NO_VALUE && chromaBitdepth != Format.NO_VALUE

    val isDataSpaceValid: Boolean
        get() = colorSpace != Format.NO_VALUE && colorRange != Format.NO_VALUE && colorTransfer != Format.NO_VALUE

    fun toLogString(): String {
        val dataspaceString =
            if (this.isDataSpaceValid)
                Util.formatInvariant(
                    "%s/%s/%s",
                    colorSpaceToString(colorSpace),
                    colorRangeToString(colorRange),
                    colorTransferToString(colorTransfer)
                )
            else
                "NA/NA/NA"
        val bitdepthsString =
            if (this.isBitdepthValid) lumaBitdepth.toString() + "/" + chromaBitdepth else "NA/NA"
        return "$dataspaceString/$bitdepthsString"
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj == null || javaClass != obj.javaClass) {
            return false
        }
        val other = obj as ColorInfo
        return colorSpace == other.colorSpace && colorRange == other.colorRange && colorTransfer == other.colorTransfer && hdrStaticInfo.contentEquals(
            other.hdrStaticInfo
        ) && lumaBitdepth == other.lumaBitdepth && chromaBitdepth == other.chromaBitdepth
    }

    override fun hashCode(): Int {
        if (hashCode == 0) {
            var result = 17
            result = 31 * result + colorSpace
            result = 31 * result + colorRange
            result = 31 * result + colorTransfer
            result = 31 * result + hdrStaticInfo.contentHashCode()
            result = 31 * result + lumaBitdepth
            result = 31 * result + chromaBitdepth
            hashCode = result
        }
        return hashCode
    }

    override fun toString(): String {
        return ("ColorInfo("
                + colorSpaceToString(colorSpace)
                + ", "
                + colorRangeToString(colorRange)
                + ", "
                + colorTransferToString(colorTransfer)
                + ", "
                + (hdrStaticInfo != null)
                + ", "
                + lumaBitdepthToString(lumaBitdepth)
                + ", "
                + chromaBitdepthToString(chromaBitdepth)
                + ")")
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putInt(FIELD_COLOR_SPACE, colorSpace)
        bundle.putInt(FIELD_COLOR_RANGE, colorRange)
        bundle.putInt(FIELD_COLOR_TRANSFER, colorTransfer)
        bundle.putByteArray(FIELD_HDR_STATIC_INFO, hdrStaticInfo)
        bundle.putInt(FIELD_LUMA_BITDEPTH, lumaBitdepth)
        bundle.putInt(FIELD_CHROMA_BITDEPTH, chromaBitdepth)
        return bundle
    }

    class Builder {
        private var colorSpace: @C.ColorSpace Int
        private var colorRange: @ColorRange Int
        private var colorTransfer: @ColorTransfer Int
        private var hdrStaticInfo: ByteArray? = null
        private var lumaBitdepth: Int
        private var chromaBitdepth: Int

        constructor() {
            colorSpace = Format.NO_VALUE
            colorRange = Format.NO_VALUE
            colorTransfer = Format.NO_VALUE
            lumaBitdepth = Format.NO_VALUE
            chromaBitdepth = Format.NO_VALUE
        }

        constructor(colorInfo: ColorInfo) {
            this.colorSpace = colorInfo.colorSpace
            this.colorRange = colorInfo.colorRange
            this.colorTransfer = colorInfo.colorTransfer
            this.hdrStaticInfo = colorInfo.hdrStaticInfo
            this.lumaBitdepth = colorInfo.lumaBitdepth
            this.chromaBitdepth = colorInfo.chromaBitdepth
        }

        fun setColorSpace(colorSpace: @C.ColorSpace Int): Builder {
            this.colorSpace = colorSpace
            return this
        }

        fun setColorRange(colorRange: @ColorRange Int): Builder {
            this.colorRange = colorRange
            return this
        }

        fun setColorTransfer(colorTransfer: @ColorTransfer Int): Builder {
            this.colorTransfer = colorTransfer
            return this
        }

        fun setHdrStaticInfo(hdrStaticInfo: ByteArray?): Builder {
            this.hdrStaticInfo = hdrStaticInfo
            return this
        }

        fun setLumaBitdepth(lumaBitdepth: Int): Builder {
            this.lumaBitdepth = lumaBitdepth
            return this
        }

        fun setChromaBitdepth(chromaBitdepth: Int): Builder {
            this.chromaBitdepth = chromaBitdepth
            return this
        }

        fun build(): ColorInfo {
            return ColorInfo(
                colorSpace, colorRange, colorTransfer, hdrStaticInfo, lumaBitdepth, chromaBitdepth
            )
        }
    }

    companion object {
        val SDR_BT709_LIMITED: ColorInfo = Builder()
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .setColorTransfer(C.COLOR_TRANSFER_SDR)
            .build()

        val SRGB_BT709_FULL: ColorInfo = Builder()
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorRange(C.COLOR_RANGE_FULL)
            .setColorTransfer(C.COLOR_TRANSFER_SRGB)
            .build()

        fun isEquivalentToAssumedSdrDefault(colorInfo: ColorInfo?): Boolean {
            if (colorInfo == null) {
                return true
            }
            return (colorInfo.colorSpace == Format.NO_VALUE || colorInfo.colorSpace == C.COLOR_SPACE_BT709 || colorInfo.colorSpace == C.COLOR_SPACE_BT601)
                    && (colorInfo.colorRange == Format.NO_VALUE
                    || colorInfo.colorRange == C.COLOR_RANGE_LIMITED)
                    && (colorInfo.colorTransfer == Format.NO_VALUE
                    || colorInfo.colorTransfer == C.COLOR_TRANSFER_SDR)
                    && colorInfo.hdrStaticInfo == null && (colorInfo.chromaBitdepth == Format.NO_VALUE || colorInfo.chromaBitdepth == 8)
                    && (colorInfo.lumaBitdepth == Format.NO_VALUE || colorInfo.lumaBitdepth == 8)
        }

        @Pure
        fun isoColorPrimariesToColorSpace(isoColorPrimaries: Int): @C.ColorSpace Int {
            when (isoColorPrimaries) {
                1 -> return C.COLOR_SPACE_BT709
                4, 5, 6, 7 -> return C.COLOR_SPACE_BT601
                9 -> return C.COLOR_SPACE_BT2020
                else ->         // Remaining color primaries are either reserved or unspecified.
                    return Format.NO_VALUE
            }
        }

        fun colorSpaceToIsoColorPrimaries(colorSpace: @C.ColorSpace Int): Int {
            when (colorSpace) {
                Format.NO_VALUE, C.COLOR_SPACE_BT709 -> return 1
                C.COLOR_SPACE_BT601 -> return 5
                C.COLOR_SPACE_BT2020 -> return 9
            }
            return 1
        }

        fun colorSpaceToIsoMatrixCoefficients(colorSpace: @C.ColorSpace Int): Int {
            when (colorSpace) {
                Format.NO_VALUE, C.COLOR_SPACE_BT709 -> return 1
                C.COLOR_SPACE_BT601 -> return 6
                C.COLOR_SPACE_BT2020 -> return 9
            }
            return 1
        }

        @Pure
        fun isoTransferCharacteristicsToColorTransfer(
            isoTransferCharacteristics: Int
        ): @ColorTransfer Int {
            when (isoTransferCharacteristics) {
                1, 6, 7 -> return C.COLOR_TRANSFER_SDR
                4 -> return C.COLOR_TRANSFER_GAMMA_2_2
                13 -> return C.COLOR_TRANSFER_SRGB
                16 -> return C.COLOR_TRANSFER_ST2084
                18 -> return C.COLOR_TRANSFER_HLG
                else -> return Format.NO_VALUE
            }
        }

        fun colorTransferToIsoTransferCharacteristics(colorTransfer: @ColorTransfer Int): Int {
            when (colorTransfer) {
                C.COLOR_TRANSFER_LINEAR -> return 8
                C.COLOR_TRANSFER_SRGB -> return 13
                Format.NO_VALUE, C.COLOR_TRANSFER_SDR -> return 1
                C.COLOR_TRANSFER_ST2084 -> return 16
                C.COLOR_TRANSFER_HLG -> return 18
                C.COLOR_TRANSFER_GAMMA_2_2 -> return 4
            }
            return 1
        }

        fun isTransferHdr(colorInfo: ColorInfo?): Boolean {
            return colorInfo != null
                    && (colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG
                    || colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084)
        }

        private fun lumaBitdepthToString(`val`: Int): String {
            return if (`val` != Format.NO_VALUE) `val`.toString() + "bit Luma" else "NA"
        }

        private fun chromaBitdepthToString(`val`: Int): String {
            return if (`val` != Format.NO_VALUE) `val`.toString() + "bit Chroma" else "NA"
        }

        private fun colorSpaceToString(colorSpace: @C.ColorSpace Int): String {
            // LINT.IfChange(color_space)
            when (colorSpace) {
                Format.NO_VALUE -> return "Unset color space"
                C.COLOR_SPACE_BT601 -> return "BT601"
                C.COLOR_SPACE_BT709 -> return "BT709"
                C.COLOR_SPACE_BT2020 -> return "BT2020"
                else -> return "Undefined color space $colorSpace"
            }
            // LINT.ThenChange(C.java:color_space)
        }

        private fun colorTransferToString(colorTransfer: @ColorTransfer Int): String {
            when (colorTransfer) {
                Format.NO_VALUE -> return "Unset color transfer"
                C.COLOR_TRANSFER_LINEAR -> return "Linear"
                C.COLOR_TRANSFER_SDR -> return "SDR SMPTE 170M"
                C.COLOR_TRANSFER_SRGB -> return "sRGB"
                C.COLOR_TRANSFER_GAMMA_2_2 -> return "Gamma 2.2"
                C.COLOR_TRANSFER_ST2084 -> return "ST2084 PQ"
                C.COLOR_TRANSFER_HLG -> return "HLG"
                else -> return "Undefined color transfer $colorTransfer"
            }
        }

        private fun colorRangeToString(colorRange: @ColorRange Int): String {
            when (colorRange) {
                Format.NO_VALUE -> return "Unset color range"
                C.COLOR_RANGE_LIMITED -> return "Limited range"
                C.COLOR_RANGE_FULL -> return "Full range"
                else -> return "Undefined color range $colorRange"
            }
        }

        private val FIELD_COLOR_SPACE: String = Util.intToStringMaxRadix(0)
        private val FIELD_COLOR_RANGE: String = Util.intToStringMaxRadix(1)
        private val FIELD_COLOR_TRANSFER: String = Util.intToStringMaxRadix(2)
        private val FIELD_HDR_STATIC_INFO: String = Util.intToStringMaxRadix(3)
        private val FIELD_LUMA_BITDEPTH: String = Util.intToStringMaxRadix(4)
        private val FIELD_CHROMA_BITDEPTH: String = Util.intToStringMaxRadix(5)

        fun fromBundle(bundle: Bundle): ColorInfo {
            return ColorInfo(
                bundle.getInt(FIELD_COLOR_SPACE, Format.NO_VALUE),
                bundle.getInt(FIELD_COLOR_RANGE, Format.NO_VALUE),
                bundle.getInt(FIELD_COLOR_TRANSFER, Format.NO_VALUE),
                bundle.getByteArray(FIELD_HDR_STATIC_INFO),
                bundle.getInt(FIELD_LUMA_BITDEPTH, Format.NO_VALUE),
                bundle.getInt(FIELD_CHROMA_BITDEPTH, Format.NO_VALUE)
            )
        }
    }
}