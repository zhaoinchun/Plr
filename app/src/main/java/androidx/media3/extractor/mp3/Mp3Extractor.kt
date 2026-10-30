package androidx.media3.extractor.mp3

import androidx.annotation.IntDef
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.GaplessInfoHolder
import androidx.media3.extractor.Id3Peeker
import androidx.media3.extractor.MpegAudioUtil
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.MlltFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame

import org.checkerframework.checker.nullness.qual.RequiresNonNull

class Mp3Extractor @kotlin.jvm.JvmOverloads constructor(
    flags: @androidx.media3.extractor.mp3.Mp3Extractor.Flags kotlin.Int = 0,
    forcedFirstSampleTimestampUs: kotlin.Long = androidx.media3.common.C.TIME_UNSET
) : Extractor {
    @kotlin.annotation.MustBeDocumented
    @kotlin.annotation.Retention(kotlin.annotation.AnnotationRetention.SOURCE)
    @kotlin.annotation.Target(
        kotlin.annotation.AnnotationTarget.CLASS,
        kotlin.annotation.AnnotationTarget.TYPE,
        kotlin.annotation.AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        flag = true,
        value = [FLAG_ENABLE_CONSTANT_BITRATE_SEEKING, FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS, FLAG_ENABLE_INDEX_SEEKING, FLAG_DISABLE_ID3_METADATA, FLAG_DISABLE_ARTWORK_METADATA
        ]
    )
    annotation class Flags

    @kotlin.annotation.MustBeDocumented
    @kotlin.annotation.Target(
        kotlin.annotation.AnnotationTarget.CLASS,
        kotlin.annotation.AnnotationTarget.TYPE,
        kotlin.annotation.AnnotationTarget.TYPE_PARAMETER
    )
    @kotlin.annotation.Retention(kotlin.annotation.AnnotationRetention.SOURCE)
    @IntDef(value = [SEEK_HEADER_XING, SEEK_HEADER_INFO, SEEK_HEADER_VBRI, SEEK_HEADER_UNSET])
    private annotation class SeekHeader

    private val flags: @androidx.media3.extractor.mp3.Mp3Extractor.Flags kotlin.Int
    private val forcedFirstSampleTimestampUs: kotlin.Long
    private val scratch: ParsableByteArray
    private val synchronizedHeader: androidx.media3.extractor.MpegAudioUtil.Header
    private val gaplessInfoHolder: GaplessInfoHolder
    private val id3Peeker: Id3Peeker
    private val skippingTrackOutput: TrackOutput

    private lateinit var extractorOutput:ExtractorOutput
    private lateinit var realTrackOutput:TrackOutput
    private var currentTrackOutput: TrackOutput// skippingTrackOutput or realTrackOutput.

    private var synchronizedHeaderData = 0

    private var id3Metadata: androidx.media3.common.Metadata? = null
    private var xingMetadata: androidx.media3.common.Metadata? = null
    private var basisTimeUs: kotlin.Long
    private var samplesRead: kotlin.Long = 0
    private var firstSamplePosition: kotlin.Long = 0
    private var endPositionOfLastSampleRead: kotlin.Long
    private var sampleBytesRemaining = 0

    private var seeker:Seeker? = null
    private var disableSeeking = false
    private var isSeekInProgress = false
    private var seekTimeUs: kotlin.Long = 0

    init {
        var flags = flags
        if ((flags and FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS) != 0) {
            flags = flags or FLAG_ENABLE_CONSTANT_BITRATE_SEEKING
        }
        this.flags = flags
        this.forcedFirstSampleTimestampUs = forcedFirstSampleTimestampUs
        scratch = ParsableByteArray(SCRATCH_LENGTH)
        synchronizedHeader = androidx.media3.extractor.MpegAudioUtil.Header()
        gaplessInfoHolder = GaplessInfoHolder()
        basisTimeUs = androidx.media3.common.C.TIME_UNSET
        id3Peeker = Id3Peeker()
        skippingTrackOutput = DiscardingTrackOutput()
        currentTrackOutput = skippingTrackOutput
        endPositionOfLastSampleRead = androidx.media3.common.C.INDEX_UNSET.toLong()
    }

    // Extractor implementation.
    @kotlin.Throws(java.io.IOException::class)
    override fun sniff(input: ExtractorInput): kotlin.Boolean {
        return synchronize(input, true)
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        realTrackOutput = extractorOutput.track(0, androidx.media3.common.C.TRACK_TYPE_AUDIO)
        currentTrackOutput = realTrackOutput
        extractorOutput.endTracks()
    }

    override fun seek(position: kotlin.Long, timeUs: kotlin.Long) {
        synchronizedHeaderData = 0
        basisTimeUs = androidx.media3.common.C.TIME_UNSET
        samplesRead = 0
        sampleBytesRemaining = 0
        endPositionOfLastSampleRead = androidx.media3.common.C.INDEX_UNSET.toLong()
        seekTimeUs = timeUs
        if (seeker is IndexSeeker && !(seeker as IndexSeeker).isTimeUsInIndex(timeUs)) {
            isSeekInProgress = true
            currentTrackOutput = skippingTrackOutput
        }
    }

    override fun release() {
        // Do nothing
    }

    @kotlin.Throws(java.io.IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): kotlin.Int {
        assertInitialized()
        val readResult = readInternal(input)
        if (readResult == Extractor.RESULT_END_OF_INPUT && seeker is IndexSeeker) {
            // Duration is exact when index seeker is used.
            val finalSampleIndex = samplesRead - 1
            val durationUs =
                if (finalSampleIndex >= 0)
                    computeFinalIndexSeekerDurationUs(finalSampleIndex)
                else
                    androidx.media3.common.C.TIME_UNSET
            if (seeker!!.durationUs !== durationUs) {
                (seeker as IndexSeeker).durationUs = durationUs
                extractorOutput.seekMap(seeker!!)
                realTrackOutput.durationUs(seeker!!.durationUs)
            }
        }
        return readResult
    }

    fun disableSeeking() {
        disableSeeking = true
    }

    @kotlin.Throws(java.io.IOException::class)
    private fun readInternal(input: ExtractorInput): kotlin.Int {
        if (synchronizedHeaderData == 0) {
            try {
                synchronize(input, false)
            } catch (e: java.io.EOFException) {
                return Extractor.RESULT_END_OF_INPUT
            }
        }
        if (seeker == null) {
            seeker = computeSeeker(input)
            realTrackOutput.durationUs(seeker!!.durationUs)
            extractorOutput.seekMap(seeker!!)
            val metadata: androidx.media3.common.Metadata?
            if (id3Metadata != null && (flags and FLAG_DISABLE_ID3_METADATA) == 0) {
                metadata =
                    if (xingMetadata != null)
                        id3Metadata!!.copyWithAppendedEntriesFrom(xingMetadata)
                    else
                        id3Metadata
            } else {
                metadata = xingMetadata
            }
            val format =
                androidx.media3.common.Format.Builder()
                    .setContainerMimeType(MimeTypes.AUDIO_MPEG)
                    .setSampleMimeType(synchronizedHeader.mimeType)
                    .setMaxInputSize(MpegAudioUtil.MAX_FRAME_SIZE_BYTES)
                    .setChannelCount(synchronizedHeader.channels)
                    .setSampleRate(synchronizedHeader.sampleRate)
                    .setEncoderDelay(gaplessInfoHolder.encoderDelay)
                    .setEncoderPadding(gaplessInfoHolder.encoderPadding)
                    .setMetadata(metadata)
            if (seeker!!.averageBitrate != androidx.media3.common.C.RATE_UNSET_INT) {
                format.setAverageBitrate(seeker!!.averageBitrate)
            }
            currentTrackOutput.format(format.build())
            firstSamplePosition = input.position
        } else if (firstSamplePosition != 0L) {
            val inputPosition: kotlin.Long = input.position
            if (inputPosition < firstSamplePosition) {
                // Skip past the seek frame.
                input.skipFully((firstSamplePosition - inputPosition).toInt())
            }
        }
        return readSample(input)
    }

    @RequiresNonNull("realTrackOutput", "seeker")
    @kotlin.Throws(java.io.IOException::class)
    private fun readSample(extractorInput: ExtractorInput): kotlin.Int {
        if (sampleBytesRemaining == 0) {
            extractorInput.resetPeekPosition()
            if (peekEndOfStreamOrHeader(extractorInput)) {
                return Extractor.RESULT_END_OF_INPUT
            }
            scratch.setPosition(0)
            val sampleHeaderData = scratch.readInt()
            if (!headersMatch(
                    sampleHeaderData,
                    synchronizedHeaderData.toLong()
                )
                || MpegAudioUtil.getFrameSize(sampleHeaderData) == androidx.media3.common.C.LENGTH_UNSET
            ) {
                // We have lost synchronization, so attempt to resynchronize starting at the next byte.
                extractorInput.skipFully(1)
                synchronizedHeaderData = 0
                return Extractor.RESULT_CONTINUE
            }
            synchronizedHeader.setForHeaderData(sampleHeaderData)
            if (basisTimeUs == androidx.media3.common.C.TIME_UNSET) {
                basisTimeUs = seeker!!.getTimeUs(extractorInput.position)
                if (forcedFirstSampleTimestampUs != androidx.media3.common.C.TIME_UNSET) {
                    val embeddedFirstSampleTimestampUs = seeker!!.getTimeUs(0)
                    basisTimeUs += forcedFirstSampleTimestampUs - embeddedFirstSampleTimestampUs
                }
            }
            sampleBytesRemaining = synchronizedHeader.frameSize
            endPositionOfLastSampleRead = extractorInput.position + synchronizedHeader.frameSize
            if (seeker is IndexSeeker) {
                val indexSeeker = seeker as IndexSeeker
                // Add seek point corresponding to the next frame instead of the current one to be able to
                // start writing to the realTrackOutput on time when a seek is in progress.
                indexSeeker.maybeAddSeekPoint(
                    computeTimeUs(samplesRead + synchronizedHeader.samplesPerFrame),
                    endPositionOfLastSampleRead
                )
                if (isSeekInProgress && indexSeeker.isTimeUsInIndex(seekTimeUs)) {
                    isSeekInProgress = false
                    currentTrackOutput = realTrackOutput
                }
            }
        }
        val bytesAppended =
            currentTrackOutput.sampleData(extractorInput, sampleBytesRemaining, true)
        if (bytesAppended == androidx.media3.common.C.RESULT_END_OF_INPUT) {
            return Extractor.RESULT_END_OF_INPUT
        }
        sampleBytesRemaining -= bytesAppended
        if (sampleBytesRemaining > 0) {
            return Extractor.RESULT_CONTINUE
        }
        currentTrackOutput.sampleMetadata(
            computeTimeUs(samplesRead),
            androidx.media3.common.C.BUFFER_FLAG_KEY_FRAME,
            synchronizedHeader.frameSize,
            0,
            null
        )
        samplesRead += synchronizedHeader.samplesPerFrame.toLong()
        sampleBytesRemaining = 0
        return Extractor.RESULT_CONTINUE
    }

    private fun computeTimeUs(samplesRead: kotlin.Long): kotlin.Long {
        return basisTimeUs + samplesRead * androidx.media3.common.C.MICROS_PER_SECOND / synchronizedHeader.sampleRate
    }

    private fun computeFinalIndexSeekerDurationUs(finalSampleIndex: kotlin.Long): kotlin.Long {
        val finalGaplessSampleIndex =
            if (gaplessInfoHolder.hasGaplessInfo())
                finalSampleIndex - gaplessInfoHolder.encoderDelay - gaplessInfoHolder.encoderPadding
            else
                finalSampleIndex
        return if (finalGaplessSampleIndex >= 0) computeTimeUs(finalGaplessSampleIndex) else androidx.media3.common.C.TIME_UNSET
    }

    @kotlin.Throws(java.io.IOException::class)
    private fun synchronize(input: ExtractorInput, sniffing: kotlin.Boolean): kotlin.Boolean {
        var validFrameCount = 0
        var candidateSynchronizedHeaderData = 0
        var peekedId3Bytes = 0
        var searchedBytes = 0
        input.resetPeekPosition()
        if (input.position == 0L) {
            // We need to parse enough ID3 metadata to retrieve any gapless/seeking playback information
            // even if ID3 metadata parsing is disabled.
            val parseAllId3Frames =
                (flags and FLAG_DISABLE_ID3_METADATA) == 0
            val ignoreArtwork =
                (flags and FLAG_DISABLE_ARTWORK_METADATA) != 0
            val id3FramePredicate: Id3Decoder.FramePredicate? =
                if (!parseAllId3Frames)
                    REQUIRED_ID3_FRAME_PREDICATE
                else
                    (if (ignoreArtwork) Id3Decoder.NO_ARTWORK_PREDICATE else null)
            id3Metadata = id3Peeker.peekId3Data(
                input,
                id3FramePredicate,
                MAX_SEARCH_BYTES
            )
            if (id3Metadata != null) {
                gaplessInfoHolder.setFromMetadata(id3Metadata!!)
            }
            peekedId3Bytes = input.peekPosition as kotlin.Int
            if (!sniffing) {
                input.skipFully(peekedId3Bytes)
            }
        }
        while (true) {
            if (peekEndOfStreamOrHeader(input)) {
                if (validFrameCount > 0) {
                    // We reached the end of the stream but found at least one valid frame.
                    break
                }
                maybeUpdateCbrDurationToLastSample()
                throw java.io.EOFException()
            }
            scratch.setPosition(0)
            val headerData = scratch.readInt()
            var frameSize: kotlin.Int = MpegAudioUtil.getFrameSize(headerData)
            if ((candidateSynchronizedHeaderData != 0
                        && !headersMatch(
                    headerData,
                    candidateSynchronizedHeaderData.toLong()
                ))
                || frameSize == androidx.media3.common.C.LENGTH_UNSET
            ) {
                // The header doesn't match the candidate header or is invalid. Try the next byte offset.
                if (searchedBytes++ == MAX_SEARCH_BYTES) {
                    if (!sniffing) {
                        maybeUpdateCbrDurationToLastSample()
                        throw java.io.EOFException()
                    }
                    return false
                }
                validFrameCount = 0
                candidateSynchronizedHeaderData = 0
                if (sniffing) {
                    input.resetPeekPosition()
                    input.advancePeekPosition(peekedId3Bytes + searchedBytes)
                } else {
                    input.skipFully(1)
                }
            } else {
                // The header matches the candidate header and/or is valid.
                validFrameCount++
                if (validFrameCount == 1) {
                    synchronizedHeader.setForHeaderData(headerData)
                    candidateSynchronizedHeaderData = headerData
                } else if (validFrameCount == 4) {
                    break
                }
                input.advancePeekPosition(frameSize - 4)
            }
        }
        // Prepare to read the synchronized frame.
        if (sniffing) {
            input.skipFully(peekedId3Bytes + searchedBytes)
        } else {
            input.resetPeekPosition()
        }
        synchronizedHeaderData = candidateSynchronizedHeaderData
        return true
    }

    @kotlin.Throws(java.io.IOException::class)
    private fun peekEndOfStreamOrHeader(extractorInput: ExtractorInput): kotlin.Boolean {
        if (seeker != null) {
            val dataEndPosition = seeker!!.dataEndPosition
            if (dataEndPosition != androidx.media3.common.C.INDEX_UNSET.toLong()
                && extractorInput.peekPosition > dataEndPosition - 4
            ) {
                return true
            }
        }
        try {
            return !extractorInput.peekFully(
                scratch.data,  0,  4,  true
            )
        } catch (e: java.io.EOFException) {
            return true
        }
    }

    @kotlin.Throws(java.io.IOException::class)
    private fun computeSeeker(input: ExtractorInput): Seeker {
        // Read past any seek frame and set the seeker based on metadata or a seek frame. Metadata
        // takes priority as it can provide greater precision.
        val seekFrameSeeker = maybeReadSeekFrame(input)
        val metadataSeeker: Seeker? =
            maybeHandleSeekMetadata(id3Metadata, input.position)

        if (disableSeeking) {
            return Seeker.UnseekableSeeker()
        }

        val resultSeeker: Seeker?
        if (metadataSeeker != null) {
            resultSeeker = metadataSeeker
        } else if (seekFrameSeeker != null) {
            resultSeeker = seekFrameSeeker
        } else {
            resultSeeker = getConstantBitrateSeeker(input)
        }

        if ((flags and FLAG_ENABLE_INDEX_SEEKING) != 0 && !resultSeeker.isSeekable) {
            return IndexSeeker(
                resultSeeker.durationUs,  
                input.position,
                resultSeeker.dataEndPosition
            )
        }

        if (resultSeeker is ConstantBitrateSeeker) {
            return resultSeeker
        }

        if (!shouldFallbackToConstantBitrateSeeking(resultSeeker)) {
            return resultSeeker
        }

        val durationUs = resultSeeker.durationUs
        val inputLength =
            if (resultSeeker.dataEndPosition != androidx.media3.common.C.INDEX_UNSET.toLong())
                resultSeeker.dataEndPosition
            else
                input.length
        if (durationUs == androidx.media3.common.C.TIME_UNSET || inputLength == androidx.media3.common.C.LENGTH_UNSET.toLong()) {
            // resultSeeker doesn't provide enough info to do 'enhanced' CBR seeking, so we just do
            // normal CBR seeking without any additional info from the file.
            return getConstantBitrateSeeker(input)
        }
        val dataStart =
            if (resultSeeker.dataStartPosition != androidx.media3.common.C.INDEX_UNSET.toLong())
                resultSeeker.dataStartPosition
            else
                0
        val averageBitrate = Mp3Util.computeAverageBitrate(inputLength - dataStart, durationUs)
        if (averageBitrate == androidx.media3.common.C.RATE_UNSET_INT) {
            // Bitrate couldn't be determined (dataStart >= inputLength?) so we just do normal CBR seeking
            return getConstantBitrateSeeker(input)
        }

        // resultSeeker provides a duration and we know the input length, and CBR seeking has been
        // requested, so we can do 'enhanced' CBR seeking using this info. inputLength will never be
        // LENGTH_UNSET because of the if-condition above, so we can
        // pass (vacuously) false here for allowSeeksIfLengthUnknown.
        return ConstantBitrateSeeker(
            inputLength,
            dataStart,
            averageBitrate,  
            androidx.media3.common.C.LENGTH_UNSET,  
            false,
            durationUs
        )
    }

    private fun shouldFallbackToConstantBitrateSeeking(seeker: Seeker): kotlin.Boolean {
        return !seeker.isSeekable && (flags and FLAG_ENABLE_CONSTANT_BITRATE_SEEKING) != 0
    }

    @kotlin.Throws(java.io.IOException::class)
    private fun maybeReadSeekFrame(input: ExtractorInput): Seeker? {
        val frame: ParsableByteArray = ParsableByteArray(synchronizedHeader.frameSize)
        input.peekFully(frame.data, 0, synchronizedHeader.frameSize)
        val xingBase =
            if ((synchronizedHeader.version and 1) != 0)
                (if (synchronizedHeader.channels != 1) 36 else 21) // MPEG 1
            else
                (if (synchronizedHeader.channels != 1) 21 else 13) // MPEG 2 or 2.5
        val seekHeader: @SeekHeader kotlin.Int =
            getSeekFrameHeader(frame, xingBase)
        val seeker: Seeker?
        when (seekHeader) {
            SEEK_HEADER_XING, SEEK_HEADER_INFO -> {
                val xingFrame: XingFrame = XingFrame.parse(synchronizedHeader, frame)
                if (!gaplessInfoHolder.hasGaplessInfo() && xingFrame.encoderDelay != androidx.media3.common.C.LENGTH_UNSET && xingFrame.encoderPadding != androidx.media3.common.C.LENGTH_UNSET) {
                    gaplessInfoHolder.encoderDelay = xingFrame.encoderDelay
                    gaplessInfoHolder.encoderPadding = xingFrame.encoderPadding
                }
                xingMetadata = xingFrame.metadata
                val startPosition: kotlin.Long = input.position
                input.skipFully(synchronizedHeader.frameSize)
                // An Xing frame indicates the file is VBR (so we have to use the seek header for seeking)
                // while an Info header indicates the file is CBR, in which case ConstantBitrateSeeker will
                // give more accurate seeking than the low-resolution seek table in the Info header. We can
                // still use the length from the Info frame if we don't know the stream length directly.
                if (seekHeader == SEEK_HEADER_XING) {
                    seeker = XingSeeker.create(xingFrame, startPosition, input.length)
                } else { // seekHeader == SEEK_HEADER_INFO
                    seeker = getConstantBitrateSeeker(startPosition, xingFrame, input.length)
                }
            }

            SEEK_HEADER_VBRI -> {
                seeker =
                    VbriSeeker.create(
                        input.length,
                        input.position,
                        synchronizedHeader,
                        frame
                    )
                input.skipFully(synchronizedHeader.frameSize)
            }

            SEEK_HEADER_UNSET -> {
                // This frame doesn't contain seeking information, so reset the peek position.
                seeker = null
                input.resetPeekPosition()
            }

            else -> {
                seeker = null
                input.resetPeekPosition()
            }
        }
        return seeker
    }

    @kotlin.Throws(java.io.IOException::class)
    private fun getConstantBitrateSeeker(input: ExtractorInput): Seeker {
        input.peekFully(scratch.data, 0, 4)
        scratch.setPosition(0)
        synchronizedHeader.setForHeaderData(scratch.readInt())
        return ConstantBitrateSeeker(
            input.length,
            input.position,
            synchronizedHeader,  
            (flags and FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS)
                    != 0
        )
    }

    private fun getConstantBitrateSeeker(
        infoFramePosition: kotlin.Long, infoFrame: XingFrame, fallbackStreamLength: kotlin.Long
    ): Seeker? {
        val durationUs = infoFrame.computeDurationUs()
        if (durationUs == androidx.media3.common.C.TIME_UNSET) {
            return null
        }
        val streamLength: kotlin.Long
        val audioLength: kotlin.Long
        // Prefer the stream length from the Info frame, because it may deliberately
        // exclude some unplayable/non-MP3 trailer data (e.g. album artwork), see
        // https://github.com/androidx/media/issues/1376#issuecomment-2117211184.
        if (infoFrame.dataSize != androidx.media3.common.C.LENGTH_UNSET.toLong()) {
            streamLength = infoFramePosition + infoFrame.dataSize
            audioLength = infoFrame.dataSize - infoFrame.header.frameSize
        } else if (fallbackStreamLength != androidx.media3.common.C.LENGTH_UNSET.toLong()) {
            streamLength = fallbackStreamLength
            audioLength = fallbackStreamLength - infoFramePosition - infoFrame.header.frameSize
        } else {
            return null
        }

        // Derive the bitrate and frame size by averaging over the length of playable audio, to allow
        // for 'mostly' CBR streams that might have a small number of frames with a different bitrate.
        // See also https://github.com/androidx/media/issues/1376.
        val averageBitrate = Mp3Util.computeAverageBitrate(audioLength, durationUs)
        if (averageBitrate == androidx.media3.common.C.RATE_UNSET_INT) {
            // Invalid Info sizes or durations should fall back to the next frame header bitrate rather
            // than constructing a ConstantBitrateSeeker with an unset bitrate.
            return null
        }
        val frameSize =
            com.google.common.primitives.Ints.checkedCast(
                com.google.common.math.LongMath.divide(
                    audioLength,
                    infoFrame.frameCount,
                    java.math.RoundingMode.HALF_UP
                )
            )
        // Set the seeker frame size to the average frame size (even though some constant bitrate
        // streams have variable frame sizes due to padding), to avoid the need to re-synchronize for
        // constant frame size streams.
        return ConstantBitrateSeeker(
            streamLength,  
            infoFramePosition + infoFrame.header.frameSize,
            averageBitrate,
            frameSize,  
            false,
            durationUs
        )
    }

    private fun maybeUpdateCbrDurationToLastSample() {
        if (seeker is ConstantBitrateSeeker
            && seeker!!.isSeekable
            && endPositionOfLastSampleRead != androidx.media3.common.C.INDEX_UNSET.toLong() && endPositionOfLastSampleRead != seeker!!.dataEndPosition
        ) {
            seeker =
                (seeker as ConstantBitrateSeeker).copyWithNewDataEndPosition(
                    endPositionOfLastSampleRead
                )
            extractorOutput!!.seekMap(seeker!!)
            realTrackOutput!!.durationUs(seeker!!.durationUs)
        }
    }

    private fun assertInitialized() {
        realTrackOutput!!
        extractorOutput!!
    }

    companion object {
        val FACTORY: ExtractorsFactory =
           object :ExtractorsFactory {
               override fun createExtractors(): Array<Extractor> {
                   return  kotlin.arrayOf(Mp3Extractor())
               }
           }

        const val FLAG_ENABLE_CONSTANT_BITRATE_SEEKING: kotlin.Int = 1

        const  val FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS: kotlin.Int = 1 shl 1

        const  val FLAG_ENABLE_INDEX_SEEKING: kotlin.Int = 1 shl 2

        const  val FLAG_DISABLE_ID3_METADATA: kotlin.Int = 1 shl 3
        const  val FLAG_DISABLE_ARTWORK_METADATA: kotlin.Int = 1 shl 4

        private const val TAG = "Mp3Extractor"

        private val REQUIRED_ID3_FRAME_PREDICATE: Id3Decoder.FramePredicate =
            object :Id3Decoder.FramePredicate {
                override fun evaluate(
                    majorVersion: Int,
                    id0: Int,
                    id1: Int,
                    id2: Int,
                    id3: Int
                ): Boolean {
                    return  ((id0 == 'C'.code && id1 == 'O'.code && id2 == 'M'.code && (id3 == 'M'.code || majorVersion == 2))
                            || (id0 == 'M'.code && id1 == 'L'.code && id2 == 'L'.code && (id3 == 'T'.code || majorVersion == 2)))
                }

            }

        private val MAX_SEARCH_BYTES = 128 * 1024
        private const val SCRATCH_LENGTH = 10
        private const val MPEG_AUDIO_HEADER_MASK = -0x1f400
        private const val SEEK_HEADER_XING = 0x58696e67
        private const val SEEK_HEADER_INFO = 0x496e666f
        private const val SEEK_HEADER_VBRI = 0x56425249
        private const val SEEK_HEADER_UNSET = 0
        private fun headersMatch(headerA: kotlin.Int, headerB: kotlin.Long): kotlin.Boolean {
            return (headerA and MPEG_AUDIO_HEADER_MASK).toLong() == (headerB and MPEG_AUDIO_HEADER_MASK.toLong())
        }

        private fun getSeekFrameHeader(
            frame: ParsableByteArray,
            xingBase: kotlin.Int
        ): @SeekHeader kotlin.Int {
            if (frame.limit() >= xingBase + 4) {
                frame.setPosition(xingBase)
                val headerData: kotlin.Int = frame.readInt()
                if (headerData == SEEK_HEADER_XING || headerData == SEEK_HEADER_INFO) {
                    return headerData
                }
            }
            if (frame.limit() >= 40) {
                frame.setPosition(36) // MPEG audio header (4 bytes) + 32 bytes.
                if (frame.readInt() == SEEK_HEADER_VBRI) {
                    return SEEK_HEADER_VBRI
                }
            }
            return SEEK_HEADER_UNSET
        }

        private fun maybeHandleSeekMetadata(
            metadata: androidx.media3.common.Metadata?, firstFramePosition: kotlin.Long
        ): MlltSeeker? {
            if (metadata == null) {
                return null
            }
            val mlltFrame: MlltFrame? = metadata.getFirstEntryOfType(MlltFrame::class.java)
            if (mlltFrame == null) {
                return null
            }
            return MlltSeeker.create(
                firstFramePosition,
                mlltFrame,
                getId3TlenUs(metadata)
            )
        }

        private fun getId3TlenUs(metadata: androidx.media3.common.Metadata?): kotlin.Long {
            if (metadata == null) {
                return androidx.media3.common.C.TIME_UNSET
            }
            val tlenFrame: TextInformationFrame? =
                metadata.getFirstMatchingEntry(
                    TextInformationFrame::class.java,
                    com.google.common.base.Predicate { tif: TextInformationFrame -> tif.id.equals("TLEN") })
            if (tlenFrame == null) {
                return androidx.media3.common.C.TIME_UNSET
            }
            return androidx.media3.common.util.Util.msToUs(tlenFrame.values[0].toLong())
        }
    }
}
