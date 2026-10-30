
package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.C.RoleFlags
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util
import androidx.media3.extractor.AacUtil.buildAudioSpecificConfig
import androidx.media3.extractor.AacUtil.parseAudioSpecificConfig
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import kotlin.math.min

class AdtsReader(
    exposeId3: Boolean,
    language: String?,
    @RoleFlags roleFlags: @RoleFlags Int,
    containerMimeType: String?
) : ElementaryStreamReader {
    private val exposeId3: Boolean
    private val adtsScratch: ParsableBitArray
    private val id3HeaderBuffer: ParsableByteArray
    private val language: String?

    @RoleFlags
    private val roleFlags: @RoleFlags Int
    private val containerMimeType: String?

    private var formatId:String? = null
    private var output:TrackOutput? = null
    private var id3Output:TrackOutput? = null

    private var state = 0
    private var bytesRead = 0

    private var matchState = 0

    private var hasCrc = false
    private var foundFirstFrame = false

    // Used to verifies sync words
    private var firstFrameVersion: Int
    private var firstFrameSampleRateIndex: Int

    private var currentFrameVersion = 0

    // Used when parsing the header.
    private var hasOutputFormat = false

    var sampleDurationUs: Long
        private set
    private var sampleSize = 0

    // Used when reading the samples.
    private var timeUs: Long

    private var currentOutput:TrackOutput? = null
    private var currentSampleDuration: Long = 0

    constructor(exposeId3: Boolean, containerMimeType: String?) : this(
        exposeId3,
        null,  
        0,
        containerMimeType
    )

    init {
        adtsScratch = ParsableBitArray(ByteArray(HEADER_SIZE + CRC_SIZE))
        id3HeaderBuffer = ParsableByteArray(ID3_IDENTIFIER.copyOf(ID3_HEADER_SIZE))
        firstFrameVersion = VERSION_UNSET
        firstFrameSampleRateIndex = C.INDEX_UNSET
        sampleDurationUs = C.TIME_UNSET
        timeUs = C.TIME_UNSET
        this.exposeId3 = exposeId3
        this.language = language
        this.roleFlags = roleFlags
        this.containerMimeType = containerMimeType
        setFindingSampleState()
    }

    override fun seek() {
        timeUs = C.TIME_UNSET
        resetSync()
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.getFormatId()
        output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO)
        currentOutput = output
        if (exposeId3) {
            idGenerator.generateNewId()
            id3Output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_METADATA)
            id3Output!!.format(
                Format.Builder()
                    .setId(idGenerator.getFormatId())
                    .setContainerMimeType(containerMimeType)
                    .setSampleMimeType(MimeTypes.APPLICATION_ID3)
                    .build()
            )
        } else {
            id3Output = DiscardingTrackOutput()
        }
    }

    override fun packetStarted(pesTimeUs: Long, flags: @TsPayloadReader.Flags Int) {
        timeUs = pesTimeUs
    }

    @Throws(ParserException::class)
    override fun consume(data: ParsableByteArray) {
        assertTracksCreated()
        while (data.bytesLeft() > 0) {
            when (state) {
                STATE_FINDING_SAMPLE -> findNextSample(data)
                STATE_READING_ID3_HEADER -> if (continueRead(
                        data,
                        id3HeaderBuffer.data,
                        ID3_HEADER_SIZE
                    )
                ) {
                    parseId3Header()
                }

                STATE_CHECKING_ADTS_HEADER -> checkAdtsHeader(data)
                STATE_READING_ADTS_HEADER -> {
                    val targetLength: Int = if (hasCrc) HEADER_SIZE + CRC_SIZE else HEADER_SIZE
                    if (continueRead(data, adtsScratch.data, targetLength)) {
                        parseAdtsHeader()
                    }
                }

                STATE_READING_SAMPLE -> readSample(data)
                else -> throw IllegalStateException()
            }
        }
    }

    private fun resetSync() {
        foundFirstFrame = false
        setFindingSampleState()
    }

    private fun continueRead(
        source: ParsableByteArray,
        target: ByteArray,
        targetLength: Int
    ): Boolean {
        val bytesToRead = min(source.bytesLeft(), targetLength - bytesRead)
        source.readBytes(target, bytesRead, bytesToRead)
        bytesRead += bytesToRead
        return bytesRead == targetLength
    }

    private fun setFindingSampleState() {
        state = STATE_FINDING_SAMPLE
        bytesRead = 0
        matchState = MATCH_STATE_START
    }

    private fun setReadingId3HeaderState() {
        state = STATE_READING_ID3_HEADER
        bytesRead = ID3_IDENTIFIER.size
        sampleSize = 0
        id3HeaderBuffer.setPosition(0)
    }

    private fun setReadingSampleState(
        outputToUse: TrackOutput?, currentSampleDuration: Long, priorReadBytes: Int, sampleSize: Int
    ) {
        state = STATE_READING_SAMPLE
        bytesRead = priorReadBytes
        this.currentOutput = outputToUse
        this.currentSampleDuration = currentSampleDuration
        this.sampleSize = sampleSize
    }

    private fun setReadingAdtsHeaderState() {
        state = STATE_READING_ADTS_HEADER
        bytesRead = 0
    }

    private fun setCheckingAdtsHeaderState() {
        state = STATE_CHECKING_ADTS_HEADER
        bytesRead = 0
    }

    private fun findNextSample(pesBuffer: ParsableByteArray) {
        val adtsData = pesBuffer.data
        var position = pesBuffer.getPosition()
        val endOffset = pesBuffer.limit()
        while (position < endOffset) {
            val data = adtsData[position++].toInt() and 0xFF
            if (matchState == MATCH_STATE_FF && isAdtsSyncBytes(0xFF.toByte(), data.toByte())) {
                if (foundFirstFrame
                    || checkSyncPositionValid(pesBuffer,  position - 2)
                ) {
                    currentFrameVersion = (data and 0x8) shr 3
                    hasCrc = (data and 0x1) == 0
                    if (!foundFirstFrame) {
                        setCheckingAdtsHeaderState()
                    } else {
                        setReadingAdtsHeaderState()
                    }
                    pesBuffer.setPosition(position)
                    return
                }
            }

            when (matchState or data) {
                MATCH_STATE_START or 0xFF -> matchState = MATCH_STATE_FF
                MATCH_STATE_START or 'I'.code -> matchState = MATCH_STATE_I
                MATCH_STATE_I or 'D'.code -> matchState = MATCH_STATE_ID
                MATCH_STATE_ID or '3'.code -> {
                    setReadingId3HeaderState()
                    pesBuffer.setPosition(position)
                    return
                }

                else -> if (matchState != MATCH_STATE_START) {
                    // If matching fails in a later state, revert to MATCH_STATE_START and
                    // check this byte again
                    matchState = MATCH_STATE_START
                    position--
                }
            }
        }
        pesBuffer.setPosition(position)
    }

    private fun checkAdtsHeader(buffer: ParsableByteArray) {
        if (buffer.bytesLeft() == 0) {
            // Not enough data to check yet, defer this check.
            return
        }
        // Peek the next byte of buffer into scratch array.
        adtsScratch.data[0] = buffer.data[buffer.getPosition()]

        adtsScratch.position=(2)
        val currentFrameSampleRateIndex = adtsScratch.readBits(4)
        if (firstFrameSampleRateIndex != C.INDEX_UNSET
            && currentFrameSampleRateIndex != firstFrameSampleRateIndex
        ) {
            // Invalid header.
            resetSync()
            return
        }

        if (!foundFirstFrame) {
            foundFirstFrame = true
            firstFrameVersion = currentFrameVersion
            firstFrameSampleRateIndex = currentFrameSampleRateIndex
        }
        setReadingAdtsHeaderState()
    }

    private fun checkSyncPositionValid(
        pesBuffer: ParsableByteArray,
        syncPositionCandidate: Int
    ): Boolean {
        pesBuffer.setPosition(syncPositionCandidate + 1)
        if (!tryRead(pesBuffer, adtsScratch.data, 1)) {
            return false
        }

        // The MPEG version of this frame must match the previously detected version.
        adtsScratch.position=(4)
        val currentFrameVersion = adtsScratch.readBits(1)
        if (firstFrameVersion != VERSION_UNSET && currentFrameVersion != firstFrameVersion) {
            return false
        }

        // The sample rate index of this frame must match the previously detected sample rate index.
        if (firstFrameSampleRateIndex != C.INDEX_UNSET) {
            if (!tryRead(pesBuffer, adtsScratch.data, 1)) {
                // Insufficient data for further checks.
                return true
            }
            adtsScratch.position=(2)
            val currentFrameSampleRateIndex = adtsScratch.readBits(4)
            if (currentFrameSampleRateIndex != firstFrameSampleRateIndex) {
                return false
            }
            pesBuffer.setPosition(syncPositionCandidate + 2)
        }

        // The frame size must be at least 7 bytes.
        if (!tryRead(pesBuffer, adtsScratch.data, 4)) {
            // Insufficient data for further checks.
            return true
        }
        adtsScratch.position=(14)
        val frameSize = adtsScratch.readBits(13)
        if (frameSize < 7) {
            return false
        }

        // The bytes following the frame must be either another SYNC word with the same MPEG version, or
        // the start of an ID3 header.
        val data = pesBuffer.data
        val dataLimit = pesBuffer.limit()
        val nextSyncPosition = syncPositionCandidate + frameSize
        if (nextSyncPosition >= dataLimit) {
            // Insufficient data for further checks.
            return true
        }
        if (data[nextSyncPosition] == 0xFF.toByte()) {
            if (nextSyncPosition + 1 == dataLimit) {
                // Insufficient data for further checks.
                return true
            }
            return isAdtsSyncBytes(0xFF.toByte(), data[nextSyncPosition + 1])
                    && ((data[nextSyncPosition + 1].toInt() and 0x8) shr 3) == currentFrameVersion
        } else {
            if (data[nextSyncPosition] != 'I'.code.toByte()) {
                return false
            }
            if (nextSyncPosition + 1 == dataLimit) {
                // Insufficient data for further checks.
                return true
            }
            if (data[nextSyncPosition + 1] != 'D'.code.toByte()) {
                return false
            }
            if (nextSyncPosition + 2 == dataLimit) {
                // Insufficient data for further checks.
                return true
            }
            return data[nextSyncPosition + 2] == '3'.code.toByte()
        }
    }

    private fun isAdtsSyncBytes(firstByte: Byte, secondByte: Byte): Boolean {
        val syncWord = (firstByte.toInt() and 0xFF) shl 8 or (secondByte.toInt() and 0xFF)
        return isAdtsSyncWord(syncWord)
    }

    private fun tryRead(source: ParsableByteArray, target: ByteArray, targetLength: Int): Boolean {
        if (source.bytesLeft() < targetLength) {
            return false
        }
        source.readBytes(target,  0, targetLength)
        return true
    }

    @RequiresNonNull("id3Output")
    private fun parseId3Header() {
        id3Output!!.sampleData(id3HeaderBuffer, ID3_HEADER_SIZE)
        id3HeaderBuffer.setPosition(ID3_SIZE_OFFSET)
        setReadingSampleState(
            id3Output, 0, ID3_HEADER_SIZE, id3HeaderBuffer.readSynchSafeInt() + ID3_HEADER_SIZE
        )
    }

    @RequiresNonNull("output")
    @Throws(ParserException::class)
    private fun parseAdtsHeader() {
        adtsScratch.position=(0)

        if (!hasOutputFormat) {
            var audioObjectType = adtsScratch.readBits(2) + 1
            if (audioObjectType != 2) {
                // The stream indicates AAC-Main (1), AAC-SSR (3) or AAC-LTP (4). When the stream indicates
                // AAC-Main it's more likely that the stream contains HE-AAC (5), which cannot be
                // represented correctly in the 2 bit audio_object_type field in the ADTS header. In
                // practice when the stream indicates AAC-SSR or AAC-LTP it more commonly contains AAC-LC or
                // HE-AAC. Since most Android devices don't support AAC-Main, AAC-SSR or AAC-LTP, and since
                // indicating AAC-LC works for HE-AAC streams, we pretend that we're dealing with AAC-LC and
                // hope for the best. In practice this often works.
                // See: https://github.com/google/ExoPlayer/issues/774
                // See: https://github.com/google/ExoPlayer/issues/1383
                w(TAG, "Detected audio object type: " + audioObjectType + ", but assuming AAC LC.")
                audioObjectType = 2
            }

            adtsScratch.skipBits(5)
            val channelConfig = adtsScratch.readBits(3)

            val audioSpecificConfig =
                buildAudioSpecificConfig(
                    audioObjectType, firstFrameSampleRateIndex, channelConfig
                )
            val aacConfig = parseAudioSpecificConfig(audioSpecificConfig)
            val format =
                Format.Builder()
                    .setId(formatId)
                    .setContainerMimeType(containerMimeType)
                    .setSampleMimeType(MimeTypes.AUDIO_AAC)
                    .setCodecs(aacConfig.codecs)
                    .setChannelCount(aacConfig.channelCount)
                    .setSampleRate(aacConfig.sampleRateHz)
                    .setInitializationData(mutableListOf<ByteArray>(audioSpecificConfig))
                    .setLanguage(language)
                    .setRoleFlags(roleFlags)
                    .build()
            // In this class a sample is an access unit, but the MediaFormat sample rate specifies the
            // number of PCM audio samples per second.
            sampleDurationUs = (C.MICROS_PER_SECOND * 1024) / format.sampleRate
            output!!.format(format)
            hasOutputFormat = true
        } else {
            adtsScratch.skipBits(10)
        }

        adtsScratch.skipBits(4)
        var sampleSize: Int = adtsScratch.readBits(13) - 2  - HEADER_SIZE
        if (hasCrc) {
            sampleSize -= CRC_SIZE
        }

        setReadingSampleState(output, sampleDurationUs, 0, sampleSize)
    }

    @RequiresNonNull("currentOutput")
    private fun readSample(data: ParsableByteArray) {
        val bytesToRead = min(data.bytesLeft(), sampleSize - bytesRead)
        currentOutput!!.sampleData(data, bytesToRead)
        bytesRead += bytesToRead
        if (bytesRead == sampleSize) {
            // packetStarted method must be called before reading samples.
            Preconditions.checkState(timeUs != C.TIME_UNSET)
            currentOutput!!.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, sampleSize, 0, null)
            timeUs += currentSampleDuration
            setFindingSampleState()
        }
    }

    private fun assertTracksCreated() {
        output!!
        currentOutput!!
        id3Output!!
    }

    companion object {
        private const val TAG = "AdtsReader"

        private const val STATE_FINDING_SAMPLE = 0
        private const val STATE_CHECKING_ADTS_HEADER = 1
        private const val STATE_READING_ID3_HEADER = 2
        private const val STATE_READING_ADTS_HEADER = 3
        private const val STATE_READING_SAMPLE = 4

        private const val HEADER_SIZE = 5
        private const val CRC_SIZE = 2

        // Match states used while looking for the next sample
        private const val MATCH_STATE_VALUE_SHIFT = 8
        private val MATCH_STATE_START = 1 shl MATCH_STATE_VALUE_SHIFT
        private val MATCH_STATE_FF = 2 shl MATCH_STATE_VALUE_SHIFT
        private val MATCH_STATE_I = 3 shl MATCH_STATE_VALUE_SHIFT
        private val MATCH_STATE_ID = 4 shl MATCH_STATE_VALUE_SHIFT

        private const val ID3_HEADER_SIZE = 10
        private const val ID3_SIZE_OFFSET = 6
        private val ID3_IDENTIFIER =
            byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())
        private val VERSION_UNSET = -1

        fun isAdtsSyncWord(candidateSyncWord: Int): Boolean {
            return (candidateSyncWord and 0xFFF6) == 0xFFF0
        }
    }
}
