
package androidx.media3.extractor.ogg

import androidx.annotation.VisibleForTesting
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.VorbisUtil
import androidx.media3.container.VorbisUtil.CommentHeader
import androidx.media3.container.VorbisUtil.VorbisIdHeader
import androidx.media3.container.VorbisUtil.getPacketBlockSize
import androidx.media3.container.VorbisUtil.getSampleCountInPacket
import androidx.media3.container.VorbisUtil.iLog
import androidx.media3.container.VorbisUtil.readVorbisCommentHeader
import androidx.media3.container.VorbisUtil.readVorbisIdentificationHeader
import androidx.media3.container.VorbisUtil.readVorbisModes
import androidx.media3.container.VorbisUtil.verifyVorbisHeaderCapturePattern
import androidx.media3.extractor.VorbisUtil.parseVorbisComments
import java.io.IOException

internal class VorbisReader : StreamReader() {
    private var vorbisSetup: VorbisSetup? = null
    private var previousPacketBlockSize = 0
    private var seenFirstAudioPacket = false

    private var vorbisIdHeader: VorbisIdHeader? = null
    private var commentHeader: CommentHeader? = null

    override fun reset(headerData: Boolean) {
        super.reset(headerData)
        if (headerData) {
            vorbisSetup = null
            vorbisIdHeader = null
            commentHeader = null
        }
        previousPacketBlockSize = 0
        seenFirstAudioPacket = false
    }

    override fun onSeekEnd(currentGranule: Long) {
        super.onSeekEnd(currentGranule)
        seenFirstAudioPacket = currentGranule != 0L
        previousPacketBlockSize = if (vorbisIdHeader != null) vorbisIdHeader!!.blockSize0 else 0
    }

    override fun preparePayload(packet: ParsableByteArray): Long {
        // if this is not an audio packet...
        if ((packet.data[0].toInt() and 0x01) == 1) {
            return -1
        }

        // ... we need to decode the block size
        val vorbisSetup: VorbisSetup = this.vorbisSetup!!
        val packetBlockSize =
            getPacketBlockSize(packet.data[0], vorbisSetup.idHeader, vorbisSetup.modes)
        val samplesInPacket =
            getSampleCountInPacket(
                packetBlockSize, previousPacketBlockSize, seenFirstAudioPacket
            )
        // codec expects the number of samples appended to audio data
        appendNumberOfSamples(packet, samplesInPacket.toLong())

        // update state in members for next iteration
        seenFirstAudioPacket = true
        previousPacketBlockSize = packetBlockSize
        return samplesInPacket.toLong()
    }

    @Throws(IOException::class)
    override fun readHeaders(
        packet: ParsableByteArray,
        position: Long,
        setupData: SetupData
    ): Boolean {
        if (vorbisSetup != null) {
            setupData.format!!
            return false
        }

        vorbisSetup = readSetupHeaders(packet)
        if (vorbisSetup == null) {
            return true
        }
        val vorbisSetup = this.vorbisSetup

        val idHeader = vorbisSetup!!.idHeader

        val codecInitializationData = ArrayList<ByteArray>()
        codecInitializationData.add(idHeader.data)
        codecInitializationData.add(vorbisSetup.setupHeaderData)

        val metadata =
            parseVorbisComments(
                vorbisSetup.commentHeader.comments.toList()
            )

        setupData.format =
            Format.Builder()
                .setContainerMimeType(MimeTypes.AUDIO_OGG)
                .setSampleMimeType(MimeTypes.AUDIO_VORBIS)
                .setAverageBitrate(idHeader.bitrateNominal)
                .setPeakBitrate(idHeader.bitrateMaximum)
                .setChannelCount(idHeader.channels)
                .setSampleRate(idHeader.sampleRate)
                .setInitializationData(codecInitializationData)
                .setMetadata(metadata)
                .build()
        return true
    }

    @VisibleForTesting
    @Throws(IOException::class)
    fun readSetupHeaders(scratch: ParsableByteArray): VorbisSetup? {
        if (vorbisIdHeader == null) {
            vorbisIdHeader = readVorbisIdentificationHeader(scratch)
            return null
        }

        if (commentHeader == null) {
            commentHeader = readVorbisCommentHeader(scratch)
            return null
        }
        val vorbisIdHeader = this.vorbisIdHeader
        val commentHeader = this.commentHeader

        // the third packet contains the setup header
        val setupHeaderData = ByteArray(scratch.limit())
        // raw data of vorbis setup header has to be passed to decoder as CSD buffer #2
        System.arraycopy(scratch.data, 0, setupHeaderData, 0, scratch.limit())
        // partially decode setup header to get the modes
        val modes = readVorbisModes(scratch, vorbisIdHeader!!.channels)
        // we need the ilog of modes all the time when extracting, so we compute it once
        val iLogModes = iLog(modes.size - 1)

        return VorbisSetup(vorbisIdHeader, commentHeader!!, setupHeaderData, modes, iLogModes)
    }

    internal class VorbisSetup(
        val idHeader: VorbisIdHeader,
        val commentHeader: CommentHeader,
        val setupHeaderData: ByteArray,
        val modes: Array<VorbisUtil.Mode?>,
        val iLogModes: Int
    )

    companion object {
        fun verifyBitstreamType(data: ParsableByteArray): Boolean {
            try {
                return verifyVorbisHeaderCapturePattern( 0x01, data, true)
            } catch (e: ParserException) {
                return false
            }
        }

        @VisibleForTesting  fun appendNumberOfSamples(
            buffer: ParsableByteArray, packetSampleCount: Long
        ) {
            if (buffer.capacity() < buffer.limit() + 4) {
                buffer.reset(buffer.data.copyOf(buffer.limit() + 4))
            } else {
                buffer.setLimit(buffer.limit() + 4)
            }
            // The vorbis decoder expects the number of samples in the packet
            // to be appended to the audio data as an int32
            val data = buffer.data
            data[buffer.limit() - 4] = (packetSampleCount and 0xFFL).toByte()
            data[buffer.limit() - 3] = ((packetSampleCount ushr 8) and 0xFFL).toByte()
            data[buffer.limit() - 2] = ((packetSampleCount ushr 16) and 0xFFL).toByte()
            data[buffer.limit() - 1] = ((packetSampleCount ushr 24) and 0xFFL).toByte()
        }
    }
}
