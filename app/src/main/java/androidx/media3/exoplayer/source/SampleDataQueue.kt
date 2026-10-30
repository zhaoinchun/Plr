package androidx.media3.exoplayer.source

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.source.SampleQueue.SampleExtrasHolder
import androidx.media3.exoplayer.upstream.Allocation
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.extractor.TrackOutput
import com.google.common.base.Preconditions
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.math.min

internal class SampleDataQueue(private val allocator: Allocator) {
    private val allocationLength: Int = allocator.individualAllocationLength
    private val scratch: ParsableByteArray = ParsableByteArray(INITIAL_SCRATCH_SIZE)

    // References into the linked list of allocations.
    private var firstAllocationNode: AllocationNode =
        AllocationNode( 0, allocationLength)
    private var readAllocationNode: AllocationNode = firstAllocationNode
    private var writeAllocationNode: AllocationNode = firstAllocationNode

    // Called by the loading thread.
    // Accessed only by the loading thread (or the consuming thread when there is no loading thread).
    var totalBytesWritten: Long = 0
        private set

    // Called by the consuming thread, but only when there is no loading thread.
    fun reset() {
        clearAllocationNodes(firstAllocationNode)
        firstAllocationNode.reset( 0, allocationLength)
        readAllocationNode = firstAllocationNode
        writeAllocationNode = firstAllocationNode
        totalBytesWritten = 0
        allocator.trim()
    }

    fun discardUpstreamSampleBytes(totalBytesWritten: Long) {
        Preconditions.checkArgument(totalBytesWritten <= this.totalBytesWritten)
        this.totalBytesWritten = totalBytesWritten
        if (this.totalBytesWritten == 0L
            || this.totalBytesWritten == firstAllocationNode.startPosition
        ) {
            clearAllocationNodes(firstAllocationNode)
            firstAllocationNode = AllocationNode(this.totalBytesWritten, allocationLength)
            readAllocationNode = firstAllocationNode
            writeAllocationNode = firstAllocationNode
        } else {
            // Find the last node containing at least 1 byte of data that we need to keep.
            var lastNodeToKeep: AllocationNode? = firstAllocationNode
            while (this.totalBytesWritten > lastNodeToKeep!!.endPosition) {
                lastNodeToKeep = lastNodeToKeep.next
            }
            // Discard all subsequent nodes. lastNodeToKeep is initialized, therefore next cannot be null.
            val firstNodeToDiscard = lastNodeToKeep.next!!
            clearAllocationNodes(firstNodeToDiscard)
            // Reset the successor of the last node to be an uninitialized node.
            lastNodeToKeep.next = AllocationNode(lastNodeToKeep.endPosition, allocationLength)
            // Update writeAllocationNode and readAllocationNode as necessary.
            writeAllocationNode =
                if (this.totalBytesWritten == lastNodeToKeep.endPosition)
                    lastNodeToKeep.next!!
                else
                    lastNodeToKeep
            if (readAllocationNode == firstNodeToDiscard) {
                readAllocationNode = lastNodeToKeep.next!!
            }
        }
    }

    // Called by the consuming thread.
    fun rewind() {
        readAllocationNode = firstAllocationNode
    }

    fun readToBuffer(buffer: DecoderInputBuffer, extrasHolder: SampleExtrasHolder) {
        readAllocationNode =
            readSampleData(readAllocationNode, buffer, extrasHolder, scratch)
    }

    fun peekToBuffer(buffer: DecoderInputBuffer, extrasHolder: SampleExtrasHolder) {
        readSampleData(readAllocationNode, buffer, extrasHolder, scratch)
    }

    fun discardDownstreamTo(absolutePosition: Long) {
        if (absolutePosition == C.INDEX_UNSET.toLong()) {
            return
        }
        while (absolutePosition >= firstAllocationNode.endPosition) {
            // Advance firstAllocationNode to the specified absolute position. Also clear nodes that are
            // advanced past, and return their underlying allocations to the allocator.
            allocator.release(firstAllocationNode.allocation)
            firstAllocationNode = firstAllocationNode.clear()
        }
        if (readAllocationNode.startPosition < firstAllocationNode.startPosition) {
            // We discarded the node referenced by readAllocationNode. We need to advance it to the first
            // remaining node.
            readAllocationNode = firstAllocationNode
        }
    }

