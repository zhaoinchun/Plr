
package androidx.media3.extractor.ts

import android.util.SparseArray
import androidx.annotation.IntDef
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil.buildCea708InitializationData
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.ts.TsPayloadReader.EsInfo

class DefaultTsPayloadReaderFactory(
    private val flags: @Flags Int = 0,
    private val closedCaptionFormats: List<Format> = mutableListOf()
) : TsPayloadReader.Factory {
  
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true,
        value = [FLAG_ALLOW_NON_IDR_KEYFRAMES, FLAG_IGNORE_AAC_STREAM, FLAG_IGNORE_H264_STREAM, FLAG_DETECT_ACCESS_UNITS, FLAG_IGNORE_SPLICE_INFO_STREAM, FLAG_OVERRIDE_CAPTION_DESCRIPTORS, FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS]
    )
    annotation class Flags

    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> {
        return SparseArray<TsPayloadReader>()
    }

    override fun createPayloadReader(streamType: Int, esInfo: EsInfo): TsPayloadReader? {
        when (streamType) {
            TsExtractor.TS_STREAM_TYPE_MPA, TsExtractor.TS_STREAM_TYPE_MPA_LSF -> return PesReader(
                MpegAudioReader(esInfo.language, esInfo.roleFlags, MimeTypes.VIDEO_MP2T)
            )

            TsExtractor.TS_STREAM_TYPE_AAC_ADTS -> return if (isSet(FLAG_IGNORE_AAC_STREAM))
                null
            else
                PesReader(
                    AdtsReader(
                        false, esInfo.language, esInfo.roleFlags, MimeTypes.VIDEO_MP2T
                    )
                )

            TsExtractor.TS_STREAM_TYPE_AAC_LATM -> return if (isSet(FLAG_IGNORE_AAC_STREAM))
                null
            else
                PesReader(
                    LatmReader(esInfo.language, esInfo.roleFlags, MimeTypes.VIDEO_MP2T)
                )

            TsExtractor.TS_STREAM_TYPE_AC3, TsExtractor.TS_STREAM_TYPE_E_AC3 -> return PesReader(
                Ac3Reader(esInfo.language, esInfo.roleFlags, MimeTypes.VIDEO_MP2T)
            )

            TsExtractor.TS_STREAM_TYPE_AC4 -> return PesReader(
                Ac4Reader(esInfo.language, esInfo.roleFlags, MimeTypes.VIDEO_MP2T)
            )

            TsExtractor.TS_STREAM_TYPE_HDMV_DTS -> {
                if (!isSet(FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)) {
                    return null
                }
                return PesReader(
                    DtsReader(
                        esInfo.language,
                        esInfo.roleFlags,
                        DtsReader.EXTSS_HEADER_SIZE_MAX,
                        MimeTypes.VIDEO_MP2T
                    )
                )
            }

            TsExtractor.TS_STREAM_TYPE_DTS, TsExtractor.TS_STREAM_TYPE_DTS_HD -> return PesReader(
                DtsReader(
                    esInfo.language,
                    esInfo.roleFlags,
                    DtsReader.EXTSS_HEADER_SIZE_MAX,
                    MimeTypes.VIDEO_MP2T
                )
            )

            TsExtractor.TS_STREAM_TYPE_DTS_UHD -> return PesReader(
                DtsReader(
                    esInfo.language,
                    esInfo.roleFlags,
                    DtsReader.FTOC_MAX_HEADER_SIZE,
                    MimeTypes.VIDEO_MP2T
                )
            )

            TsExtractor.TS_STREAM_TYPE_H262, TsExtractor.TS_STREAM_TYPE_DC2_H262 -> return PesReader(
                H262Reader(buildUserDataReader(esInfo), MimeTypes.VIDEO_MP2T)
            )

            TsExtractor.TS_STREAM_TYPE_H263 -> return PesReader(
                H263Reader(
                    buildUserDataReader(esInfo),
                    MimeTypes.VIDEO_MP2T
                )
            )

            TsExtractor.TS_STREAM_TYPE_H264 -> return if (isSet(FLAG_IGNORE_H264_STREAM))
                null
            else
                PesReader(
                    H264Reader(
                        buildSeiReader(esInfo),
                        isSet(FLAG_ALLOW_NON_IDR_KEYFRAMES),
                        isSet(FLAG_DETECT_ACCESS_UNITS),
                        MimeTypes.VIDEO_MP2T
                    )
                )

            TsExtractor.TS_STREAM_TYPE_H265 -> return PesReader(
                H265Reader(
                    buildSeiReader(
                        esInfo
                    ), MimeTypes.VIDEO_MP2T
                )
            )

            TsExtractor.TS_STREAM_TYPE_SPLICE_INFO -> return if (isSet(
                    FLAG_IGNORE_SPLICE_INFO_STREAM
                )
            )
                null
            else
                SectionReader(
                    PassthroughSectionPayloadReader( 
                        MimeTypes.APPLICATION_SCTE35,  
                        MimeTypes.VIDEO_MP2T
                    )
                )

            TsExtractor.TS_STREAM_TYPE_ID3 -> return PesReader(Id3Reader(MimeTypes.VIDEO_MP2T))
            TsExtractor.TS_STREAM_TYPE_DVBSUBS -> return PesReader(
                DvbSubtitleReader(
                    esInfo.dvbSubtitleInfos,
                    MimeTypes.VIDEO_MP2T
                )
            )

            TsExtractor.TS_STREAM_TYPE_AIT -> return SectionReader(
                PassthroughSectionPayloadReader( 
                    MimeTypes.APPLICATION_AIT,  
                    MimeTypes.VIDEO_MP2T
                )
            )

            TsExtractor.TS_STREAM_TYPE_MHAS -> return PesReader(MpeghReader(MimeTypes.VIDEO_MP2T))
            else -> return null
        }
    }
    private fun buildSeiReader(esInfo: EsInfo): SeiReader {
        return SeiReader(getClosedCaptionFormats(esInfo), MimeTypes.VIDEO_MP2T)
    }

    private fun buildUserDataReader(esInfo: EsInfo): UserDataReader {
        return UserDataReader(getClosedCaptionFormats(esInfo), MimeTypes.VIDEO_MP2T)
    }

    private fun getClosedCaptionFormats(esInfo: EsInfo): List<Format> {
        if (isSet(FLAG_OVERRIDE_CAPTION_DESCRIPTORS)) {
            return closedCaptionFormats
        }
        val scratchDescriptorData = ParsableByteArray(esInfo.descriptorBytes)
        var closedCaptionFormats = this.closedCaptionFormats
        while (scratchDescriptorData.bytesLeft() > 0) {
            val descriptorTag = scratchDescriptorData.readUnsignedByte()
            val descriptorLength = scratchDescriptorData.readUnsignedByte()
            val nextDescriptorPosition = scratchDescriptorData.getPosition() + descriptorLength
            if (descriptorTag == DESCRIPTOR_TAG_CAPTION_SERVICE) {
                // Note: see ATSC A/65 for detailed information about the caption service descriptor.
                closedCaptionFormats = ArrayList<Format>()
                val numberOfServices = scratchDescriptorData.readUnsignedByte() and 0x1F
                for (i in 0..<numberOfServices) {
                    val language = scratchDescriptorData.readString(3)
                    val captionTypeByte = scratchDescriptorData.readUnsignedByte()
                    val isDigital = (captionTypeByte and 0x80) != 0
                    val mimeType: String?
                    val accessibilityChannel: Int
                    if (isDigital) {
                        mimeType = MimeTypes.APPLICATION_CEA708
                        accessibilityChannel = captionTypeByte and 0x3F
                    } else {
                        mimeType = MimeTypes.APPLICATION_CEA608
                        accessibilityChannel = 1
                    }

                    // easy_reader(1), wide_aspect_ratio(1), reserved(6).
                    val flags = scratchDescriptorData.readUnsignedByte().toByte()
                    // Skip reserved (8).
                    scratchDescriptorData.skipBytes(1)

                    var initializationData: MutableList<ByteArray>? = null
                    // The wide_aspect_ratio flag only has meaning for CEA-708.
                    if (isDigital) {
                        val isWideAspectRatio = (flags.toInt() and 0x40) != 0
                        initializationData =
                            buildCea708InitializationData(isWideAspectRatio)
                    }

                    closedCaptionFormats.add(
                        Format.Builder()
                            .setSampleMimeType(mimeType)
                            .setLanguage(language)
                            .setAccessibilityChannel(accessibilityChannel)
                            .setInitializationData(initializationData)
                            .build()
                    )
                }
            } else {
                // Unknown descriptor. Ignore.
            }
            scratchDescriptorData.setPosition(nextDescriptorPosition)
        }

        return closedCaptionFormats
    }

    private fun isSet(flag: @Flags Int): Boolean {
        return (flags and flag) != 0
    }

    companion object {
        const val FLAG_ALLOW_NON_IDR_KEYFRAMES: Int = 1
        const  val FLAG_IGNORE_AAC_STREAM: Int = 1 shl 1
        const  val FLAG_IGNORE_H264_STREAM: Int = 1 shl 2
        const  val FLAG_DETECT_ACCESS_UNITS: Int = 1 shl 3
        const  val FLAG_IGNORE_SPLICE_INFO_STREAM: Int = 1 shl 4
        const  val FLAG_OVERRIDE_CAPTION_DESCRIPTORS: Int = 1 shl 5
        const  val FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS: Int = 1 shl 6

        private const val DESCRIPTOR_TAG_CAPTION_SERVICE = 0x86
    }
}
