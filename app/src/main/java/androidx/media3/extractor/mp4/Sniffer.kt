
package androidx.media3.extractor.mp4

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.container.Mp4Box
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.SniffFailure
import java.io.IOException

object Sniffer {
    
    const val BRAND_QUICKTIME: Int = 0x71742020

    private val SEARCH_LENGTH = 4 * 1024

    private val COMPATIBLE_BRANDS = intArrayOf(
        0x69736f6d,  // isom
        0x69736f32,  // iso2
        0x69736f33,  // iso3
        0x69736f34,  // iso4
        0x69736f35,  // iso5
        0x69736f36,  // iso6
        0x69736f39,  // iso9
        0x61766331,  // avc1
        0x68766331,  // hvc1
        0x68657631,  // hev1
        0x61763031,  // av01
        0x6d703431,  // mp41
        0x6d703432,  // mp42
        0x33673261,  // 3g2a
        0x33673262,  // 3g2b
        0x33677236,  // 3gr6
        0x33677336,  // 3gs6
        0x33676536,  // 3ge6
        0x33676736,  // 3gg6
        0x4d345620,  // M4V[space]
        0x4d344120,  // M4A[space]
        0x66347620,  // f4v[space]
        0x6b646469,  // kddi
        0x4d345650,  // M4VP
        BRAND_QUICKTIME,  // qt[space][space]
        0x4d534e56,  // MSNV, Sony PSP
        0x64627931,  // dby1, Dolby Vision
        0x69736d6c,  // isml
        0x70696666,  // piff
    )

    @Throws(IOException::class)
    fun sniffFragmented(input: ExtractorInput): SniffFailure? {
        return sniffInternal(input,  true)
    }

    @Throws(IOException::class)
    fun sniffUnfragmented(input: ExtractorInput): SniffFailure? {
        return sniffInternal(input,  false)
    }