    @Throws(IOException::class)
    fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int {
        var length = length
        length = preAppend(length)
        val bytesAppended =
            input.read(
                writeAllocationNode._allocation!!.data!!,
                writeAllocationNode.translateOffset(totalBytesWritten),
                length
            )
        if (bytesAppended == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) {
                return C.RESULT_END_OF_INPUT
            }
            throw EOFException()
        }
        postAppend(bytesAppended)
        return bytesAppended
    }

    fun sampleData(buffer: ParsableByteArray, length: Int) {
        var length = length
        while (length > 0) {
            val bytesAppended = preAppend(length)
            buffer.readBytes(
                writeAllocationNode._allocation!!.data!!,
                writeAllocationNode.translateOffset(totalBytesWritten),
                bytesAppended
            )
            length -= bytesAppended
            postAppend(bytesAppended)
        }
    }

    // Private methods.
    private fun clearAllocationNodes(fromNode: AllocationNode) {
        if (fromNode._allocation == null) {
            return
        }
        // Bulk release allocations for performance (it's significantly faster when using
        // DefaultAllocator because the allocator's lock only needs to be acquired and released once)
        // [Internal: See b/29542039].
        allocator.release(fromNode)
        fromNode.clear()
    }

    private fun preAppend(length: Int): Int {
        if (writeAllocationNode._allocation == null) {
            writeAllocationNode.initialize(
                allocator.allocate(),
                AllocationNode(writeAllocationNode.endPosition, allocationLength)
            )
        }
        return min(length, (writeAllocationNode.endPosition - totalBytesWritten).toInt())
    }

    private fun postAppend(length: Int) {
        totalBytesWritten += length.toLong()
        if (totalBytesWritten == writeAllocationNode.endPosition) {
            writeAllocationNode = writeAllocationNode.next!!
        }
    }

    private class AllocationNode(startPosition: Long, allocationLength: Int) :
        Allocator.AllocationNode {
        var startPosition: Long = 0
        var endPosition: Long = 0
        var _allocation: Allocation? = null
        override val allocation: Allocation
            get() = _allocation!!
        var next: AllocationNode? = null

        init {
            reset(startPosition, allocationLength)
        }

        fun reset(startPosition: Long, allocationLength: Int) {
            Preconditions.checkState(_allocation == null)
            this.startPosition = startPosition
            this.endPosition = startPosition + allocationLength
        }

        fun initialize(allocation: Allocation?, next: AllocationNode?) {
            this._allocation = allocation
            this.next = next
        }

        fun translateOffset(absolutePosition: Long): Int {
            return (absolutePosition - startPosition).toInt() + _allocation!!.offset
        }

        fun clear(): AllocationNode {
            _allocation = null
            val temp = next
            next = null
            return temp!!
        }

        override fun next(): Allocator.AllocationNode? {
            return if (next == null || next!!._allocation == null) {
                null
            } else {
                next
            }
        }
    }

    companion object {
        private const val INITIAL_SCRATCH_SIZE = 32

        private fun readSampleData(
            allocationNode: AllocationNode,
            buffer: DecoderInputBuffer,
            extrasHolder: SampleExtrasHolder,
            scratch: ParsableByteArray
        ): AllocationNode {
            var allocationNode = allocationNode
            if (buffer.isEncrypted) {
                allocationNode = readEncryptionData(allocationNode, buffer, extrasHolder, scratch)
            }
            // Read sample data, extracting supplemental data into a separate buffer if needed.
            if (buffer.hasSupplementalData()) {
                // If there is supplemental data, the sample data is prefixed by its size.
                scratch.reset(4)
                allocationNode =
                    readData(allocationNode, extrasHolder.offset, scratch.data, 4)!!
                val sampleSize = scratch.readUnsignedIntToInt()
                extrasHolder.offset += 4
                extrasHolder.size -= 4

                // Write the sample data.
                buffer.ensureSpaceForWrite(sampleSize)
                allocationNode = Companion.readData(
                    allocationNode,
                    extrasHolder.offset,
                    buffer.data!!,
                    sampleSize
                )!!
                extrasHolder.offset += sampleSize.toLong()
                extrasHolder.size -= sampleSize

                // Write the remaining data as supplemental data.
                buffer.resetSupplementalData(extrasHolder.size)
                allocationNode = readData(
                    allocationNode,
                    extrasHolder.offset,
                    buffer.supplementalData!!,
                    extrasHolder.size
                )!!
            } else {
                // Write the sample data.
                buffer.ensureSpaceForWrite(extrasHolder.size)
                allocationNode = readData(
                        allocationNode,
                        extrasHolder.offset,
                        buffer.data!!,
                        extrasHolder.size
                    )!!
            }
            return allocationNode
        }

        private fun readEncryptionData(
            allocationNode: AllocationNode,
            buffer: DecoderInputBuffer,
            extrasHolder: SampleExtrasHolder,
            scratch: ParsableByteArray
        ): AllocationNode {
            var allocationNode = allocationNode
            var offset = extrasHolder.offset

            // Read the signal byte.
            scratch.reset(1)
            allocationNode = readData(allocationNode, offset, scratch.data, 1)!!
            offset++
            val signalByte = scratch.data[0]
            val subsampleEncryption = (signalByte.toInt() and 0x80) != 0
            val ivSize = signalByte.toInt() and 0x7F

            // Read the initialization vector.
            val cryptoInfo = buffer.cryptoInfo
            if (cryptoInfo.iv == null) {
                cryptoInfo.iv = ByteArray(16)
            } else {
                // Zero out cryptoInfo.iv so that if ivSize < 16, the remaining bytes are correctly set to 0.
                Arrays.fill(cryptoInfo.iv!!, 0.toByte())
            }
            allocationNode = readData(allocationNode, offset, cryptoInfo.iv!!, ivSize)!!
            offset += ivSize.toLong()

            // Read the subsample count, if present.
            val subsampleCount: Int
            if (subsampleEncryption) {
                scratch.reset(2)
                allocationNode = readData(allocationNode, offset, scratch.data, 2)!!
                offset += 2
                subsampleCount = scratch.readUnsignedShort()
            } else {
                subsampleCount = 1
            }

            // Write the clear and encrypted subsample sizes.
            var clearDataSizes = cryptoInfo.numBytesOfClearData
            if (clearDataSizes == null || clearDataSizes.size < subsampleCount) {
                clearDataSizes = IntArray(subsampleCount)
            }
            var encryptedDataSizes = cryptoInfo.numBytesOfEncryptedData
            if (encryptedDataSizes == null || encryptedDataSizes.size < subsampleCount) {
                encryptedDataSizes = IntArray(subsampleCount)
            }
            if (subsampleEncryption) {
                val subsampleDataLength = 6 * subsampleCount
                scratch.reset(subsampleDataLength)
                allocationNode = readData(
                    allocationNode,
                    offset,
                    scratch.data,
                    subsampleDataLength
                )!!
                offset += subsampleDataLength.toLong()
                scratch.setPosition(0)
                for (i in 0..<subsampleCount) {
                    clearDataSizes[i] = scratch.readUnsignedShort()
                    encryptedDataSizes[i] = scratch.readUnsignedIntToInt()
                }
            } else {
                clearDataSizes[0] = 0
                encryptedDataSizes[0] = extrasHolder.size - (offset - extrasHolder.offset).toInt()
            }

            // Populate the cryptoInfo.
            val cryptoData: TrackOutput.CryptoData = extrasHolder.cryptoData!!
            cryptoInfo.set(
                subsampleCount,
                clearDataSizes,
                encryptedDataSizes,
                cryptoData.encryptionKey,
                cryptoInfo.iv,
                cryptoData.cryptoMode,
                cryptoData.encryptedBlocks,
                cryptoData.clearBlocks
            )

            // Adjust the offset and size to take into account the bytes read.
            val bytesRead = (offset - extrasHolder.offset).toInt()
            extrasHolder.offset += bytesRead.toLong()
            extrasHolder.size -= bytesRead
            return allocationNode
        }

        private fun readData(
            allocationNode: AllocationNode, absolutePosition: Long, target: ByteBuffer, length: Int
        ): AllocationNode {
            var allocationNode = allocationNode
            var absolutePosition = absolutePosition
            allocationNode = getNodeContainingPosition(allocationNode, absolutePosition)
            var remaining = length
            while (remaining > 0) {
                val toCopy = min(remaining, (allocationNode.endPosition - absolutePosition).toInt())
                val allocation = allocationNode._allocation!!
                target.put(
                    allocation.data!!,
                    allocationNode.translateOffset(absolutePosition),
                    toCopy
                )
                remaining -= toCopy
                absolutePosition += toCopy.toLong()
                if (absolutePosition == allocationNode.endPosition) {
                    allocationNode = allocationNode.next!!
                }
            }
            return allocationNode
        }

        private fun readData(
            allocationNode: AllocationNode, absolutePosition: Long, target: ByteArray, length: Int
        ): AllocationNode {
            var allocationNode = allocationNode
            var absolutePosition = absolutePosition
            allocationNode = getNodeContainingPosition(allocationNode, absolutePosition)
            var remaining = length
            while (remaining > 0) {
                val toCopy = min(remaining, (allocationNode.endPosition - absolutePosition).toInt())
                val allocation = allocationNode.allocation
                System.arraycopy(
                    allocation.data!!,
                    allocationNode.translateOffset(absolutePosition),
                    target,
                    length - remaining,
                    toCopy
                )
                remaining -= toCopy
                absolutePosition += toCopy.toLong()
                if (absolutePosition == allocationNode.endPosition) {
                    allocationNode = allocationNode.next!!
                }
            }
            return allocationNode
        }

        private fun getNodeContainingPosition(
            allocationNode: AllocationNode, absolutePosition: Long
        ): AllocationNode {
            var allocationNode = allocationNode
            while (absolutePosition >= allocationNode.endPosition) {
                allocationNode = allocationNode.next!!
            }
            return allocationNode
        }
    }
}
