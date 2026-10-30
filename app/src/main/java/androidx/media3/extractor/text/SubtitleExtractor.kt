package androidx.media3.extractor.text

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.binarySearchFloor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.IndexSeekMap
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser.OutputOptions
import com.google.common.base.Preconditions
import com.google.common.primitives.Ints

import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

class SubtitleExtractor(private val subtitleParser: SubtitleParser, format: Format?) : Extractor {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [STATE_CREATED, STATE_INITIALIZED, STATE_EXTRACTING, STATE_SEEKING, STATE_FINISHED, STATE_RELEASED
        ]
    )
    private annotation class State

    private val cueEncoder: CueEncoder
    private val format: Format?
    private val samples: MutableList<Sample>
    private val scratchSampleArray: ParsableByteArray

    private var subtitleData: ByteArray
    private var trackOutput:TrackOutput? = null
    private var extractorOutput:ExtractorOutput? = null
    private var seekMap:IndexSeekMap? = null
    private var bytesRead = 0
    private var state: @State Int
    private var timestamps: LongArray
    private var seekTimeUs: Long

    init {
        cueEncoder = CueEncoder()
        subtitleData = Util.EMPTY_BYTE_ARRAY
        scratchSampleArray = ParsableByteArray()
        // TODO: b/376693592 - Simplify this by taking the post-transformation Format as a parameter
        //  instead.
        this.format =
            if (format != null)
                format
                    .buildUpon()
                    .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                    .setCodecs(format.sampleMimeType)
                    .setCueReplacementBehavior(subtitleParser.cueReplacementBehavior)
                    .build()
            else
                null
        samples = ArrayList<Sample>()
        state = STATE_CREATED
        timestamps = Util.EMPTY_LONG_ARRAY
        seekTimeUs = C.TIME_UNSET
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        // TODO: Implement sniff() according to the Extractor interface documentation. For now sniff()
        // can safely return true because we plan to use this class in an ExtractorFactory that returns
        // exactly one Extractor implementation.
        return true
    }

    override fun init(output: ExtractorOutput) {
        Preconditions.checkState(state == STATE_CREATED)
        this.extractorOutput = output
        trackOutput = output.track(TRACK_ID, C.TRACK_TYPE_TEXT)
        if (format != null) {
            trackOutput!!.format(format)
            output.endTracks()
            seekMap =
                IndexSeekMap(
                    listOf(0),
                    listOf(0),
                    C.TIME_UNSET
                )
            output.seekMap(seekMap!!)
        }
        state = STATE_INITIALIZED
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        Preconditions.checkState(state != STATE_CREATED && state != STATE_RELEASED)
        if (state == STATE_INITIALIZED) {
            val length =
                if (input.length != C.LENGTH_UNSET.toLong())
                    Ints.checkedCast(input.length)
                else
                    DEFAULT_BUFFER_SIZE
            if (length > subtitleData.size) {
                subtitleData = ByteArray(length)
            }
            bytesRead = 0
            state = STATE_EXTRACTING
        }
        if (state == STATE_EXTRACTING) {
            val inputFinished = readFromInput(input)
            if (inputFinished) {
                parseAndWriteToOutput()
                state = STATE_FINISHED
            }
        }
        if (state == STATE_SEEKING) {
            val inputFinished = skipInput(input)
            if (inputFinished) {
                writeToOutput()
                state = STATE_FINISHED
            }
        }
        if (state == STATE_FINISHED) {
            return Extractor.RESULT_END_OF_INPUT
        }
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        Preconditions.checkState(state != STATE_CREATED && state != STATE_RELEASED)
        seekTimeUs = timeUs
        if (state == STATE_EXTRACTING) {
            state = STATE_INITIALIZED
        }
        if (state == STATE_FINISHED) {
            state = STATE_SEEKING
        }
    }

    override fun release() {
        if (state == STATE_RELEASED) {
            return
        }
        subtitleParser.reset()
        state = STATE_RELEASED
    }

    @Throws(IOException::class)
    private fun skipInput(input: ExtractorInput): Boolean {
        return (input.skip(
            if (input.length != C.LENGTH_UNSET.toLong())
                Ints.checkedCast(input.length)
            else
                DEFAULT_BUFFER_SIZE
        )
                == C.RESULT_END_OF_INPUT)
    }

    @Throws(IOException::class)
    private fun readFromInput(input: ExtractorInput): Boolean {
        if (subtitleData.size == bytesRead) {
            subtitleData =
                subtitleData.copyOf( subtitleData.size + DEFAULT_BUFFER_SIZE)
        }
        val readResult =
            input.read(
                subtitleData,  bytesRead,  subtitleData.size - bytesRead
            )
        if (readResult != C.RESULT_END_OF_INPUT) {
            bytesRead += readResult
        }
        val inputLength = input.length
        return (inputLength != C.LENGTH_UNSET.toLong() && bytesRead.toLong() == inputLength)
                || readResult == C.RESULT_END_OF_INPUT
    }

    @Throws(IOException::class)
    private fun parseAndWriteToOutput() {
        try {
            val outputOptions: OutputOptions =
                if (seekTimeUs != C.TIME_UNSET)
                    OutputOptions.cuesAfterThenRemainingCuesBefore(seekTimeUs)
                else
                    OutputOptions.allCues()
            val maxEndTimeUs = AtomicLong(C.TIME_UNSET)
            subtitleParser.parse(
                subtitleData,  
                0,  
                bytesRead,
                outputOptions,
                 { cuesWithTiming ->
                    val sample =
                        Sample(
                            cuesWithTiming.startTimeUs,
                            cueEncoder.encode(cuesWithTiming.cues, cuesWithTiming.durationUs)!!
                        )
                    samples.add(sample)
                    if (cuesWithTiming.endTimeUs !== C.TIME_UNSET) {
                        maxEndTimeUs.set(
                            if (maxEndTimeUs.get() == C.TIME_UNSET)
                                cuesWithTiming.endTimeUs
                            else
                                Math.max(maxEndTimeUs.get(), cuesWithTiming.endTimeUs)
                        )
                    }
                    if (seekTimeUs == C.TIME_UNSET || cuesWithTiming.endTimeUs >= seekTimeUs) {
                        writeToOutput(sample)
                    }
                })
            Collections.sort<Sample?>(samples)
            timestamps = LongArray(samples.size)
            for (i in samples.indices) {
                timestamps[i] = samples[i]!!.timeUs
            }
            // Duration is exact after parsing all subtitle cues.
            if (maxEndTimeUs.get() != C.TIME_UNSET) {
                trackOutput!!.durationUs(maxEndTimeUs.get())
                if (seekMap != null) {
                    seekMap!!.durationUs=(maxEndTimeUs.get())
                    extractorOutput!!.seekMap(seekMap!!)
                }
            }
            subtitleData = Util.EMPTY_BYTE_ARRAY
        } catch (e: RuntimeException) {
            throw ParserException.createForMalformedContainer("SubtitleParser failed.", e)
        }
    }

    private fun writeToOutput() {
        val index =
            if (seekTimeUs == C.TIME_UNSET)
                0
            else
                binarySearchFloor(
                    timestamps, seekTimeUs,  true,  true
                )
        for (i in index..<samples.size) {
            writeToOutput(samples[i]!!)
        }
    }

    private fun writeToOutput(sample: Sample) {
        val size = sample.data.size
        scratchSampleArray.reset(sample.data)
        trackOutput!!.sampleData(scratchSampleArray, size)
        trackOutput!!.sampleMetadata(
            sample.timeUs,  
            C.BUFFER_FLAG_KEY_FRAME,  
            size,  
            0,  
            null
        )
    }

     class Sample( val timeUs: Long,  val data: ByteArray) :
        Comparable<Sample> {
        override fun compareTo(sample: Sample): Int {
            return timeUs.compareTo( sample.timeUs)
        }
    }

    companion object {
        
        const val TRACK_ID: Int = 0

        private const val STATE_CREATED = 0

        private const val STATE_INITIALIZED = 1

        private const val STATE_EXTRACTING = 2

        private const val STATE_SEEKING = 3

        private const val STATE_FINISHED = 4

        private const val STATE_RELEASED = 5

        private const val DEFAULT_BUFFER_SIZE = 1024
    }
}
