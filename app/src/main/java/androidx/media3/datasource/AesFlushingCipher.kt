package androidx.media3.datasource

import androidx.media3.common.util.Util.splitAtFirst
import com.google.common.base.Preconditions
import java.nio.ByteBuffer
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import javax.crypto.Cipher
import javax.crypto.NoSuchPaddingException
import javax.crypto.ShortBufferException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesFlushingCipher(mode: Int, secretKey: ByteArray?, nonce: Long, offset: Long) {
    private val cipher: Cipher
    private val blockSize: Int
    private val zerosBlock: ByteArray
    private val flushedBlock: ByteArray

    private var pendingXorBytes = 0

    constructor(mode: Int, secretKey: ByteArray?, nonce: String?, offset: Long) : this(
        mode,
        secretKey,
        getFNV64Hash(nonce),
        offset
    )

    init {
        try {
            cipher = Cipher.getInstance("AES/CTR/NoPadding")
            blockSize = cipher.getBlockSize()
            zerosBlock = ByteArray(blockSize)
            flushedBlock = ByteArray(blockSize)
            val counter = offset / blockSize
            val startPadding = (offset % blockSize).toInt()
            cipher.init(
                mode,
                SecretKeySpec(secretKey, splitAtFirst(cipher.getAlgorithm(), "/")[0]),
                IvParameterSpec(getInitializationVector(nonce, counter))
            )
            if (startPadding != 0) {
                updateInPlace(ByteArray(startPadding), 0, startPadding)
            }
        } catch (e: NoSuchAlgorithmException) {
            // Should never happen.
            throw RuntimeException(e)
        } catch (e: NoSuchPaddingException) {
            throw RuntimeException(e)
        } catch (e: InvalidKeyException) {
            throw RuntimeException(e)
        } catch (e: InvalidAlgorithmParameterException) {
            throw RuntimeException(e)
        }
    }

    fun updateInPlace(data: ByteArray, offset: Int, length: Int) {
        update(data, offset, length, data, offset)
    }

    fun update(`in`: ByteArray, inOffset: Int, length: Int, out: ByteArray, outOffset: Int) {
        // If we previously flushed the cipher by inputting zeros up to a block boundary, then we need
        // to manually transform the data that actually ended the block. See the comment below for more
        // details.
        var inOffset = inOffset
        var length = length
        var outOffset = outOffset
        while (pendingXorBytes > 0) {
            out[outOffset] =
                (`in`[inOffset].toInt() xor flushedBlock[blockSize - pendingXorBytes].toInt()).toByte()
            outOffset++
            inOffset++
            pendingXorBytes--
            length--
            if (length == 0) {
                return
            }
        }

        // Do the bulk of the update.
        var written = nonFlushingUpdate(`in`, inOffset, length, out, outOffset)
        if (length == written) {
            return
        }

        // We need to finish the block to flush out the remaining bytes. We do so by inputting zeros,
        // so that the corresponding bytes output by the cipher are those that would have been XORed
        // against the real end-of-block data to transform it. We store these bytes so that we can
        // perform the transformation manually in the case of a subsequent call to this method with
        // the real data.
        val bytesToFlush = length - written
        Preconditions.checkState(bytesToFlush < blockSize)
        outOffset += written
        pendingXorBytes = blockSize - bytesToFlush
        written = nonFlushingUpdate(zerosBlock, 0, pendingXorBytes, flushedBlock, 0)
        Preconditions.checkState(written == blockSize)
        // The first part of xorBytes contains the flushed data, which we copy out. The remainder
        // contains the bytes that will be needed for manual transformation in a subsequent call.
        for (i in 0..<bytesToFlush) {
            out[outOffset++] = flushedBlock[i]
        }
    }

    private fun nonFlushingUpdate(
        `in`: ByteArray?,
        inOffset: Int,
        length: Int,
        out: ByteArray?,
        outOffset: Int
    ): Int {
        try {
            return cipher.update(`in`, inOffset, length, out, outOffset)
        } catch (e: ShortBufferException) {
            // Should never happen.
            throw RuntimeException(e)
        }
    }

    private fun getInitializationVector(nonce: Long, counter: Long): ByteArray {
        return ByteBuffer.allocate(16).putLong(nonce).putLong(counter).array()
    }

    companion object {
        private fun getFNV64Hash(input: String?): Long {
            if (input == null) {
                return 0
            }

            var hash: Long = 0
            for (i in 0..<input.length) {
                hash = hash xor input[i].code.toLong()
                // This is equivalent to hash *= 0x100000001b3 (the FNV magic prime number).
                hash += (hash shl 1) + (hash shl 4) + (hash shl 5) + (hash shl 7) + (hash shl 8) + (hash shl 40)
            }
            return hash
        }
    }
}