    @Throws(IOException::class)
    private fun sniffInternal(input: ExtractorInput, fragmented: Boolean): SniffFailure? {
        val inputLength = input.length
        var bytesToSearch =
            (if (inputLength == C.LENGTH_UNSET.toLong() || inputLength > SEARCH_LENGTH)
                SEARCH_LENGTH
                    .toLong()
            else
                inputLength).toInt()

        val buffer = ParsableByteArray(64)
        var bytesSearched = 0
        var foundGoodFileType = false
        var isFragmented = false
        while (bytesSearched < bytesToSearch) {
            // Read an atom header.
            var headerSize = Mp4Box.HEADER_SIZE
            buffer.reset(headerSize)
            val success =
                input.peekFully(buffer.data, 0, headerSize,  true)
            if (!success) {
                // We've reached the end of the file.
                break
            }
            var atomSize = buffer.readUnsignedInt()
            val atomType = buffer.readInt()
            if (atomSize == Mp4Box.DEFINES_LARGE_SIZE.toLong()) {
                // Read the large atom size.
                headerSize = Mp4Box.LONG_HEADER_SIZE
                input.peekFully(
                    buffer.data,
                    Mp4Box.HEADER_SIZE,
                    Mp4Box.LONG_HEADER_SIZE - Mp4Box.HEADER_SIZE
                )
                buffer.setLimit(Mp4Box.LONG_HEADER_SIZE)
                atomSize = buffer.readLong()
            } else if (atomSize == Mp4Box.EXTENDS_TO_END_SIZE.toLong()) {
                // The atom extends to the end of the file.
                val fileEndPosition = input.length
                if (fileEndPosition != C.LENGTH_UNSET.toLong()) {
                    atomSize = fileEndPosition - input.peekPosition + headerSize
                }
            }

            if (atomSize < headerSize) {
                if (atomType == Mp4Box.TYPE_free && headerSize == Mp4Box.HEADER_SIZE) {
                    // Workaround for writers that could create a malformed 'free' box with a size less than
                    // its header, causing file corruption. [See internal: b/438187097].
                    atomSize = headerSize.toLong()
                } else {
                    // The file is invalid because the atom size is too small for its header.
                    return AtomSizeTooSmallSniffFailure(atomType, atomSize, headerSize)
                }
            }
            bytesSearched += headerSize

            if (atomType == Mp4Box.TYPE_moov || atomType == Mp4Box.TYPE_uuid) {
                // These boxes can both be quite large, so we increase the search size, either to sniff
                // additional boxes after the uuid box, or to make sure we don't miss an mvex box inside
                // the moov.
                bytesToSearch += atomSize.toInt()
                if (inputLength != C.LENGTH_UNSET.toLong() && bytesToSearch > inputLength) {
                    // Make sure we don't exceed the file size.
                    bytesToSearch = inputLength.toInt()
                }
                if (atomType == Mp4Box.TYPE_moov) {
                    // Check for an mvex atom inside the moov atom to identify whether the file is fragmented.
                    continue
                }
            }

            // Peek inside the boxes that will lead to stbl, so that a very large stbl box can be used to
            // short-circuit the fragmented/non-fragmented decision.
            if (atomType == Mp4Box.TYPE_trak || atomType == Mp4Box.TYPE_mdia || atomType == Mp4Box.TYPE_minf) {
                continue
            }

            if (atomType == Mp4Box.TYPE_moof || atomType == Mp4Box.TYPE_mvex) {
                // The movie is fragmented. Stop searching as we must have read any ftyp atom already.
                isFragmented = true
                break
            }

            if (atomType == Mp4Box.TYPE_mdat) {
                // The original QuickTime specification did not require files to begin with the ftyp atom.
                // See https://developer.apple.com/standards/qtff-2001.pdf.
                foundGoodFileType = true
            }

            if (atomType == Mp4Box.TYPE_stbl && atomSize > 1000000) {
                // We exit early as soon as we found a moof atom, so this must be nested inside a moov atom.
                // An stbl this large would only be in a non-fragmented MP4.
                isFragmented = false
                break
            }

            if (bytesSearched + atomSize - headerSize >= bytesToSearch) {
                // Stop searching as peeking this atom would exceed the search limit.
                break
            }

            val atomDataSize = (atomSize - headerSize).toInt()
            bytesSearched += atomDataSize
            if (atomType == Mp4Box.TYPE_ftyp) {
                // Parse the atom and check the file type/brand is compatible with the extractors.
                if (atomDataSize < 8) {
                    return AtomSizeTooSmallSniffFailure(atomType, atomDataSize.toLong(), 8)
                }
                buffer.reset(atomDataSize)
                input.peekFully(buffer.data, 0, atomDataSize)
                val majorBrand = buffer.readInt()
                if (isCompatibleBrand(majorBrand)) {
                    foundGoodFileType = true
                }
                // Skip the minorVersion.
                buffer.skipBytes(4)
                val compatibleBrandsCount = buffer.bytesLeft() / 4
                var compatibleBrands: IntArray? = null
                if (!foundGoodFileType && compatibleBrandsCount > 0) {
                    compatibleBrands = IntArray(compatibleBrandsCount)
                    for (i in 0..<compatibleBrandsCount) {
                        compatibleBrands[i] = buffer.readInt()
                        if (isCompatibleBrand(compatibleBrands[i])) {
                            foundGoodFileType = true
                            break
                        }
                    }
                }
                if (!foundGoodFileType) {
                    // The types were not compatible and there is only one ftyp atom, so reject the file.
                    return UnsupportedBrandsSniffFailure(majorBrand, compatibleBrands)
                }
            } else if (atomDataSize != 0) {
                // Skip the atom.
                input.advancePeekPosition(atomDataSize)
            }
        }
        if (!foundGoodFileType) {
            return NoDeclaredBrandSniffFailure.INSTANCE
        } else if (fragmented != isFragmented) {
            return if (isFragmented)
                IncorrectFragmentationSniffFailure.FILE_FRAGMENTED
            else
                IncorrectFragmentationSniffFailure.FILE_NOT_FRAGMENTED
        } else {
            return null
        }
    }

    private fun isCompatibleBrand(brand: Int): Boolean {
        if (brand ushr 8 == 0x00336770) {
            // Brand starts with '3gp'.
            return true
        }
        for (compatibleBrand in COMPATIBLE_BRANDS) {
            if (compatibleBrand == brand) {
                return true
            }
        }
        return false
    }
}
