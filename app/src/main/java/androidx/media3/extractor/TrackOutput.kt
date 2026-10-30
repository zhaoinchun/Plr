
package androidx.media3.extractor

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.C.BufferFlags
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray

import java.io.IOException

interface TrackOutput {
    class CryptoData(
        val cryptoMode: @C.CryptoMode Int,
       val encryptionKey: ByteArray?,
       val encryptedBlocks: Int,
       val clearBlocks: Int
    ) {
        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj == null || javaClass != obj.javaClass) {
                return false
            }
            val other = obj as CryptoData
            return cryptoMode == other.cryptoMode && encryptedBlocks == other.encryptedBlocks && clearBlocks == other.clearBlocks && encryptionKey.contentEquals(
                other.encryptionKey
            )
        }

        override fun hashCode(): Int {
            var result = cryptoMode
            result = 31 * result + encryptionKey.contentHashCode()
            result = 31 * result + encryptedBlocks
            result = 31 * result + clearBlocks
            return result
        }
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [SAMPLE_DATA_PART_MAIN, SAMPLE_DATA_PART_ENCRYPTION, SAMPLE_DATA_PART_SUPPLEMENTAL])
    annotation class SampleDataPart

    fun durationUs(durationUs: Long) {}

    fun format(format: Format)

    @Throws(IOException::class)
    fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int {
        return sampleData(input, length, allowEndOfInput, SAMPLE_DATA_PART_MAIN)
    }

    fun sampleData(data: ParsableByteArray, length: Int) {
        sampleData(data, length, SAMPLE_DATA_PART_MAIN)
    }

    @Throws(IOException::class)
    fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: @SampleDataPart Int
    ): Int

    fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: @SampleDataPart Int)

    fun sampleMetadata(
        timeUs: Long, flags: @BufferFlags Int, size: Int, offset: Int, cryptoData: CryptoData?
    )

    companion object {
        const val SAMPLE_DATA_PART_MAIN: Int = 0
        const val SAMPLE_DATA_PART_ENCRYPTION: Int = 1
        const val SAMPLE_DATA_PART_SUPPLEMENTAL: Int = 2
    }
}
