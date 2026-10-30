
package androidx.media3.extractor

import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.Id3Decoder.FramePredicate
import com.google.errorprone.annotations.InlineMe
import java.io.EOFException
import java.io.IOException

class Id3Peeker {
    private val scratch: ParsableByteArray

    init {
        scratch = ParsableByteArray(Id3Decoder.ID3_HEADER_LENGTH)
    }

    @InlineMe(replacement = "this.peekId3Data(input, id3FramePredicate,  0)")
    @Deprecated("Use {@link #peekId3Data(ExtractorInput, FramePredicate, int)} instead.")
    @Throws(
        IOException::class
    )
    fun peekId3Data(
        input: ExtractorInput, id3FramePredicate: FramePredicate?
    ): Metadata? {
        return peekId3Data(input, id3FramePredicate,  0)
    }

    @Throws(IOException::class)
    fun peekId3Data(
        input: ExtractorInput,
        id3FramePredicate: FramePredicate?,
        maxTagPeekBytes: Int
    ): Metadata? {
        var peekedId3Bytes = 0
        var metadata: Metadata? = null
        while (peekId3HeaderIntoScratch(input, maxTagPeekBytes)) {
            val id3HeaderStartInScratch = scratch.getPosition()
            scratch.skipBytes(6) // Skip header, major version, minor version and flags.
            val framesLength = scratch.readSynchSafeInt()
            val tagLength = Id3Decoder.ID3_HEADER_LENGTH + framesLength

            if (metadata == null) {
                val id3Data = ByteArray(tagLength)
                System.arraycopy(
                    scratch.data,
                    id3HeaderStartInScratch,
                    id3Data,
                    0,
                    Id3Decoder.ID3_HEADER_LENGTH
                )
                input.peekFully(id3Data, Id3Decoder.ID3_HEADER_LENGTH, framesLength)

                metadata = Id3Decoder(id3FramePredicate).decode(id3Data, tagLength)
            } else {
                input.advancePeekPosition(framesLength)
            }

            peekedId3Bytes += tagLength
        }

        input.resetPeekPosition()
        input.advancePeekPosition(peekedId3Bytes)
        return metadata
    }

    @Throws(IOException::class)
    private fun peekId3HeaderIntoScratch(input: ExtractorInput, maxTagPeekBytes: Int): Boolean {
        var tagSearchBytes = 0
        do {
            val headerStartIndexInScratch = tagSearchBytes % Id3Decoder.ID3_HEADER_LENGTH
            val headerEndIndexInScratch = headerStartIndexInScratch + Id3Decoder.ID3_HEADER_LENGTH
            if (headerStartIndexInScratch == 0 && tagSearchBytes != 0) {
                // We've reached the end of the double-length array. Move the existing data back to the
                // start.
                System.arraycopy(
                    scratch.data,
                    Id3Decoder.ID3_HEADER_LENGTH,
                    scratch.data,
                    0,
                    Id3Decoder.ID3_HEADER_LENGTH - 1
                )
            }
            val peekLength = if (tagSearchBytes == 0) Id3Decoder.ID3_HEADER_LENGTH else 1
            try {
                input.peekFully(
                    scratch.data,
                    headerEndIndexInScratch - peekLength,  
                    peekLength
                )
            } catch (e: EOFException) {
                return false
            }
            scratch.setPosition(headerStartIndexInScratch)
            scratch.setLimit(headerEndIndexInScratch)
            if (scratch.peekUnsignedInt24() == Id3Decoder.ID3_TAG) {
                return true
            } else if (MpegAudioUtil.getFrameSize(scratch.peekInt()) != C.LENGTH_UNSET) {
                // We've found something that looks like an MP3 header, stop searching for ID3 headers.
                return false
            }
            // Allow reading individual bytes up to twice the header length, then copy everything back to
            // the beginning, to avoid too many repeated array allocations while also limiting the size
            // of the array to be reasonable.
            if (tagSearchBytes == 0) {
                scratch.ensureCapacity(Id3Decoder.ID3_HEADER_LENGTH * 2)
            }
            tagSearchBytes++
        } while (tagSearchBytes <= maxTagPeekBytes)
        return false
    }
}
