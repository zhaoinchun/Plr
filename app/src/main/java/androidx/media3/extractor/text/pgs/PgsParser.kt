package androidx.media3.extractor.text.pgs

import android.graphics.Bitmap
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.maybeInflate
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import java.util.Arrays
import java.util.zip.Inflater
import kotlin.math.min

class PgsParser : SubtitleParser {
    private val buffer: ParsableByteArray
    private val inflatedBuffer: ParsableByteArray
    private val cueBuilder: CueBuilder
    private var inflater: Inflater? = null

    init {
        buffer = ParsableByteArray()
        inflatedBuffer = ParsableByteArray()
        cueBuilder = CueBuilder()
    }

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: (CuesWithTiming)-> Unit
    ) {
        buffer.reset(data!!,  offset + length)
        buffer.setPosition(offset)
        if (inflater == null) {
            inflater = Inflater()
        }
        if (maybeInflate(buffer, inflatedBuffer, inflater)) {
            buffer.reset(inflatedBuffer.data, inflatedBuffer.limit())
        }
        cueBuilder.reset()
        val cues = ArrayList<Cue>()
        while (buffer.bytesLeft() >= 3) {
            val cue: Cue? = readNextSection(buffer, cueBuilder)
            if (cue != null) {
                cues.add(cue)
            }
        }
        output(
            CuesWithTiming(cues,  C.TIME_UNSET,  C.TIME_UNSET)
        )
    }

    private class CueBuilder {
        private val bitmapData: ParsableByteArray
        private val colors: IntArray

        private var colorsSet = false
        private var planeWidth = 0
        private var planeHeight = 0
        private var bitmapX = 0
        private var bitmapY = 0
        private var bitmapWidth = 0
        private var bitmapHeight = 0

        init {
            bitmapData = ParsableByteArray()
            colors = IntArray(256)
        }

        fun parsePaletteSection(buffer: ParsableByteArray, sectionLength: Int) {
            if ((sectionLength % 5) != 2) {
                // Section must be two bytes then a whole number of (index, Y, Cr, Cb, alpha) entries.
                return
            }
            buffer.skipBytes(2)

            Arrays.fill(colors, 0)
            val entryCount = sectionLength / 5
            for (i in 0..<entryCount) {
                val index = buffer.readUnsignedByte()
                val y = buffer.readUnsignedByte()
                val cr = buffer.readUnsignedByte()
                val cb = buffer.readUnsignedByte()
                val a = buffer.readUnsignedByte()
                val r = (y + (1.40200 * (cr - 128))).toInt()
                val g = (y - (0.34414 * (cb - 128)) - (0.71414 * (cr - 128))).toInt()
                val b = (y + (1.77200 * (cb - 128))).toInt()
                colors[index] =
                    ((a shl 24)
                            or (constrainValue(r, 0, 255) shl 16)
                            or (constrainValue(g, 0, 255) shl 8)
                            or constrainValue(b, 0, 255))
            }
            colorsSet = true
        }

        fun parseBitmapSection(buffer: ParsableByteArray, sectionLength: Int) {
            var sectionLength = sectionLength
            if (sectionLength < 4) {
                return
            }
            buffer.skipBytes(3) // Id (2 bytes), version (1 byte).
            val isBaseSection = (0x80 and buffer.readUnsignedByte()) != 0
            sectionLength -= 4

            if (isBaseSection) {
                if (sectionLength < 7) {
                    return
                }
                val totalLength = buffer.readUnsignedInt24()
                if (totalLength < 4) {
                    return
                }
                bitmapWidth = buffer.readUnsignedShort()
                bitmapHeight = buffer.readUnsignedShort()
                bitmapData.reset(totalLength - 4)
                sectionLength -= 7
            }

            val position = bitmapData.getPosition()
            val limit = bitmapData.limit()
            if (position < limit && sectionLength > 0) {
                val bytesToRead = min(sectionLength, limit - position)
                buffer.readBytes(bitmapData.data, position, bytesToRead)
                bitmapData.setPosition(position + bytesToRead)
            }
        }

        fun parseIdentifierSection(buffer: ParsableByteArray, sectionLength: Int) {
            if (sectionLength < 19) {
                return
            }
            planeWidth = buffer.readUnsignedShort()
            planeHeight = buffer.readUnsignedShort()
            buffer.skipBytes(11)
            bitmapX = buffer.readUnsignedShort()
            bitmapY = buffer.readUnsignedShort()
        }

        fun build(): Cue? {
            if (planeWidth == 0 || planeHeight == 0 || bitmapWidth == 0 || bitmapHeight == 0 || bitmapData.limit() == 0 || bitmapData.getPosition() != bitmapData.limit() || !colorsSet) {
                return null
            }
            // Build the bitmapData.
            bitmapData.setPosition(0)
            val argbBitmapData = IntArray(bitmapWidth * bitmapHeight)
            var argbBitmapDataIndex = 0
            while (argbBitmapDataIndex < argbBitmapData.size) {
                val colorIndex = bitmapData.readUnsignedByte()
                if (colorIndex != 0) {
                    argbBitmapData[argbBitmapDataIndex++] = colors[colorIndex]
                } else {
                    val switchBits = bitmapData.readUnsignedByte()
                    if (switchBits != 0) {
                        val runLength =
                            if ((switchBits and 0x40) == 0)
                                (switchBits and 0x3F)
                            else
                                (((switchBits and 0x3F) shl 8) or bitmapData.readUnsignedByte())
                        val color =
                            if ((switchBits and 0x80) == 0) colors[0] else colors[bitmapData.readUnsignedByte()]
                        Arrays.fill(
                            argbBitmapData,
                            argbBitmapDataIndex,
                            argbBitmapDataIndex + runLength,
                            color
                        )
                        argbBitmapDataIndex += runLength
                    }
                }
            }
            val bitmap =
                Bitmap.createBitmap(
                    argbBitmapData,
                    bitmapWidth,
                    bitmapHeight,
                    Bitmap.Config.ARGB_8888
                )
            // Build the cue.
            return Cue.Builder()
                .setBitmap(bitmap)
                .setPosition(bitmapX.toFloat() / planeWidth)
                .setPositionAnchor(Cue.ANCHOR_TYPE_START)
                .setLine(bitmapY.toFloat() / planeHeight, Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(Cue.ANCHOR_TYPE_START)
                .setSize(bitmapWidth.toFloat() / planeWidth)
                .setBitmapHeight(bitmapHeight.toFloat() / planeHeight)
                .build()
        }

        fun reset() {
            planeWidth = 0
            planeHeight = 0
            bitmapX = 0
            bitmapY = 0
            bitmapWidth = 0
            bitmapHeight = 0
            bitmapData.reset(0)
            colorsSet = false
        }
    }

    override val cueReplacementBehavior: Int
        get() = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

    companion object {
        
        val cueReplacementBehavior: @Format.CueReplacementBehavior Int =
            Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

        private const val SECTION_TYPE_PALETTE = 0x14
        private const val SECTION_TYPE_BITMAP_PICTURE = 0x15
        private const val SECTION_TYPE_IDENTIFIER = 0x16
        private const val SECTION_TYPE_END = 0x80

        private fun readNextSection(buffer: ParsableByteArray, cueBuilder: CueBuilder): Cue? {
            val limit = buffer.limit()
            val sectionType = buffer.readUnsignedByte()
            val sectionLength = buffer.readUnsignedShort()

            val nextSectionPosition = buffer.getPosition() + sectionLength
            if (nextSectionPosition > limit) {
                buffer.setPosition(limit)
                return null
            }

            var cue: Cue? = null
            when (sectionType) {
                SECTION_TYPE_PALETTE -> cueBuilder.parsePaletteSection(buffer, sectionLength)
                SECTION_TYPE_BITMAP_PICTURE -> cueBuilder.parseBitmapSection(buffer, sectionLength)
                SECTION_TYPE_IDENTIFIER -> cueBuilder.parseIdentifierSection(buffer, sectionLength)
                SECTION_TYPE_END -> {
                    cue = cueBuilder.build()
                    cueBuilder.reset()
                }

                else -> {}
            }

            buffer.setPosition(nextSectionPosition)
            return cue
        }
    }
}
