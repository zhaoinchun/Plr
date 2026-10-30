/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.jpeg

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
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

internal class JpegMotionPhotoExtractor : Extractor {
    
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [STATE_READING_MARKER, STATE_READING_SEGMENT_LENGTH, STATE_READING_SEGMENT, STATE_SNIFFING_MOTION_PHOTO_VIDEO, STATE_READING_MOTION_PHOTO_VIDEO, STATE_ENDED
        ]
    )
    private annotation class State

    private val scratch: ParsableByteArray

    private var extractorOutput:ExtractorOutput? = null

    private var state: @State Int = 0
    private var marker = 0
    private var segmentLength = 0
    private var mp4StartPosition: Long

    private var motionPhotoMetadata: MotionPhotoMetadata? = null
    private var lastExtractorInput:ExtractorInput? = null
    private var mp4ExtractorStartOffsetExtractorInput:StartOffsetExtractorInput? =
        null
    private var mp4Extractor: Mp4Extractor? = null

    init {
        scratch = ParsableByteArray( MARKER_SIZE)
        mp4StartPosition = C.INDEX_UNSET.toLong()
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean {
        // See ITU-T.81 (1992) subsection B.1.1.3.
        if (peekMarker(input) != MARKER_SOI) {
            return false
        }

        while (true) {
            marker = peekMarker(input)

            if (marker == MARKER_SOS) {
                // Reached the Start of Scan (image data), no more metadata to parse.
                break
            }

            val payloadLength = peekSegmentLength(input)
            if (payloadLength < 0) {
                break
            }

            if (marker != MARKER_APP1) {
                input.advancePeekPosition(payloadLength)
                continue
            }

            scratch.reset(payloadLength)
            input.peekFully(scratch.data,  0,  payloadLength)
            if (isMotionPhotoXmp(scratch)) {
                return true
            }
        }

        return false
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
    }

    @Throws(IOException::class)
    override fun read(
        input: ExtractorInput,
        seekPosition: PositionHolder
    ): @Extractor.ReadResult Int {
        when (state) {
            STATE_READING_MARKER -> {
                readMarker(input)
                return Extractor.RESULT_CONTINUE
            }

            STATE_READING_SEGMENT_LENGTH -> {
                readSegmentLength(input)
                return Extractor.RESULT_CONTINUE
            }

            STATE_READING_SEGMENT -> {
                readSegment(input)
                return Extractor.RESULT_CONTINUE
            }

            STATE_SNIFFING_MOTION_PHOTO_VIDEO -> {
                if (input.position !== mp4StartPosition) {
                    seekPosition.position = mp4StartPosition
                    return Extractor.RESULT_SEEK
                }
                sniffMotionPhotoVideo(input)
                return Extractor.RESULT_CONTINUE
            }

            STATE_READING_MOTION_PHOTO_VIDEO -> {
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

            STATE_ENDED -> return Extractor.RESULT_END_OF_INPUT
            else -> throw IllegalStateException()
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        if (position == 0L) {
            state = STATE_READING_MARKER
            mp4Extractor = null
        } else if (state == STATE_READING_MOTION_PHOTO_VIDEO) {
            mp4Extractor!!.seek(position, timeUs)
        }
    }

    override fun release() {
        if (mp4Extractor != null) {
            mp4Extractor!!.release()
        }
    }

    @Throws(IOException::class)
    private fun peekMarker(input: ExtractorInput): Int {
        scratch.reset( MARKER_SIZE)
        input.peekFully(scratch.data,  0,  MARKER_SIZE)
        return scratch.readUnsignedShort()
    }

    private fun isMotionPhotoXmp(payload: ParsableByteArray): Boolean {
        if (payload.readNullTerminatedString() != HEADER_XMP_APP1) {
            return false
        }
        return XmpMotionPhotoDescriptionParser.isMotionPhotoXmp(payload.readNullTerminatedString())
    }

    @Throws(IOException::class)
    private fun readMarker(input: ExtractorInput) {
        scratch.reset( MARKER_SIZE)
        input.readFully(scratch.data,  0,  MARKER_SIZE)
        marker = scratch.readUnsignedShort()
        if (marker == MARKER_SOS) { // Start of scan.
            if (mp4StartPosition != C.INDEX_UNSET.toLong()) {
                state = STATE_SNIFFING_MOTION_PHOTO_VIDEO
            } else {
                endReading()
            }
        } else if ((marker < 0xFFD0 || marker > 0xFFD9) && marker != 0xFF01) {
            state = STATE_READING_SEGMENT_LENGTH
        }
    }

    @Throws(IOException::class)
    private fun peekSegmentLength(input: ExtractorInput): Int {
        scratch.reset(SEGMENT_LENGTH_SIZE)
        input.peekFully(scratch.data,  0,  SEGMENT_LENGTH_SIZE)
        return scratch.readUnsignedShort() - SEGMENT_LENGTH_SIZE
    }

    @Throws(IOException::class)
    private fun readSegmentLength(input: ExtractorInput) {
        segmentLength = peekSegmentLength(input)
        input.skipFully(SEGMENT_LENGTH_SIZE)
        state = STATE_READING_SEGMENT
    }

    @Throws(IOException::class)
    private fun readSegment(input: ExtractorInput) {
        if (marker == MARKER_APP1) {
            val payload = ParsableByteArray(segmentLength)
            input.readFully(payload.data,  0,  segmentLength)
            if (motionPhotoMetadata == null
                && HEADER_XMP_APP1 == payload.readNullTerminatedString()
            ) {
                val xmpString = payload.readNullTerminatedString()
                if (xmpString != null) {
                    motionPhotoMetadata = getMotionPhotoMetadata(xmpString, input.length)
                    if (motionPhotoMetadata != null) {
                        mp4StartPosition = motionPhotoMetadata!!.videoStartPosition
                    }
                }
            }
        } else {
            input.skipFully(segmentLength)
        }
        state = STATE_READING_MARKER
    }

    @Throws(IOException::class)
    private fun sniffMotionPhotoVideo(input: ExtractorInput) {
        // Check if the file is truncated.
        val peekedData =
            input.peekFully(
                scratch.data,  0,  1,  true
            )
        if (!peekedData) {
            endReading()
        } else {
            input.resetPeekPosition()
            if (mp4Extractor == null) {
                mp4Extractor =
                    Mp4Extractor(
                        SubtitleParser.Factory.UNSUPPORTED,
                        Mp4Extractor.FLAG_MARK_FIRST_VIDEO_TRACK_WITH_MAIN_ROLE
                    )
            }
            mp4ExtractorStartOffsetExtractorInput =
                StartOffsetExtractorInput(input, mp4StartPosition)
            if (mp4Extractor!!.sniff(mp4ExtractorStartOffsetExtractorInput!!)) {
                mp4Extractor!!.init(
                    StartOffsetExtractorOutput(
                        mp4StartPosition,
                        extractorOutput!!
                    )
                )
                startReadingMotionPhoto()
            } else {
                endReading()
            }
        }
    }

    private fun startReadingMotionPhoto() {
        outputImageTrack(motionPhotoMetadata!!)
        state = STATE_READING_MOTION_PHOTO_VIDEO
    }

    private fun endReading() {
        extractorOutput!!.endTracks()
        extractorOutput!!.seekMap(Unseekable( C.TIME_UNSET))
        state = STATE_ENDED
    }

    private fun outputImageTrack(motionPhotoMetadata: MotionPhotoMetadata) {
        val imageTrackOutput =
            extractorOutput!!.track(
                SingleSampleExtractor.IMAGE_TRACK_ID, C.TRACK_TYPE_IMAGE
            )
        imageTrackOutput!!.format(
            Format.Builder()
                .setContainerMimeType(MimeTypes.IMAGE_JPEG)
                .setMetadata(Metadata(listOf(motionPhotoMetadata)))
                .build()
        )
    }

    companion object {
        private const val STATE_READING_MARKER = 0
        private const val STATE_READING_SEGMENT_LENGTH = 1
        private const val STATE_READING_SEGMENT = 2
        private const val STATE_SNIFFING_MOTION_PHOTO_VIDEO = 4
        private const val STATE_READING_MOTION_PHOTO_VIDEO = 5
        private const val STATE_ENDED = 6

        private const val MARKER_SOI = 0xFFD8 // Start of image marker
        private const val MARKER_SOS = 0xFFDA // Start of scan (image data) marker
        private const val MARKER_APP1 = 0xFFE1 // Application data 1 marker
        private const val HEADER_XMP_APP1 = "http://ns.adobe.com/xap/1.0/"

        private const val MARKER_SIZE = 2
        private const val SEGMENT_LENGTH_SIZE = 2

        @Throws(IOException::class)
        private fun getMotionPhotoMetadata(
            xmpString: String?,
            inputLength: Long
        ): MotionPhotoMetadata? {
            // Metadata defines offsets from the end of the stream, so we need the stream length to
            // determine start offsets.
            if (inputLength == C.LENGTH_UNSET.toLong()) {
                return null
            }

            // Motion photos have (at least) a primary image media item and a secondary video media item.
            val motionPhotoDescription =
                XmpMotionPhotoDescriptionParser.parse(xmpString)
            if (motionPhotoDescription == null) {
                return null
            }
            return motionPhotoDescription.getMotionPhotoMetadata(inputLength)
        }
    }
}
