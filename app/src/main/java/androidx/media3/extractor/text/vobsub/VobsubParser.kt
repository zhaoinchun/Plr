package androidx.media3.extractor.text.vobsub

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util.maybeInflate
import androidx.media3.common.util.Util.split
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.zip.Inflater
import kotlin.math.min

class VobsubParser(initializationData: List<ByteArray>) : SubtitleParser {
    private val scratch: ParsableByteArray
    private val inflatedScratch: ParsableByteArray
    private val cueBuilder: CueBuilder
    private var inflater: Inflater? = null

    init {
        scratch = ParsableByteArray()
        inflatedScratch = ParsableByteArray()
        cueBuilder = CueBuilder()
        cueBuilder.parseIdx(kotlin.text.String(initializationData[0]!!, StandardCharsets.UTF_8))
    }

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: (CuesWithTiming)-> Unit
    ) {
        scratch.reset(data!!, offset + length)
        scratch.setPosition(offset)
        output(parse())
    }

    private fun parse(): CuesWithTiming {
        if (inflater == null) {
            inflater = Inflater()
        }
        if (maybeInflate(scratch, inflatedScratch, inflater)) {
            scratch.reset(inflatedScratch.data, inflatedScratch.limit())
        }
        cueBuilder.reset()
        val bytesLeft = scratch.bytesLeft()
        if (bytesLeft < 2 || scratch.readUnsignedShort() != bytesLeft) {
            return EMPTY_CUES
        }
        cueBuilder.parseSpuControlSequenceTable(scratch)
        val result = cueBuilder.build(scratch)
        val cueDurationUs: Long
        if (cueBuilder.endTimeUs != C.TIME_UNSET) {
            if (cueBuilder.startTimeUs != C.TIME_UNSET && cueBuilder.endTimeUs > cueBuilder.startTimeUs) {
                cueDurationUs = cueBuilder.endTimeUs - cueBuilder.startTimeUs
            } else {
                cueDurationUs = cueBuilder.endTimeUs
            }
        } else {
            cueDurationUs = C.TIME_UNSET
        }
        return CuesWithTiming(
            if (result != null) listOf(result) else listOf(),
            cueBuilder.startTimeUs,
            cueDurationUs
        )
    }

    private class CueBuilder {
        private val colors: IntArray

         var startTimeUs: Long
         var endTimeUs: Long
        private var hasPlane = false
        private var hasColors = false
        private var palette:IntArray? = null
        private var planeWidth = 0
        private var planeHeight = 0
        private var boundingBox: Rect? = null
        private var dataOffset0: Int
        private var dataOffset1: Int

        init {
            startTimeUs = C.TIME_UNSET
            endTimeUs = C.TIME_UNSET
            colors = IntArray(4)
            dataOffset0 = C.INDEX_UNSET
            dataOffset1 = C.INDEX_UNSET
        }

        fun parseIdx(idx: String) {
            for (line in split(idx.trim { it <= ' ' }, "\\r?\\n")) {
                if (line.startsWith("palette: ")) {
                    val values: Array<String> = split(line.substring("palette: ".length), ",")
                    palette = IntArray(values.size)

                    for (i in values.indices) {
                        palette!![i] = parseColor(values[i]!!.trim { it <= ' ' })
                    }
                } else if (line.startsWith("size: ")) {
                    // We need this line to calculate the relative positions and size required when building
                    // the Cue below.
                    val sizes: Array<String> =
                        split(line.substring("size: ".length).trim { it <= ' ' }, "x")
                    if (sizes.size != 2) {
                        w(TAG, "Ignoring malformed IDX size line: '" + line + "'")
                        continue
                    }
                    try {
                        planeWidth = sizes[0]!!.toInt()
                        planeHeight = sizes[1]!!.toInt()
                        hasPlane = true
                    } catch (e: RuntimeException) {
                        w(TAG, "Parsing IDX failed", e)
                    }
                }
            }
        }

        fun parseSpuControlSequenceTable(buffer: ParsableByteArray) {
            // Give up if we don't have the color palette or the video size.
            if (palette == null) {
                w(TAG, "Skipping SPU (no palette)")
                return
            }
            if (!hasPlane) {
                w(TAG, "Skipping SPU (no plane)")
                return
            }
            // The SPU packet starts before the size that has already been read.
            val spuStartOffset = buffer.getPosition() - 2
            val controlStartOffset = buffer.readUnsignedShort()
            // Skip over the image data to reach the control sequence table. The image data will be
            // parsed later in CueBuilder.build().
            buffer.setPosition(spuStartOffset + controlStartOffset)
            var hasNextSequence: Boolean
            do {
                hasNextSequence = parseControlSequence(buffer, spuStartOffset)
            } while (hasNextSequence)
        }

        @RequiresNonNull("this.palette")
        fun parseControlSequence(buffer: ParsableByteArray, spuStartPosition: Int): Boolean {
            if (buffer.bytesLeft() < 4) {
                return false
            }
            val sequenceStartPosition = buffer.getPosition()
            // SPU 'date' is stored in 100ths of a second
            val spuTimeUs = buffer.readUnsignedShort() * 10000
            val nextSequencePosition = spuStartPosition + buffer.readUnsignedShort()
            val hasNextSequence =
                nextSequencePosition != sequenceStartPosition && nextSequencePosition < buffer.limit()
            val sequenceEndPosition = if (hasNextSequence) nextSequencePosition else buffer.limit()

            var hasNextCommand = true
            while (buffer.getPosition() < sequenceEndPosition && hasNextCommand) {
                hasNextCommand = parseCommand(spuTimeUs.toLong(), buffer)
            }
            if (hasNextSequence) {
                buffer.setPosition(nextSequencePosition)
            }
            return hasNextSequence
        }

        @RequiresNonNull("this.palette")
        fun parseCommand(spuTimeUs: Long, buffer: ParsableByteArray): Boolean {
            val command = buffer.readUnsignedByte()
            when (command) {
                CMD_START -> {
                    startTimeUs = spuTimeUs
                    return true
                }

                CMD_COLORS -> return parseControlColors(buffer)
                CMD_ALPHA -> return parseControlAlpha(buffer)
                CMD_AREA -> return parseControlArea(buffer)
                CMD_OFFSETS -> return parseControlOffsets(buffer)
                CMD_STOP -> {
                    endTimeUs = spuTimeUs
                    return true
                }

                CMD_FORCE_START ->           // ignore unused command without arguments
                    return true

                CMD_END -> return false
                else -> {
                    w(TAG, "Unrecognized command: " + command)
                    return false
                }
            }
        }

        @RequiresNonNull("this.palette")
        fun parseControlColors(buffer: ParsableByteArray): Boolean {
            if (buffer.bytesLeft() < 2) {
                w(TAG, "Incomplete color command")
                return false
            }

            val byte0 = buffer.readUnsignedByte()
            val byte1 = buffer.readUnsignedByte()

            colors[3] = Companion.getColor(palette!!, byte0 shr 4)
            colors[2] = Companion.getColor(palette!!, byte0 and 0xf)
            colors[1] = Companion.getColor(palette!!, byte1 shr 4)
            colors[0] = Companion.getColor(palette!!, byte1 and 0xf)
            hasColors = true

            return true
        }

        fun parseControlAlpha(buffer: ParsableByteArray): Boolean {
            if (buffer.bytesLeft() < 2) {
                w(TAG, "Incomplete alpha command")
                return false
            }
            if (!hasColors) {
                w(TAG, "Ignoring alpha command before color command")
                return false
            }

            val byte0 = buffer.readUnsignedByte()
            val byte1 = buffer.readUnsignedByte()

            colors[3] = setAlpha(colors[3], (byte0 shr 4))
            colors[2] = setAlpha(colors[2], (byte0 and 0xf))
            colors[1] = setAlpha(colors[1], (byte1 shr 4))
            colors[0] = setAlpha(colors[0], (byte1 and 0xf))

            return true
        }

        fun parseControlArea(buffer: ParsableByteArray): Boolean {
            if (buffer.bytesLeft() < 6) {
                w(TAG, "Incomplete area command")
                return false
            }

            val byte0 = buffer.readUnsignedByte()
            val byte1 = buffer.readUnsignedByte()
            val byte2 = buffer.readUnsignedByte()

            val left = (byte0 shl 4) or (byte1 shr 4)
            val right = ((byte1 and 0xf) shl 8) or byte2

            val byte3 = buffer.readUnsignedByte()
            val byte4 = buffer.readUnsignedByte()
            val byte5 = buffer.readUnsignedByte()

            val top = (byte3 shl 4) or (byte4 shr 4)
            val bottom = ((byte4 and 0xf) shl 8) or byte5

            boundingBox = Rect(left, top, right + 1, bottom + 1)

            return true
        }

        fun parseControlOffsets(buffer: ParsableByteArray): Boolean {
            if (buffer.bytesLeft() < 4) {
                w(TAG, "Incomplete offsets command")
                return false
            }

            dataOffset0 = buffer.readUnsignedShort()
            dataOffset1 = buffer.readUnsignedShort()

            return true
        }

        fun build(buffer: ParsableByteArray): Cue? {
            if (palette == null || !hasPlane || !hasColors || boundingBox == null || dataOffset0 == C.INDEX_UNSET || dataOffset1 == C.INDEX_UNSET || boundingBox!!.width() < 2 || boundingBox!!.height() < 2) {
                return null
            }
            val boundingBox = this.boundingBox
            val bitmapData = IntArray(boundingBox!!.width() * boundingBox.height())
            val bitBuffer = ParsableBitArray()

            buffer.setPosition(dataOffset0)
            bitBuffer.reset(buffer)
            parseRleData(bitBuffer,  true, boundingBox, bitmapData)
            buffer.setPosition(dataOffset1)
            bitBuffer.reset(buffer)
            parseRleData(bitBuffer,  false, boundingBox, bitmapData)

            val bitmap =
                Bitmap.createBitmap(
                    bitmapData,
                    boundingBox.width(),
                    boundingBox.height(),
                    Bitmap.Config.ARGB_8888
                )

            return Cue.Builder()
                .setBitmap(bitmap)
                .setPosition(boundingBox.left.toFloat() / planeWidth)
                .setPositionAnchor(Cue.ANCHOR_TYPE_START)
                .setLine(boundingBox.top.toFloat() / planeHeight, Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(Cue.ANCHOR_TYPE_START)
                .setSize(boundingBox.width().toFloat() / planeWidth)
                .setBitmapHeight(boundingBox.height().toFloat() / planeHeight)
                .build()
        }

        fun parseRleData(
            bitBuffer: ParsableBitArray,
            evenInterlace: Boolean,
            boundingBox: Rect,
            bitmapData: IntArray
        ) {
            val width = boundingBox.width()
            val height = boundingBox.height()
            var x = 0
            var y = if (evenInterlace) 0 else 1
            var outIndex = y * width
            val run = Run()

            while (true) {
                parseRun(bitBuffer, width, run)

                val length = min(run.length, width - x)
                if (length > 0) {
                    Arrays.fill(bitmapData, outIndex, outIndex + length, colors[run.colorIndex])
                    outIndex += length
                    x += length
                }
                if (x >= width) {
                    y += 2
                    if (y >= height) {
                        break
                    }
                    x = 0
                    outIndex = y * width
                    bitBuffer.byteAlign()
                }
            }
        }

        fun reset() {
            startTimeUs = C.TIME_UNSET
            endTimeUs = C.TIME_UNSET
            hasColors = false
            boundingBox = null
            dataOffset0 = C.INDEX_UNSET
            dataOffset1 = C.INDEX_UNSET
        }

        private class Run {
            var colorIndex: Int = 0
            var length: Int = 0
        }

        companion object {
            private const val CMD_FORCE_START = 0
            private const val CMD_START = 1
            private const val CMD_STOP = 2
            private const val CMD_COLORS = 3
            private const val CMD_ALPHA = 4
            private const val CMD_AREA = 5
            private const val CMD_OFFSETS = 6
            private const val CMD_END = 255

            private fun parseColor(value: String): Int {
                try {
                    return value.toInt(16)
                } catch (e: RuntimeException) {
                    w(TAG, "Parsing color failed", e)
                    return 0
                }
            }

            private fun getColor(palette: IntArray, index: Int): Int {
                return if (index >= 0 && index < palette.size) palette[index] else palette[0]
            }

            private fun setAlpha(color: Int, alpha: Int): Int {
                return ((color and 0x00ffffff) or ((alpha * 17) shl 24))
            }

            private fun parseRun(bitBuffer: ParsableBitArray, width: Int, output: Run) {
                var value = 0
                var test = 1

                while (value < test && test <= 0x40) {
                    if (bitBuffer.bitsLeft() < 4) {
                        output.colorIndex = C.INDEX_UNSET
                        output.length = 0
                        return
                    }
                    value = (value shl 4) or bitBuffer.readBits(4)
                    test = test shl 2
                }
                output.colorIndex = value and 3
                output.length = if (value < 4) width else (value shr 2)
            }
        }
    }

    override val cueReplacementBehavior: Int
        get() = VobsubParser.cueReplacementBehavior

    companion object {
        
        val cueReplacementBehavior: @Format.CueReplacementBehavior Int =
            Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

        private const val TAG = "VobsubParser"
        val EMPTY_CUES: CuesWithTiming = CuesWithTiming(
            listOf(),  C.TIME_UNSET,  C.TIME_UNSET
        )
    }
}
