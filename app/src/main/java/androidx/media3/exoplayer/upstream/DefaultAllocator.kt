package androidx.media3.exoplayer.upstream

import androidx.media3.common.util.Util.ceilDivide
import com.google.common.base.Preconditions
import java.util.Arrays
import kotlin.math.max

class DefaultAllocator(
    private val trimOnReset: Boolean,
    private val individualAllocationSize: Int,
    initialAllocationCount: Int = 0
) : Allocator {
    private val initialAllocationBlock: ByteArray?
    private var targetBufferSize = 0
    private var allocatedCount = 0
    private var availableCount = initialAllocationCount
    private var availableAllocations =
        arrayOfNulls<Allocation>(initialAllocationCount + AVAILABLE_EXTRA_CAPACITY)

    init {
        Preconditions.checkArgument(individualAllocationSize > 0)
        Preconditions.checkArgument(initialAllocationCount >= 0)
        if (initialAllocationCount > 0) {
            initialAllocationBlock = ByteArray(initialAllocationCount * individualAllocationSize)
            for (i in 0..initialAllocationCount) {
                val allocationOffset = i * individualAllocationSize
                availableAllocations[i] = Allocation(initialAllocationBlock, allocationOffset)
            }
        } else {
            initialAllocationBlock = null
        }
    }

    @Synchronized
    fun reset() {
        if (trimOnReset) {
            setTargetBufferSize(0)
        }
    }

    @Synchronized
    fun setTargetBufferSize(targetBufferSize: Int) {
        val targetBufferSizeReduced = targetBufferSize < this.targetBufferSize
        this.targetBufferSize = targetBufferSize
        if (targetBufferSizeReduced) {
            trim()
        }
    }

    @Synchronized
    override fun allocate(): Allocation {
        allocatedCount++
        val allocation: Allocation?
        if (availableCount > 0) {
            allocation = availableAllocations[--availableCount]!!
            availableAllocations[availableCount] = null
        } else {
            allocation = Allocation(ByteArray(individualAllocationSize), 0)
            if (allocatedCount > availableAllocations.size) {
                // Make availableAllocations be large enough to contain all allocations made by this
                // allocator so that release() does not need to grow the availableAllocations array. See
                // [Internal ref: b/209801945].
                availableAllocations =
                    availableAllocations.copyOf<Allocation?>(availableAllocations.size * 2)
            }
        }
        return allocation
    }

    @Synchronized
    override fun release(allocation: Allocation) {
        availableAllocations[availableCount++] = allocation
        allocatedCount--
    }

    @Synchronized
    override fun release(allocationNode: Allocator.AllocationNode) {
        var allocationNode: Allocator.AllocationNode? = allocationNode
        while (allocationNode != null) {
            availableAllocations[availableCount++] = allocationNode.allocation
            allocatedCount--
            allocationNode = allocationNode.next()
        }
    }

    @Synchronized
    override fun trim() {
        val targetAllocationCount = ceilDivide(targetBufferSize, individualAllocationSize)
        var targetAvailableCount = max(0, targetAllocationCount - allocatedCount)
        if (targetAvailableCount >= availableCount) {
            // We're already at or below the target.
            return
        }

        if (initialAllocationBlock != null) {
            // Some allocations are backed by an initial block. We need to make sure that we hold onto all
            // such allocations. Re-order the available allocations so that the ones backed by the initial
            // block come first.
            var lowIndex = 0
            var highIndex = availableCount - 1
            while (lowIndex <= highIndex) {
                val lowAllocation = availableAllocations[lowIndex]!!
                if (lowAllocation.data == initialAllocationBlock) {
                    lowIndex++
                } else {
                    val highAllocation = availableAllocations[highIndex]!!
                    if (highAllocation.data != initialAllocationBlock) {
                        highIndex--
                    } else {
                        availableAllocations[lowIndex++] = highAllocation
                        availableAllocations[highIndex--] = lowAllocation
                    }
                }
            }
            // lowIndex is the index of the first allocation not backed by an initial block.
            targetAvailableCount = max(targetAvailableCount, lowIndex)
            if (targetAvailableCount >= availableCount) {
                // We're already at or below the target.
                return
            }
        }

        // Discard allocations beyond the target.
        Arrays.fill(availableAllocations, targetAvailableCount, availableCount, null)
        availableCount = targetAvailableCount
    }

    override val totalBytesAllocated: Int
        get() = allocatedCount * individualAllocationSize

    override val individualAllocationLength: Int
        get() = individualAllocationSize

    @get:Synchronized
    val unusedBytesAllocated: Int
        get() = availableCount * individualAllocationSize

    companion object {
        private const val AVAILABLE_EXTRA_CAPACITY = 100
    }
}
