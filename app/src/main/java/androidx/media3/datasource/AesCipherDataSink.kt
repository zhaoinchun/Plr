package androidx.media3.datasource

import java.io.IOException
import javax.crypto.Cipher
import kotlin.math.min

class AesCipherDataSink(
    private val secretKey: ByteArray?,
    private val wrappedDataSink: DataSink,
    private val scratch: ByteArray? = null
) : DataSink {
    private var cipher: AesFlushingCipher? = null

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec) {
        wrappedDataSink.open(dataSpec)
        cipher =
            AesFlushingCipher(
                Cipher.ENCRYPT_MODE,
                secretKey,
                dataSpec.key,
                dataSpec.uriPositionOffset + dataSpec.position
            )
    }

    @Throws(IOException::class)
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (scratch == null) {
            // In-place mode. Writes over the input data.
            cipher!!.updateInPlace(buffer, offset, length)
            wrappedDataSink.write(buffer, offset, length)
        } else {
            // Use scratch space. The original data remains intact.
            var bytesProcessed = 0
            while (bytesProcessed < length) {
                val bytesToProcess = min(length - bytesProcessed, scratch.size)
                cipher!!.update(
                        buffer,
                        offset + bytesProcessed,
                        bytesToProcess,
                        scratch,  
                        0
                    )
                wrappedDataSink.write(scratch,  0, bytesToProcess)
                bytesProcessed += bytesToProcess
            }
        }
    }

    @Throws(IOException::class)
    override fun close() {
        cipher = null
        wrappedDataSink.close()
    }
}
