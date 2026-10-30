
package androidx.media3.extractor.heif

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.Mp4Box
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap.Unseekable
import androidx.media3.extractor.SingleSampleExtractor
import androidx.media3.extractor.StartOffsetExtractorInput
import androidx.media3.extractor.StartOffsetExtractorOutput
import androidx.media3.extractor.metadata.MotionPhotoMetadata
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.base.Preconditions

import java.io.IOException

internal class HeicMotionPhotoExtractor : Extractor {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [STATE_READING_ATOM_HEADER, STATE_READING_ATOM_PAYLOAD, STATE_SNIFFING_MOTION_PHOTO_VIDEO, STATE_READING_MOTION_PHOTO_VIDEO, STATE_ENDED
        ]
    )
    private annotation class State

    private val scratch: ParsableByteArray

    private var extractorOutput:ExtractorOutput? = null
    private var motionPhotoMetadata:MotionPhotoMetadata? = null
    private var lastExtractorInput:ExtractorInput? = null
    private var mp4ExtractorStartOffsetExtractorInput:StartOffsetExtractorInput? =
        null
    private var mp4Extractor: Mp4Extractor? = null

    private var state: @State Int
    private var atomType = 0
    private var atomSize: Long = 0
    private var atomHeaderBytesRead = 0
    private var mp4StartPosition: Long

    init {
        scratch = ParsableByteArray(Mp4Box.LONG_HEADER_SIZE)
        mp4StartPosition = C.INDEX_UNSET.toLong()
        state = STATE_READING_ATOM_HEADER
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        return HeifSniffer.sniff(input,  true)
    }

    override fun init(output: ExtractorOutput) {
        this.extractorOutput = output
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        while (true) {
            when (state) {
                STATE_READING_ATOM_HEADER -> if (!readAtomHeader(input)) {
                    endReading()
                    return Extractor.RESULT_END_OF_INPUT
                }

                STATE_READING_ATOM_PAYLOAD -> readAtomPayload(input)
                STATE_SNIFFING_MOTION_PHOTO_VIDEO -> sniffMotionPhotoVideo(input)
                STATE_READING_MOTION_PHOTO_VIDEO -> return readMotionPhotoVideo(input, seekPosition)
                STATE_ENDED -> return Extractor.RESULT_END_OF_INPUT
                else -> throw IllegalStateException()
            }
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        if (position == 0L) {
            state = STATE_READING_ATOM_HEADER
            atomHeaderBytesRead = 0
            mp4StartPosition = C.INDEX_UNSET.toLong()
            if (mp4Extractor != null) {
                mp4Extractor!!.release()
                mp4Extractor = null
            }
        } else if (state == STATE_READING_MOTION_PHOTO_VIDEO) {
            mp4Extractor!!.seek(position, timeUs)
        }
    }

    override fun release() {
        if (mp4Extractor != null) {
            mp4Extractor!!.release()
            mp4Extractor = null
        }
    }

    @Throws(IOException::class)
    private fun readAtomHeader(input: ExtractorInput): Boolean {
        if (atomHeaderBytesRead == 0) {
            if (!input.readFully(
                    scratch.data,  
                    0,
                    Mp4Box.HEADER_SIZE,  
                    true
                )
            ) {
                return false
            }
            atomHeaderBytesRead = Mp4Box.HEADER_SIZE
            scratch.setPosition(0)
            atomSize = scratch.readUnsignedInt()
            atomType = scratch.readInt()
        }

        if (atomSize == Mp4Box.DEFINES_LARGE_SIZE.toLong()) {
            val headerBytesRemaining = Mp4Box.LONG_HEADER_SIZE - Mp4Box.HEADER_SIZE
            input.readFully(scratch.data, Mp4Box.HEADER_SIZE, headerBytesRemaining)
            atomHeaderBytesRead += headerBytesRemaining
            atomSize = scratch.readUnsignedLongToLong()
        }

        if (atomType == Mp4Box.TYPE_mpvd) {
            mp4StartPosition = input.position
            val boxStartPosition = mp4StartPosition - atomHeaderBytesRead
            motionPhotoMetadata =
                MotionPhotoMetadata( 
                    0,  
                    boxStartPosition,  
                    C.TIME_UNSET,  
                    mp4StartPosition,  
                    atomSize - atomHeaderBytesRead
                )
            outputImageTrack(motionPhotoMetadata!!)
            state = STATE_SNIFFING_MOTION_PHOTO_VIDEO
        } else {
            state = STATE_READING_ATOM_PAYLOAD
        }
        return true
    }

    @Throws(IOException::class)
    private fun readAtomPayload(input: ExtractorInput) {
        val atomPayloadSize = atomSize - atomHeaderBytesRead
        input.skipFully(atomPayloadSize.toInt())
        atomHeaderBytesRead = 0
        state = STATE_READING_ATOM_HEADER
    }

    @Throws(IOException::class)
    private fun sniffMotionPhotoVideo(input: ExtractorInput) {
        if (mp4Extractor == null) {
            mp4Extractor =
                Mp4Extractor(
                    SubtitleParser.Factory.UNSUPPORTED,
                    Mp4Extractor.FLAG_MARK_FIRST_VIDEO_TRACK_WITH_MAIN_ROLE
                )
        }
        mp4ExtractorStartOffsetExtractorInput = StartOffsetExtractorInput(input, mp4StartPosition)
        if (mp4Extractor!!.sniff(mp4ExtractorStartOffsetExtractorInput!!)) {
            mp4Extractor!!.init(
                StartOffsetExtractorOutput(
                    mp4StartPosition,
                    extractorOutput!!
                )
            )
            state = STATE_READING_MOTION_PHOTO_VIDEO
        } else {
            endReading()
        }
    }

    @Throws(IOException::class)
    private fun readMotionPhotoVideo(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (mp4ExtractorStartOffsetExtractorInput == null || input !== lastExtractorInput) {
            lastExtractorInput = input
            mp4ExtractorStartOffsetExtractorInput =
                StartOffsetExtractorInput(input, mp4StartPosition)
        }
        val readResult: @Extractor.ReadResult Int =
            mp4Extractor!!.read(mp4ExtractorStartOffsetExtractorInput!!, seekPosition)
        if (readResult == Extractor.RESULT_SEEK) {
            seekPosition.position += mp4StartPosition
        }
        return readResult
    }

    private fun outputImageTrack(motionPhotoMetadata: MotionPhotoMetadata) {
        val imageTrackOutput =
            extractorOutput!!.track(
                SingleSampleExtractor.IMAGE_TRACK_ID, C.TRACK_TYPE_IMAGE
            )
        imageTrackOutput!!.format(
            Format.Builder()
                .setContainerMimeType(MimeTypes.IMAGE_HEIC)
                .setMetadata(Metadata(listOf(motionPhotoMetadata)))
                .build()
        )
    }

    private fun endReading() {
        extractorOutput!!.endTracks()
        extractorOutput!!.seekMap(Unseekable( C.TIME_UNSET))
        state = STATE_ENDED
    }

    companion object {
        private const val STATE_READING_ATOM_HEADER = 0
        private const val STATE_READING_ATOM_PAYLOAD = 1
        private const val STATE_SNIFFING_MOTION_PHOTO_VIDEO = 2
        private const val STATE_READING_MOTION_PHOTO_VIDEO = 3
        private const val STATE_ENDED = 4
    }
}
