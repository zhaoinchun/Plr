package androidx.media3.extractor

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util.getBigEndianInt
import java.nio.ByteBuffer

object Ac3Util {
    val AC3_MAX_RATE_BYTES_PER_SECOND: Int = 640 * 1000 / 8
    val E_AC3_MAX_RATE_BYTES_PER_SECOND: Int = 6144 * 1000 / 8
    val TRUEHD_MAX_RATE_BYTES_PER_SECOND: Int = 24500 * 1000 / 8
    const val TRUEHD_RECHUNK_SAMPLE_COUNT: Int = 16
    const val TRUEHD_SYNCFRAME_PREFIX_LENGTH: Int = 10
    private const val AUDIO_SAMPLES_PER_AUDIO_BLOCK = 256
    private val AC3_SYNCFRAME_AUDIO_SAMPLE_COUNT = 6 * AUDIO_SAMPLES_PER_AUDIO_BLOCK
    private val BLOCKS_PER_SYNCFRAME_BY_NUMBLKSCOD = intArrayOf(1, 2, 3, 6)
    private val SAMPLE_RATE_BY_FSCOD = intArrayOf(48000, 44100, 32000)
    private val SAMPLE_RATE_BY_FSCOD2 = intArrayOf(24000, 22050, 16000)
    private val CHANNEL_COUNT_BY_ACMOD = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)
    private val BITRATE_BY_HALF_FRMSIZECOD = intArrayOf(
        32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640
    )
    private val SYNCFRAME_SIZE_WORDS_BY_HALF_FRMSIZECOD_44_1 = intArrayOf(
        69, 87, 104, 121, 139, 174, 208, 243, 278, 348, 417, 487, 557, 696, 835, 975, 1114, 1253,
        1393
    )

    fun parseAc3AnnexFFormat(
        data: ParsableByteArray,
        trackId: String?,
        language: String?,
        drmInitData: DrmInitData?
    ): Format {
        val dataBitArray = ParsableBitArray()
        dataBitArray.reset(data)

        val fscod = dataBitArray.readBits(2)
        val sampleRate = SAMPLE_RATE_BY_FSCOD[fscod]
        dataBitArray.skipBits(8) // bsid, bsmod
        var channelCount = CHANNEL_COUNT_BY_ACMOD[dataBitArray.readBits(3)] // acmod
        if (dataBitArray.readBits(1) != 0) { // lfeon
            channelCount++
        }
        val halfFrmsizecod = dataBitArray.readBits(5) // bit_rate_code
        val constantBitrate = BITRATE_BY_HALF_FRMSIZECOD[halfFrmsizecod] * 1000
        // Update data position
        dataBitArray.byteAlign()
        data.setPosition(dataBitArray.bytePosition)
        return Format.Builder()
            .setId(trackId)
            .setSampleMimeType(MimeTypes.AUDIO_AC3)
            .setChannelCount(channelCount)
            .setSampleRate(sampleRate)
            .setDrmInitData(drmInitData)
            .setLanguage(language)
            .setAverageBitrate(constantBitrate)
            .setPeakBitrate(constantBitrate)
            .build()
    }

        fun parseEAc3AnnexFFormat(
        data: ParsableByteArray,
        trackId: String?,
        language: String?,
        drmInitData: DrmInitData?
    ): Format {
        val dataBitArray = ParsableBitArray()
        dataBitArray.reset(data)

        val peakBitrate = dataBitArray.readBits(13) * 1000 // data_rate
        dataBitArray.skipBits(3) // num_ind_sub

        // Read the first independent substream.
        val fscod = dataBitArray.readBits(2)
        val sampleRate = SAMPLE_RATE_BY_FSCOD[fscod]
        dataBitArray.skipBits(10) // bsid, reserved, asvc, bsmod
        var channelCount = CHANNEL_COUNT_BY_ACMOD[dataBitArray.readBits(3)] // acmod
        if (dataBitArray.readBits(1) != 0) { // lfeon
            channelCount++
        }

        // Read the first dependent substream.
        dataBitArray.skipBits(3) // reserved
        val numDepSub = dataBitArray.readBits(4) // num_dep_sub
        dataBitArray.skipBits(1) // numDepSub > 0 ? LFE2 : reserved
        if (numDepSub > 0) {
            dataBitArray.skipBits(6) // other channel configurations
            // Read Lrs/Rrs pair
            // TODO: Read other channel configuration
            if (dataBitArray.readBits(1) != 0) {
                channelCount += 2
            }
            dataBitArray.skipBits(1) // Lc/Rc pair
        }

        var mimeType = MimeTypes.AUDIO_E_AC3
        if (dataBitArray.bitsLeft() > 7) {
            dataBitArray.skipBits(7) // reserved
            if (dataBitArray.readBits(1) != 0) { // flag_ec3_extension_type_a
                mimeType = MimeTypes.AUDIO_E_AC3_JOC
            }
        }
        // Update data position
        dataBitArray.byteAlign()
        data.setPosition(dataBitArray.bytePosition)
        return Format.Builder()
            .setId(trackId)
            .setSampleMimeType(mimeType)
            .setChannelCount(channelCount)
            .setSampleRate(sampleRate)
            .setDrmInitData(drmInitData)
            .setLanguage(language)
            .setPeakBitrate(peakBitrate)
            .build()
    }

    fun parseAc3SyncframeInfo(data: ParsableBitArray): SyncFrameInfo {
        val initialPosition = data.position
        data.skipBits(40)
        // Parse the bitstream ID for AC-3 and E-AC-3 (see subsections 4.3, E.1.2 and E.1.3.1.6).
        val isEac3 = data.readBits(5) > 10
        data.position=(initialPosition)
        var mimeType: String?
        var streamType: @SyncFrameInfo.StreamType Int =
            SyncFrameInfo.STREAM_TYPE_UNDEFINED
        val sampleRate: Int
        val acmod: Int
        val frameSize: Int
        val sampleCount: Int
        val lfeon: Boolean
        val channelCount: Int
        val bitrate: Int
        if (isEac3) {
            // Subsection E.1.2.
            data.skipBits(16) // syncword
            when (data.readBits(2)) {
                0 -> streamType = SyncFrameInfo.STREAM_TYPE_TYPE0
                1 -> streamType = SyncFrameInfo.STREAM_TYPE_TYPE1
                2 -> streamType = SyncFrameInfo.STREAM_TYPE_TYPE2
                else -> streamType = SyncFrameInfo.STREAM_TYPE_UNDEFINED
            }
            data.skipBits(3) // substreamid
            frameSize = (data.readBits(11) + 1) * 2 // See frmsiz in subsection E.1.3.1.3.
            val fscod = data.readBits(2)
            val audioBlocks: Int
            val numblkscod: Int
            if (fscod == 3) {
                numblkscod = 3
                sampleRate = SAMPLE_RATE_BY_FSCOD2[data.readBits(2)]
                audioBlocks = 6
            } else {
                numblkscod = data.readBits(2)
                audioBlocks = BLOCKS_PER_SYNCFRAME_BY_NUMBLKSCOD[numblkscod]
                sampleRate = SAMPLE_RATE_BY_FSCOD[fscod]
            }
            sampleCount = AUDIO_SAMPLES_PER_AUDIO_BLOCK * audioBlocks
            bitrate = calculateEac3Bitrate(frameSize, sampleRate, audioBlocks)
            acmod = data.readBits(3)
            lfeon = data.readBit()
            channelCount = CHANNEL_COUNT_BY_ACMOD[acmod] + (if (lfeon) 1 else 0)
            data.skipBits(5 + 5) // bsid, dialnorm
            if (data.readBit()) { // compre
                data.skipBits(8) // compr
            }
            if (acmod == 0) {
                data.skipBits(5) // dialnorm2
                if (data.readBit()) { // compr2e
                    data.skipBits(8) // compr2
                }
            }
            if (streamType == SyncFrameInfo.STREAM_TYPE_TYPE1 && data.readBit()) { // chanmape
                data.skipBits(16) // chanmap
            }
            if (data.readBit()) { // mixmdate
                if (acmod > 2) {
                    data.skipBits(2) // dmixmod
                }
                if ((acmod and 0x01) != 0 && acmod > 2) {
                    data.skipBits(3 + 3) // ltrtcmixlev, lorocmixlev
                }
                if ((acmod and 0x04) != 0) {
                    data.skipBits(6) // ltrtsurmixlev, lorosurmixlev
                }
                if (lfeon && data.readBit()) { // lfemixlevcode
                    data.skipBits(5) // lfemixlevcod
                }
                if (streamType == SyncFrameInfo.STREAM_TYPE_TYPE0) {
                    if (data.readBit()) { // pgmscle
                        data.skipBits(6) // pgmscl
                    }
                    if (acmod == 0 && data.readBit()) { // pgmscl2e
                        data.skipBits(6) // pgmscl2
                    }
                    if (data.readBit()) { // extpgmscle
                        data.skipBits(6) // extpgmscl
                    }
                    val mixdef = data.readBits(2)
                    if (mixdef == 1) {
                        data.skipBits(1 + 1 + 3) // premixcmpsel, drcsrc, premixcmpscl
                    } else if (mixdef == 2) {
                        data.skipBits(12) // mixdata
                    } else if (mixdef == 3) {
                        val mixdeflen = data.readBits(5)
                        if (data.readBit()) { // mixdata2e
                            data.skipBits(1 + 1 + 3) // premixcmpsel, drcsrc, premixcmpscl
                            if (data.readBit()) { // extpgmlscle
                                data.skipBits(4) // extpgmlscl
                            }
                            if (data.readBit()) { // extpgmcscle
                                data.skipBits(4) // extpgmcscl
                            }
                            if (data.readBit()) { // extpgmrscle
                                data.skipBits(4) // extpgmrscl
                            }
                            if (data.readBit()) { // extpgmlsscle
                                data.skipBits(4) // extpgmlsscl
                            }
                            if (data.readBit()) { // extpgmrsscle
                                data.skipBits(4) // extpgmrsscl
                            }
                            if (data.readBit()) { // extpgmlfescle
                                data.skipBits(4) // extpgmlfescl
                            }
                            if (data.readBit()) { // dmixscle
                                data.skipBits(4) // dmixscl
                            }
                            if (data.readBit()) { // addche
                                if (data.readBit()) { // extpgmaux1scle
                                    data.skipBits(4) // extpgmaux1scl
                                }
                                if (data.readBit()) { // extpgmaux2scle
                                    data.skipBits(4) // extpgmaux2scl
                                }
                            }
                        }
                        if (data.readBit()) { // mixdata3e
                            data.skipBits(5) // spchdat
                            if (data.readBit()) { // addspchdate
                                data.skipBits(5 + 2) // spchdat1, spchan1att
                                if (data.readBit()) { // addspdat1e
                                    data.skipBits(5 + 3) // spchdat2, spchan2att
                                }
                            }
                        }
                        data.skipBits(8 * (mixdeflen + 2)) // mixdata
                        data.byteAlign() // mixdatafill
                    }
                    if (acmod < 2) {
                        if (data.readBit()) { // paninfoe
                            data.skipBits(8 + 6) // panmean, paninfo
                        }
                        if (acmod == 0) {
                            if (data.readBit()) { // paninfo2e
                                data.skipBits(8 + 6) // panmean2, paninfo2
                            }
                        }
                    }
                    if (data.readBit()) { // frmmixcfginfoe
                        if (numblkscod == 0) {
                            data.skipBits(5) // blkmixcfginfo[0]
                        } else {
                            for (blk in 0..<audioBlocks) {
                                if (data.readBit()) { // blkmixcfginfoe
                                    data.skipBits(5) // blkmixcfginfo[blk]
                                }
                            }
                        }
                    }
                }
            }
            if (data.readBit()) { // infomdate
                data.skipBits(3 + 1 + 1) // bsmod, copyrightb, origbs
                if (acmod == 2) {
                    data.skipBits(2 + 2) // dsurmod, dheadphonmod
                }
                if (acmod >= 6) {
                    data.skipBits(2) // dsurexmod
                }
                if (data.readBit()) { // audioprodie
                    data.skipBits(5 + 2 + 1) // mixlevel, roomtyp, adconvtyp
                }
                if (acmod == 0 && data.readBit()) { // audioprodi2e
                    data.skipBits(5 + 2 + 1) // mixlevel2, roomtyp2, adconvtyp2
                }
                if (fscod < 3) {
                    data.skipBit() // sourcefscod
                }
            }
            if (streamType == SyncFrameInfo.STREAM_TYPE_TYPE0 && numblkscod != 3) {
                data.skipBit() // convsync
            }
            if (streamType == SyncFrameInfo.STREAM_TYPE_TYPE2
                && (numblkscod == 3 || data.readBit())
            ) { // blkid
                data.skipBits(6) // frmsizecod
            }
            mimeType = MimeTypes.AUDIO_E_AC3
            if (data.readBit()) { // addbsie
                val addbsil = data.readBits(6)
                if (addbsil == 1 && data.readBits(8) == 1) { // addbsi
                    mimeType = MimeTypes.AUDIO_E_AC3_JOC
                }
            }
        } else   {
            mimeType = MimeTypes.AUDIO_AC3
            data.skipBits(16 + 16) // syncword, crc1
            val fscod = data.readBits(2)
            if (fscod == 3) {
                // fscod '11' indicates that the decoder should not attempt to decode audio. We invalidate
                // the MIME type to prevent association with a renderer.
                mimeType = null
            }
            val frmsizecod = data.readBits(6)
            bitrate = BITRATE_BY_HALF_FRMSIZECOD[frmsizecod / 2] * 1000
            frameSize = getAc3SyncframeSize(fscod, frmsizecod)
            data.skipBits(5 + 3) // bsid, bsmod
            acmod = data.readBits(3)
            if ((acmod and 0x01) != 0 && acmod != 1) {
                data.skipBits(2) // cmixlev
            }
            if ((acmod and 0x04) != 0) {
                data.skipBits(2) // surmixlev
            }
            if (acmod == 2) {
                data.skipBits(2) // dsurmod
            }
            sampleRate =
                if (fscod < SAMPLE_RATE_BY_FSCOD.size) SAMPLE_RATE_BY_FSCOD[fscod] else Format.NO_VALUE
            sampleCount = AC3_SYNCFRAME_AUDIO_SAMPLE_COUNT
            lfeon = data.readBit()
            channelCount = CHANNEL_COUNT_BY_ACMOD[acmod] + (if (lfeon) 1 else 0)
        }
        return Ac3Util.SyncFrameInfo(
            mimeType, streamType, channelCount, sampleRate, frameSize, sampleCount, bitrate
        )
    }

    fun parseAc3SyncframeSize(data: ByteArray): Int {
        if (data.size < 6) {
            return C.LENGTH_UNSET
        }
        // Parse the bitstream ID for AC-3 and E-AC-3 (see subsections 4.3, E.1.2 and E.1.3.1.6).
        val isEac3 = ((data[5].toInt() and 0xF8) shr 3) > 10
        if (isEac3) {
            var frmsiz = (data[2].toInt() and 0x07) shl 8 // Most significant 3 bits.
            frmsiz = frmsiz or (data[3].toInt() and 0xFF) // Least significant 8 bits.
            return (frmsiz + 1) * 2 // See frmsiz in subsection E.1.3.1.3.
        } else {
            val fscod = (data[4].toInt() and 0xC0) shr 6
            val frmsizecod = data[4].toInt() and 0x3F
            return getAc3SyncframeSize(fscod, frmsizecod)
        }
    }

    fun parseAc3SyncframeAudioSampleCount(buffer: ByteBuffer): Int {
        // Parse the bitstream ID for AC-3 and E-AC-3 (see subsections 4.3, E.1.2 and E.1.3.1.6).
        val isEac3 = ((buffer.get(buffer.position() + 5).toInt() and 0xF8) shr 3) > 10
        if (isEac3) {
            val fscod = (buffer.get(buffer.position() + 4).toInt() and 0xC0) shr 6
            val numblkscod =
                if (fscod == 0x03) 3 else (buffer.get(buffer.position() + 4).toInt() and 0x30) shr 4
            return BLOCKS_PER_SYNCFRAME_BY_NUMBLKSCOD[numblkscod] * AUDIO_SAMPLES_PER_AUDIO_BLOCK
        } else {
            return AC3_SYNCFRAME_AUDIO_SAMPLE_COUNT
        }
    }

    fun findTrueHdSyncframeOffset(buffer: ByteBuffer): Int {
        val startIndex = buffer.position()
        val endIndex = buffer.limit() - TRUEHD_SYNCFRAME_PREFIX_LENGTH
        for (i in startIndex..endIndex) {
            // The syncword ends 0xBA for TrueHD or 0xBB for MLP.
            if ((getBigEndianInt(buffer, i + 4) and -0x2) == -0x78d9046) {
                return i - startIndex
            }
        }
        return C.INDEX_UNSET
    }

    fun parseTrueHdSyncframeAudioSampleCount(syncframe: ByteArray): Int {
        // See "Dolby TrueHD (MLP) high-level bitstream description" on the Dolby developer site,
        // subsections 2.2 and 4.2.1. The syncword ends 0xBA for TrueHD or 0xBB for MLP.
        if (syncframe[4] != 0xF8.toByte() || syncframe[5] != 0x72.toByte() || syncframe[6] != 0x6F.toByte() || (syncframe[7].toInt() and 0xFE) != 0xBA) {
            return 0
        }
        val isMlp = (syncframe[7].toInt() and 0xFF) == 0xBB
        return 40 shl ((syncframe[if (isMlp) 9 else 8].toInt() shr 4) and 0x07)
    }

    fun parseTrueHdSyncframeAudioSampleCount(buffer: ByteBuffer, offset: Int): Int {
        // TODO: Link to specification if available.
        val isMlp = (buffer.get(buffer.position() + offset + 7).toInt() and 0xFF) == 0xBB
        return 40 shl ((buffer.get(buffer.position() + offset + (if (isMlp) 9 else 8))
            .toInt() shr 4) and 0x07)
    }

    private fun getAc3SyncframeSize(fscod: Int, frmsizecod: Int): Int {
        val halfFrmsizecod = frmsizecod / 2
        if (fscod < 0 || fscod >= SAMPLE_RATE_BY_FSCOD.size || frmsizecod < 0 || halfFrmsizecod >= SYNCFRAME_SIZE_WORDS_BY_HALF_FRMSIZECOD_44_1.size) {
            // Invalid values provided.
            return C.LENGTH_UNSET
        }
        val sampleRate = SAMPLE_RATE_BY_FSCOD[fscod]
        if (sampleRate == 44100) {
            return 2 * (SYNCFRAME_SIZE_WORDS_BY_HALF_FRMSIZECOD_44_1[halfFrmsizecod] + (frmsizecod % 2))
        }
        val bitrate = BITRATE_BY_HALF_FRMSIZECOD[halfFrmsizecod]
        if (sampleRate == 32000) {
            return 6 * bitrate
        } else { // sampleRate == 48000
            return 4 * bitrate
        }
    }

    private fun calculateEac3Bitrate(frameSize: Int, sampleRate: Int, audioBlocks: Int): Int {
        return (frameSize * sampleRate) / (audioBlocks * 32)
    }

    class SyncFrameInfo constructor(
        val mimeType: String?,
        val streamType: @StreamType Int,
        val channelCount: Int,
        val sampleRate: Int,
        val frameSize: Int,
        val sampleCount: Int,
        val bitrate: Int
    ) {
        @MustBeDocumented
        @Retention(AnnotationRetention.SOURCE)
        @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
        @IntDef(value = [STREAM_TYPE_UNDEFINED, STREAM_TYPE_TYPE0, STREAM_TYPE_TYPE1, STREAM_TYPE_TYPE2])
        annotation class StreamType

        companion object {
            const val STREAM_TYPE_UNDEFINED: Int = -1
            const val STREAM_TYPE_TYPE0: Int = 0
            const val STREAM_TYPE_TYPE1: Int = 1
            const val STREAM_TYPE_TYPE2: Int = 2
        }
    }
}
