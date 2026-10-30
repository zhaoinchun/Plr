
package androidx.media3.extractor.mp4

import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorInput

import java.io.IOException

internal class TrackFragment {
    
    var header:DefaultSampleValues? = null

    var atomPosition: Long = 0

    var dataPosition: Long = 0

    var auxiliaryDataPosition: Long = 0

    var trunCount: Int = 0

    var sampleCount: Int = 0

    var trunDataPosition: LongArray?

    var trunLength: IntArray

    var sampleSizeTable: IntArray

    var samplePresentationTimesUs: LongArray

    var sampleIsSyncFrameTable: BooleanArray?

    var definesEncryptionData: Boolean = false

    var sampleHasSubsampleEncryptionTable: BooleanArray

    var trackEncryptionBox: TrackEncryptionBox? = null

    val sampleEncryptionData: ParsableByteArray

    var sampleEncryptionDataNeedsFill: Boolean = false

    var nextFragmentDecodeTime: Long = 0

    var nextFragmentDecodeTimeIncludesMoov: Boolean = false

    init {
        trunDataPosition = LongArray(0)
        trunLength = IntArray(0)
        sampleSizeTable = IntArray(0)
        samplePresentationTimesUs = LongArray(0)
        sampleIsSyncFrameTable = BooleanArray(0)
        sampleHasSubsampleEncryptionTable = BooleanArray(0)
        sampleEncryptionData = ParsableByteArray()
    }

    fun reset() {
        trunCount = 0
        nextFragmentDecodeTime = 0
        nextFragmentDecodeTimeIncludesMoov = false
        definesEncryptionData = false
        sampleEncryptionDataNeedsFill = false
        trackEncryptionBox = null
    }

    fun initTables(trunCount: Int, sampleCount: Int) {
        this.trunCount = trunCount
        this.sampleCount = sampleCount
        if (trunLength.size < trunCount) {
            trunDataPosition = LongArray(trunCount)
            trunLength = IntArray(trunCount)
        }
        if (sampleSizeTable.size < sampleCount) {
            // Size the tables 25% larger than needed, so as to make future resize operations less
            // likely. The choice of 25% is relatively arbitrary.
            val tableSize = (sampleCount * 125) / 100
            sampleSizeTable = IntArray(tableSize)
            samplePresentationTimesUs = LongArray(tableSize)
            sampleIsSyncFrameTable = BooleanArray(tableSize)
            sampleHasSubsampleEncryptionTable = BooleanArray(tableSize)
        }
    }

    fun initEncryptionData(length: Int) {
        sampleEncryptionData.reset(length)
        definesEncryptionData = true
        sampleEncryptionDataNeedsFill = true
    }

    @Throws(IOException::class)
    fun fillEncryptionData(input: ExtractorInput) {
        input.readFully(sampleEncryptionData.data, 0, sampleEncryptionData.limit())
        sampleEncryptionData.setPosition(0)
        sampleEncryptionDataNeedsFill = false
    }

    fun fillEncryptionData(source: ParsableByteArray) {
        source.readBytes(sampleEncryptionData.data, 0, sampleEncryptionData.limit())
        sampleEncryptionData.setPosition(0)
        sampleEncryptionDataNeedsFill = false
    }

    fun getSamplePresentationTimeUs(index: Int): Long {
        return samplePresentationTimesUs[index]
    }

    fun sampleHasSubsampleEncryptionTable(index: Int): Boolean {
        return definesEncryptionData && sampleHasSubsampleEncryptionTable[index]
    }
}
