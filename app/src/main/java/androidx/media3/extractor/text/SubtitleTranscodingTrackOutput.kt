/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.text

import androidx.media3.common.C
import androidx.media3.common.C.BufferFlags
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.getTrackType
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.TrackOutput.CryptoData
import androidx.media3.extractor.TrackOutput.SampleDataPart
import androidx.media3.extractor.text.SubtitleParser.OutputOptions
import com.google.common.base.Preconditions

import java.io.EOFException
import java.io.IOException
import kotlin.math.max

internal class SubtitleTranscodingTrackOutput(
    private val delegate: TrackOutput,
    private val subtitleParserFactory: SubtitleParser.Factory
) : TrackOutput {
    private val cueEncoder: CueEncoder
    private val parsableScratch: ParsableByteArray

    private var sampleDataStart: Int
    private var sampleDataEnd: Int
    private var sampleData: ByteArray
    private var currentSubtitleParser: SubtitleParser? = null
    private var currentFormat:Format? = null
    private var shouldSuppressParsingErrors = false

    init {
        this.cueEncoder = CueEncoder()
        this.sampleDataStart = 0
        this.sampleDataEnd = 0
        this.sampleData = Util.EMPTY_BYTE_ARRAY
        this.parsableScratch = ParsableByteArray()
    }

    fun resetSubtitleParser() {
        if (currentSubtitleParser != null) {
            currentSubtitleParser!!.reset()
        }
    }

    fun shouldSuppressParsingErrors(shouldSuppressParsingErrors: Boolean) {
        this.shouldSuppressParsingErrors = shouldSuppressParsingErrors
    }

    // TrackOutput implementation
    override fun format(format: Format) {
        Preconditions.checkArgument(getTrackType(format.sampleMimeType!!) == C.TRACK_TYPE_TEXT)
        if (!format.equals(currentFormat)) {
            currentFormat = format
            currentSubtitleParser =
                if (subtitleParserFactory.supportsFormat(format))
                    subtitleParserFactory.create(format)
                else
                    null
        }
        if (currentSubtitleParser == null) {
            delegate.format(format)
        } else {
            delegate.format(
                format
                    .buildUpon()
                    .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                    .setCodecs(format.sampleMimeType) // Reset this value to the default. All non-default timestamp adjustments are done
                    // below in sampleMetadata() and there are no 'subsamples' after transcoding.
                    .setSubsampleOffsetUs(Format.OFFSET_SAMPLE_RELATIVE)
                    .setCueReplacementBehavior(
                        subtitleParserFactory.getCueReplacementBehavior(
                            format
                        )
                    )
                    .build()
            )
        }
    }

    @Throws(IOException::class)
    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: @SampleDataPart Int
    ): Int {
        if (currentSubtitleParser == null) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        ensureSampleDataCapacity(length)
        val bytesRead = input.read(sampleData,  sampleDataEnd, length)
        if (bytesRead == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) {
                return C.RESULT_END_OF_INPUT
            } else {
                throw EOFException()
            }
        } else {
            sampleDataEnd += bytesRead
            return bytesRead
        }
    }

    override fun sampleData(
        data: ParsableByteArray,
        length: Int,
        sampleDataPart: @SampleDataPart Int
    ) {
        if (currentSubtitleParser == null) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }
        ensureSampleDataCapacity(length)
        data.readBytes(sampleData,  sampleDataEnd, length)
        sampleDataEnd += length
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: @BufferFlags Int,
        size: Int,
        offset: Int,
        cryptoData: CryptoData?
    ) {
        if (currentSubtitleParser == null) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        Preconditions.checkArgument(cryptoData == null, "DRM on subtitles is not supported")

        val sampleStart = sampleDataEnd - offset - size
        try {
            currentSubtitleParser!!.parse(
                sampleData,
                sampleStart,
                size,
                OutputOptions.allCues(),
                 { cuesWithTiming -> outputSample(cuesWithTiming, timeUs, flags) })
        } catch (e: RuntimeException) {
            if (shouldSuppressParsingErrors) {
                // TODO: b/391362063 - Propagate this error out in a non-fatal way.
                w(TAG, "Parsing subtitles failed, ignoring sample.", e)
            } else {
                throw e
            }
        }
        sampleDataStart = sampleStart + size
        if (sampleDataStart == sampleDataEnd) {
            // The array is now empty, so we can move the start and end pointers back to the start.
            sampleDataStart = 0
            sampleDataEnd = 0
        }
    }

    private fun outputSample(
        cuesWithTiming: CuesWithTiming,
        timeUs: Long,
        flags: @BufferFlags Int
    ) {
        // format() must be called before sampleMetadata()
        currentFormat!!
        val cuesWithDurationBytes =
            cueEncoder.encode(cuesWithTiming.cues, cuesWithTiming.durationUs)!!
        parsableScratch.reset(cuesWithDurationBytes)
        delegate.sampleData(parsableScratch, cuesWithDurationBytes.size)
        val outputSampleTimeUs: Long
        if (cuesWithTiming.startTimeUs == C.TIME_UNSET) {
            Preconditions.checkState(currentFormat!!.subsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE)
            outputSampleTimeUs = timeUs
        } else if (currentFormat!!.subsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE) {
            outputSampleTimeUs = timeUs + cuesWithTiming.startTimeUs
        } else {
            outputSampleTimeUs = cuesWithTiming.startTimeUs + currentFormat!!.subsampleOffsetUs
        }
        delegate.sampleMetadata(
            outputSampleTimeUs,
            flags or C.BUFFER_FLAG_KEY_FRAME,
            cuesWithDurationBytes!!.size,  
            0,  
            null
        )
    }

    private fun ensureSampleDataCapacity(newSampleSize: Int) {
        if (sampleData.size - sampleDataEnd >= newSampleSize) {
            return
        }
        val existingSampleDataLength = sampleDataEnd - sampleDataStart
        // Make sure there's enough space for the new sample (after we move existing data to the
        // beginning of the array).
        val targetLength =
            max(existingSampleDataLength * 2, existingSampleDataLength + newSampleSize)
        val newSampleData =
            if (targetLength <= sampleData.size) sampleData else ByteArray(targetLength)
        System.arraycopy(sampleData, sampleDataStart, newSampleData, 0, existingSampleDataLength)
        sampleDataStart = 0
        sampleDataEnd = existingSampleDataLength
        sampleData = newSampleData
    }

    companion object {
        private const val TAG = "SubtitleTranscodingTO"
    }
}
