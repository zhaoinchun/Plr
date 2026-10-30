package androidx.media3.extractor.text.dvb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray

import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.constrainValue
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser

import kotlin.math.min

class DvbParser(initializationData: List<ByteArray>) : SubtitleParser {
    private val defaultPaint: Paint
    private val fillRegionPaint: Paint
    private val canvas: Canvas
    private val defaultDisplayDefinition: DisplayDefinition
    private val defaultClutDefinition: ClutDefinition
    private val subtitleService: SubtitleService

    private var bitmap:Bitmap? = null

    init {
        val data = ParsableByteArray(initializationData[0]!!)
        val subtitleCompositionPage = data.readUnsignedShort()
        val subtitleAncillaryPage = data.readUnsignedShort()
        defaultPaint = Paint()
        defaultPaint.setStyle(Paint.Style.FILL_AND_STROKE)
        defaultPaint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC))
        defaultPaint.setPathEffect(null)
        fillRegionPaint = Paint()
        fillRegionPaint.setStyle(Paint.Style.FILL)
        fillRegionPaint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.DST_OVER))
        fillRegionPaint.setPathEffect(null)
        canvas = Canvas()
        defaultDisplayDefinition = DisplayDefinition(719, 575, 0, 719, 0, 575)
        defaultClutDefinition =
            ClutDefinition(
                0,
                generateDefault2BitClutEntries(),
                generateDefault4BitClutEntries(),
                generateDefault8BitClutEntries()
            )
        subtitleService = SubtitleService(subtitleCompositionPage, subtitleAncillaryPage)
    }

    override fun reset() {
        subtitleService.reset()
    }

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: (CuesWithTiming) -> Unit
    ) {
        val dataBitArray = ParsableBitArray(data!!,  offset + length)
        dataBitArray.position=(offset)
        output(parse(dataBitArray))
    }

    private fun parse(dataBitArray: ParsableBitArray): CuesWithTiming {
        while (dataBitArray.bitsLeft() >= 48 // sync_byte (8) + segment header (40)
            && dataBitArray.readBits(8) == 0x0F
        ) {
            parseSubtitlingSegment(dataBitArray, subtitleService)
        }

        val pageComposition = subtitleService.pageComposition
        if (pageComposition == null) {
            return CuesWithTiming(
                listOf(),  
                C.TIME_UNSET,  
                C.TIME_UNSET
            )
        }

        // Update the canvas bitmap if necessary.
        val displayDefinition: DisplayDefinition =
            (if (subtitleService.displayDefinition != null)
                subtitleService.displayDefinition
            else
                defaultDisplayDefinition)!!
        if (bitmap == null || displayDefinition.width + 1 != bitmap!!.getWidth() || displayDefinition.height + 1 != bitmap!!.getHeight()) {
            bitmap =
                Bitmap.createBitmap(
                    displayDefinition.width + 1,
                    displayDefinition.height + 1,
                    Bitmap.Config.ARGB_8888
                )
            canvas.setBitmap(bitmap)
        }

        // Build the cues.
        val cues: MutableList<Cue> = ArrayList<Cue>()
        val pageRegions = pageComposition.regions
        for (i in 0..<pageRegions.size()) {
            // Save clean clipping state.
            canvas.save()
            val pageRegion = pageRegions.valueAt(i)
            val regionId = pageRegions.keyAt(i)
            val regionComposition = subtitleService.regions.get(regionId)

            // Clip drawing to the current region and display definition window.
            val baseHorizontalAddress =
                pageRegion.horizontalAddress + displayDefinition.horizontalPositionMinimum
            val baseVerticalAddress =
                pageRegion.verticalAddress + displayDefinition.verticalPositionMinimum
            val clipRight =
                min(
                    baseHorizontalAddress + regionComposition.width,
                    displayDefinition.horizontalPositionMaximum
                )
            val clipBottom =
                min(
                    baseVerticalAddress + regionComposition.height,
                    displayDefinition.verticalPositionMaximum
                )
            canvas.clipRect(baseHorizontalAddress, baseVerticalAddress, clipRight, clipBottom)
            var clutDefinition = subtitleService.cluts.get(regionComposition.clutId)
            if (clutDefinition == null) {
                clutDefinition = subtitleService.ancillaryCluts.get(regionComposition.clutId)
                if (clutDefinition == null) {
                    clutDefinition = defaultClutDefinition
                }
            }

            val regionObjects = regionComposition.regionObjects
            for (j in 0..<regionObjects.size()) {
                val objectId = regionObjects.keyAt(j)
                val regionObject = regionObjects.valueAt(j)
                var objectData = subtitleService.objects.get(objectId)
                if (objectData == null) {
                    objectData = subtitleService.ancillaryObjects.get(objectId)
                }
                if (objectData != null) {
                    val paint = if (objectData.nonModifyingColorFlag) null else defaultPaint
                    paintPixelDataSubBlocks(
                        objectData,
                        clutDefinition,
                        regionComposition.depth,
                        baseHorizontalAddress + regionObject.horizontalPosition,
                        baseVerticalAddress + regionObject.verticalPosition,
                        paint,
                        canvas
                    )
                }
            }

            if (regionComposition.fillFlag) {
                val color: Int
                if (regionComposition.depth == REGION_DEPTH_8_BIT) {
                    color = clutDefinition.clutEntries8Bit[regionComposition.pixelCode8Bit]
                } else if (regionComposition.depth == REGION_DEPTH_4_BIT) {
                    color = clutDefinition.clutEntries4Bit[regionComposition.pixelCode4Bit]
                } else {
                    color = clutDefinition.clutEntries2Bit[regionComposition.pixelCode2Bit]
                }
                fillRegionPaint.setColor(color)
                canvas.drawRect(
                    baseHorizontalAddress.toFloat(),
                    baseVerticalAddress.toFloat(),
                    (baseHorizontalAddress + regionComposition.width).toFloat(),
                    (baseVerticalAddress + regionComposition.height).toFloat(),
                    fillRegionPaint
                )
            }

            cues.add(
                Cue.Builder()
                    .setBitmap(
                        Bitmap.createBitmap(
                            bitmap!!,
                            baseHorizontalAddress,
                            baseVerticalAddress,
                            regionComposition.width,
                            regionComposition.height
                        )
                    )
                    .setPosition(baseHorizontalAddress.toFloat() / displayDefinition.width)
                    .setPositionAnchor(Cue.ANCHOR_TYPE_START)
                    .setLine(
                        baseVerticalAddress.toFloat() / displayDefinition.height,
                        Cue.LINE_TYPE_FRACTION
                    )
                    .setLineAnchor(Cue.ANCHOR_TYPE_START)
                    .setSize(regionComposition.width.toFloat() / displayDefinition.width)
                    .setBitmapHeight(regionComposition.height.toFloat() / displayDefinition.height)
                    .build()
            )

            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            // Restore clean clipping state.
            canvas.restore()
        }

        return CuesWithTiming(
            cues,  C.TIME_UNSET,  C.TIME_UNSET
        )
    }

    // Private inner classes.
    
    private class SubtitleService(val subtitlePageId: Int, val ancillaryPageId: Int) {
        val regions: SparseArray<RegionComposition>
        val cluts: SparseArray<ClutDefinition?>
        val objects: SparseArray<ObjectData?>
        val ancillaryCluts: SparseArray<ClutDefinition?>
        val ancillaryObjects: SparseArray<ObjectData?>

        var displayDefinition: DisplayDefinition? = null
        var pageComposition: PageComposition? = null

        init {
            regions = SparseArray<RegionComposition>()
            cluts = SparseArray<ClutDefinition?>()
            objects = SparseArray<ObjectData?>()
            ancillaryCluts = SparseArray<ClutDefinition?>()
            ancillaryObjects = SparseArray<ObjectData?>()
        }

        fun reset() {
            regions.clear()
            cluts.clear()
            objects.clear()
            ancillaryCluts.clear()
            ancillaryObjects.clear()
            displayDefinition = null
            pageComposition = null
        }
    }

    private class DisplayDefinition(
        val width: Int,
        val height: Int,
        val horizontalPositionMinimum: Int,
        val horizontalPositionMaximum: Int,
        val verticalPositionMinimum: Int,
        val verticalPositionMaximum: Int
    )

    private class PageComposition(// TODO: Use this or remove it.
        val timeOutSecs: Int, val version: Int, val state: Int, val regions: SparseArray<PageRegion>
    )

    private class PageRegion(val horizontalAddress: Int, val verticalAddress: Int)

    private class RegionComposition(
        val id: Int,
        val fillFlag: Boolean,
        val width: Int,
        val height: Int,
        // TODO: Use this or remove it.
        val levelOfCompatibility: Int,
        val depth: Int,
        val clutId: Int,
        val pixelCode8Bit: Int,
        val pixelCode4Bit: Int,
        val pixelCode2Bit: Int,
        val regionObjects: SparseArray<RegionObject>
    ) {
        fun mergeFrom(otherRegionComposition: RegionComposition) {
            val otherRegionObjects = otherRegionComposition.regionObjects
            for (i in 0..<otherRegionObjects.size()) {
                regionObjects.put(otherRegionObjects.keyAt(i), otherRegionObjects.valueAt(i))
            }
        }
    }

    private class RegionObject(// TODO: Use this or remove it.
        val type: Int,
        // TODO: Use this or remove it.
        val provider: Int,
        val horizontalPosition: Int,
        val verticalPosition: Int,
        // TODO: Use this or remove it.
        val foregroundPixelCode: Int,
        // TODO: Use this or remove it.
        val backgroundPixelCode: Int
    ) {
    }

    private class ClutDefinition(
        id: Int,
        clutEntries2Bit: IntArray,
        clutEntries4Bit: IntArray,
        clutEntries8bit: IntArray
    ) {
        val id: Int
        val clutEntries2Bit: IntArray
        val clutEntries4Bit: IntArray
        val clutEntries8Bit: IntArray

        init {
            this.id = id
            this.clutEntries2Bit = clutEntries2Bit
            this.clutEntries4Bit = clutEntries4Bit
            this.clutEntries8Bit = clutEntries8bit
        }
    }

    private class ObjectData(
        id: Int,
        nonModifyingColorFlag: Boolean,
        topFieldData: ByteArray?,
        bottomFieldData: ByteArray?
    ) {
        val id: Int
        val nonModifyingColorFlag: Boolean
        val topFieldData: ByteArray?
        val bottomFieldData: ByteArray?

        init {
            this.id = id
            this.nonModifyingColorFlag = nonModifyingColorFlag
            this.topFieldData = topFieldData
            this.bottomFieldData = bottomFieldData
        }
    }

    override val cueReplacementBehavior: Int
        get() = DvbParser.cueReplacementBehavior

    companion object {
        
        val cueReplacementBehavior: @Format.CueReplacementBehavior Int =
            Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

        private const val TAG = "DvbParser"

        // Segment types, as defined by ETSI EN 300 743 Table 2
        private const val SEGMENT_TYPE_PAGE_COMPOSITION = 0x10
        private const val SEGMENT_TYPE_REGION_COMPOSITION = 0x11
        private const val SEGMENT_TYPE_CLUT_DEFINITION = 0x12
        private const val SEGMENT_TYPE_OBJECT_DATA = 0x13
        private const val SEGMENT_TYPE_DISPLAY_DEFINITION = 0x14

        // Page states, as defined by ETSI EN 300 743 Table 3
        private const val PAGE_STATE_NORMAL = 0 // Update. Only changed elements.

        // private static final int PAGE_STATE_ACQUISITION = 1; // Refresh. All elements.
        // private static final int PAGE_STATE_CHANGE = 2; // New. All elements.
        // Region depths, as defined by ETSI EN 300 743 Table 5
        // private static final int REGION_DEPTH_2_BIT = 1;
        private const val REGION_DEPTH_4_BIT = 2
        private const val REGION_DEPTH_8_BIT = 3

        // Object codings, as defined by ETSI EN 300 743 Table 8
        private const val OBJECT_CODING_PIXELS = 0
        private const val OBJECT_CODING_STRING = 1

        // Pixel-data types, as defined by ETSI EN 300 743 Table 9
        private const val DATA_TYPE_2BP_CODE_STRING = 0x10
        private const val DATA_TYPE_4BP_CODE_STRING = 0x11
        private const val DATA_TYPE_8BP_CODE_STRING = 0x12
        private const val DATA_TYPE_24_TABLE_DATA = 0x20
        private const val DATA_TYPE_28_TABLE_DATA = 0x21
        private const val DATA_TYPE_48_TABLE_DATA = 0x22
        private const val DATA_TYPE_END_LINE = 0xF0

        // Clut mapping tables, as defined by ETSI EN 300 743 10.4, 10.5, 10.6
        private val defaultMap2To4 =
            byteArrayOf(0x00.toByte(), 0x07.toByte(), 0x08.toByte(), 0x0F.toByte())
        private val defaultMap2To8 =
            byteArrayOf(0x00.toByte(), 0x77.toByte(), 0x88.toByte(), 0xFF.toByte())
        private val defaultMap4To8 = byteArrayOf(
            0x00.toByte(), 0x11.toByte(), 0x22.toByte(), 0x33.toByte(),
            0x44.toByte(), 0x55.toByte(), 0x66.toByte(), 0x77.toByte(),
            0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(),
            0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()
        )

        // Static parsing.
        
        private fun parseSubtitlingSegment(data: ParsableBitArray, service: SubtitleService) {
            val segmentType = data.readBits(8)
            val pageId = data.readBits(16)
            val dataFieldLength = data.readBits(16)
            val dataFieldLimit = data.bytePosition + dataFieldLength

            if ((dataFieldLength * 8) > data.bitsLeft()) {
                w(TAG, "Data field length exceeds limit")
                // Skip to the very end.
                data.skipBits(data.bitsLeft())
                return
            }

            when (segmentType) {
                SEGMENT_TYPE_DISPLAY_DEFINITION -> if (pageId == service.subtitlePageId) {
                    service.displayDefinition = parseDisplayDefinition(data)
                }

                SEGMENT_TYPE_PAGE_COMPOSITION -> if (pageId == service.subtitlePageId) {
                    val current = service.pageComposition
                    val pageComposition: PageComposition =
                        parsePageComposition(data, dataFieldLength)
                    if (pageComposition.state != PAGE_STATE_NORMAL) {
                        service.pageComposition = pageComposition
                        service.regions.clear()
                        service.cluts.clear()
                        service.objects.clear()
                    } else if (current != null && current.version != pageComposition.version) {
                        service.pageComposition = pageComposition
                    }
                }

                SEGMENT_TYPE_REGION_COMPOSITION -> {
                    val pageComposition = service.pageComposition
                    if (pageId == service.subtitlePageId && pageComposition != null) {
                        val regionComposition: RegionComposition =
                            parseRegionComposition(data, dataFieldLength)
                        if (pageComposition.state == PAGE_STATE_NORMAL) {
                            val existingRegionComposition =
                                service.regions.get(regionComposition.id)
                            if (existingRegionComposition != null) {
                                regionComposition.mergeFrom(existingRegionComposition)
                            }
                        }
                        service.regions.put(regionComposition.id, regionComposition)
                    }
                }

                SEGMENT_TYPE_CLUT_DEFINITION -> if (pageId == service.subtitlePageId) {
                    val clutDefinition: ClutDefinition = parseClutDefinition(data, dataFieldLength)
                    service.cluts.put(clutDefinition.id, clutDefinition)
                } else if (pageId == service.ancillaryPageId) {
                    val clutDefinition: ClutDefinition = parseClutDefinition(data, dataFieldLength)
                    service.ancillaryCluts.put(clutDefinition.id, clutDefinition)
                }

                SEGMENT_TYPE_OBJECT_DATA -> if (pageId == service.subtitlePageId) {
                    val objectData: ObjectData = parseObjectData(data)
                    service.objects.put(objectData.id, objectData)
                } else if (pageId == service.ancillaryPageId) {
                    val objectData: ObjectData = parseObjectData(data)
                    service.ancillaryObjects.put(objectData.id, objectData)
                }

                else -> {}
            }

            // Skip to the next segment.
            data.skipBytes(dataFieldLimit - data.bytePosition)
        }

        private fun parseDisplayDefinition(data: ParsableBitArray): DisplayDefinition {
            data.skipBits(4) // dds_version_number (4).
            val displayWindowFlag = data.readBit()
            data.skipBits(3) // Skip reserved.
            val width = data.readBits(16)
            val height = data.readBits(16)

            val horizontalPositionMinimum: Int
            val horizontalPositionMaximum: Int
            val verticalPositionMinimum: Int
            val verticalPositionMaximum: Int
            if (displayWindowFlag) {
                horizontalPositionMinimum = data.readBits(16)
                horizontalPositionMaximum = data.readBits(16)
                verticalPositionMinimum = data.readBits(16)
                verticalPositionMaximum = data.readBits(16)
            } else {
                horizontalPositionMinimum = 0
                horizontalPositionMaximum = width
                verticalPositionMinimum = 0
                verticalPositionMaximum = height
            }

            return DisplayDefinition(
                width,
                height,
                horizontalPositionMinimum,
                horizontalPositionMaximum,
                verticalPositionMinimum,
                verticalPositionMaximum
            )
        }

        private fun parsePageComposition(data: ParsableBitArray, length: Int): PageComposition {
            val timeoutSecs = data.readBits(8)
            val version = data.readBits(4)
            val state = data.readBits(2)
            data.skipBits(2)
            var remainingLength = length - 2

            val regions = SparseArray<PageRegion>()
            while (remainingLength > 0) {
                val regionId = data.readBits(8)
                data.skipBits(8) // Skip reserved.
                val regionHorizontalAddress = data.readBits(16)
                val regionVerticalAddress = data.readBits(16)
                remainingLength -= 6
                regions.put(regionId, PageRegion(regionHorizontalAddress, regionVerticalAddress))
            }

            return PageComposition(timeoutSecs, version, state, regions)
        }

        private fun parseRegionComposition(data: ParsableBitArray, length: Int): RegionComposition {
            val id = data.readBits(8)
            data.skipBits(4) // Skip region_version_number
            val fillFlag = data.readBit()
            data.skipBits(3) // Skip reserved.
            val width = data.readBits(16)
            val height = data.readBits(16)
            val levelOfCompatibility = data.readBits(3)
            val depth = data.readBits(3)
            data.skipBits(2) // Skip reserved.
            val clutId = data.readBits(8)
            val pixelCode8Bit = data.readBits(8)
            val pixelCode4Bit = data.readBits(4)
            val pixelCode2Bit = data.readBits(2)
            data.skipBits(2) // Skip reserved
            var remainingLength = length - 10

            val regionObjects = SparseArray<RegionObject>()
            while (remainingLength > 0) {
                val objectId = data.readBits(16)
                val objectType = data.readBits(2)
                val objectProvider = data.readBits(2)
                val objectHorizontalPosition = data.readBits(12)
                data.skipBits(4) // Skip reserved.
                val objectVerticalPosition = data.readBits(12)
                remainingLength -= 6

                var foregroundPixelCode = 0
                var backgroundPixelCode = 0
                if (objectType == 0x01 || objectType == 0x02) { // Only seems to affect to char subtitles.
                    foregroundPixelCode = data.readBits(8)
                    backgroundPixelCode = data.readBits(8)
                    remainingLength -= 2
                }

                regionObjects.put(
                    objectId,
                    RegionObject(
                        objectType,
                        objectProvider,
                        objectHorizontalPosition,
                        objectVerticalPosition,
                        foregroundPixelCode,
                        backgroundPixelCode
                    )
                )
            }

            return RegionComposition(
                id,
                fillFlag,
                width,
                height,
                levelOfCompatibility,
                depth,
                clutId,
                pixelCode8Bit,
                pixelCode4Bit,
                pixelCode2Bit,
                regionObjects
            )
        }

        private fun parseClutDefinition(data: ParsableBitArray, length: Int): ClutDefinition {
            val clutId = data.readBits(8)
            data.skipBits(8) // Skip clut_version_number (4), reserved (4)
            var remainingLength = length - 2

            val clutEntries2Bit: IntArray = generateDefault2BitClutEntries()
            val clutEntries4Bit: IntArray = generateDefault4BitClutEntries()
            val clutEntries8Bit: IntArray = generateDefault8BitClutEntries()

            while (remainingLength > 0) {
                val entryId = data.readBits(8)
                val entryFlags = data.readBits(8)
                remainingLength -= 2

                val clutEntries: IntArray?
                if ((entryFlags and 0x80) != 0) {
                    clutEntries = clutEntries2Bit
                } else if ((entryFlags and 0x40) != 0) {
                    clutEntries = clutEntries4Bit
                } else {
                    clutEntries = clutEntries8Bit
                }

                val y: Int
                var cr: Int
                var cb: Int
                var t: Int
                if ((entryFlags and 0x01) != 0) {
                    y = data.readBits(8)
                    cr = data.readBits(8)
                    cb = data.readBits(8)
                    t = data.readBits(8)
                    remainingLength -= 4
                } else {
                    y = data.readBits(6) shl 2
                    cr = data.readBits(4) shl 4
                    cb = data.readBits(4) shl 4
                    t = data.readBits(2) shl 6
                    remainingLength -= 2
                }

                if (y == 0x00) {
                    cr = 0x00
                    cb = 0x00
                    t = 0xFF
                }

                val a = (0xFF - (t and 0xFF)).toByte().toInt()
                val r = (y + (1.40200 * (cr - 128))).toInt()
                val g = (y - (0.34414 * (cb - 128)) - (0.71414 * (cr - 128))).toInt()
                val b = (y + (1.77200 * (cb - 128))).toInt()
                clutEntries[entryId] =
                    getColor(
                        a,
                        constrainValue(r, 0, 255),
                        constrainValue(g, 0, 255),
                        constrainValue(b, 0, 255)
                    )
            }

            return ClutDefinition(clutId, clutEntries2Bit, clutEntries4Bit, clutEntries8Bit)
        }

        private fun parseObjectData(data: ParsableBitArray): ObjectData {
            val objectId = data.readBits(16)
            data.skipBits(4) // Skip object_version_number
            val objectCodingMethod = data.readBits(2)
            val nonModifyingColorFlag = data.readBit()
            data.skipBits(1) // Skip reserved.

            var topFieldData = Util.EMPTY_BYTE_ARRAY
            var bottomFieldData = Util.EMPTY_BYTE_ARRAY

            if (objectCodingMethod == OBJECT_CODING_STRING) {
                val numberOfCodes = data.readBits(8)
                // TODO: Parse and use character_codes.
                data.skipBits(numberOfCodes * 16) // Skip character_codes.
            } else if (objectCodingMethod == OBJECT_CODING_PIXELS) {
                val topFieldDataLength = data.readBits(16)
                val bottomFieldDataLength = data.readBits(16)
                if (topFieldDataLength > 0) {
                    topFieldData = ByteArray(topFieldDataLength)
                    data.readBytes(topFieldData, 0, topFieldDataLength)
                }
                if (bottomFieldDataLength > 0) {
                    bottomFieldData = ByteArray(bottomFieldDataLength)
                    data.readBytes(bottomFieldData, 0, bottomFieldDataLength)
                } else {
                    bottomFieldData = topFieldData
                }
            }

            return ObjectData(objectId, nonModifyingColorFlag, topFieldData, bottomFieldData)
        }

        private fun generateDefault2BitClutEntries(): IntArray {
            val entries = IntArray(4)
            entries[0] = 0x00000000
            entries[1] = -0x1
            entries[2] = -0x1000000
            entries[3] = -0x808081
            return entries
        }

        private fun generateDefault4BitClutEntries(): IntArray {
            val entries = IntArray(16)
            entries[0] = 0x00000000
            for (i in 1..<entries.size) {
                if (i < 8) {
                    entries[i] =
                        getColor(
                            0xFF,
                            (if ((i and 0x01) != 0) 0xFF else 0x00),
                            (if ((i and 0x02) != 0) 0xFF else 0x00),
                            (if ((i and 0x04) != 0) 0xFF else 0x00)
                        )
                } else {
                    entries[i] =
                        getColor(
                            0xFF,
                            (if ((i and 0x01) != 0) 0x7F else 0x00),
                            (if ((i and 0x02) != 0) 0x7F else 0x00),
                            (if ((i and 0x04) != 0) 0x7F else 0x00)
                        )
                }
            }
            return entries
        }

        private fun generateDefault8BitClutEntries(): IntArray {
            val entries = IntArray(256)
            entries[0] = 0x00000000
            for (i in entries.indices) {
                if (i < 8) {
                    entries[i] =
                        getColor(
                            0x3F,
                            (if ((i and 0x01) != 0) 0xFF else 0x00),
                            (if ((i and 0x02) != 0) 0xFF else 0x00),
                            (if ((i and 0x04) != 0) 0xFF else 0x00)
                        )
                } else {
                    when (i and 0x88) {
                        0x00 -> entries[i] =
                            getColor(
                                0xFF,
                                ((if ((i and 0x01) != 0) 0x55 else 0x00) + (if ((i and 0x10) != 0) 0xAA else 0x00)),
                                ((if ((i and 0x02) != 0) 0x55 else 0x00) + (if ((i and 0x20) != 0) 0xAA else 0x00)),
                                ((if ((i and 0x04) != 0) 0x55 else 0x00) + (if ((i and 0x40) != 0) 0xAA else 0x00))
                            )

                        0x08 -> entries[i] =
                            getColor(
                                0x7F,
                                ((if ((i and 0x01) != 0) 0x55 else 0x00) + (if ((i and 0x10) != 0) 0xAA else 0x00)),
                                ((if ((i and 0x02) != 0) 0x55 else 0x00) + (if ((i and 0x20) != 0) 0xAA else 0x00)),
                                ((if ((i and 0x04) != 0) 0x55 else 0x00) + (if ((i and 0x40) != 0) 0xAA else 0x00))
                            )

                        0x80 -> entries[i] =
                            getColor(
                                0xFF,
                                (127 + (if ((i and 0x01) != 0) 0x2B else 0x00) + (if ((i and 0x10) != 0) 0x55 else 0x00)),
                                (127 + (if ((i and 0x02) != 0) 0x2B else 0x00) + (if ((i and 0x20) != 0) 0x55 else 0x00)),
                                (127 + (if ((i and 0x04) != 0) 0x2B else 0x00) + (if ((i and 0x40) != 0) 0x55 else 0x00))
                            )

                        0x88 -> entries[i] =
                            getColor(
                                0xFF,
                                ((if ((i and 0x01) != 0) 0x2B else 0x00) + (if ((i and 0x10) != 0) 0x55 else 0x00)),
                                ((if ((i and 0x02) != 0) 0x2B else 0x00) + (if ((i and 0x20) != 0) 0x55 else 0x00)),
                                ((if ((i and 0x04) != 0) 0x2B else 0x00) + (if ((i and 0x40) != 0) 0x55 else 0x00))
                            )
                    }
                }
            }
            return entries
        }

        private fun getColor(a: Int, r: Int, g: Int, b: Int): Int {
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        // Static drawing.
        
        private fun paintPixelDataSubBlocks(
            objectData: ObjectData,
            clutDefinition: ClutDefinition,
            regionDepth: Int,
            horizontalAddress: Int,
            verticalAddress: Int,
            paint: Paint?,
            canvas: Canvas
        ) {
            val clutEntries: IntArray
            if (regionDepth == REGION_DEPTH_8_BIT) {
                clutEntries = clutDefinition.clutEntries8Bit
            } else if (regionDepth == REGION_DEPTH_4_BIT) {
                clutEntries = clutDefinition.clutEntries4Bit
            } else {
                clutEntries = clutDefinition.clutEntries2Bit
            }
            paintPixelDataSubBlock(
                objectData.topFieldData,
                clutEntries,
                regionDepth,
                horizontalAddress,
                verticalAddress,
                paint,
                canvas
            )
            paintPixelDataSubBlock(
                objectData.bottomFieldData,
                clutEntries,
                regionDepth,
                horizontalAddress,
                verticalAddress + 1,
                paint,
                canvas
            )
        }

        private fun paintPixelDataSubBlock(
            pixelData: ByteArray?,
            clutEntries: IntArray,
            regionDepth: Int,
            horizontalAddress: Int,
            verticalAddress: Int,
            paint: Paint?,
            canvas: Canvas
        ) {
            val data = ParsableBitArray(pixelData!!)
            var column = horizontalAddress
            var line = verticalAddress
            var clutMapTable2To4: ByteArray? = null
            var clutMapTable2To8: ByteArray? = null
            var clutMapTable4To8: ByteArray? = null

            while (data.bitsLeft() != 0) {
                val dataType = data.readBits(8)
                when (dataType) {
                    DATA_TYPE_2BP_CODE_STRING -> {
                        val clutMapTable2ToX: ByteArray?
                        if (regionDepth == REGION_DEPTH_8_BIT) {
                            clutMapTable2ToX =
                                if (clutMapTable2To8 == null) defaultMap2To8 else clutMapTable2To8
                        } else if (regionDepth == REGION_DEPTH_4_BIT) {
                            clutMapTable2ToX =
                                if (clutMapTable2To4 == null) defaultMap2To4 else clutMapTable2To4
                        } else {
                            clutMapTable2ToX = null
                        }
                        column =
                            paint2BitPixelCodeString(
                                data, clutEntries, clutMapTable2ToX, column, line, paint, canvas
                            )
                        data.byteAlign()
                    }

                    DATA_TYPE_4BP_CODE_STRING -> {
                        val clutMapTable4ToX: ByteArray?
                        if (regionDepth == REGION_DEPTH_8_BIT) {
                            clutMapTable4ToX =
                                if (clutMapTable4To8 == null) defaultMap4To8 else clutMapTable4To8
                        } else {
                            clutMapTable4ToX = null
                        }
                        column =
                            paint4BitPixelCodeString(
                                data, clutEntries, clutMapTable4ToX, column, line, paint, canvas
                            )
                        data.byteAlign()
                    }

                    DATA_TYPE_8BP_CODE_STRING -> column =
                        paint8BitPixelCodeString(
                            data, clutEntries,  null, column, line, paint, canvas
                        )

                    DATA_TYPE_24_TABLE_DATA -> clutMapTable2To4 = buildClutMapTable(4, 4, data)
                    DATA_TYPE_28_TABLE_DATA -> clutMapTable2To8 = buildClutMapTable(4, 8, data)
                    DATA_TYPE_48_TABLE_DATA -> clutMapTable4To8 = buildClutMapTable(16, 8, data)
                    DATA_TYPE_END_LINE -> {
                        column = horizontalAddress
                        line += 2
                    }

                    else -> {}
                }
            }
        }

        private fun paint2BitPixelCodeString(
            data: ParsableBitArray,
            clutEntries: IntArray,
            clutMapTable: ByteArray?,
            column: Int,
            line: Int,
            paint: Paint?,
            canvas: Canvas
        ): Int {
            var column = column
            var endOfPixelCodeString = false
            do {
                var runLength = 0
                var clutIndex = 0
                val peek = data.readBits(2)
                if (peek != 0x00) {
                    runLength = 1
                    clutIndex = peek
                } else if (data.readBit()) {
                    runLength = 3 + data.readBits(3)
                    clutIndex = data.readBits(2)
                } else if (data.readBit()) {
                    runLength = 1
                } else {
                    when (data.readBits(2)) {
                        0x00 -> endOfPixelCodeString = true
                        0x01 -> runLength = 2
                        0x02 -> {
                            runLength = 12 + data.readBits(4)
                            clutIndex = data.readBits(2)
                        }

                        0x03 -> {
                            runLength = 29 + data.readBits(8)
                            clutIndex = data.readBits(2)
                        }
                    }
                }

                if (runLength != 0 && paint != null) {
                    paint.setColor(clutEntries[if (clutMapTable != null) clutMapTable[clutIndex].toInt() else clutIndex])
                    canvas.drawRect(
                        column.toFloat(),
                        line.toFloat(),
                        (column + runLength).toFloat(),
                        (line + 1).toFloat(),
                        paint
                    )
                }

                column += runLength
            } while (!endOfPixelCodeString)

            return column
        }

        private fun paint4BitPixelCodeString(
            data: ParsableBitArray,
            clutEntries: IntArray,
            clutMapTable: ByteArray?,
            column: Int,
            line: Int,
            paint: Paint?,
            canvas: Canvas
        ): Int {
            var column = column
            var endOfPixelCodeString = false
            do {
                var runLength = 0
                var clutIndex = 0
                var peek = data.readBits(4)
                if (peek != 0x00) {
                    runLength = 1
                    clutIndex = peek
                } else if (!data.readBit()) {
                    peek = data.readBits(3)
                    if (peek != 0x00) {
                        runLength = 2 + peek
                        clutIndex = 0x00
                    } else {
                        endOfPixelCodeString = true
                    }
                } else if (!data.readBit()) {
                    runLength = 4 + data.readBits(2)
                    clutIndex = data.readBits(4)
                } else {
                    when (data.readBits(2)) {
                        0x00 -> runLength = 1
                        0x01 -> runLength = 2
                        0x02 -> {
                            runLength = 9 + data.readBits(4)
                            clutIndex = data.readBits(4)
                        }

                        0x03 -> {
                            runLength = 25 + data.readBits(8)
                            clutIndex = data.readBits(4)
                        }
                    }
                }

                if (runLength != 0 && paint != null) {
                    paint.setColor(clutEntries[if (clutMapTable != null) clutMapTable[clutIndex].toInt() else clutIndex])
                    canvas.drawRect(
                        column.toFloat(),
                        line.toFloat(),
                        (column + runLength).toFloat(),
                        (line + 1).toFloat(),
                        paint
                    )
                }

                column += runLength
            } while (!endOfPixelCodeString)

            return column
        }

        private fun paint8BitPixelCodeString(
            data: ParsableBitArray,
            clutEntries: IntArray,
            clutMapTable: ByteArray?,
            column: Int,
            line: Int,
            paint: Paint?,
            canvas: Canvas
        ): Int {
            var column = column
            var endOfPixelCodeString = false
            do {
                var runLength = 0
                var clutIndex = 0
                var peek = data.readBits(8)
                if (peek != 0x00) {
                    runLength = 1
                    clutIndex = peek
                } else {
                    if (!data.readBit()) {
                        peek = data.readBits(7)
                        if (peek != 0x00) {
                            runLength = peek
                            clutIndex = 0x00
                        } else {
                            endOfPixelCodeString = true
                        }
                    } else {
                        runLength = data.readBits(7)
                        clutIndex = data.readBits(8)
                    }
                }

                if (runLength != 0 && paint != null) {
                    paint.setColor(clutEntries[if (clutMapTable != null) clutMapTable[clutIndex].toInt() else clutIndex])
                    canvas.drawRect(
                        column.toFloat(),
                        line.toFloat(),
                        (column + runLength).toFloat(),
                        (line + 1).toFloat(),
                        paint
                    )
                }
                column += runLength
            } while (!endOfPixelCodeString)

            return column
        }

        private fun buildClutMapTable(
            length: Int,
            bitsPerEntry: Int,
            data: ParsableBitArray
        ): ByteArray {
            val clutMapTable = ByteArray(length)
            for (i in 0..<length) {
                clutMapTable[i] = data.readBits(bitsPerEntry).toByte()
            }
            return clutMapTable
        }
    }
}
