package androidx.media3.common

import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.SharedMemory
import android.system.OsConstants
import androidx.annotation.RequiresApi
import androidx.media3.common.util.Log
import androidx.media3.common.util.Util.EMPTY_BYTE_ARRAY
import androidx.media3.common.util.Util.ceilDivide
import androidx.media3.common.util.Util.intToStringMaxRadix
import com.google.common.collect.Iterables
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.math.min

class BundleableByteArray(private val byteArray: ByteArray) {
    private val inProcessBinder: InProcessBinder

    var sharedMemoryApi27: SharedMemoryApi27? = null
    var splitArrayRetriever: SplitArrayRetriever? = null

    init {
        this.inProcessBinder = InProcessBinder()
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putBinder(FIELD_IN_PROCESS_BINDER, inProcessBinder)
        if (Build.VERSION.SDK_INT >= 27 && byteArray.size > 0) {
            if (sharedMemoryApi27 == null) {
                sharedMemoryApi27 = SharedMemoryApi27.create(byteArray)
            }
            if (sharedMemoryApi27 != null) {
                sharedMemoryApi27!!.writeToBundle(bundle)
                return bundle
            }
        }
        if (splitArrayRetriever == null) {
            splitArrayRetriever = SplitArrayRetriever(byteArray)
        }
        splitArrayRetriever!!.writeToBundle(bundle)
        return bundle
    }

    @RequiresApi(27)
    class SharedMemoryApi27(private val sharedMemory: SharedMemory?) {
        fun writeToBundle(bundle: Bundle) {
            bundle.putParcelable(FIELD_SHARED_MEMORY, sharedMemory)
        }

        companion object {
            fun create(byteArray: ByteArray): SharedMemoryApi27? {
                var sharedMemory: SharedMemory? = null
                try {
                    sharedMemory = SharedMemory.create(TAG, byteArray.size)
                    val byteBuffer = sharedMemory.mapReadWrite()
                    byteBuffer.put(byteArray)
                    SharedMemory.unmap(byteBuffer)
                    sharedMemory.setProtect(OsConstants.PROT_READ)
                    return SharedMemoryApi27(sharedMemory)
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Failed to allocate shared memory for byte array, size=" + byteArray.size,
                        e
                    )
                    if (sharedMemory != null) {
                        sharedMemory.close()
                    }
                    return null
                }
            }

            internal fun readFromBundle(bundle: Bundle): ByteArray? {
                val sharedMemory = bundle.getParcelable<SharedMemory?>(FIELD_SHARED_MEMORY)
                if (sharedMemory == null) {
                    return null
                }
                var byteBuffer: ByteBuffer? = null
                try {
                    byteBuffer = sharedMemory.mapReadOnly()
                    val byteArray = ByteArray(sharedMemory.getSize())
                    byteBuffer.get(byteArray)
                    return byteArray
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read byte array from shared memory", e)
                    return null
                } finally {
                    if (byteBuffer != null) {
                        SharedMemory.unmap(byteBuffer)
                    }
                    sharedMemory.close()
                }
            }
        }
    }

    class SplitArrayRetriever(byteArray: ByteArray) {
        private val bundleListRetriever: BundleListRetriever

        init {
            val splitListBuilder = mutableListOf<Bundle>()
            val chunkCount = ceilDivide(byteArray.size, CHUNK_SIZE)
            for (i in 0..<chunkCount) {
                val bundle = Bundle()
                val from = i * CHUNK_SIZE
                val to = min(from + CHUNK_SIZE, byteArray.size)
                bundle.putByteArray(BUNDLE_KEY, byteArray.copyOfRange(from, to))
                splitListBuilder.add(bundle)
            }
            bundleListRetriever = BundleListRetriever(splitListBuilder.toList())
        }

        fun writeToBundle(bundle: Bundle) {
            bundle.putBinder(FIELD_SPLIT_ARRAY_RETRIEVER, bundleListRetriever)
        }

        companion object {
            private val CHUNK_SIZE = C.SUGGESTED_MAX_IPC_SIZE
            private const val BUNDLE_KEY = "bytes"

            internal fun readFromBundle(bundle: Bundle): ByteArray? {
                val binder = bundle.getBinder(FIELD_SPLIT_ARRAY_RETRIEVER) ?: return null
                val list: List<Bundle?>
                try {
                    list = BundleListRetriever.getList(binder)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Failed to read byte array from bundle list retriever", e)
                    return null
                }
                if (list.isEmpty()) {
                    return EMPTY_BYTE_ARRAY
                }
                val lastByteArray = Iterables.getLast<Bundle?>(list)!!.getByteArray(BUNDLE_KEY)
                if (lastByteArray == null) {
                    return null
                }
                val fullChunkCount = list.size - 1
                val outArray = ByteArray(fullChunkCount * CHUNK_SIZE + lastByteArray.size)
                System.arraycopy(
                    lastByteArray, 0, outArray, fullChunkCount * CHUNK_SIZE, lastByteArray.size
                )
                for (i in 0..<fullChunkCount) {
                    val chunk = list[i]!!.getByteArray(BUNDLE_KEY)
                    if (chunk == null || chunk.size != CHUNK_SIZE) {
                        return null
                    }
                    System.arraycopy(chunk, 0, outArray, i * CHUNK_SIZE, CHUNK_SIZE)
                }
                return outArray
            }
        }
    }

    private inner class InProcessBinder : Binder() {
        fun getByteArray(): ByteArray {
            return this@BundleableByteArray.byteArray
        }
    }

    companion object {
        private const val TAG = "BundleableByteArray"

        private val FIELD_IN_PROCESS_BINDER = intToStringMaxRadix(0)
        private val FIELD_SHARED_MEMORY = intToStringMaxRadix(1)
        private val FIELD_SPLIT_ARRAY_RETRIEVER = intToStringMaxRadix(2)

        fun fromBundle(bundle: Bundle): ByteArray? {
            val inProcessBinder = bundle.getBinder(FIELD_IN_PROCESS_BINDER)
            if (inProcessBinder == null) {
                return null
            }
            val isLocal = inProcessBinder is InProcessBinder
            if (isLocal) {
                return inProcessBinder.getByteArray()
            }
            if (Build.VERSION.SDK_INT >= 27) {
                val byteArray = SharedMemoryApi27.readFromBundle(bundle)
                if (byteArray != null) {
                    return byteArray
                }
            }
            return SplitArrayRetriever.readFromBundle(bundle)
        }
    }
}
