package androidx.media3.datasource

import android.net.Uri
import androidx.media3.common.C
import com.google.common.base.Preconditions
import java.io.IOException
import javax.crypto.Cipher

class AesCipherDataSource(private val secretKey: ByteArray, private val upstream: DataSource) :
    DataSource {
    private var cipher: AesFlushingCipher? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val dataLength = upstream.open(dataSpec)
        cipher =
            AesFlushingCipher(
                Cipher.DECRYPT_MODE,
                secretKey,
                dataSpec.key,
                dataSpec.uriPositionOffset + dataSpec.position
            )
        return dataLength
    }

    override val uri: Uri?
        get() = upstream.uri

    override val responseHeaders: MutableMap<String, List<String>>
        get() = upstream.responseHeaders

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }
        val read: Int = upstream.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) {
            return C.RESULT_END_OF_INPUT
        }
        cipher?.updateInPlace(buffer, offset, read)
        return read
    }

    @Throws(IOException::class)
    override fun close() {
        cipher = null
        upstream.close()
    }
}
