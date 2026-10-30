package androidx.media3.extractor.metadata.id3

import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes.normalizeMimeType
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util
import androidx.media3.extractor.metadata.MetadataInputBuffer
import androidx.media3.extractor.metadata.SimpleMetadataDecoder
import com.google.common.base.Ascii
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Locale

class Id3Decoder(private val framePredicate: FramePredicate? = null) :
    SimpleMetadataDecoder() {
    interface FramePredicate {

        fun evaluate(majorVersion: Int, id0: Int, id1: Int, id2: Int, id3: Int): Boolean
    }

    override fun decode(inputBuffer: MetadataInputBuffer, buffer: ByteBuffer): Metadata? {
        return decode(buffer.array(), buffer.limit())
    }

    fun decode(data: ByteArray?, size: Int): Metadata? {
        val id3Frames: MutableList<Id3Frame> = mutableListOf()
        val id3Data = ParsableByteArray(data!!, size)

        val id3Header: Id3Header? = decodeHeader(id3Data)
        if (id3Header == null) {
            return null
        }

        val startPosition = id3Data.getPosition()
        val frameHeaderSize = if (id3Header.majorVersion == 2) 6 else 10
        var framesSize = id3Header.framesSize
        if (id3Header.isUnsynchronized) {
            framesSize = removeUnsynchronization(id3Data, id3Header.framesSize)
        }
        id3Data.setLimit(startPosition + framesSize)

        var unsignedIntFrameSizeHack = false
        if (!validateFrames(id3Data, id3Header.majorVersion, frameHeaderSize, false)) {
            if (id3Header.majorVersion == 4 && validateFrames(id3Data, 4, frameHeaderSize, true)) {
                unsignedIntFrameSizeHack = true
            } else {
                w(TAG, "Failed to validate ID3 tag with majorVersion=" + id3Header.majorVersion)
                return null
            }
        }

        while (id3Data.bytesLeft() >= frameHeaderSize) {
            val frame: Id3Frame? =
                decodeFrame(
                    id3Header.majorVersion,
                    id3Data,
                    unsignedIntFrameSizeHack,
                    frameHeaderSize,
                    framePredicate
                )
            if (frame != null) {
                id3Frames.add(frame)
            }
        }

        return Metadata(id3Frames)
    }

    private class Id3Header(
         val majorVersion: Int,
         val isUnsynchronized: Boolean,
         val framesSize: Int
    )
    companion object {
        val NO_FRAMES_PREDICATE: FramePredicate =
            object :Id3Decoder.FramePredicate {
                override fun evaluate(
                    majorVersion: Int,
                    id0: Int,
                    id1: Int,
                    id2: Int,
                    id3: Int
                ): Boolean {
                    return false
                }

            }

        val NO_ARTWORK_PREDICATE: FramePredicate =
          object : Id3Decoder.FramePredicate {
              override fun evaluate(
                  majorVersion: Int,
                  id0: Int,
                  id1: Int,
                  id2: Int,
                  id3: Int
              ): Boolean {
                return  if (majorVersion == 2) {
                      !(id0 == 'P'.code && id1 == 'I'.code && id2 == 'C'.code)
                  } else {
                       !(id0 == 'A'.code && id1 == 'P'.code && id2 == 'I'.code && id3 == 'C'.code)
                  }
              }
          }

        private const val TAG = "Id3Decoder"

        const val ID3_TAG: Int = 0x00494433

        const val ID3_HEADER_LENGTH: Int = 10

        private const val FRAME_FLAG_V3_IS_COMPRESSED = 0x0080
        private const val FRAME_FLAG_V3_IS_ENCRYPTED = 0x0040
        private const val FRAME_FLAG_V3_HAS_GROUP_IDENTIFIER = 0x0020
        private const val FRAME_FLAG_V4_IS_COMPRESSED = 0x0008
        private const val FRAME_FLAG_V4_IS_ENCRYPTED = 0x0004
        private const val FRAME_FLAG_V4_HAS_GROUP_IDENTIFIER = 0x0040
        private const val FRAME_FLAG_V4_IS_UNSYNCHRONIZED = 0x0002
        private const val FRAME_FLAG_V4_HAS_DATA_LENGTH = 0x0001

        private const val ID3_TEXT_ENCODING_ISO_8859_1 = 0
        private const val ID3_TEXT_ENCODING_UTF_16 = 1
        private const val ID3_TEXT_ENCODING_UTF_16BE = 2
        private const val ID3_TEXT_ENCODING_UTF_8 = 3

        private fun decodeHeader(data: ParsableByteArray): Id3Header? {
            if (data.bytesLeft() < ID3_HEADER_LENGTH) {
                w(TAG, "Data too short to be an ID3 tag")
                return null
            }

            val id = data.readUnsignedInt24()
            if (id != ID3_TAG) {
                w(
                    TAG,
                    "Unexpected first three bytes of ID3 tag header: 0x" + String.format("%06X", id)
                )
                return null
            }

            val majorVersion = data.readUnsignedByte()
            data.skipBytes(1) // Skip minor version.
            val flags = data.readUnsignedByte()
            var framesSize = data.readSynchSafeInt()

            if (majorVersion == 2) {
                val isCompressed = (flags and 0x40) != 0
                if (isCompressed) {
                    w(TAG, "Skipped ID3 tag with majorVersion=2 and undefined compression scheme")
                    return null
                }
            } else if (majorVersion == 3) {
                val hasExtendedHeader = (flags and 0x40) != 0
                if (hasExtendedHeader) {
                    val extendedHeaderSize = data.readInt() // Size excluding size field.
                    data.skipBytes(extendedHeaderSize)
                    framesSize -= (extendedHeaderSize + 4)
                }
            } else if (majorVersion == 4) {
                val hasExtendedHeader = (flags and 0x40) != 0
                if (hasExtendedHeader) {
                    val extendedHeaderSize = data.readSynchSafeInt() // Size including size field.
                    data.skipBytes(extendedHeaderSize - 4)
                    framesSize -= extendedHeaderSize
                }
                val hasFooter = (flags and 0x10) != 0
                if (hasFooter) {
                    framesSize -= 10
                }
            } else {
                w(TAG, "Skipped ID3 tag with unsupported majorVersion=" + majorVersion)
                return null
            }

            // isUnsynchronized is advisory only in version 4. Frame level flags are used instead.
            val isUnsynchronized = majorVersion < 4 && (flags and 0x80) != 0
            return Id3Header(majorVersion, isUnsynchronized, framesSize)
        }

        private fun validateFrames(
            id3Data: ParsableByteArray,
            majorVersion: Int,
            frameHeaderSize: Int,
            unsignedIntFrameSizeHack: Boolean
        ): Boolean {
            val startPosition = id3Data.getPosition()
            try {
                while (id3Data.bytesLeft() >= frameHeaderSize) {
                    // Read the next frame header.
                    val id: Int
                    var frameSize: Long
                    val flags: Int
                    if (majorVersion >= 3) {
                        id = id3Data.readInt()
                        frameSize = id3Data.readUnsignedInt()
                        flags = id3Data.readUnsignedShort()
                    } else {
                        id = id3Data.readUnsignedInt24()
                        frameSize = id3Data.readUnsignedInt24().toLong()
                        flags = 0
                    }
                    // Validate the frame header and skip to the next one.
                    if (id == 0 && frameSize == 0L && flags == 0) {
                        // We've reached zero padding after the end of the final frame.
                        return true
                    } else {
                        if (majorVersion == 4 && !unsignedIntFrameSizeHack) {
                            // Parse the data size as a synchsafe integer, as per the spec.
                            if ((frameSize and 0x808080L) != 0L) {
                                return false
                            }
                            frameSize =
                                ((frameSize and 0xFFL)
                                        or (((frameSize shr 8) and 0xFFL) shl 7)
                                        or (((frameSize shr 16) and 0xFFL) shl 14)
                                        or (((frameSize shr 24) and 0xFFL) shl 21))
                        }
                        var hasGroupIdentifier = false
                        var hasDataLength = false
                        if (majorVersion == 4) {
                            hasGroupIdentifier = (flags and FRAME_FLAG_V4_HAS_GROUP_IDENTIFIER) != 0
                            hasDataLength = (flags and FRAME_FLAG_V4_HAS_DATA_LENGTH) != 0
                        } else if (majorVersion == 3) {
                            hasGroupIdentifier = (flags and FRAME_FLAG_V3_HAS_GROUP_IDENTIFIER) != 0
                            // A V3 frame has data length if and only if it's compressed.
                            hasDataLength = (flags and FRAME_FLAG_V3_IS_COMPRESSED) != 0
                        }
                        var minimumFrameSize = 0
                        if (hasGroupIdentifier) {
                            minimumFrameSize++
                        }
                        if (hasDataLength) {
                            minimumFrameSize += 4
                        }
                        if (frameSize < minimumFrameSize) {
                            return false
                        }
                        if (id3Data.bytesLeft() < frameSize) {
                            return false
                        }
                        id3Data.skipBytes(frameSize.toInt()) // flags
                    }
                }
                return true
            } finally {
                id3Data.setPosition(startPosition)
            }
        }

        private fun decodeFrame(
            majorVersion: Int,
            id3Data: ParsableByteArray,
            unsignedIntFrameSizeHack: Boolean,
            frameHeaderSize: Int,
            framePredicate: FramePredicate?
        ): Id3Frame? {
            val frameId0 = id3Data.readUnsignedByte()
            val frameId1 = id3Data.readUnsignedByte()
            val frameId2 = id3Data.readUnsignedByte()
            val frameId3 = if (majorVersion >= 3) id3Data.readUnsignedByte() else 0

            var frameSize: Int
            if (majorVersion == 4) {
                frameSize = id3Data.readUnsignedIntToInt()
                if (!unsignedIntFrameSizeHack) {
                    frameSize =
                        ((frameSize and 0xFF)
                                or (((frameSize shr 8) and 0xFF) shl 7)
                                or (((frameSize shr 16) and 0xFF) shl 14)
                                or (((frameSize shr 24) and 0xFF) shl 21))
                }
            } else if (majorVersion == 3) {
                frameSize = id3Data.readUnsignedIntToInt()
            } else   {
                frameSize = id3Data.readUnsignedInt24()
            }

            val flags = if (majorVersion >= 3) id3Data.readUnsignedShort() else 0
            if (frameId0 == 0 && frameId1 == 0 && frameId2 == 0 && frameId3 == 0 && frameSize == 0 && flags == 0) {
                // We must be reading zero padding at the end of the tag.
                id3Data.setPosition(id3Data.limit())
                return null
            }

            val nextFramePosition = id3Data.getPosition() + frameSize
            if (nextFramePosition > id3Data.limit()) {
                w(TAG, "Frame size exceeds remaining tag data")
                id3Data.setPosition(id3Data.limit())
                return null
            }

            if (framePredicate != null
                && !framePredicate.evaluate(majorVersion, frameId0, frameId1, frameId2, frameId3)
            ) {
                // Filtered by the predicate.
                id3Data.setPosition(nextFramePosition)
                return null
            }

            // Frame flags.
            var isCompressed = false
            var isEncrypted = false
            var isUnsynchronized = false
            var hasDataLength = false
            var hasGroupIdentifier = false
            if (majorVersion == 3) {
                isCompressed = (flags and FRAME_FLAG_V3_IS_COMPRESSED) != 0
                isEncrypted = (flags and FRAME_FLAG_V3_IS_ENCRYPTED) != 0
                hasGroupIdentifier = (flags and FRAME_FLAG_V3_HAS_GROUP_IDENTIFIER) != 0
                // A V3 frame has data length if and only if it's compressed.
                hasDataLength = isCompressed
            } else if (majorVersion == 4) {
                hasGroupIdentifier = (flags and FRAME_FLAG_V4_HAS_GROUP_IDENTIFIER) != 0
                isCompressed = (flags and FRAME_FLAG_V4_IS_COMPRESSED) != 0
                isEncrypted = (flags and FRAME_FLAG_V4_IS_ENCRYPTED) != 0
                isUnsynchronized = (flags and FRAME_FLAG_V4_IS_UNSYNCHRONIZED) != 0
                hasDataLength = (flags and FRAME_FLAG_V4_HAS_DATA_LENGTH) != 0
            }

            if (isCompressed || isEncrypted) {
                w(TAG, "Skipping unsupported compressed or encrypted frame")
                id3Data.setPosition(nextFramePosition)
                return null
            }

            if (hasGroupIdentifier) {
                frameSize--
                id3Data.skipBytes(1)
            }
            if (hasDataLength) {
                frameSize -= 4
                id3Data.skipBytes(4)
            }
            if (isUnsynchronized) {
                frameSize = removeUnsynchronization(id3Data, frameSize)
            }

            var frame: Id3Frame? = null
            var error: Throwable? = null
            try {
                if (frameId0 == 'T'.code && frameId1 == 'X'.code && frameId2 == 'X'.code && (majorVersion == 2 || frameId3 == 'X'.code)) {
                    frame = decodeTxxxFrame(id3Data, frameSize)
                } else if (frameId0 == 'T'.code) {
                    val id: String =
                        getFrameId(majorVersion, frameId0, frameId1, frameId2, frameId3)
                    frame = decodeTextInformationFrame(id3Data, frameSize, id)
                } else if (frameId0 == 'W'.code && frameId1 == 'X'.code && frameId2 == 'X'.code && (majorVersion == 2 || frameId3 == 'X'.code)) {
                    frame = decodeWxxxFrame(id3Data, frameSize)
                } else if (frameId0 == 'W'.code) {
                    val id: String =
                        getFrameId(majorVersion, frameId0, frameId1, frameId2, frameId3)
                    frame = decodeUrlLinkFrame(id3Data, frameSize, id)
                } else if (frameId0 == 'P'.code && frameId1 == 'R'.code && frameId2 == 'I'.code && frameId3 == 'V'.code) {
                    frame = decodePrivFrame(id3Data, frameSize)
                } else if (frameId0 == 'G'.code && frameId1 == 'E'.code && frameId2 == 'O'.code && (frameId3 == 'B'.code || majorVersion == 2)) {
                    frame = decodeGeobFrame(id3Data, frameSize)
                } else if (if (majorVersion == 2)
                        (frameId0 == 'P'.code && frameId1 == 'I'.code && frameId2 == 'C'.code)
                    else
                        (frameId0 == 'A'.code && frameId1 == 'P'.code && frameId2 == 'I'.code && frameId3 == 'C'.code)
                ) {
                    frame = decodeApicFrame(id3Data, frameSize, majorVersion)
                } else if (frameId0 == 'C'.code && frameId1 == 'O'.code && frameId2 == 'M'.code && (frameId3 == 'M'.code || majorVersion == 2)) {
                    frame = decodeCommentFrame(id3Data, frameSize)
                } else if (frameId0 == 'C'.code && frameId1 == 'H'.code && frameId2 == 'A'.code && frameId3 == 'P'.code) {
                    frame =
                        decodeChapterFrame(
                            id3Data,
                            frameSize,
                            majorVersion,
                            unsignedIntFrameSizeHack,
                            frameHeaderSize,
                            framePredicate
                        )
                } else if (frameId0 == 'C'.code && frameId1 == 'T'.code && frameId2 == 'O'.code && frameId3 == 'C'.code) {
                    frame =
                        decodeChapterTOCFrame(
                            id3Data,
                            frameSize,
                            majorVersion,
                            unsignedIntFrameSizeHack,
                            frameHeaderSize,
                            framePredicate
                        )
                } else if (frameId0 == 'M'.code && frameId1 == 'L'.code && frameId2 == 'L'.code && frameId3 == 'T'.code) {
                    frame = decodeMlltFrame(id3Data, frameSize)
                } else {
                    val id: String =
                        getFrameId(majorVersion, frameId0, frameId1, frameId2, frameId3)
                    frame = decodeBinaryFrame(id3Data, frameSize, id)
                }
            } catch (e: OutOfMemoryError) {
                error = e
            } catch (e: Exception) {
                error = e
            } finally {
                id3Data.setPosition(nextFramePosition)
            }
            if (frame == null) {
                w(
                    TAG,
                    ("Failed to decode frame: id="
                            + getFrameId(majorVersion, frameId0, frameId1, frameId2, frameId3)
                            + ", frameSize="
                            + frameSize),
                    error
                )
            }
            return frame
        }

        private fun decodeTxxxFrame(
            id3Data: ParsableByteArray,
            frameSize: Int
        ): TextInformationFrame? {
            if (frameSize < 1) {
                // Frame is malformed.
                return null
            }

            val encoding = id3Data.readUnsignedByte()

            val data = ByteArray(frameSize - 1)
            id3Data.readBytes(data, 0, frameSize - 1)

            val descriptionEndIndex: Int = indexOfTerminator(data, 0, encoding)
            val description = String(data, 0, descriptionEndIndex, getCharset(encoding))

            val values: List<String> =
                decodeTextInformationFrameValues(
                    data, encoding, descriptionEndIndex + delimiterLength(encoding)
                )
            return TextInformationFrame("TXXX", description, values)
        }

        private fun decodeTextInformationFrame(
            id3Data: ParsableByteArray, frameSize: Int, id: String?
        ): TextInformationFrame? {
            if (frameSize < 1) {
                // Frame is malformed.
                return null
            }

            val encoding = id3Data.readUnsignedByte()

            val data = ByteArray(frameSize - 1)
            id3Data.readBytes(data, 0, frameSize - 1)

            val values: List<String> = decodeTextInformationFrameValues(data, encoding, 0)
            return TextInformationFrame(id!!, null, values)
        }

        private fun decodeTextInformationFrameValues(
            data: ByteArray, encoding: Int, index: Int
        ): List<String> {
            if (index >= data.size) {
                return listOf("")
            }

            val values = mutableListOf<String>()
            var valueStartIndex = index
            var valueEndIndex: Int = indexOfTerminator(data, valueStartIndex, encoding)
            while (valueStartIndex < valueEndIndex) {
                val value = String(
                    data,
                    valueStartIndex,
                    valueEndIndex - valueStartIndex,
                    getCharset(encoding)
                )
                values.add(value)

                valueStartIndex = valueEndIndex + delimiterLength(encoding)
                valueEndIndex = indexOfTerminator(data, valueStartIndex, encoding)
            }

            val result = values.toList()
            return if (result.isEmpty()) listOf("") else result
        }

        private fun decodeWxxxFrame(id3Data: ParsableByteArray, frameSize: Int): UrlLinkFrame? {
            if (frameSize < 1) {
                // Frame is malformed.
                return null
            }

            val encoding = id3Data.readUnsignedByte()

            val data = ByteArray(frameSize - 1)
            id3Data.readBytes(data, 0, frameSize - 1)

            val descriptionEndIndex: Int = indexOfTerminator(data, 0, encoding)
            val description = String(data, 0, descriptionEndIndex, getCharset(encoding))

            val urlStartIndex: Int = descriptionEndIndex + delimiterLength(encoding)
            val urlEndIndex: Int = indexOfZeroByte(data, urlStartIndex)
            val url: String =
                decodeStringIfValid(data, urlStartIndex, urlEndIndex, StandardCharsets.ISO_8859_1)

            return UrlLinkFrame("WXXX", description, url)
        }

        private fun decodeUrlLinkFrame(
            id3Data: ParsableByteArray, frameSize: Int, id: String?
        ): UrlLinkFrame {
            val data = ByteArray(frameSize)
            id3Data.readBytes(data, 0, frameSize)

            val urlEndIndex: Int = indexOfZeroByte(data, 0)
            val url = String(data, 0, urlEndIndex, StandardCharsets.ISO_8859_1)

            return UrlLinkFrame(id!!, null, url)
        }

        private fun decodePrivFrame(id3Data: ParsableByteArray, frameSize: Int): PrivFrame {
            val data = ByteArray(frameSize)
            id3Data.readBytes(data, 0, frameSize)

            val ownerEndIndex: Int = indexOfZeroByte(data, 0)
            val owner = String(data, 0, ownerEndIndex, StandardCharsets.ISO_8859_1)

            val privateDataStartIndex = ownerEndIndex + 1
            val privateData: ByteArray? = copyOfRangeIfValid(data, privateDataStartIndex, data.size)

            return PrivFrame(owner, privateData!!)
        }

        private fun decodeGeobFrame(id3Data: ParsableByteArray, frameSize: Int): GeobFrame {
            val encoding = id3Data.readUnsignedByte()
            val charset: Charset = getCharset(encoding)

            val data = ByteArray(frameSize - 1)
            id3Data.readBytes(data, 0, frameSize - 1)

            val mimeTypeEndIndex: Int = indexOfZeroByte(data, 0)
            val mimeType =
                normalizeMimeType(
                    String(data, 0, mimeTypeEndIndex, StandardCharsets.ISO_8859_1)
                )

            val filenameStartIndex = mimeTypeEndIndex + 1
            val filenameEndIndex: Int = indexOfTerminator(data, filenameStartIndex, encoding)
            val filename: String =
                decodeStringIfValid(data, filenameStartIndex, filenameEndIndex, charset)

            val descriptionStartIndex: Int = filenameEndIndex + delimiterLength(encoding)
            val descriptionEndIndex: Int = indexOfTerminator(data, descriptionStartIndex, encoding)
            val description: String =
                decodeStringIfValid(data, descriptionStartIndex, descriptionEndIndex, charset)

            val objectDataStartIndex: Int = descriptionEndIndex + delimiterLength(encoding)
            val objectData: ByteArray? = copyOfRangeIfValid(data, objectDataStartIndex, data.size)

            return GeobFrame(mimeType!!, filename, description, objectData!!)
        }

        private fun decodeApicFrame(
            id3Data: ParsableByteArray, frameSize: Int, majorVersion: Int
        ): ApicFrame {
            val encoding = id3Data.readUnsignedByte()
            val charset: Charset = getCharset(encoding)

            val data = ByteArray(frameSize - 1)
            id3Data.readBytes(data, 0, frameSize - 1)

            var mimeType: String?
            val mimeTypeEndIndex: Int
            if (majorVersion == 2) {
                mimeTypeEndIndex = 2
                mimeType =
                    "image/" + Ascii.toLowerCase(String(data, 0, 3, StandardCharsets.ISO_8859_1))
                if ("image/jpg" == mimeType) {
                    mimeType = "image/jpeg"
                }
            } else {
                mimeTypeEndIndex = indexOfZeroByte(data, 0)
                mimeType =
                    Ascii.toLowerCase(
                        String(
                            data,
                            0,
                            mimeTypeEndIndex,
                            StandardCharsets.ISO_8859_1
                        )
                    )
                if (mimeType.indexOf('/') == -1) {
                    mimeType = "image/" + mimeType
                }
            }

            val pictureType = data[mimeTypeEndIndex + 1].toInt() and 0xFF

            val descriptionStartIndex = mimeTypeEndIndex + 2
            val descriptionEndIndex: Int = indexOfTerminator(data, descriptionStartIndex, encoding)
            val description = String(
                data, descriptionStartIndex, descriptionEndIndex - descriptionStartIndex, charset
            )

            val pictureDataStartIndex: Int = descriptionEndIndex + delimiterLength(encoding)
            val pictureData: ByteArray? = copyOfRangeIfValid(data, pictureDataStartIndex, data.size)

            return ApicFrame(mimeType, description, pictureType, pictureData!!)
        }

        private fun decodeCommentFrame(id3Data: ParsableByteArray, frameSize: Int): CommentFrame? {
            if (frameSize < 4) {
                // Frame is malformed.
                return null
            }

            val encoding = id3Data.readUnsignedByte()
            val charset: Charset = getCharset(encoding)

            var data = ByteArray(3)
            id3Data.readBytes(data, 0, 3)
            val language = String(data, 0, 3)

            data = ByteArray(frameSize - 4)
            id3Data.readBytes(data, 0, frameSize - 4)

            val descriptionEndIndex: Int = indexOfTerminator(data, 0, encoding)
            val description = String(data, 0, descriptionEndIndex, charset)

            val textStartIndex: Int = descriptionEndIndex + delimiterLength(encoding)
            val textEndIndex: Int = indexOfTerminator(data, textStartIndex, encoding)
            val text: String = decodeStringIfValid(data, textStartIndex, textEndIndex, charset)

            return CommentFrame(language, description, text)
        }

        private fun decodeChapterFrame(
            id3Data: ParsableByteArray,
            frameSize: Int,
            majorVersion: Int,
            unsignedIntFrameSizeHack: Boolean,
            frameHeaderSize: Int,
            framePredicate: FramePredicate?
        ): ChapterFrame? {
            val framePosition = id3Data.getPosition()
            val chapterIdEndIndex: Int = indexOfZeroByte(id3Data.data, framePosition)
            val chapterId = String(
                id3Data.data,
                framePosition,
                chapterIdEndIndex - framePosition,
                StandardCharsets.ISO_8859_1
            )
            id3Data.setPosition(chapterIdEndIndex + 1)

            val startTime = id3Data.readInt()
            val endTime = id3Data.readInt()
            if (startTime > endTime) {
                return null
            }
            var startOffset = id3Data.readUnsignedInt()
            if (startOffset == 0xFFFFFFFFL) {
                startOffset = C.INDEX_UNSET.toLong()
            }
            var endOffset = id3Data.readUnsignedInt()
            if (endOffset == 0xFFFFFFFFL) {
                endOffset = C.INDEX_UNSET.toLong()
            }

            val subFrames = ArrayList<Id3Frame?>()
            val limit = framePosition + frameSize
            while (id3Data.getPosition() < limit) {
                val frame: Id3Frame? =
                    decodeFrame(
                        majorVersion,
                        id3Data,
                        unsignedIntFrameSizeHack,
                        frameHeaderSize,
                        framePredicate
                    )
                if (frame != null) {
                    subFrames.add(frame)
                }
            }

            val subFrameArray = subFrames.toTypedArray<Id3Frame?>()
            return ChapterFrame(
                chapterId,
                startTime,
                endTime,
                startOffset,
                endOffset,
                subFrameArray
            )
        }

        private fun decodeChapterTOCFrame(
            id3Data: ParsableByteArray,
            frameSize: Int,
            majorVersion: Int,
            unsignedIntFrameSizeHack: Boolean,
            frameHeaderSize: Int,
            framePredicate: FramePredicate?
        ): ChapterTocFrame {
            val framePosition = id3Data.getPosition()
            val elementIdEndIndex: Int = indexOfZeroByte(id3Data.data, framePosition)
            val elementId = String(
                id3Data.data,
                framePosition,
                elementIdEndIndex - framePosition,
                StandardCharsets.ISO_8859_1
            )
            id3Data.setPosition(elementIdEndIndex + 1)

            val ctocFlags = id3Data.readUnsignedByte()
            val isRoot = (ctocFlags and 0x0002) != 0
            val isOrdered = (ctocFlags and 0x0001) != 0

            val childCount = id3Data.readUnsignedByte()
            val children = arrayOfNulls<String>(childCount)
            for (i in 0..<childCount) {
                val startIndex = id3Data.getPosition()
                val endIndex: Int = indexOfZeroByte(id3Data.data, startIndex)
                children[i] = String(
                    id3Data.data,
                    startIndex,
                    endIndex - startIndex,
                    StandardCharsets.ISO_8859_1
                )
                id3Data.setPosition(endIndex + 1)
            }

            val subFrames = ArrayList<Id3Frame?>()
            val limit = framePosition + frameSize
            while (id3Data.getPosition() < limit) {
                val frame: Id3Frame? =
                    decodeFrame(
                        majorVersion,
                        id3Data,
                        unsignedIntFrameSizeHack,
                        frameHeaderSize,
                        framePredicate
                    )
                if (frame != null) {
                    subFrames.add(frame)
                }
            }

            val subFrameArray = subFrames.toTypedArray<Id3Frame?>()
            return ChapterTocFrame(elementId, isRoot, isOrdered, children, subFrameArray)
        }

        private fun decodeMlltFrame(id3Data: ParsableByteArray, frameSize: Int): MlltFrame {
            // See ID3v2.4.0 native frames subsection 4.6.
            val mpegFramesBetweenReference = id3Data.readUnsignedShort()
            val bytesBetweenReference = id3Data.readUnsignedInt24()
            val millisecondsBetweenReference = id3Data.readUnsignedInt24()
            val bitsForBytesDeviation = id3Data.readUnsignedByte()
            val bitsForMillisecondsDeviation = id3Data.readUnsignedByte()

            val references = ParsableBitArray()
            references.reset(id3Data)
            val referencesBits = 8 * (frameSize - 10)
            val bitsPerReference = bitsForBytesDeviation + bitsForMillisecondsDeviation
            val referencesCount = referencesBits / bitsPerReference
            val bytesDeviations = IntArray(referencesCount)
            val millisecondsDeviations = IntArray(referencesCount)
            for (i in 0..<referencesCount) {
                val bytesDeviation = references.readBits(bitsForBytesDeviation)
                val millisecondsDeviation = references.readBits(bitsForMillisecondsDeviation)
                bytesDeviations[i] = bytesDeviation
                millisecondsDeviations[i] = millisecondsDeviation
            }

            return MlltFrame(
                mpegFramesBetweenReference,
                bytesBetweenReference,
                millisecondsBetweenReference,
                bytesDeviations,
                millisecondsDeviations
            )
        }

        private fun decodeBinaryFrame(
            id3Data: ParsableByteArray, frameSize: Int, id: String?
        ): BinaryFrame {
            val frame = ByteArray(frameSize)
            id3Data.readBytes(frame, 0, frameSize)

            return BinaryFrame(id!!, frame)
        }

        private fun removeUnsynchronization(data: ParsableByteArray, length: Int): Int {
            var length = length
            val bytes = data.data
            val startPosition = data.getPosition()
            var i = startPosition
            while (i + 1 < startPosition + length) {
                if ((bytes[i].toInt() and 0xFF) == 0xFF && bytes[i + 1].toInt() == 0x00) {
                    val relativePosition = i - startPosition
                    System.arraycopy(bytes, i + 2, bytes, i + 1, length - relativePosition - 2)
                    length--
                }
                i++
            }
            return length
        }

        private fun getCharset(encodingByte: Int): Charset {
            when (encodingByte) {
                ID3_TEXT_ENCODING_UTF_16 -> return StandardCharsets.UTF_16
                ID3_TEXT_ENCODING_UTF_16BE -> return StandardCharsets.UTF_16BE
                ID3_TEXT_ENCODING_UTF_8 -> return StandardCharsets.UTF_8
                ID3_TEXT_ENCODING_ISO_8859_1 -> return StandardCharsets.ISO_8859_1
                else -> return StandardCharsets.ISO_8859_1
            }
        }

        private fun getFrameId(
            majorVersion: Int, frameId0: Int, frameId1: Int, frameId2: Int, frameId3: Int
        ): String {
            return if (majorVersion == 2) String.format(
                Locale.US,
                "%c%c%c",
                frameId0,
                frameId1,
                frameId2
            ) else String.format(
                Locale.US, "%c%c%c%c", frameId0, frameId1, frameId2, frameId3
            )
        }

        private fun indexOfTerminator(data: ByteArray, fromIndex: Int, encoding: Int): Int {
            var terminationPos: Int = indexOfZeroByte(data, fromIndex)

            // For single byte encoding charsets, we're done.
            if (encoding == ID3_TEXT_ENCODING_ISO_8859_1 || encoding == ID3_TEXT_ENCODING_UTF_8) {
                return terminationPos
            }

            // Otherwise ensure an even offset from the start, and look for a second zero byte.
            while (terminationPos < data.size - 1) {
                if ((terminationPos - fromIndex) % 2 == 0 && data[terminationPos + 1] == 0.toByte()) {
                    return terminationPos
                }
                terminationPos = indexOfZeroByte(data, terminationPos + 1)
            }

            return data.size
        }

        private fun indexOfZeroByte(data: ByteArray, fromIndex: Int): Int {
            for (i in fromIndex..<data.size) {
                if (data[i] == 0.toByte()) {
                    return i
                }
            }
            return data.size
        }

        private fun delimiterLength(encodingByte: Int): Int {
            return if (encodingByte == ID3_TEXT_ENCODING_ISO_8859_1 || encodingByte == ID3_TEXT_ENCODING_UTF_8)
                1
            else
                2
        }

        private fun copyOfRangeIfValid(data: ByteArray, from: Int, to: Int): ByteArray? {
            if (to <= from) {
                // Invalid or zero length range.
                return Util.EMPTY_BYTE_ARRAY
            }
            return Arrays.copyOfRange(data, from, to)
        }

        private fun decodeStringIfValid(
            data: ByteArray,
            from: Int,
            to: Int,
            charset: Charset
        ): String {
            if (to <= from || to > data.size) {
                return ""
            }
            return String(data, from, to - from, charset)
        }
    }
}
